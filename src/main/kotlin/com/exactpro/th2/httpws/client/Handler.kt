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

package com.exactpro.th2.httpws.client

import com.exactpro.th2.http.client.HttpClient
import com.exactpro.th2.http.client.api.IStateManager
import com.exactpro.th2.http.client.api.IStateManager.StateManagerContext
import com.exactpro.th2.ws.client.api.IClient
import com.exactpro.th2.ws.client.api.IClientSettings
import com.exactpro.th2.ws.client.api.IHandler
import com.exactpro.th2.ws.client.api.impl.WebSocketClient
import mu.KotlinLogging
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import java.util.Timer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.timer
import kotlin.concurrent.withLock

class Handler(private val pingInterval: Long) : IStateManager, IHandler {
    private val logger = KotlinLogging.logger {}
    private val startLock = ReentrantLock()
    private val timerLock = ReentrantLock()

    private lateinit var httpClient: HttpClient
    private lateinit var wsClient: WebSocketClient
    private lateinit var timer: Timer
    @Volatile private var isRunning = false

    override fun init(context: StateManagerContext) {}

    fun init(httpClient: HttpClient, wsClient: WebSocketClient) {
        this.httpClient = httpClient
        this.wsClient = wsClient
    }

     private fun restart() {
        logger.warn { "Restarting client" }

        if (!isRunning) {
            logger.warn { "Skipping restart because client is not running" }
            return
        }

        startLock.withLock {
            onStop()
            onStart()
        }
    }

    override fun onStart() {
        logger.info { "Executing onStart sequence" }

        startLock.withLock {
            try {
                isRunning = true
                wsClient.start()
            } catch (e: Exception) {
                logger.error(e) { "Failed to execute onStart sequence" }
                restart()
            }
        }
    }

    override fun preOpen(clientSettings: IClientSettings) {
        //TODO: retrieve data required for WS connection
    }

    private fun cancelTimer() = timerLock.withLock {
        if (::timer.isInitialized) {
            timer.runCatching(Timer::cancel).onFailure {
                logger.error(it) { "Failed to cancel existing ping timer" }
            }
        }
    }

    private fun createTimer(client: IClient) = timerLock.withLock {
        cancelTimer()

        this.timer = timer(initialDelay = pingInterval, period = pingInterval) {
            EMPTY_MESSAGE.runCatching(client::sendPing).onFailure {
                logger.error(it) { "Failed to send ping" }
            }
        }
    }

    override fun onOpen(client: IClient) {
        // TODO: stuff necessary after opening WS connection
        createTimer(client)
    }

    override fun prepareRequest(request: RawHttpRequest): RawHttpRequest = request

    override fun onResponse(response: RawHttpResponse<*>) = Unit

    override fun onPing(client: IClient, data: ByteArray) = startLock.withLock { createTimer(client) }

    override fun onError(error: Throwable) = restart()

    override fun onClose(statusCode: Int, reason: String) = restart()

    override fun onStop() {
        logger.info { "Executing onStop sequence" }

        startLock.withLock {
            try {
                isRunning = false
                wsClient.stop()
            } catch (e: Exception) {
                logger.error(e) { "Failed to execute onStop sequence" }
            }
        }
    }

    override fun close() = cancelTimer()

    companion object {
        private val EMPTY_MESSAGE = byteArrayOf()
    }
}
