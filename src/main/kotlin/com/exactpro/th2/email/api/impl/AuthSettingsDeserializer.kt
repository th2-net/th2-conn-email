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
package com.exactpro.th2.email.api.impl

import com.exactpro.th2.email.api.IReceiverAuthSettings
import com.exactpro.th2.email.api.ISenderAuthSettings
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.deser.std.StdDeserializer

class ReceiverAuthSettingsDeserializer<T : IReceiverAuthSettings>(private val type: Class<T>) : StdDeserializer<T>(type) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): T = p.readValueAs(type)
}

class SenderAuthSettingsDeserializer<T : ISenderAuthSettings>(private val type: Class<T>) : StdDeserializer<T>(type) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): T = p.readValueAs(type)
}