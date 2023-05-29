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

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Date
import mu.KotlinLogging

data class FileState(
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "EEE, d MMM yyyy HH:mm:ss Z (z)")
    val lastProcessedMessageDate: Date
)

data class FilesState(
    val states: Map<String, FileState>
) {
    companion object {
        private val K_LOGGER = KotlinLogging.logger {  }
        fun load(path: String): FilesState {
            val file = File(path)
            if (!file.exists()) {
                return FilesState(emptyMap())
            }
            return try {
                FileInputStream(file).use {
                    OBJECT_MAPPER.readValue(it, FilesState::class.java)
                }
            } catch (e: Exception) {
                K_LOGGER.error(e) { "Error while reading json state file" }
                FilesState(emptyMap())
            }
        }

        fun write(path: String, states: Map<String, FileState>) {
            val file = File(path)
            if (!file.exists()) {
                file.createNewFile()
            }
            FileOutputStream(file).use { outputStream ->
                OBJECT_MAPPER.writeValue(outputStream, FilesState(states))
            }
        }
        private val OBJECT_MAPPER = JsonMapper()
            .registerModule(KotlinModule.Builder().build())
            .registerModule(JavaTimeModule())
    }
}