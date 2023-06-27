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
import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.direction
import com.exactpro.th2.common.schema.factory.CommonFactory
import com.exactpro.th2.common.schema.message.QueueAttribute
import com.exactpro.th2.common.utils.message.RawMessageBatcher
import com.exactpro.th2.common.utils.message.sessionAlias
import com.exactpro.th2.dataprovider.lw.grpc.DataProviderService
import com.exactpro.th2.email.api.IReceiver
import com.exactpro.th2.email.api.IReceiverAuthSettings
import com.exactpro.th2.email.api.IReceiverAuthSettingsProvider
import com.exactpro.th2.email.api.ISenderAuthSettings
import com.exactpro.th2.email.api.ISenderAuthSettingsProvider
import com.exactpro.th2.email.api.impl.BasicAuthSettingsProvider
import com.exactpro.th2.email.api.impl.IMAPSessionProvider
import com.exactpro.th2.email.api.impl.POP3SessionProvider
import com.exactpro.th2.email.api.impl.ReceiverAuthSettingsDeserializer
import com.exactpro.th2.email.api.impl.SMTPSessionProvider
import com.exactpro.th2.email.api.impl.SenderAuthSettingsDeserializer
import com.exactpro.th2.email.config.ReceiverType
import com.exactpro.th2.email.config.Settings
import com.exactpro.th2.email.loader.CradleTimeLoader
import com.exactpro.th2.email.loader.FileTimeLoader
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import jakarta.mail.Message
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
private val QUEUE_ATTRIBUTES = arrayOf(QueueAttribute.RAW.value, QueueAttribute.PUBLISH.value, QueueAttribute.STORE.value)

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

    val authSettingsSenderType = load<ISenderAuthSettingsProvider>(BasicAuthSettingsProvider::class.java).senderType
    val authSettingsReceiverType = load<IReceiverAuthSettingsProvider>(BasicAuthSettingsProvider::class.java).receiverType

    val mapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().configure(KotlinFeature.NullIsSameAsDefault, true).build())
        .addModule(JavaTimeModule())
        .addModule(
            SimpleModule()
                .addDeserializer(IReceiverAuthSettings::class.java, ReceiverAuthSettingsDeserializer(authSettingsReceiverType))
                .addDeserializer(ISenderAuthSettings::class.java, SenderAuthSettingsDeserializer(authSettingsSenderType))
        )
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
        try {
            messageRouter.sendAll(it, *QUEUE_ATTRIBUTES)
        } catch (e: Exception) {
            LOGGER.error(e) { "Error while sending batch." }
        }
    }

    val rootEventId = toEventID(factory.rootEventId)

    val sendHandlers = mutableMapOf<String, (RawMessage) -> Unit>()
    val receivers = mutableMapOf<String, IReceiver>()


    val timeLoader = if(settings.loadStateFromCradle) {
        val grpcRouter = factory.grpcRouter
        resources += "grpc router" to grpcRouter::close
        val dataProviderService = grpcRouter.getService(DataProviderService::class.java)
        CradleTimeLoader(dataProviderService)
    } else {
        FileTimeLoader(settings.stateFilePath)
    }

    resources += "Saving state" to {
        timeLoader.writeState()
    }

    for(client in settings.clients) {
        val connectionId = ConnectionID.newBuilder().apply {
            sessionAlias = client.sessionAlias
            sessionGroup = client.sessionAlias
        }.build()

        val handler: (Message) -> Unit = {
            LOGGER.debug { "Received message: ${it.subject}" }
            messageBatcher.onMessage(it.toRawMessage(connectionId, Direction.FIRST, client.whitelist))
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

        val dateLoader: (folder: String) -> Date? = {
            timeLoader.loadLastProcessedMessageReceiveDate(client.sessionAlias, it)
        }

        val receiver = when (client.receiver.type) {
            ReceiverType.IMAP -> {
                val sessionProvider = IMAPSessionProvider(client.receiver.sessionConfiguration)
                val authenticator = client.receiver.authSettings.authenticator
                IMAPReceiver(
                    sessionProvider.getSession(authenticator),
                    handler,
                    client.receiver,
                    receiverExecutor,
                    { dateLoader(client.receiver.folder) }
                ) {
                    eventRouter.sendAll(it.toBatchProto(rootEventId))
                }
            }
            ReceiverType.POP3 -> {
                val sessionProvider = POP3SessionProvider(client.receiver.sessionConfiguration)
                val authenticator = client.receiver.authSettings.authenticator
                POP3Receiver(
                    sessionProvider.getSession(authenticator),
                    handler,
                    client.receiver,
                    receiverExecutor,
                    { dateLoader(client.receiver.folder) }
                ) {
                    eventRouter.sendAll(it.toBatchProto(rootEventId))
                }
            }
        }

        val senderSessionProvider = SMTPSessionProvider(client.sender.sessionConfiguration)
        val senderAuth = client.sender.authSettings.authenticator

        val sender = SMTPSender(senderSessionProvider.getSession(senderAuth), client.sender.reconnectInterval)

        val send: (RawMessage) -> Unit = {
            val message = it.toMimeMessage(sender.session, client)
            sender.send(message)
            messageBatcher.onMessage(message.toRawMessage(connectionId, Direction.SECOND, client.whitelist))
        }

        receivers[client.sessionAlias] = receiver
        sendHandlers[client.sessionAlias] = send

        resources += "${client.sessionAlias} receiver state" to {
            receiver.getState()?.let { timeLoader.updateState(client.sessionAlias, client.receiver.folder, it) }
        }

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

private inline fun <reified T> load(defaultImpl: Class<out T>): T {
    val instances = ServiceLoader.load(T::class.java).toList()

    return when (instances.size) {
        0 -> error("No instances of ${T::class.simpleName}")
        1 -> instances.first()
        2 -> instances.first { !defaultImpl.isInstance(it) }
        else -> error("More than 1 non-default instance of ${T::class.simpleName} has been found: $instances")
    }
}

