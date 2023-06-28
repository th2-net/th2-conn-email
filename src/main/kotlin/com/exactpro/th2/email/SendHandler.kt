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
import com.exactpro.th2.common.event.EventUtils.toEventID
import com.exactpro.th2.common.grpc.AnyMessage
import com.exactpro.th2.common.grpc.EventBatch
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.grpc.MessageID
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.sessionAlias
import com.exactpro.th2.common.schema.message.MessageRouter
import com.exactpro.th2.common.schema.message.storeEvent
import mu.KotlinLogging

class SendHandler(
    private val eventRouter: MessageRouter<EventBatch>,
    private val messageRouter: MessageRouter<MessageGroupBatch>,
    private val senders: Map<String, (RawMessage) -> Unit>,
    private val rootEventId: String?,
    private val registerResource: (name: String, destructor: () -> Unit) -> Unit,
) {
    private val errorEventId by lazy { eventRouter.storeEvent("Errors".toErrorEvent(), rootEventId).id }

    fun run() {
        runCatching {
            checkNotNull(messageRouter.subscribe(::handleBatch, INPUT_QUEUE_ATTRIBUTE))
        }.onSuccess { monitor ->
            registerResource("raw-monitor", monitor::unsubscribe)
        }.onFailure {
            throw IllegalStateException("Failed to subscribe to input queue", it)
        }
    }

    private fun handleBatch(tag: String, batch: MessageGroupBatch) {
        batch.groupsList.forEach { group ->
            group.runCatching(::handleGroup).recoverCatching { cause ->
                onError("Failed to handle message group", group, cause)
            }
        }
    }

    private fun handleGroup(group: MessageGroup) {
        if (group.messagesCount != 1) {
            onError("Message group must contain only a single message", group)
            return
        }

        val message = group.messagesList[0]

        if (!message.hasRawMessage()) {
            onError("Message is not a raw message", message)
            return
        }

        val rawMessage = message.rawMessage
        val sessionAlias = rawMessage.sessionAlias

        val sender = senders[message.rawMessage.sessionAlias]
        if (sender != null) {
            sender(rawMessage)
        } else {
            onError("Not found sender for ${message.rawMessage.sessionAlias}", message)
        }
    }

    private fun onError(error: String, message: AnyMessage, cause: Throwable? = null) {
        val id = message.messageId
        val event = error.toErrorEvent(cause).messageID(id)
        logger.error("$error (message: ${id.logId})", cause)
        onEvent(event, message.getErrorEventId())
    }

    private fun onError(error: String, group: MessageGroup, cause: Throwable? = null) {
        val messageIds = group.messagesList.groupBy(
            { it.getErrorEventId() },
            { it.messageId }
        )

        logger.error(cause) { "$error (messages: ${messageIds.values.flatten().map(MessageID::logId)})" }

        messageIds.forEach { (parentEventId, messageIds) ->
            val event = error.toErrorEvent(cause)
            messageIds.forEach(event::messageID)
            onEvent(event, parentEventId)
        }
    }

    private fun onEvent(event: Event, parentId: String) {
        eventRouter.send(event.toBatchProto(toEventID(parentId)))
    }

    private fun AnyMessage.getErrorEventId(): String {
        return eventId?.id ?: errorEventId;
    }

    companion object {
        private const val INPUT_QUEUE_ATTRIBUTE = "send"
        private val logger = KotlinLogging.logger {  }
    }
}