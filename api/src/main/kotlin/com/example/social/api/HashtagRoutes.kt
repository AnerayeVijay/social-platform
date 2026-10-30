package com.example.social.api

import com.example.social.application.*
import com.example.social.domain.*
import com.example.social.http.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.http4k.core.*
import org.http4k.routing.*
import java.time.Instant

private val hashtagMapper = jacksonObjectMapper()

private data class HashtagSearchRequest(val query: String = "", val limit: Int = 10, val offset: Int = 0)

fun hashtagRoutes(queries: PostQueries, access: AccessControl? = null): RoutingHttpHandler = routes(
    "/api/hashtags/search" bind Method.POST to { request ->
        apiSafely {
            val search = try { hashtagMapper.readValue<HashtagSearchRequest>(request.bodyString()) }
                catch (_: Exception) { throw IllegalArgumentException("Invalid search request") }
            val query = search.query.trim().removePrefix("#").lowercase()
            require(Hashtag.pattern.matches(query)) { "Search must be 1-50 letters, digits or underscores" }
            val viewer = access?.session(request)?.accountId
            val hashtags = queries.searchHashtags(query, search.limit, search.offset, viewer).map { hashtagJson(it.name, it.posts) }
            apiJson(mapOf("hashtags" to hashtags, "limit" to search.limit, "offset" to search.offset))
        }
    },

    "/api/hashtags/trending" bind Method.GET to { request ->
        apiSafely {
            val period = request.query("period") ?: "24h"
            val window = TrendWindow.parse(period)
            val limit = request.query("limit")?.let { it.toIntOrNull() ?: throw IllegalArgumentException("Invalid limit") } ?: 10
            val trends = queries.trending(limit, window, Instant.now()).mapIndexed { index, trend -> mapOf(
                "rank" to index + 1, "name" to trend.name, "posts" to trend.posts,
                "previousPosts" to trend.previousPosts, "growthPercent" to trend.growthPercent
            ) }
            apiJson(mapOf("period" to period, "hashtags" to trends))
        }
    },

    "/api/hashtags/{name}" bind Method.GET to { request ->
        apiSafely {
            val tag = Hashtag.from(request.path("name")!!)
            apiJson(hashtagJson(tag.value, queries.hashtagPostCount(tag, access?.session(request)?.accountId)))
        }
    },

    "/api/hashtags/{name}/posts" bind Method.GET to { request ->
        apiSafely {
            val tag = Hashtag.from(request.path("name")!!)
            val limit = request.query("limit")?.let { it.toIntOrNull() ?: throw IllegalArgumentException("Invalid limit") } ?: 20
            val before = request.query("before")?.let(::decodePostCursor)
            feedResponse(queries.feed(limit, before, access?.session(request)?.accountId, tag))
        }
    }
)

private fun hashtagJson(name: String, posts: Long) = mapOf("name" to name, "posts" to posts)
