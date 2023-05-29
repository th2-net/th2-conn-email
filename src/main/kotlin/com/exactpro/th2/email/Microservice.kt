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

import com.exactpro.th2.common.event.EventUtils.toEventID
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.direction
import com.exactpro.th2.common.schema.factory.CommonFactory
import com.exactpro.th2.common.schema.message.QueueAttribute
import com.exactpro.th2.common.utils.message.RawMessageBatcher
import com.exactpro.th2.common.utils.message.sessionAlias
import com.exactpro.th2.dataprovider.lw.grpc.DataProviderService
import com.exactpro.th2.email.api.IReceiver
import com.exactpro.th2.email.api.impl.IMAPSessionProvider
import com.exactpro.th2.email.api.impl.POP3SessionProvider
import com.exactpro.th2.email.api.impl.SMTPSessionProvider
import com.exactpro.th2.email.config.ReceiverType
import com.exactpro.th2.email.config.Settings
import com.exactpro.th2.email.loader.TimeLoader
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import jakarta.mail.Message
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.system.exitProcess
import mu.KotlinLogging

private val LOGGER = KotlinLogging.logger { }

fun main(args: Array<String>) = try {
    val resources = ConcurrentLinkedDeque<Pair<String, () -> Unit>>()

    Runtime.getRuntime().addShutdownHook(thread(start = false, name = "shutdown-hook") {
        resources.descendingIterator().forEach { (resource, destructor) ->
            LOGGER.debug { "Destroying resource: $resource" }
            runCatching(destructor).apply {
                onSuccess { LOGGER.debug { "Successfully destroyed resource: $resource" } }
                onFailure { LOGGER.error(it) { "Failed to destroy resource: $resource" } }
            }
        }
    })

    val factory = runCatching {
        CommonFactory.createFromArguments(*args)
    }.getOrElse {
        LOGGER.error(it) { "Failed to create common factory with arguments: ${args.joinToString(" ")}" }
        CommonFactory()
    }.apply { resources += "factory" to ::close }

    val mapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().configure(KotlinFeature.NullIsSameAsDefault, true).build())
        .addModule(JavaTimeModule())
        .build()

    val settings = factory.getCustomConfiguration(Settings::class.java, mapper)

    val eventRouter = factory.eventBatchRouter
    val messageRouter = factory.messageRouterMessageGroupBatch

    val batcherExecutor = Executors.newSingleThreadScheduledExecutor().apply {
        resources += "batcher executor" to {
            shutdown()

            if (!awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn { "Failed to shutdown executor in 5 seconds" }
                shutdownNow()
            }
        }
    }

    val messageBatcher = RawMessageBatcher(
        settings.maxBatchSize,
        settings.maxFlushTime,
        batchSelector = { it.sessionAlias to it.direction },
        batcherExecutor,
    ) {
        messageRouter.sendAll(it, QueueAttribute.RAW.name)
    }

    val dataProviderService = if(settings.clients.any { it.receiver.loadDatesFromCradle }) {
        val grpcRouter = factory.grpcRouter
        resources += "grpc router" to grpcRouter::close
        grpcRouter.getService(DataProviderService::class.java)
    } else null

    val rootEventId = toEventID(factory.rootEventId)

    val sendHandlers = mutableMapOf<String, (RawMessage) -> Unit>()
    val receivers = mutableMapOf<String, IReceiver>()

    val timeLoader = dataProviderService?.let { TimeLoader(dataProviderService) }

    for(client in settings.clients) {
        val handler: (Message) -> Unit = {
            messageBatcher.onMessage(it.toRawMessage(client.sessionAlias))
        }

        val receiverExecutor = Executors.newSingleThreadExecutor().apply {
            resources += "${client.sessionAlias} receiver executor" to {
                shutdown()

                if (!awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.warn { "Failed to shutdown executor in 5 seconds" }
                    shutdownNow()
                }
            }
        }

        val dateLoader: () -> Date? = {
            if (client.receiver.loadDatesFromCradle) {
                timeLoader?.loadLastProcessedMessageReceiveDate(client.sessionAlias)
            } else {
                null
            }
        }

        val receiver = when (client.receiver.type) {
            ReceiverType.IMAP.alias -> {
                val sessionProvider = IMAPSessionProvider(client.receiver.sessionConfiguration)
                val authenticator = client.receiver.authSettings.authenticator()
                IMAPReceiver(
                    sessionProvider.getSession(authenticator),
                    handler,
                    client.receiver,
                    receiverExecutor,
                    dateLoader
                ) {
                    eventRouter.sendAll(it.toBatchProto(rootEventId))
                }
            }
            ReceiverType.POP3.alias -> {
                val sessionProvider = POP3SessionProvider(client.receiver.sessionConfiguration)
                val authenticator = client.receiver.authSettings.authenticator()
                POP3Receiver(
                    sessionProvider.getSession(authenticator),
                    handler,
                    client.receiver,
                    receiverExecutor,
                    dateLoader
                ) {
                    /*eventRouter.sendAll(it.toBatchProto(rootEventId))*/
                }
            }
            else -> error("Unknown receiver type: ${client.receiver.type}")
        }

        val senderSessionProvider = SMTPSessionProvider(client.sender.sessionConfiguration)
        val senderAuth = client.sender.authSettings.authenticator()

        val sender = SMTPSender(senderSessionProvider.getSession(senderAuth), client.sender.reconnectInterval)

        val send: (RawMessage) -> Unit = {
            val message = MimeMessage(sender.session)
            message.setFrom(InternetAddress(client.from))
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(client.to))
            message.setText(it.body.toString(Charsets.UTF_8))
            message.subject = it.metadata.propertiesMap[SUBJECT_PROPERTY]
            sender.send(message)
            messageBatcher.onMessage(message.toRawMessage(client.sessionAlias))
        }

        receivers[client.sessionAlias] = receiver
        sendHandlers[client.sessionAlias] = send

        resources += "${client.sessionAlias} sender" to sender::close
        resources += "${client.sessionAlias} receiver" to receiver::stop
    }

    val sendHandler = SendHandler(eventRouter, messageRouter, sendHandlers, rootEventId?.id) { resource, destructor ->
        resources += resource to destructor
    }

    sendHandler.run()

    receivers.values.forEach { it.start() }

    LOGGER.info { "Successfully started" }

    ReentrantLock().run {
        val condition = newCondition()
        resources += "await-shutdown" to { withLock(condition::signalAll) }
        withLock(condition::await)
    }

    LOGGER.info { "Finished running" }
} catch (e: Exception) {
    LOGGER.error(e) { "Uncaught exception. Shutting down" }
    exitProcess(1)
}

