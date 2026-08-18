/*
 * DataKhoj — a personal, unrestricted universal data collector.
 * Copyright (C) 2026 soobujmiah
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details: <https://www.gnu.org/licenses/>.
 *
 * "DataKhoj" and its logo are trademarks of the copyright holder and are NOT
 * licensed under the AGPL. Forks must use their own name and branding.
 */

package dev.datakhoj.app

import android.app.Application
import dev.datakhoj.app.net.DuckDuckGoProvider
import dev.datakhoj.core.provider.ProviderRegistry

class DataKhojApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Register built-in sources. New data types plug in here.
        ProviderRegistry.register(DuckDuckGoProvider())
    }
}
