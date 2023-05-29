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
import com.exactpro.th2.email.loader.FileState
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Service
import jakarta.mail.Session
import java.lang.Integer.min
import java.util.Date
import java.util.concurrent.ExecutorService
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPMessage
import org.eclipse.angus.mail.imap.IMAPStore

class IMAPReceiver(
    session: Session,
    private val handler: (Message) -> Unit,
    private val receiverConfig: ReceiverConfig,
    private val executorService: ExecutorService,
    private val getLastProcessedDate: () -> Date?,
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
    private lateinit var folder: IMAPFolder
    private val emailListener = EmailListener(handler)
    private var lastProcessedMessageDate: Date? = getLastProcessedDate()

    @Volatile private var isRunning = true

    override fun start() {
        store.connect()
        isRunning = true
        folder = store.getFolder(receiverConfig.folder) as IMAPFolder
    }

    override fun subscribe() {
        if(!folder.isOpen) folder.open(Folder.READ_ONLY)

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

    override fun getState() = lastProcessedMessageDate?.let { FileState(it) }

    private fun findResumeMessageNumber(folder: Folder, previousDate: Date): Int? {
        var low = 1
        var high = folder.messageCount
        var resumeMessageNumber: Int? = null

        var nearestMessageNumberTop: Int? = null
        var nearestMessageNumberBottom: Int? = null

        while (low <= high) {
            val mid = (low + high) / 2
            val message = folder.getMessage(mid) as IMAPMessage

            val messageDate = message.date()

            if (messageDate != null && messageDate.after(previousDate)) {
                nearestMessageNumberTop = mid
                high = mid - 1
            } else {
                nearestMessageNumberBottom = mid
                low = mid + 1
            }
        }


        if(nearestMessageNumberBottom == null && nearestMessageNumberTop == null) return null

        if(nearestMessageNumberTop != null) {
            while (nearestMessageNumberTop > 0) {
                val message = folder.getMessage(nearestMessageNumberTop)
                val messageDate = message.date() ?: continue
                if(messageDate.before(previousDate) || messageDate == previousDate) break
                resumeMessageNumber = nearestMessageNumberTop
                nearestMessageNumberTop -= 1
            }

            return resumeMessageNumber
        }

        if(nearestMessageNumberBottom != null) {
            while (nearestMessageNumberBottom < folder.messageCount + 1) {
                val message = folder.getMessage(nearestMessageNumberBottom)
                val messageDate = message.date() ?: continue
                if(messageDate.after(previousDate)) break
                resumeMessageNumber = nearestMessageNumberBottom
                nearestMessageNumberBottom += 1
            }
        }

        return resumeMessageNumber
    }

    override fun stop() {
        isRunning = false
        folder.removeMessageCountListener(emailListener)
        if(folder.isOpen) folder.close()
        store.close()
    }
}