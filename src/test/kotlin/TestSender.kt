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
import com.exactpro.th2.email.SMTPSender
import com.exactpro.th2.email.api.impl.SMTPSessionProvider
import com.exactpro.th2.email.config.BaseSessionSettings
import com.icegreen.greenmail.configuration.GreenMailConfiguration
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test


class TestSender {
    @Test
    fun `test basic smtp send`() {
        val greenMail = GreenMail(ServerSetup.SMTP).withConfiguration(
            GreenMailConfiguration().withUser("user@noreply.com", "password")
        )
        val sender = SMTPSender(senderSession(false, false, 25), 1000)

        greenMail.start()

        sender.send(
            MimeMessage(sender.session).apply {
                setFrom("user@noreply.com")
                setRecipients(Message.RecipientType.TO, "user2@noreply.com")
                setText("test")
                subject = "test"
            }
        )

        assertEquals(greenMail.receivedMessages.size, 1)
        val message = greenMail.receivedMessages[0]
        assertEquals(message.from.firstOrNull()?.toString() ?: "", "user@noreply.com")
        assertEquals(message.allRecipients.firstOrNull()?.toString() ?: "", "user2@noreply.com")
        assertEquals(message.subject, "test")
        assertEquals(message.content.toString(), "test")

        greenMail.stop()
    }

    companion object {
        fun senderSession(ssl: Boolean, startTls: Boolean, port: Int): Session {
            val sessionProvider = SMTPSessionProvider(
                BaseSessionSettings(
                    "localhost", port, "user@noreply.com", ssl, startTls
                )
            )
            val auth = object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication("user@noreply.com", "password")
                }
            }
            return sessionProvider.getSession(auth)
        }
    }
}