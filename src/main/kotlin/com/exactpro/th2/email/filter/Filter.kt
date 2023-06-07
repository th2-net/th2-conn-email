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
package com.exactpro.th2.email.filter

import jakarta.mail.Message
import jakarta.mail.internet.InternetAddress

class Filter(private val senderFilter: SenderFilter? = null) {
    fun isAllowed(message: Message): Boolean {
        senderFilter?.let {
            it.isAllowed(message)
        }

        return true
    }

    companion object {
        fun List<Filter>.allowed(message: Message) = all { it.isAllowed(message) }
    }
}

data class SenderFilter(val allowedSenders: String) {
    private val allowedAddresses = InternetAddress.parse(allowedSenders).map {
        it.address
    }.toSet()
    fun isAllowed(message: Message): Boolean {
        val from = (message.from ?: return true)
            .mapNotNull {
                when (it) {
                    is InternetAddress -> it
                    else -> null
                }
            }
            .map { it.address }
            .toSet()

        return allowedAddresses.containsAll(from)
    }
}