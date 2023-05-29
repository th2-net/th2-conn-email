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
import com.exactpro.th2.email.DATE_HEADER
import com.exactpro.th2.email.IMAPReceiver
import com.exactpro.th2.email.POP3Receiver
import com.exactpro.th2.email.api.impl.IMAPSessionProvider
import com.exactpro.th2.email.api.impl.POP3SessionProvider
import com.exactpro.th2.email.config.BaseSessionSettings
import com.exactpro.th2.email.config.ReceiverConfig
import com.exactpro.th2.email.date
import com.icegreen.greenmail.configuration.GreenMailConfiguration
import com.icegreen.greenmail.util.GreenMail
import jakarta.mail.Authenticator
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.time.Instant
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class TestReceiver {

    @Test
    fun `basic pop3 receiver test`() {
        val greenMailPOP3 = GreenMail().withConfiguration(
            GreenMailConfiguration()
                .withUser("user@noreply.com", "password")
                .withUser("user2@noreply.com", "password")
        )

        greenMailPOP3.start()

        val oneMessageLatch = CountDownLatch(1)
        val twoMessagesLatch = CountDownLatch(2)

        val receivedMessages = mutableListOf<Message>()
        var lastProcessedDate: Date? = null
        val handler: (Message) -> Unit = {
            lastProcessedDate = it.date()
            receivedMessages.add(it)
            oneMessageLatch.countDown()
            twoMessagesLatch.countDown()
        }

        val smtpSession: Session = greenMailPOP3.smtp.createSession()

        val msg: Message = MimeMessage(smtpSession)
        msg.setFrom(InternetAddress("user@noreply.com"))
        msg.addRecipient(
            Message.RecipientType.TO,
            InternetAddress("user2@noreply.com")
        )
        msg.subject = "test1"
        msg.setText("test")
        val transport = smtpSession.transport
        transport.connect("user@noreply.com", "password")
        transport.sendMessage(msg, msg.allRecipients)


        val receiver = POP3Receiver(pop3Session(), handler, ReceiverConfig(pollInterval = 1000), Executors.newSingleThreadExecutor(), {lastProcessedDate}) {}
        receiver.start()

        oneMessageLatch.await(10, TimeUnit.SECONDS)

        val msg2: Message = MimeMessage(smtpSession)
        msg2.setFrom(InternetAddress("user@noreply.com"))
        msg2.addRecipient(
            Message.RecipientType.TO,
            InternetAddress("user2@noreply.com")
        )
        msg2.subject = "test2"
        msg2.setText("test")
        transport.sendMessage(msg2, msg2.allRecipients)

        twoMessagesLatch.await(10, TimeUnit.SECONDS)

        receiver.stop()
        Thread.sleep(1000)

        val msg3: Message = MimeMessage(smtpSession)
        msg3.setFrom(InternetAddress("user@noreply.com"))
        msg3.addRecipient(
            Message.RecipientType.TO,
            InternetAddress("user2@noreply.com")
        )
        msg3.subject = "test3"
        msg3.setText("test")
        transport.sendMessage(msg3, msg3.allRecipients)

        receiver.start()
        Thread.sleep(1000)
        receiver.stop()

        assertEquals(receivedMessages.size, 3)

        receivedMessages.forEachIndexed { idx, it ->
            if(!it.folder.isOpen) it.folder.open(Folder.READ_ONLY)
            assertEquals(it.subject, "test${idx + 1}")
            assertEquals(it.from.firstOrNull()?.toString() ?: "", "user@noreply.com")
            assertEquals(it.allRecipients.firstOrNull()?.toString() ?: "", "user2@noreply.com")
            assertEquals(it.content.toString(), "test\r\n")
        }

        greenMailPOP3.stop()
    }

    @Test
    fun `basic imap receiver test`() {
        val greenMailIMAP = GreenMail().withConfiguration(
            GreenMailConfiguration()
                .withUser("user@noreply.com", "password")
                .withUser("user2@noreply.com", "password")
        )

        greenMailIMAP.start()

        val oneMessageLatch = CountDownLatch(1)
        val twoMessagesLatch = CountDownLatch(2)
        val receivedMessages = mutableListOf<Message>()
        var lastProcessedDate: Date? = null
        val handler: (Message) -> Unit = {
            lastProcessedDate = it.date()
            receivedMessages.add(it)
            oneMessageLatch.countDown()
            twoMessagesLatch.countDown()
        }

        val smtpSession: Session = greenMailIMAP.smtp.createSession()

        val msg: Message = MimeMessage(smtpSession)
        msg.setFrom(InternetAddress("user@noreply.com"))
        msg.addRecipient(
            Message.RecipientType.TO,
            InternetAddress("user2@noreply.com")
        )
        msg.subject = "test1"
        msg.setText("test")
        val transport = smtpSession.transport
        transport.connect("user@noreply.com", "password")
        transport.sendMessage(msg, msg.allRecipients)

        val receiver = IMAPReceiver(imapSession(), handler, ReceiverConfig(), Executors.newSingleThreadExecutor(), {lastProcessedDate}) {}

        receiver.start()

        Thread.sleep(1000)

        val msg2: Message = MimeMessage(smtpSession)
        msg2.setFrom(InternetAddress("user@noreply.com"))
        msg2.addRecipient(
            Message.RecipientType.TO,
            InternetAddress("user2@noreply.com")
        )
        msg2.subject = "test2"
        msg2.setText("test")
        transport.sendMessage(msg2, msg2.allRecipients)

        Thread.sleep(2000)

        receiver.stop()

        val msg3: Message = MimeMessage(smtpSession)
        msg3.setFrom(InternetAddress("user@noreply.com"))
        msg3.addRecipient(
            Message.RecipientType.TO,
            InternetAddress("user2@noreply.com")
        )
        msg3.subject = "test3"
        msg3.setText("test")
        transport.sendMessage(msg3, msg3.allRecipients)

        receiver.start()
        Thread.sleep(1000)
        receiver.stop()

        Thread.sleep(1000)

        assertEquals(receivedMessages.size, 3)
        receivedMessages.forEachIndexed { idx, it ->
            if(!it.folder.isOpen) it.folder.open(Folder.READ_ONLY)
            assertEquals(it.subject, "test${idx + 1}")
            assertEquals(it.from.firstOrNull()?.toString() ?: "", "user@noreply.com")
            assertEquals(it.allRecipients.firstOrNull()?.toString() ?: "", "user2@noreply.com")
            assertEquals(it.content.toString(), "test")
        }
        greenMailIMAP.stop()
    }

    companion object {
        fun pop3Session(): Session {
            val sessionProvider = POP3SessionProvider(settings(3110))
            return sessionProvider.getSession(auth())
        }

        fun imapSession(): Session {
            val sessionProvider = IMAPSessionProvider(settings(3143))
            return sessionProvider.getSession(auth())
        }

        fun settings(port: Int): BaseSessionSettings = BaseSessionSettings(
            "localhost", port, "user2@noreply.com", false, false
        )

        fun auth() = object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication("user2@noreply.com", "password")
            }
        }
    }
}