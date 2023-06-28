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
import com.exactpro.th2.email.filter.Filter
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
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore

class IMAPReceiver(
    session: Session,
    private val handler: (Message) -> Unit,
    private val receiverConfig: ReceiverConfig,
    private val executorService: ExecutorService,
    getLastProcessedDate: () -> Date?,
    private val sendEvent: (Event) -> Unit
): IReceiver {
    override val service: Service = session.store
    private val store = (service as IMAPStore).apply {
        addConnectionListener(ReceiverConnectionListener(
            this@IMAPReceiver,
            receiverConfig.reconnectInterval,
            sendEvent,
            executorService
        ))
    }
    private val filters: List<Filter> = receiverConfig.filters
    private val emailListener = EmailListener(handler, filters) { lastProcessedMessageDate = it }

    private var lastProcessedMessageDate: Date? = getLastProcessedDate()

    @Volatile private var folder: IMAPFolder? = null
    @Volatile private var isRunning = true

    override fun start() {
        try {
            store.connect()
        } catch (e: Exception) {
            K_LOGGER.error(e) { "Error while connection to server." }
        }
        isRunning = true
    }

    override fun subscribe() {
        val folder = store.getFolder(receiverConfig.folder) as IMAPFolder
        if(!folder.isOpen) folder.open(Folder.READ_ONLY)
        this.folder = folder

        val resumeDate = resumeDate(lastProcessedMessageDate, receiverConfig.startProcessingAtLeastFrom)
        val resumeMessage = if(resumeDate == null) {
            1
        } else {
            findResumeMessageNumber(folder, resumeDate) ?: return
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
                    message.date()?.let { lastProcessedMessageDate = it }
                    continue
                }
                handler(message)
                message.date()?.let { lastProcessedMessageDate = it }
            }
            if(rangeEnd == folder.messageCount) break
            rangeStart = rangeEnd
            rangeEnd = min(rangeStart + receiverConfig.fetchCount, folder.messageCount)
        }

        folder.addMessageCountListener(emailListener)

        while (isRunning) {
            folder.idle()
        }

        folder.close()
        service.close()
    }

    override fun getState() = lastProcessedMessageDate?.let { FolderState(it) }

    override fun stop() {
        isRunning = false
        folder?.removeMessageCountListener(emailListener)
        if(folder?.isOpen == true) folder?.close()
        store.close()
    }

    companion object {
        private val K_LOGGER = KotlinLogging.logger {  }
    }
}