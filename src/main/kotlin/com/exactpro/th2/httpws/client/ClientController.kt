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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit.SECONDS

class ClientController(private val client: HttpClient) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var stopFuture: Future<*> = CompletableFuture.completedFuture(null)

    val isRunning: Boolean
        get() = client.isRunning

    fun start(stopAfter: Int = 0) = synchronized(this) {
        if (!isRunning) {
            client.start()

            if (stopAfter > 0) {
                stopFuture = executor.schedule(client::stop, stopAfter.toLong(), SECONDS)
            }
        }
    }

    fun stop() = synchronized(this) {
        if (isRunning) {
            stopFuture.cancel(true)
            client.stop()
        }
    }

    override fun close() = synchronized(this) {
        executor.shutdown()
        if (!executor.awaitTermination(5, SECONDS)) executor.shutdownNow()
    }
}