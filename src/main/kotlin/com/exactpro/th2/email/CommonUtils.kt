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
import com.exactpro.th2.common.event.EventUtils
import com.exactpro.th2.common.grpc.AnyMessage
import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.grpc.Message
import com.exactpro.th2.common.grpc.MessageID
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.direction
import com.exactpro.th2.common.message.toJson
import com.exactpro.th2.email.config.ClientSettings
import com.google.protobuf.ByteString
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimePartDataSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.apache.commons.lang3.exception.ExceptionUtils
import jakarta.mail.Message as MailMessage

fun String.toErrorEvent(cause: Throwable? = null): Event = toEvent(Event.Status.FAILED, cause)

private fun String.toEvent(
    status: Event.Status,
    cause: Throwable? = null
) = Event.start().apply {
    name(this@toEvent)
    type(if (status == Event.Status.PASSED) "Info" else "Error")
    status(status)
    generateSequence(cause, Throwable::cause)
        .map(ExceptionUtils::getMessage)
        .map(EventUtils::createMessageBean)
        .forEach(::bodyData)
}

val MessageID.logId: String
    get() = buildString {
        append(connectionId.sessionAlias)
        append(":")
        append(direction.toString().lowercase())
        append(":")
        append(sequence)
        subsequenceList.forEach { append(".$it") }
    }

val RawMessage.eventId: EventID?
    get() = if (hasParentEventId()) parentEventId else null

val AnyMessage.eventId: EventID?
    get() = when (kindCase) {
        AnyMessage.KindCase.RAW_MESSAGE -> rawMessage.eventId
        AnyMessage.KindCase.MESSAGE -> message.takeIf(Message::hasParentEventId)?.parentEventId
        else -> error("Cannot get parent event id from $kindCase message: ${toJson()}")
    }

val AnyMessage.messageId: MessageID
    get() = when (kindCase) {
        AnyMessage.KindCase.RAW_MESSAGE -> rawMessage.metadata.id
        AnyMessage.KindCase.MESSAGE -> message.metadata.id
        else -> error("Cannot get message id from $kindCase message: ${toJson()}")
    }

val generateSequence = Instant.now().run {
    AtomicLong(TimeUnit.SECONDS.toNanos(epochSecond) + nano)
}::incrementAndGet

fun createId(connectionId: ConnectionID, sequence: Long): MessageID = MessageID.newBuilder().apply {
    this.connectionId = connectionId
    this.direction = Direction.FIRST
    this.sequence = sequence
}.build()

const val DATE_PROPERTY = "Date"
const val FOLDER_PROPERTY = "Folder"
private val defaultSession = Session.getDefaultInstance(Properties(), null)

fun MailMessage.toRawMessage(
    connectionId: ConnectionID,
    direction: Direction,
    headersWhiteList: Set<String>
): RawMessage.Builder = RawMessage.newBuilder().apply {
    val originalMessage = this@toRawMessage
    val cleanMimeMessage = MimeMessage(defaultSession)

    originalMessage.allHeaders
        .asSequence()
        .filter { headersWhiteList.contains(it.name)}
        .forEach { cleanMimeMessage.addHeader(it.name, it.value) }

    cleanMimeMessage.setContent(originalMessage.content, originalMessage.contentType)

    val messageDate = originalMessage.date()?.time?.toString()

    metadataBuilder.putAllProperties(
        mapOf(
            DATE_PROPERTY to (messageDate ?: ""),
            FOLDER_PROPERTY to (this@toRawMessage.folder?.toString() ?: "")
        ),
    )

    this.metadataBuilder.id = createId(connectionId, generateSequence())
    this.direction = direction
    val outputStream = ByteArrayOutputStream()
    cleanMimeMessage.writeTo(outputStream)
    this.body = ByteString.copyFrom(outputStream.toByteArray())
}

fun RawMessage.toMimeMessage(session: Session, client: ClientSettings): MailMessage {
    val content = body.toByteArray()
    return MimeMessage(session, ByteArrayInputStream(content)).apply {
        setFrom(client.fromAddress)
        setRecipients(MailMessage.RecipientType.TO, client.toAddresses)
    }
}