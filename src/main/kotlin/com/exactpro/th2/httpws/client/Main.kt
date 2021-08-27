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

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.EventBatch
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.schema.factory.CommonFactory
import com.exactpro.th2.common.schema.grpc.router.GrpcRouter
import com.exactpro.th2.common.schema.message.MessageRouter
import com.exactpro.th2.common.schema.message.QueueAttribute.FIRST
import com.exactpro.th2.common.schema.message.QueueAttribute.SECOND
import com.exactpro.th2.common.schema.message.storeEvent
import com.exactpro.th2.http.client.HttpClient
import com.exactpro.th2.http.client.api.IAuthSettings
import com.exactpro.th2.http.client.util.toBatch
import com.exactpro.th2.http.client.util.toPrettyString
import com.exactpro.th2.http.client.util.toRequest
import com.exactpro.th2.httpws.client.WebSocketSettings.FrameType.TEXT
import com.exactpro.th2.ws.client.api.IClient
import com.exactpro.th2.ws.client.api.IHandlerSettings
import com.exactpro.th2.ws.client.api.impl.WebSocketClient
import com.exactpro.th2.ws.client.util.toBatch
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import mu.KotlinLogging
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import java.net.URI
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.system.exitProcess
import kotlin.text.Charsets.UTF_8

private val LOGGER = KotlinLogging.logger { }

private const val HTTP_INPUT_QUEUE_ATTRIBUTE = "http_send"
private const val HTTP_REQUEST_OUTPUT_QUEUE_ATTRIBUTE = "http_request"
private const val HTTP_RESPONSE_OUTPUT_QUEUE_ATTRIBUTE = "http_response"

private const val WS_INPUT_QUEUE_ATTRIBUTE = "ws_send"
private const val WS_REQUEST_OUTPUT_QUEUE_ATTRIBUTE = "ws_incoming"
private const val WS_RESPONSE_OUTPUT_QUEUE_ATTRIBUTE = "ws_outgoing"

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
        .addModule(KotlinModule(nullIsSameAsDefault = true))
        .build()

    val settings = factory.getCustomConfiguration(Settings::class.java, mapper)
    val eventRouter = factory.eventBatchRouter
    val messageRouter = factory.messageRouterMessageGroupBatch
    val grpcRouter = factory.grpcRouter

    run(settings, eventRouter, messageRouter, grpcRouter) { resource, destructor ->
        resources += resource to destructor
    }
} catch (e: Exception) {
    LOGGER.error(e) { "Uncaught exception. Shutting down" }
    exitProcess(1)
}

