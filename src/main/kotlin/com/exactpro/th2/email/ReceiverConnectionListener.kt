/*
 * Copyright 2023 Exactpro (Exactpro Systems Limited)
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
package com.exactpro.th2.email

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.email.api.IReceiver
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.event.ConnectionEvent
import jakarta.mail.event.ConnectionListener
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import mu.KotlinLogging
import org.slf4j.ILoggerFactory

class ReceiverConnectionListener(
    private val receiver: IReceiver,
    private val reconnectInterval: Long,
    private val sendEvent: (Event) -> Unit,
    private val executor: ExecutorService
): ConnectionListener {

    override fun opened(e: ConnectionEvent?) {
        executor.submit {
            onInfo("Started service: ${receiver.service}")
            receiver.subscribe()
        }
    }
    override fun closed(e: ConnectionEvent?) {}

    override fun disconnected(e: ConnectionEvent?) {
        while(!receiver.service.isConnected) {
            try {
                onInfo("Reconnection attempt started. Service: ${receiver.service}")
                receiver.service.connect()
            }
            catch (e: Exception) {
                onError("Reconnection attempt failed. Next attempt in $reconnectInterval MS. Service: ${receiver.service}", e)
                Thread.sleep(reconnectInterval)
            }
        }
    }

    private fun onInfo(name: String) {
        K_LOGGER.info { name }
        sendEvent(
            Event.start()
                .type(EVENT_TYPE)
                .name(name)
        )
    }

    private fun onError(name: String, throwable: Throwable) {
        K_LOGGER.error(throwable) { name }
        sendEvent(
            Event.start()
                .type(EVENT_TYPE)
                .name(name)
                .exception(throwable, true)
        )
    }

    companion object {
        private val K_LOGGER = KotlinLogging.logger {  }
        private const val EVENT_TYPE = "Receiver Connectivity"
    }
}