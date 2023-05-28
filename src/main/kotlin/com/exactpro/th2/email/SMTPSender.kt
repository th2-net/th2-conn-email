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

import com.exactpro.th2.email.api.MailService
import jakarta.mail.Message
import jakarta.mail.Service
import jakarta.mail.Session
import mu.KotlinLogging
import org.eclipse.angus.mail.smtp.SMTPTransport

class SMTPSender(
      val session: Session,
      private val reconnectTimeout: Long
): MailService {
    override val service: Service = session.transport
    private val transport = service as SMTPTransport

    fun sendBatch(messages: List<Message>) {
        messages.forEach { send(it) }
    }

    fun send(message: Message) {
        ensureOpen()
        try {
            transport.sendMessage(message, message.allRecipients)
        } catch (e: Exception) {
            K_LOGGER.error(e) { "Failed to send message $message using $service" }
        }
    }

    fun close() = transport.close()

    private fun ensureOpen() {
        while (!service.isConnected) {
            try {
                K_LOGGER.info { "Connection attempt on sender service: $service" }
                service.connect()
            }
            catch (e: Exception) {
                K_LOGGER.error(e) { "Connect attempt failed on sender service: $service. Reconnect in $reconnectTimeout ms." }
                Thread.sleep(reconnectTimeout)
            }
        }
    }

    companion object {
        private val K_LOGGER = KotlinLogging.logger {  }
    }
}