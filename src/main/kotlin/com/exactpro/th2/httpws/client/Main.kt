/*
 * Copyright 2021-2021 Exactpro (Exactpro Systems Limited)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:JvmName("Main")

package com.exactpro.th2.httpws.client

import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.EventBatch
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.schema.factory.CommonFactory
import com.exactpro.th2.common.schema.grpc.router.GrpcRouter
import com.exactpro.th2.common.schema.message.MessageRouter
import com.exactpro.th2.common.schema.message.QueueAttribute
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.GroupBatch
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.toByteArray
import com.exactpro.th2.common.schema.message.storeEvent
import com.exactpro.th2.common.utils.message.RAW_GROUP_SELECTOR
import com.exactpro.th2.common.utils.message.RawMessageBatcher
import com.exactpro.th2.common.utils.message.transport.MessageBatcher
import com.exactpro.th2.common.utils.shutdownGracefully
import com.exactpro.th2.http.client.HttpClient
import com.exactpro.th2.http.client.api.IAuthSettings
import com.exactpro.th2.http.client.util.toPrettyString
import com.exactpro.th2.http.client.util.toProtoMessage
import com.exactpro.th2.http.client.util.toRequest
import com.exactpro.th2.http.client.util.toTransportMessage
import com.exactpro.th2.httpws.client.WebSocketSettings.FrameType.TEXT
import com.exactpro.th2.ws.client.api.IClient
import com.exactpro.th2.ws.client.api.IHandlerSettings
import com.exactpro.th2.ws.client.api.impl.WebSocketClient
import com.exactpro.th2.ws.client.util.toProto
import com.exactpro.th2.ws.client.util.toTransport
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import mu.KotlinLogging
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import java.net.URI
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.system.exitProcess
import kotlin.text.Charsets.UTF_8
import com.exactpro.th2.common.grpc.MessageGroup as ProtoMessageGroup
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.MessageGroup as TransportMessageGroup
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.RawMessage as TransportRawMessage
import com.exactpro.th2.common.utils.message.transport.MessageBatcher as TransportMessageBatcher

private val LOGGER = KotlinLogging.logger { }

private const val PROTO_GROUP_ATTRIBUTE = "group"
private const val HTTP_INPUT_QUEUE_ATTRIBUTE = "http_send"
private const val WS_INPUT_QUEUE_ATTRIBUTE = "ws_send"

fun main(args: Array<String>) = try {
    val resources = ConcurrentLinkedDeque<Pair<String, () -> Unit>>()

    Runtime.getRuntime().addShutdownHook(thread(start = false, name = "shutdown-hook") {
        resources.descendingIterator().forEach { (resource, destructor) ->
            LOGGER.debug { "Destroying resource: $resource" }
            runCatching(destructor).apply {
                onSuccess { LOGGER.debug { "Successfully destroyed resource: $resource" } }
                onFailure { LOGGER.error(it) { "Failed to destroy resource: $resource" } }
            }
        }
    })

    val factory = args.runCatching(CommonFactory::createFromArguments).getOrElse {
        LOGGER.error(it) { "Failed to create common factory with arguments: ${args.joinToString(" ")}" }
        CommonFactory()
    }.apply { resources += "factory" to ::close }

    val mapper = JsonMapper.builder()
        .addModule(
            KotlinModule.Builder()
                .withReflectionCacheSize(512)
                .configure(KotlinFeature.NullToEmptyCollection, false)
                .configure(KotlinFeature.NullToEmptyMap, false)
                .configure(KotlinFeature.NullIsSameAsDefault, enabled = true)
                .configure(KotlinFeature.SingletonSupport, false)
                .configure(KotlinFeature.StrictNullChecks, false)
                .build()
        )
        .build()

    run(
        factory.boxConfiguration.bookName,
        factory.rootEventId,
        factory.getCustomConfiguration(Settings::class.java, mapper),
        factory.eventBatchRouter,
        factory.messageRouterMessageGroupBatch,
        factory.transportGroupBatchRouter,
        factory.grpcRouter
    ) { resource, destructor ->
        resources += resource to destructor
    }
} catch (e: Exception) {
    LOGGER.error(e) { "Uncaught exception. Shutting down" }
    exitProcess(1)
}

fun run(
    book: String,
    rootEventId: EventID,
    settings: Settings,
    eventRouter: MessageRouter<EventBatch>,
    protoMessageRouter: MessageRouter<MessageGroupBatch>,
    transportMessageRouter: MessageRouter<GroupBatch>,
    grpcRouter: GrpcRouter,
    registerResource: (name: String, destructor: () -> Unit) -> Unit
) {
    val httpSettings = settings.http
    val wsSettings = settings.ws

    val handler = Handler(wsSettings.pingInterval)

    val httpIncomingSequence = createSequence()
    val httpOutgoingSequence = createSequence()

    val wsIncomingSequence = createSequence()
    val wsOutgoingSequence = createSequence()

    val executor = Executors.newScheduledThreadPool(1).also {
        registerResource("executor", it::shutdownGracefully)
    }

    val handlers: Handlers = if (settings.useTransport) {
        val messageBatcher = TransportMessageBatcher(
            settings.maxBatchSize,
            settings.maxFlushTime,
            book,
            MessageBatcher.GROUP_SELECTOR,
            executor
        ) { batch ->
            transportMessageRouter.send(batch)
        }.apply {
            registerResource("transport-message-batcher", ::close)
        }

        Handlers(
            { request: RawHttpRequest ->
                messageBatcher.onMessage(request.toTransportMessage(httpSettings.sessionAlias, httpOutgoingSequence()), settings.sessionGroup)
            },
            { request: RawHttpRequest, response: RawHttpResponse<*> ->
                messageBatcher.onMessage(response.toTransportMessage(httpSettings.sessionAlias, httpIncomingSequence(), request), settings.sessionGroup)
                handler.onResponse(response)
            },
            { message: ByteArray, _: Boolean, direction: Direction ->
                when(direction) {
                    Direction.FIRST -> messageBatcher.onMessage(message.toTransport(wsSettings.sessionAlias, direction, wsIncomingSequence()), settings.sessionGroup)
                    Direction.SECOND -> messageBatcher.onMessage(message.toTransport(wsSettings.sessionAlias, direction, wsOutgoingSequence()), settings.sessionGroup)
                    else -> error("Unsupported '$direction' direction")
                }
            }
        )
    } else {
        val messageBatcher = RawMessageBatcher(
            settings.maxBatchSize,
            settings.maxFlushTime,
            RAW_GROUP_SELECTOR,
            executor
        ) { batch ->
            protoMessageRouter.send(batch, QueueAttribute.RAW.value)
        }.apply {
            registerResource("proto-message-batcher", ::close)
        }

        val httpConnectionId = ConnectionID.newBuilder().setSessionAlias(httpSettings.sessionAlias).build()
        val wsConnectionId = ConnectionID.newBuilder().setSessionAlias(wsSettings.sessionAlias).build()

        Handlers(
            { request: RawHttpRequest ->
                messageBatcher.onMessage(request.toProtoMessage(httpConnectionId, httpOutgoingSequence()))
            },
            { request: RawHttpRequest, response: RawHttpResponse<*> ->
                messageBatcher.onMessage(response.toProtoMessage(httpConnectionId, httpIncomingSequence(), request))
                handler.onResponse(response)
            },
            { message: ByteArray, _: Boolean, direction: Direction ->
                when(direction) {
                    Direction.FIRST -> messageBatcher.onMessage(message.toProto(wsConnectionId, direction, wsIncomingSequence()))
                    Direction.SECOND -> messageBatcher.onMessage(message.toProto(wsConnectionId, direction, wsOutgoingSequence()))
                    else -> error("Unsupported '$direction' direction")
                }
            }
        )
    }

    val onEvent: (cause: Throwable?, message: () -> String) -> Unit = { cause: Throwable?, message: () -> String ->
        val type = if (cause != null) "Error" else "Info"
        eventRouter.storeEvent(rootEventId, message(), type, cause)
    }

    val httpClient = HttpClient(
        httpSettings.https,
        httpSettings.host,
        httpSettings.port,
        httpSettings.readTimeout,
        httpSettings.keepAliveTimeout,
        httpSettings.maxParallelRequests,
        httpSettings.defaultHeaders,
        handler::prepareRequest,
        handlers.onHttpRequest,
        handlers.onHttpResponse,
        handler::onStart,
        handler::onStop
    ).apply { registerResource("client", ::close) }

    val wsClient = WebSocketClient(
        URI(wsSettings.uri),
        handler,
        handlers.onWsMessage,
        onEvent
    ).apply { registerResource("client", ::stop) }

    handler.runCatching {
        registerResource("handler", ::close)
        init(httpClient, wsClient)
    }.onFailure {
        LOGGER.error(it) { "Failed to init handler" }
        onEvent(it) { "Failed to init handler" }
        throw it
    }

    val controller = subscribe(
        httpClient,
        registerResource,
        protoMessageRouter,
        settings,
        onEvent,
        transportMessageRouter,
        wsSettings,
        wsClient
    )

    if (settings.autoStart) httpClient.start()
    if (settings.grpcStartControl) grpcRouter.startServer(ControlService(controller))

    LOGGER.info { "Successfully started" }

    ReentrantLock().run {
        val condition = newCondition()
        registerResource("await-shutdown") { withLock(condition::signalAll) }
        withLock(condition::await)
    }

    LOGGER.info { "Finished running" }
}

private fun subscribe(
    httpClient: HttpClient,
    registerResource: (name: String, destructor: () -> Unit) -> Unit,
    protoMessageRouter: MessageRouter<MessageGroupBatch>,
    settings: Settings,
    onEvent: (cause: Throwable?, message: () -> String) -> Unit,
    transportMessageRouter: MessageRouter<GroupBatch>,
    wsSettings: WebSocketSettings,
    wsClient: WebSocketClient
): ClientController {
    val controller = ClientController(httpClient).apply { registerResource("controller", ::close) }

    val protoHttp =
        protoMessageRouter.checkedSubscribeProto(HTTP_INPUT_QUEUE_ATTRIBUTE, controller, settings.autoStopAfter, onEvent) {
            httpClient.send(toRequest())
        }.onSuccess {
            registerResource("proto-http-raw-monitor", it::unsubscribe)
        }

    val transportHttp = transportMessageRouter.checkedSubscribeTransport(
        HTTP_INPUT_QUEUE_ATTRIBUTE,
        controller,
        settings.autoStopAfter,
        onEvent
    ) {
        httpClient.send(toRequest())
    }.onSuccess {
        registerResource("proto-http-raw-monitor", it::unsubscribe)
    }

    if (protoHttp.isFailure && transportHttp.isFailure) {
        error("Subscribe http pin should be declared at least one of protobuf or transport protocols")
    }

    val protoWS =
        protoMessageRouter.checkedSubscribeProto(WS_INPUT_QUEUE_ATTRIBUTE, controller, settings.autoStopAfter, onEvent) {
            require(messagesCount == 1) { "Message group contains more than 1 message" }
            val message = messagesList[0]
            require(message.hasRawMessage()) { "Message in the group is not a raw message" }
            wsSettings.frameType.send(wsClient, message.rawMessage.body.toByteArray())
        }.onSuccess {
            registerResource("proto-ws-raw-monitor", it::unsubscribe)
        }

    val transportWS =
        transportMessageRouter.checkedSubscribeTransport(WS_INPUT_QUEUE_ATTRIBUTE, controller, settings.autoStopAfter, onEvent) {
            require(messages.size == 1) { "Message group contains more than 1 message" }
            val message = messages.single()
            require(message is TransportRawMessage) { "Message in the group is not a raw message" }
            wsSettings.frameType.send(wsClient, message.body.toByteArray())
        }.onSuccess {
            registerResource("proto-ws-raw-monitor", it::unsubscribe)
        }

    if (protoWS.isFailure && transportWS.isFailure) {
        error("Subscribe ws pin should be declared at least one of protobuf or transport protocols")
    }
    return controller
}

private fun MessageRouter<MessageGroupBatch>.checkedSubscribeProto(
    pinAttribute: String,
    clientController: ClientController,
    stopClientAfter: Int,
    onEvent: (cause: Throwable?, message: () -> String) -> Unit,
    onGroup: ProtoMessageGroup.() -> Unit
) = runCatching {
    checkNotNull(subscribe({ _, batch ->
        if (!clientController.isRunning) clientController.start(stopClientAfter)
        batch.groupsList.forEach { group ->
            group.runCatching(onGroup).recoverCatching {
                LOGGER.error(it) { "Failed to handle message group: ${group.toPrettyString()}" }
                onEvent(it) { "Failed to handle message group: ${group.toPrettyString()}" }
            }
        }
    }, PROTO_GROUP_ATTRIBUTE, pinAttribute))
}.onFailure {
    LOGGER.warn(it) { "Failed to subscribe to proto pin with attribute: $pinAttribute" }
}

private fun MessageRouter<GroupBatch>.checkedSubscribeTransport(
    pinAttribute: String,
    clientController: ClientController,
    stopClientAfter: Int,
    onEvent: (cause: Throwable?, message: () -> String) -> Unit,
    onGroup: TransportMessageGroup.() -> Unit
) = runCatching {
    checkNotNull(subscribe({ _, batch ->
        if (!clientController.isRunning) clientController.start(stopClientAfter)
        batch.groups.forEach { group ->
            group.runCatching(onGroup).recoverCatching {
                LOGGER.error(it) { "Failed to handle message group: $group" }
                onEvent(it) { "Failed to handle message group: $group" }
            }
        }
    }, pinAttribute))
}.onFailure {
    LOGGER.warn(it) { "Failed to subscribe to transport pin with attribute: $pinAttribute" }
}

data class Handlers(
    val onHttpRequest: (RawHttpRequest) -> Unit,
    val onHttpResponse: (RawHttpRequest, RawHttpResponse<*>) -> Unit,
    val onWsMessage: (message: ByteArray, textual: Boolean, direction: Direction) -> Unit,
)

data class Settings(
    val autoStart: Boolean = true,
    val autoStopAfter: Int = 0,
    val grpcStartControl: Boolean = true,
    val sessionGroup: String,
    val maxBatchSize: Int = 100,
    val maxFlushTime: Long  = 1000,
    val useTransport: Boolean = true,
    val http: HttpSettings,
    val ws: WebSocketSettings
)

data class HttpSettings(
    val https: Boolean = false,
    val host: String,
    val port: Int = if (https) 443 else 80,
    val readTimeout: Int = 5000,
    val keepAliveTimeout: Long = 15000,
    val defaultHeaders: Map<String, List<String>> = emptyMap(),
    val sessionAlias: String,
    val maxParallelRequests: Int = 5,
) : IAuthSettings

data class WebSocketSettings(
    val uri: String,
    val frameType: FrameType = TEXT,
    val pingInterval: Long = 30000,
    val sessionAlias: String
) : IHandlerSettings {
    enum class FrameType {
        TEXT {
            override fun send(client: IClient, data: ByteArray) = client.sendText(data.toString(UTF_8))
        },
        @Suppress("unused")
        BINARY {
            override fun send(client: IClient, data: ByteArray) = client.sendBinary(data)
        };

        abstract fun send(client: IClient, data: ByteArray)
    }
}

private fun createSequence(): () -> Long = Instant.now().run {
    AtomicLong(epochSecond * SECONDS.toNanos(1) + nano)
}::incrementAndGet