fun run(
    settings: Settings,
    eventRouter: MessageRouter<EventBatch>,
    messageRouter: MessageRouter<MessageGroupBatch>,
    grpcRouter: GrpcRouter,
    registerResource: (name: String, destructor: () -> Unit) -> Unit
) {
    val httpSettings = settings.http
    val wsSettings = settings.ws

    val rootEventId = eventRouter.storeEvent(Event.start().apply {
        name("${Instant.now()} - HTTP session: ${httpSettings.sessionAlias}, WS session: ${wsSettings.sessionAlias}")
        type("Microservice")
    }).id

    val handler = Handler(wsSettings.pingInterval)

    val httpIncomingSequence = createSequence()
    val httpOutgoingSequence = createSequence()
    val httpConnectionId = ConnectionID.newBuilder().setSessionAlias(httpSettings.sessionAlias).build()

    val onHttpRequest = { request: RawHttpRequest ->
        messageRouter.send(request.toBatch(httpConnectionId, httpOutgoingSequence()), SECOND.toString(), HTTP_REQUEST_OUTPUT_QUEUE_ATTRIBUTE)
    }

    val onHttpResponse = { request: RawHttpRequest, response: RawHttpResponse<*> ->
        messageRouter.send(response.toBatch(httpConnectionId, httpIncomingSequence(), request), FIRST.toString(), HTTP_RESPONSE_OUTPUT_QUEUE_ATTRIBUTE)
        handler.onResponse(response)
    }

    val wsIncomingSequence = createSequence()
    val wsOutgoingSequence = createSequence()
    val wsConnectionId = ConnectionID.newBuilder().setSessionAlias(wsSettings.sessionAlias).build()

    val onWsMessage = { message: ByteArray, _: Boolean, direction: Direction ->
        when(direction) {
            Direction.FIRST -> messageRouter.send(message.toBatch(wsConnectionId, direction, wsIncomingSequence()), FIRST.toString(), WS_RESPONSE_OUTPUT_QUEUE_ATTRIBUTE)
            else -> messageRouter.send(message.toBatch(wsConnectionId, direction, wsOutgoingSequence()), SECOND.toString(), WS_REQUEST_OUTPUT_QUEUE_ATTRIBUTE)
        }
    }

    val onEvent = { cause: Throwable?, message: () -> String ->
        val type = if (cause != null) "Error" else "Info"
        eventRouter.storeEvent(rootEventId, message(), type, cause)
    }

    val httpClient = HttpClient(
        httpSettings.https,
        httpSettings.host,
        httpSettings.port,
        httpSettings.readTimeout,
        httpSettings.keepAliveTimeout,
        httpSettings.defaultHeaders,
        handler::prepareRequest,
        onHttpRequest,
        onHttpResponse,
        handler::onStart,
        handler::onStop
    ).apply { registerResource("client", ::close) }

    val wsClient = WebSocketClient(
        URI(wsSettings.uri),
        handler,
        onWsMessage,
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

    val controller = ClientController(httpClient).apply { registerResource("controller", ::close) }

    messageRouter.checkedSubscribe(HTTP_INPUT_QUEUE_ATTRIBUTE, controller, settings.autoStopAfter, onEvent) {
        httpClient.send(toRequest())
    }.apply {
        registerResource("http-raw-monitor", ::unsubscribe)
    }

    messageRouter.checkedSubscribe(WS_INPUT_QUEUE_ATTRIBUTE, controller, settings.autoStopAfter, onEvent)  {
        require(messagesCount == 1) { "Message group contains more than 1 message" }
        val message = messagesList[0]
        require(message.hasRawMessage()) { "Message in the group is not a raw message" }
        wsSettings.frameType.send(wsClient, message.rawMessage.body.toByteArray())
    }.apply {
        registerResource("ws-raw-monitor", ::unsubscribe)
    }

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

private fun MessageRouter<MessageGroupBatch>.checkedSubscribe(
    queueAttribute: String,
    clientController: ClientController,
    stopClientAfter: Int,
    onEvent: (cause: Throwable?, message: () -> String) -> Unit,
    onGroup: MessageGroup.() -> Unit
) = runCatching {
    checkNotNull(subscribe({ _, batch ->
        if (!clientController.isRunning) clientController.start(stopClientAfter)
        batch.groupsList.forEach { group ->
            group.runCatching(onGroup).recoverCatching {
                LOGGER.error(it) { "Failed to handle message group: ${group.toPrettyString()}" }
                onEvent(it) { "Failed to handle message group: ${group.toPrettyString()}" }
            }
        }
    }, queueAttribute))
}.getOrElse {
    throw IllegalStateException("Failed to subscribe to queue with attribute: $queueAttribute", it)
}

data class Settings(
    val autoStart: Boolean = true,
    val autoStopAfter: Int = 0,
    val grpcStartControl: Boolean = true,
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
    val sessionAlias: String
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
        BINARY {
            override fun send(client: IClient, data: ByteArray) = client.sendBinary(data)
        };

        abstract fun send(client: IClient, data: ByteArray)
    }
}

private fun createSequence(): () -> Long = Instant.now().run {
    AtomicLong(epochSecond * SECONDS.toNanos(1) + nano)
}::incrementAndGet
