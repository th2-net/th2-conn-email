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
package com.exactpro.th2.email.config

import jakarta.mail.internet.InternetAddress

class ClientSettings(
    val sessionAlias: String,
    val from: String,
    val to: String,
    val headersWhiteList: List<String> = listOf(SUBJECT_HEADER),
    val sender: SenderConfig,
    val receiver: ReceiverConfig
)  {
    val fromAddress: InternetAddress = InternetAddress(from)
    val toAddresses: Array<InternetAddress> = InternetAddress.parse(to)
    val whitelist = headersWhiteList.toSet()

    init {
        fromAddress.validate()
        toAddresses.forEach { it.validate() }
    }

    companion object {
        private const val SUBJECT_HEADER = "Subject"
    }
}