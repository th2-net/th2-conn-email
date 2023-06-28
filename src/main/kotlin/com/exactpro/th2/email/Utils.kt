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

import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.email.config.CertificateInfo
import com.exactpro.th2.email.config.ClientSettings
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

const val DATE_HEADER = "Date"
val DATE_FORMAT = SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z (z)", Locale.ENGLISH)

fun resumeDate(lastProcessedDate: Date?, configDate: Date?) =
    if(lastProcessedDate != null && configDate != null) {
        if (lastProcessedDate.after(configDate)) {
            lastProcessedDate
        } else {
            configDate
        }
    } else lastProcessedDate ?: configDate

fun Message.date(): Date? = receivedDate ?: try {
    getHeader(DATE_HEADER)?.get(0)?.let { DATE_FORMAT.parse(it) }
} catch (e: ParseException) {
    sentDate
}

fun Date.string() = time.toString()

fun getCustomFactory(certificateInfo: CertificateInfo): SocketFactory {
    val crtFile = File(certificateInfo.certificateFilePath)
    val certificate: Certificate = CertificateFactory.getInstance("X.509").generateCertificate(FileInputStream(crtFile))

    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
    keyStore.load(null, null)
    keyStore.setCertificateEntry("server", certificate)

    val trustManagerFactory: TrustManagerFactory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    trustManagerFactory.init(keyStore)

    val sslContext: SSLContext = SSLContext.getInstance("TLS")
    sslContext.init(null, trustManagerFactory.trustManagers, null)

    return sslContext.socketFactory
}

fun findResumeMessageNumber(folder: Folder, previousDate: Date): Int? {
    var low = 1
    var high = folder.messageCount
    var resumeMessageNumber: Int? = null

    var nearestMessageNumberTop: Int? = null
    var nearestMessageNumberBottom: Int? = null

    while (low <= high) {
        val mid = (low + high) / 2
        val message = folder.getMessage(mid)

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
            if(messageDate.before(previousDate)) break
            if(messageDate == previousDate && folder.messageCount == nearestMessageNumberTop) return null
            resumeMessageNumber = nearestMessageNumberTop
            nearestMessageNumberTop -= 1
        }

        return resumeMessageNumber?.let { it + 1 }
    }

    if(nearestMessageNumberBottom != null) {
        while (nearestMessageNumberBottom < folder.messageCount + 1) {
            val message = folder.getMessage(nearestMessageNumberBottom)
            val messageDate = message.date() ?: continue
            if(messageDate.after(previousDate)) break
            if(messageDate == previousDate && folder.messageCount == nearestMessageNumberBottom) return null
            resumeMessageNumber = nearestMessageNumberBottom
            nearestMessageNumberBottom += 1
        }

        return resumeMessageNumber?.let { it - 1 }
    }

    return resumeMessageNumber
}