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
import com.exactpro.th2.email.config.ReceiverConfig
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Service
import jakarta.mail.Session
import java.lang.Integer.min
import java.util.concurrent.ExecutorService
import org.eclipse.angus.mail.pop3.POP3Folder
import org.eclipse.angus.mail.pop3.POP3Store


class POP3Receiver(
    session: Session,
    private val handler: (Message) -> Unit,
    private val receiverConfig: ReceiverConfig,
    private val executor: ExecutorService,
    private val sendEvent: (Event) -> Unit
): IReceiver {
    init {
        check(receiverConfig.folder == "INBOX") { "Only inbox folder is allowed for POP3 receiver." }
    }
    override val service: Service = session.store
    private val store = service as POP3Store
    @Volatile private var isRunning = true

    override fun start() {
        store.addConnectionListener(
            ReceiverConnectionListener(
                this,
                receiverConfig.reconnectInterval,
                sendEvent,
                executor
            )
        )
        store.connect()
    }

    override fun subscribe() {
        // TODO: Think about how to filter messages handled on previous iterations
        val folder = store.getFolder(receiverConfig.folder) as POP3Folder
        folder.open(Folder.READ_ONLY)

        var rangeStart = 1
        var rangeEnd = min(rangeStart + receiverConfig.fetchCount, folder.messageCount)

        var lastProcessedMessageNumber = 0

        while (rangeEnd <= folder.messageCount) {
            val messages = if(rangeEnd - rangeStart <= 1) {
                arrayOf(folder.getMessage(rangeEnd))
            } else {
                folder.getMessages(rangeStart, rangeEnd)
            }

            for (message in messages) {
                handler(message)
                lastProcessedMessageNumber = message.messageNumber
            }
            if(rangeEnd == folder.messageCount) break
            rangeStart = rangeEnd
            rangeEnd = min(rangeStart + receiverConfig.fetchCount, folder.messageCount)
        }

        folder.close()

        while (isRunning) {
            val folder = store.getFolder(receiverConfig.folder) as POP3Folder
            folder.open(Folder.READ_ONLY)
            lastProcessedMessageNumber = poll(lastProcessedMessageNumber, folder, handler)
            folder.close()
            Thread.sleep(receiverConfig.pollInterval)
        }

        folder.close()
        service.close()
    }

    private fun poll(lastProcessed: Int, folder: POP3Folder, handler: (Message) -> Unit): Int {
        // TODO: That is bad solution for retreiving new messages. Think about how to filter messages handled on previous iterations
        if(lastProcessed == folder.messageCount) return lastProcessed
        var lastProcessedMessageNumber = lastProcessed
        var rangeStart = lastProcessed
        var rangeEnd = min(rangeStart + receiverConfig.fetchCount, folder.messageCount)

        while (rangeEnd <= folder.messageCount) {
            val messages = if(rangeEnd - rangeStart <= 1) {
                arrayOf(folder.getMessage(rangeEnd))
            } else {
                folder.getMessages(rangeStart, rangeEnd)
            }

            for (message in messages) {
                handler(message)
                lastProcessedMessageNumber = message.messageNumber
            }
            if(rangeEnd == folder.messageCount) break
            rangeStart = rangeEnd
            rangeEnd = min(rangeStart + receiverConfig.fetchCount, folder.messageCount)
        }

        return lastProcessedMessageNumber
    }

    override fun stop() {
        isRunning = false
    }
}