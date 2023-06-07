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

import com.exactpro.th2.email.loader.MutableEmailServiceState.Companion.toMutable
import com.exactpro.th2.email.loader.MutableEmailServiceStates.Companion.toMutable
import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Date
import mu.KotlinLogging

class FolderState(
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "EEE, d MMM yyyy HH:mm:ss Z (z)")
    val lastProcessedMessageDate: Date
)

open class EmailServiceState(
    val folderStates: Map<String, FolderState>
)

open class EmailServiceStates(
    val states: Map<String, EmailServiceState>
) {
    companion object {
        private val K_LOGGER = KotlinLogging.logger {  }
        fun load(path: String): EmailServiceStates {
            val file = File(path)
            if (!file.exists()) {
                return EmailServiceStates(emptyMap())
            }
            return try {
                FileInputStream(file).use {
                    OBJECT_MAPPER.readValue(it, EmailServiceStates::class.java)
                }
            } catch (e: Exception) {
                K_LOGGER.error(e) { "Error while reading json state file" }
                EmailServiceStates(emptyMap())
            }
        }

        fun write(path: String, state: EmailServiceStates) {
            val file = File(path)
            if (!file.exists()) {
                file.createNewFile()
            }
            FileOutputStream(file).use { outputStream ->
                OBJECT_MAPPER.writeValue(outputStream, state)
            }
        }
        private val OBJECT_MAPPER = JsonMapper()
            .registerModule(KotlinModule.Builder().build())
            .registerModule(JavaTimeModule())
    }
}

class MutableEmailServiceStates(states: Map<String, MutableEmailServiceState>): EmailServiceStates(states) {
    private val _states = states.toMutableMap()

    fun updateState(sessionAlias: String, state: EmailServiceState) = _states.put(sessionAlias, state.toMutable())

    fun getState(): EmailServiceStates = EmailServiceStates(
        _states.mapValues { it.value.getState() }
    )

    fun getState(sessionAlias: String) = _states[sessionAlias]

    companion object {
        fun EmailServiceStates.toMutable(): MutableEmailServiceStates = MutableEmailServiceStates(states.mapValues { (key, value) -> value.toMutable() })
    }
}

class MutableEmailServiceState(folderStates: Map<String, FolderState>): EmailServiceState(folderStates) {
    private val _folderStates = folderStates.toMutableMap()

    fun updateState(folder: String, state: FolderState) = _folderStates.put(folder, state)

    fun getState(folder: String) = _folderStates[folder]

    fun getState() = EmailServiceState(_folderStates)

    companion object {
        fun EmailServiceState.toMutable(): MutableEmailServiceState = MutableEmailServiceState(folderStates)
    }
}