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

package dev.datakhoj.app.net

import dev.datakhoj.core.provider.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** OkHttp-backed [HttpClient]. All calls move to Dispatchers.IO. */
class AndroidHttpClient(
    private val userAgent: String =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/128.0.0.0 Mobile Safari/537.36",
) : HttpClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private fun build(url: String, headers: Map<String, String>) =
        Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "en-US,en;q=0.9,bn;q=0.8")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

    override suspend fun getText(url: String, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            client.newCall(build(url, headers)).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code} for $url")
                r.body?.string().orEmpty()
            }
        }

    override suspend fun getBytes(url: String, headers: Map<String, String>): ByteArray =
        withContext(Dispatchers.IO) {
            client.newCall(build(url, headers)).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code} for $url")
                r.body?.bytes() ?: ByteArray(0)
            }
        }

    override suspend fun postForm(
        url: String, form: Map<String, String>, headers: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val req = build(url, headers).newBuilder().post(body).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code} for $url")
            r.body?.string().orEmpty()
        }
    }
}
