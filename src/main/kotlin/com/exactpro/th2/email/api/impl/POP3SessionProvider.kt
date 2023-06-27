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

class POP3SessionProvider(private val sessionSettings: BaseSessionSettings): SessionProvider {

    private val props = Properties().apply {
        put(MAIL_POP3_HOST, sessionSettings.host)

        put(MAIL_POP3_PORT, sessionSettings.port)
        put(MAIL_POP3_USER, sessionSettings.user)

        put(MAIL_POP3_AUTH, true)
        put(MAIL_STORE_PROTOCOL, PROTOCOL)

        if(sessionSettings.ssl) {
            put(MAIL_POP3_SSL_ENABLE, true)
        }

        if(sessionSettings.startTls) {
            put(MAIL_POP3_STARTTLS_ENABLE, true)
        }

        if(sessionSettings.acceptAllCerts && sessionSettings.customCertificate == null) {
            put(MAIL_POP3_SSL_TRUST, "*")
            put(MAIL_POP3_CHECK_SERVER_IDENTITY, false)
        }

        if(sessionSettings.customCertificate != null) {
            val customFactory = getCustomFactory(sessionSettings.customCertificate)
            put(MAIL_SSL_SOCKET_FACTORY, customFactory)
        }
    }

    override fun getSession(authenticator: Authenticator): Session = Session.getInstance(props, authenticator)

    companion object {
        const val MAIL_POP3_PORT = "mail.pop3.port"
        const val MAIL_POP3_HOST = "mail.pop3.host"
        const val MAIL_POP3_USER = "mail.pop3.user"
        const val MAIL_POP3_AUTH = "mail.pop3.auth"

        private const val MAIL_POP3_SSL_TRUST = "mail.pop3.ssl.trust"
        private const val MAIL_POP3_STARTTLS_ENABLE = "mail.pop3.starttls.enable"
        private const val MAIL_POP3_SSL_ENABLE = "mail.pop3.ssl.enable"
        private const val MAIL_SSL_SOCKET_FACTORY = "mail.pop3.ssl.socketFactory"
        private const val MAIL_POP3_CHECK_SERVER_IDENTITY = "mail.pop3.ssl.checkserveridentity"

        const val MAIL_STORE_PROTOCOL = "mail.store.protocol"
        const val PROTOCOL = "pop3"
    }
}