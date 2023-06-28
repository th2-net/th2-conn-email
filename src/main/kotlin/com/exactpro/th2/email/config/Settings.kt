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

data class Settings(
    val clients: List<ClientSettings>,
    val maxFlushTime: Long = 1000,
    val maxBatchSize: Int = 1000,
    val loadStateFromCradle: Boolean = false,
    val loadStateFromFile: Boolean = true,
    val stateFilePath: String = "state.json"
) {
    init {
        require(clients.isNotEmpty()) { "At least one client should be described." }
        if(loadStateFromCradle && loadStateFromFile) {
            error("It is only possible to load state from one place: file or cradle.")
        }
    }
}