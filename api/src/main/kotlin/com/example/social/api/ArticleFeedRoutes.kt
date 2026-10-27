package com.example.social.api

import com.example.social.application.*
import com.example.social.domain.UserId
import com.example.social.http.AccessControl
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.http4k.core.*
import org.http4k.routing.*
import java.util.UUID

private val feedMapper = jacksonObjectMapper()

data class FeedSourceRequest(val name: String = "", val url: String = "")

private fun FeedSource.view() = mapOf(
    "id" to id.toString(), "name" to name, "url" to url, "createdAt" to createdAt.toString(),
    "lastAttemptAt" to lastAttemptAt?.toString(), "lastSuccessAt" to lastSuccessAt?.toString(), "lastError" to lastError
)

fun articleFeedRoutes(service: ArticleFeedService, access: AccessControl): RoutingHttpHandler {
    fun handle(request: Request, action: (UserId) -> Response): Response = apiSafely {
        action(access.session(request)?.accountId ?: throw AuthenticationFailed())
    }
    return routes(
        "/api/me/articles" bind Method.GET to { request -> handle(request) { owner ->
            val limit = request.query("limit")?.let { it.toIntOrNull() ?: throw IllegalArgumentException("Invalid limit") } ?: 20
            val page = service.articles(owner, limit, request.query("before")?.let(::parseArticleCursor))
            apiJson(mapOf("articles" to page.articles.map { mapOf(
                "id" to it.id.toString(), "sourceId" to it.sourceId.toString(), "sourceName" to it.sourceName, "title" to it.title,
                "url" to it.url, "abstract" to it.abstract, "publishedAt" to it.publishedAt.toString()
            ) }, "next" to page.next?.let { "${it.publishedAt}|${it.id}" }))
        } },
        "/api/me/feeds" bind Method.GET to { request -> handle(request) { owner ->
            apiJson(mapOf("feeds" to service.list(owner).map { it.view() }))
        } },
        "/api/me/feeds" bind Method.POST to { request -> handle(request) { owner ->
            val body = try { feedMapper.readValue<FeedSourceRequest>(request.bodyString()) }
            catch (_: JsonProcessingException) { throw IllegalArgumentException("Invalid feed request") }
            apiJson(service.add(owner, body.name, body.url).view(), Status.CREATED)
        } },
        "/api/me/feeds/{id}" bind Method.DELETE to { request -> handle(request) { owner ->
            val id = try { UUID.fromString(request.path("id")!!) } catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid feed id") }
            if (service.remove(owner, id)) Response(Status.NO_CONTENT) else apiJson(mapOf("error" to "Feed not found"), Status.NOT_FOUND)
        } }
    )
}

private fun parseArticleCursor(value: String): ArticleCursor = try {
    val (time, id) = value.split("|", limit = 2).also { require(it.size == 2) }
    ArticleCursor(java.time.Instant.parse(time), UUID.fromString(id))
} catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid cursor") }
  catch (_: java.time.format.DateTimeParseException) { throw IllegalArgumentException("Invalid cursor") }
