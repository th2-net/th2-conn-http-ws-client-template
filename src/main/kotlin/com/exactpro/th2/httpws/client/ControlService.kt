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

import com.exactpro.th2.conn.grpc.ConnGrpc.ConnImplBase
import com.exactpro.th2.conn.grpc.Response
import com.exactpro.th2.conn.grpc.Response.Status.FAILURE
import com.exactpro.th2.conn.grpc.Response.Status.SUCCESS
import com.exactpro.th2.conn.grpc.StartRequest
import com.exactpro.th2.conn.grpc.StopRequest
import io.grpc.stub.StreamObserver

class ControlService(private val controller: ClientController) : ConnImplBase() {
    override fun start(request: StartRequest, observer: StreamObserver<Response>) = observer {
        when {
            controller.isRunning -> failure("Already running")
            else -> {
                controller.start(request.stopAfter)
                when {
                    request.stopAfter > 0 -> success("Started with scheduled stop after ${request.stopAfter} seconds")
                    else -> success("Successfully started")
                }
            }
        }
    }

    override fun stop(request: StopRequest, observer: StreamObserver<Response>) = observer {
        when {
            !controller.isRunning -> failure("Already stopped")
            else -> {
                controller.stop()
                success("Successfully stopped")
            }
        }
    }

    private fun success(message: String) = Response.newBuilder().setStatus(SUCCESS).setMessage(message).build()

    private fun failure(message: String) = Response.newBuilder().setStatus(FAILURE).setMessage(message).build()

    private operator fun StreamObserver<Response>.invoke(block: () -> Response) = runCatching {
        onNext(synchronized(this@ControlService, block))
        onCompleted()
    }.getOrElse(this::onError)
}