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
import java.util.*


class IMAPSessionProvider(private val sessionSettings: BaseSessionSettings): SessionProvider {
    private val props = Properties().apply {
        put(MAIL_IMAP_HOST, sessionSettings.host)
        put(MAIL_IMAP_PORT, sessionSettings.port)
        put(MAIL_IMAP_USER, sessionSettings.user)
        put(MAIL_STORE_PROTOCOL, PROTOCOL)

        if(sessionSettings.ssl) {
            put(MAIL_IMAP_SSL_ENABLE, "true")
        }

        if(sessionSettings.startTls) {
            put(MAIL_IMAP_STARTTLS_ENABLE, "true")
        }

        if(sessionSettings.acceptAllCerts && sessionSettings.customCertificate == null) {
            put(MAIL_IMAP_SSL_TRUST, sessionSettings.host)
        }

        if(sessionSettings.customCertificate != null) {
            val customFactory = getCustomFactory(sessionSettings.customCertificate)
            put(MAIL_SSL_SOCKET_FACTORY, customFactory)
        }
    }

    override fun getSession(authenticator: Authenticator): Session = Session.getInstance(props, authenticator)

    companion object {
        private const val MAIL_IMAP_PORT = "mail.imap.port"
        private const val MAIL_IMAP_HOST = "mail.imap.host"
        private const val MAIL_IMAP_USER = "mail.imap.user"

        private const val MAIL_IMAP_SSL_TRUST = "mail.imap.ssl.trust"
        private const val MAIL_IMAP_STARTTLS_ENABLE = "mail.imap.starttls.enable"
        private const val MAIL_IMAP_SSL_ENABLE = "mail.imap.ssl.enable"
        private const val MAIL_SSL_SOCKET_FACTORY = "mail.smtp.ssl.socketFactory"

        private const val MAIL_STORE_PROTOCOL = "mail.store.protocol"
        private const val PROTOCOL = "imap"
    }
}