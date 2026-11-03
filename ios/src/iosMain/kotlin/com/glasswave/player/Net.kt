// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter

class HttpError(val code: Int, message: String) : Exception(message)

/** Web requests (the phone app uses OkHttp, which is Java-only). */
object Net {
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    val client = HttpClient(Darwin) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
        }
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val r = client.get(url) {
            header("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> header(k, v) }
        }
        return r.status.value to r.bodyAsText()
    }

    suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val r = client.post(url) {
            header("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> header(k, v) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return r.status.value to r.bodyAsText()
    }
}

fun enc(s: String) = s.encodeURLParameter()
