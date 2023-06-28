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
package com.exactpro.th2.email.loader

import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.message.toTimestamp
import com.exactpro.th2.dataprovider.lw.grpc.DataProviderService
import com.exactpro.th2.dataprovider.lw.grpc.MessageSearchRequest
import com.exactpro.th2.dataprovider.lw.grpc.MessageSearchResponse
import com.exactpro.th2.dataprovider.lw.grpc.MessageStream
import com.exactpro.th2.dataprovider.lw.grpc.TimeRelation
import com.exactpro.th2.email.DATE_PROPERTY
import com.exactpro.th2.email.FOLDER_PROPERTY
import com.google.protobuf.util.Timestamps
import io.grpc.Context
import java.time.Instant
import java.util.Date
import mu.KotlinLogging

class CradleTimeLoader(
    private val dataProvider: DataProviderService,
): TimeLoader {

    override fun loadLastProcessedMessageReceiveDate(sessionAlias: String, folder: String): Date? = withCancellation {
        findMessageWithDate(dataProvider.searchMessages(createSearchRequest(sessionAlias)), folder)
    }.also {
        K_LOGGER.info { "Starting processing messages from $it for session $sessionAlias." }
    }

    override fun updateState(sessionAlias: String, folder: String, fileState: FolderState) {}

    override fun writeState() {}

    private fun findMessageWithDate(
        iterator: Iterator<MessageSearchResponse>,
        expectedFolder: String
    ): Date? {
        var response: MessageSearchResponse?

        while (iterator.hasNext()) {
            response = iterator.next()
            if(!response.hasMessage()) continue
            val message = response.message
            val date = message.messagePropertiesMap[DATE_PROPERTY] ?: continue
            val folder = message.messagePropertiesMap[FOLDER_PROPERTY] ?: continue
            if(expectedFolder != folder) continue
            return Date(date.toLongOrNull() ?: continue)
        }
        return null
    }

    private fun createSearchRequest(sessionAlias: String) =
        MessageSearchRequest.newBuilder().apply {
            startTimestamp = Instant.now().toTimestamp()
            endTimestamp = Timestamps.MIN_VALUE
            searchDirection = TimeRelation.PREVIOUS
            addResponseFormats(BASE_64_FORMAT)
            addStream(
                MessageStream.newBuilder()
                    .setName(sessionAlias)
                    .setDirection(Direction.FIRST)
            )
        }.build()

    companion object {
        const val BASE_64_FORMAT = "BASE_64"
        private val K_LOGGER = KotlinLogging.logger {  }

        fun <T> withCancellation(code: () -> T): T {
            return Context.current().withCancellation().use { context ->
                val toRestore = context.attach()
                val result = try {
                    code()
                } finally {
                    context.detach(toRestore)
                }
                return@use result
            }
        }
    }
}