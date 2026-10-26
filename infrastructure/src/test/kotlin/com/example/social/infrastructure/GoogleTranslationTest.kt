package com.example.social.infrastructure

import com.example.social.application.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import kotlin.test.*

class GoogleTranslationTest {
    @Test fun `cache separates languages and failures remain retryable without discarding cached results`() {
        var calls = 0
        var fail = false
        val targets = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls++
            val form = URLDecoder.decode(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8), Charsets.UTF_8)
            targets += form.substringAfter("target=").substringBefore('&')
            val body = (if (fail) "{}" else """{"data":{"translations":[{"translatedText":"translated $calls","detectedSourceLanguage":"en"}]}}""").toByteArray()
            exchange.sendResponseHeaders(if (fail) 503 else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val translator = GoogleTranslation("key", URI("http://127.0.0.1:${server.address.port}/"))
            assertEquals("translated 1", translator.translate(listOf("Hello"), Language.HINDI).single()?.text)
            assertEquals("translated 2", translator.translate(listOf("Hello"), Language.GUJARATI).single()?.text)
            fail = true
            val partial = translator.translate(listOf("Hello", "New text"), Language.HINDI)
            assertEquals("translated 1", partial[0]?.text)
            assertNull(partial[1])
            fail = false
            assertEquals("translated 4", translator.translate(listOf("New text"), Language.HINDI).single()?.text)
            assertEquals(listOf("hi", "gu", "hi", "hi"), targets)
        } finally { server.stop(0) }
    }

    @Test fun `Google adapter batches plain text auto detects language and caches by target and content`() {
        var calls = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls++
            val form = URLDecoder.decode(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8), Charsets.UTF_8)
            assertTrue(form.contains("format=text"))
            assertTrue(form.contains("q=Hello <world>"))
            assertTrue(form.contains("target=mr"))
            assertFalse(form.contains("source="))
            assertEquals("test-key", exchange.requestHeaders.getFirst("X-Goog-Api-Key"))
            val body = """{"data":{"translations":[{"translatedText":"नमस्कार <जग>","detectedSourceLanguage":"en"},{"translatedText":"छान","detectedSourceLanguage":"en"}]}}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val translator = GoogleTranslation("test-key", URI("http://127.0.0.1:${server.address.port}/"))
            val texts = listOf("Hello <world>", "Nice", "Hello <world>")
            val result = translator.translate(texts, Language.MARATHI)
            assertEquals(listOf("नमस्कार <जग>", "छान", "नमस्कार <जग>"), result.map { it?.text })
            assertEquals("en", result.first()?.sourceLanguage)
            assertEquals(result, translator.translate(texts, Language.MARATHI))
            assertEquals(1, calls)
        } finally { server.stop(0) }
    }
}
