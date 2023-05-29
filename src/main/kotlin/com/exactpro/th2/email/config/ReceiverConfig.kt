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

import com.fasterxml.jackson.annotation.JsonFormat
import java.util.Date

data class ReceiverConfig(
    val type: String = "pop3",
    val sessionConfiguration: BaseSessionSettings = BaseSessionSettings(),
    val authSettings: BasicAuthSettings = BasicAuthSettings(),
    val folder: String = "INBOX",
    val fetchCount: Int = 1000,
    val reconnectInterval: Long = 1000,
    val pollInterval: Long = 60000,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssZ")
    val startProcessingAtLeastFrom: Date? = Date(Long.MIN_VALUE)
) {
    init {
        require(type in ReceiverType.aliases()) { "Invalid type ${type}. Type should be one of the following: ${ReceiverType.aliases()}" }
    }
}