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
package com.exactpro.th2.email.api.impl

import com.exactpro.th2.email.api.SessionProvider
import com.exactpro.th2.email.config.BaseSessionSettings
import com.exactpro.th2.email.getCustomFactory
import jakarta.mail.Authenticator
import jakarta.mail.Session
import java.util.Properties

class SMTPSessionProvider(private val sessionSettings: BaseSessionSettings): SessionProvider {

    private val props = Properties().apply {
        put(MAIL_SMTP_HOST, sessionSettings.host)

        put(MAIL_SMTP_PORT, sessionSettings.port)
        put(MAIL_SMTP_USER, sessionSettings.user)

        put(MAIL_SMTP_AUTH, "true")
        put(MAIL_TRANSPORT_PROTOCOL, PROTOCOL)

        if(sessionSettings.ssl) {
            put(MAIL_SMTP_SSL_ENABLE, "true")
        }

        if(sessionSettings.startTls) {
            put(MAIL_SMTP_STARTTLS_ENABLE, "true");
        }

        if(sessionSettings.acceptAllCerts && sessionSettings.customCertificate == null) {
            put(MAIL_SMTP_SSL_TRUST, "*")
        }

        if(sessionSettings.customCertificate != null) {
            val customFactory = getCustomFactory(sessionSettings.customCertificate)
            put(MAIL_SSL_SOCKET_FACTORY, customFactory)
        }
    }

    override fun getSession(authenticator: Authenticator): Session = Session.getInstance(props, authenticator)

    companion object {
        private const val MAIL_SMTP_HOST = "mail.smtp.host"
        private const val MAIL_SMTP_PORT = "mail.smtp.port"
        private const val MAIL_SMTP_USER = "mail.smtp.user"
        private const val MAIL_SMTP_AUTH = "mail.smtp.auth"

        private const val MAIL_SMTP_SSL_TRUST = "mail.smtp.ssl.trust"
        private const val MAIL_SMTP_STARTTLS_ENABLE = "mail.smtp.starttls.enable"
        private const val MAIL_SMTP_SSL_ENABLE = "mail.smtp.ssl.enable"
        private const val MAIL_SSL_SOCKET_FACTORY = "mail.smtp.ssl.socketFactory"

        private const val MAIL_TRANSPORT_PROTOCOL = "mail.transport.protocol"
        private const val PROTOCOL = "smtp"
    }
}