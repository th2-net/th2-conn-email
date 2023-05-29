package com.exactpro.th2.email.loader

import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.message.toTimestamp
import com.exactpro.th2.dataprovider.lw.grpc.DataProviderService
import com.exactpro.th2.dataprovider.lw.grpc.MessageGroupResponse
import com.exactpro.th2.dataprovider.lw.grpc.MessageSearchRequest
import com.exactpro.th2.dataprovider.lw.grpc.MessageSearchResponse
import com.exactpro.th2.dataprovider.lw.grpc.MessageStream
import com.exactpro.th2.dataprovider.lw.grpc.TimeRelation
import com.exactpro.th2.email.DATE_PROPERTY
import com.google.protobuf.Timestamp
import com.google.protobuf.util.Timestamps
import io.grpc.Context
import java.time.Instant
import java.util.*

class TimeLoader(val dataProvider: DataProviderService) {

    // TODO: make it possible to filter by folder
    fun loadLastProcessedMessageReceiveDate(sessionAlias: String) = withCancellation {
        findMessageWithDate(dataProvider.searchMessages(createSearchRequest(sessionAlias)))
    }

    private fun findMessageWithDate(
        iterator: Iterator<MessageSearchResponse>
    ): Date? {
        var response: MessageSearchResponse?

        while (iterator.hasNext()) {
            response = iterator.next()
            if(!response.hasMessage()) continue
            val message = response.message
            val date = message.messagePropertiesMap[DATE_PROPERTY] ?: continue
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