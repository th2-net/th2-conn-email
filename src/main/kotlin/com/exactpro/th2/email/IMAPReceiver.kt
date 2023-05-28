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
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore

class IMAPReceiver(
    session: Session,
    private val handler: (Message) -> Unit,
    private val receiverConfig: ReceiverConfig,
    private val executorService: ExecutorService,
    private val sendEvent: (Event) -> Unit
): IReceiver {
    override val service: Service = session.store
    private val store = service as IMAPStore

    @Volatile private var isRunning = true

    override fun start() {
        store.addConnectionListener(
            ReceiverConnectionListener(
                this,
                receiverConfig.reconnectInterval,
                sendEvent,
                executorService
            )
        )
        store.connect()
    }

    override fun subscribe() {
        // TODO: Think about filtering messages handled on previous iterations
        val folder = store.getFolder(receiverConfig.folder) as IMAPFolder
        folder.open(Folder.READ_ONLY)

        var rangeStart = 1
        var rangeEnd = min(rangeStart + receiverConfig.fetchCount, folder.messageCount)

        while (rangeEnd <= folder.messageCount) {
            val messages = if(rangeEnd - rangeStart <= 1) {
                arrayOf(folder.getMessage(rangeEnd))
            } else {
                folder.getMessages(rangeStart, rangeEnd)
            }
            for (message in messages) {
                handler(message)
            }
            if(rangeEnd == folder.messageCount) break
            rangeStart = rangeEnd
            rangeEnd = min(rangeStart + receiverConfig.fetchCount, folder.messageCount)
        }

        folder.addMessageCountListener(EmailListener(handler))

        while (isRunning) {
            folder.idle()
        }

        folder.close()
        service.close()
    }

    override fun stop() {
        isRunning = false
    }
}