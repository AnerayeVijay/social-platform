package com.example.social.infrastructure

import com.example.social.application.*
import com.google.api.client.json.gson.GsonFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.time.Instant

class GoogleTranslation(
    private val apiKey: String,
    private val endpoint: URI = URI("https://translation.googleapis.com/language/translate/v2")
) : TextTranslation {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private data class Key(val text: String, val language: Language)
    private data class Cached(val value: Translation, val expires: Instant)
    private val cache = object : LinkedHashMap<Key, Cached>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Cached>?) = size > 2000
    }

    @Synchronized
    override fun translate(texts: List<String>, target: Language): List<Translation?> {
        val now = Instant.now()
        cache.entries.removeIf { it.value.expires <= now }
        val missing = texts.distinct().filter { Key(it, target) !in cache }
        missing.chunked(10).forEach { batch ->
            try {
                val translated = requestTranslation(batch, target)
                batch.zip(translated).forEach { (text, value) -> cache[Key(text, target)] = Cached(value, now.plusSeconds(86400)) }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return texts.map { cache[Key(it, target)]?.value }
            } catch (_: Exception) {
                // Keep successful cache entries; retry unavailable content on the next request.
                return texts.map { cache[Key(it, target)]?.value }
            }
        }
        return texts.map { cache[Key(it, target)]?.value }
    }

    private fun requestTranslation(batch: List<String>, target: Language): List<Translation> {
        val fields = batch.map { "q" to it } + listOf("target" to target.code, "format" to "text")
        val body = fields.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, UTF_8)}" }
        val request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/x-www-form-urlencoded").header("X-Goog-Api-Key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Translation service unavailable" }
        val root = GsonFactory.getDefaultInstance().createJsonParser(response.body()).use { it.parse(HashMap::class.java) }
        val results = (root["data"] as Map<*, *>)["translations"] as List<*>
        check(results.size == batch.size)
        return results.map { value ->
            val row = value as Map<*, *>
            Translation(row["translatedText"] as String, row["detectedSourceLanguage"] as? String ?: "und")
        }
    }
}
