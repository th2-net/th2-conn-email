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
import com.exactpro.th2.email.filter.Filter.Companion.allowed
import com.exactpro.th2.email.loader.FolderState
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Service
import jakarta.mail.Session
import java.lang.Integer.min
import java.util.Date
import java.util.concurrent.ExecutorService
import mu.KotlinLogging
import org.eclipse.angus.mail.pop3.POP3Store


class POP3Receiver(
    session: Session,
    private val handler: (Message) -> Unit,
    private val receiverConfig: ReceiverConfig,
    private val executor: ExecutorService,
    private val getLastProcessedDate: () -> Date?,
    private val sendEvent: (Event) -> Unit
): IReceiver {
    init {
        check(receiverConfig.folder == "INBOX") { "Only inbox folder is allowed for POP3 receiver." }
    }
    override val service: Service = session.store
    private val store = service as POP3Store
    private val filters = receiverConfig.filters

    @Volatile private var isRunning = true
    private var lastProcessedMessageDate: Date? = getLastProcessedDate()

    override fun start() {
        isRunning = true
        executor.submit {
            K_LOGGER.info { "Started POP3 listener." }
            subscribe()
        }
    }

    override fun subscribe() {
        var lastProcessedMessage = getUpdatesSinceLastRun()
        while (isRunning) {
            K_LOGGER.info { "Polling ${receiverConfig.folder} for new messages." }
            lastProcessedMessage = poll(lastProcessedMessage)
            Thread.sleep(receiverConfig.pollInterval)
        }
        service.close()
    }

    override fun getState() = lastProcessedMessageDate?.let { FolderState(it) }

    private fun poll(lastProcessedMessage: Int): Int {
        try {
            if (!store.isConnected) store.connect()
            val folder = store.getFolder(receiverConfig.folder)
            if (!folder.isOpen) folder.open(Folder.READ_ONLY)
            if (folder.messageCount < 0) return lastProcessedMessage
            if (folder.messageCount == lastProcessedMessage) return lastProcessedMessage

            var rangeStart = lastProcessedMessage
            var rangeEnd = min(rangeStart + receiverConfig.fetchCount, folder.messageCount)

            while (rangeEnd <= folder.messageCount) {
                val messages = if (rangeEnd - rangeStart <= 1) {
                    arrayOf(folder.getMessage(rangeEnd))
                } else {
                    folder.getMessages(rangeStart, rangeEnd)
                }

                for (message in messages) {
                    if (!filters.allowed(message)) {
                        message?.date()?.let { lastProcessedMessageDate = it }
                        continue
                    }
                    handler(message)
                    message?.date()?.let { lastProcessedMessageDate = it }
                }
                if (rangeEnd == folder.messageCount) break
                rangeStart = rangeEnd
                rangeEnd = min(rangeStart + receiverConfig.fetchCount, folder.messageCount)
            }

            folder.close()
            return rangeEnd
        } catch (e: Exception) {
            K_LOGGER.error(e) { "Error while polling." }
            return lastProcessedMessage
        }
    }

    private fun getUpdatesSinceLastRun(): Int {
        try {
            if(!store.isConnected) store.connect()
            val folder = store.getFolder(receiverConfig.folder)
            if(!folder.isOpen) folder.open(Folder.READ_ONLY)
            val messageCount = folder.messageCount
            if(messageCount < 0) return messageCount

            val resumeDate = resumeDate(lastProcessedMessageDate, receiverConfig.startProcessingAtLeastFrom)
            val resumeMessage = if(resumeDate == null) {
                1
            } else {
                findResumeMessageNumber(folder, resumeDate) ?: return messageCount
            }

            var rangeStart = resumeMessage
            var rangeEnd = min(rangeStart + receiverConfig.fetchCount, folder.messageCount)

            while (rangeEnd <= folder.messageCount) {
                val messages = if(rangeEnd - rangeStart <= 1) {
                    arrayOf(folder.getMessage(rangeEnd))
                } else {
                    folder.getMessages(rangeStart, rangeEnd)
                }

                for (message in messages) {
                    if(!filters.allowed(message)) {
                        message?.date()?.let { lastProcessedMessageDate = it }
                        continue
                    }
                    handler(message)
                    message?.date()?.let { lastProcessedMessageDate = it }
                }
                if(rangeEnd == folder.messageCount) break
                rangeStart = rangeEnd
                rangeEnd = min(rangeStart + receiverConfig.fetchCount, folder.messageCount)
            }

            folder.close()
            return messageCount
        } catch (e: Exception) {
            K_LOGGER.error(e) { "Error while getting update." }
            return 0
        }
    }

    override fun stop() {
        isRunning = false
        Thread.sleep(receiverConfig.reconnectInterval)
        if(store.isConnected) store.close()
    }

    companion object {
        private val K_LOGGER = KotlinLogging.logger {  }
    }
}