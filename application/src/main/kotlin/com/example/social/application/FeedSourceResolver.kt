package com.example.social.application

import java.net.URI

class NoFeedFound : IllegalArgumentException("No feed found for this address. Paste the publisher's RSS or Atom feed URL instead.")

/**
 * An optional, operator run RSSHub. Only the exact feed URLs built from [routes] (host to route path) are ever
 * requested; members never supply RSSHub paths.
 */
class RssHubFeeds(baseUrl: String, routes: Map<String, String>, val fetcher: ArticleFeedFetcher) {
    private val feedUrls = routes.entries.associate { (host, route) -> host.lowercase() to baseUrl.trimEnd('/') + route }

    fun feedUrlFor(host: String): String? = feedUrls[host.lowercase()]
    fun allows(url: String) = url in feedUrls.values
}

/** Sends refreshes of allow-listed RSSHub feeds to RSSHub's own fetcher and everything else to the public one. */
class FeedFetchRouter(private val public: ArticleFeedFetcher, private val rssHub: RssHubFeeds?) : ArticleFeedFetcher {
    override fun fetch(url: String): List<FeedEntry> = if (rssHub?.allows(url) == true) rssHub.fetcher.fetch(url) else public.fetch(url)
}

/** Turns a pasted site address into a feed URL: the address itself, an advertised feed, then a configured RSSHub route. */
class FeedSourceResolver(
    private val fetcher: ArticleFeedFetcher,
    private val discovery: FeedDiscovery?,
    private val rssHub: RssHubFeeds?
) {
    fun resolve(address: String): String =
        takeIf { isFeed(address) }?.let { address }
            ?: advertisedFeeds(address).firstOrNull(::isFeed)
            ?: rssHubFeed(address)
            ?: throw NoFeedFound()

    private fun isFeed(url: String) = try { fetcher.fetch(url); true } catch (_: Exception) { false }

    private fun advertisedFeeds(address: String): List<String> =
        try { discovery?.discover(address).orEmpty() } catch (_: Exception) { emptyList() }

    private fun rssHubFeed(address: String): String? {
        val hub = rssHub ?: return null
        val host = try { URI(address).host } catch (_: Exception) { null } ?: return null
        val url = hub.feedUrlFor(host) ?: return null
        return url.takeIf { try { hub.fetcher.fetch(it); true } catch (_: Exception) { false } }
    }
}

/** RSSHUB_ROUTES: comma separated `host=/route` pairs; blank or malformed entries are ignored. */
fun parseRssHubRoutes(value: String?): Map<String, String> = value.orEmpty().split(',').mapNotNull { entry ->
    val (host, route) = entry.split('=', limit = 2).map { it.trim() }.takeIf { it.size == 2 } ?: return@mapNotNull null
    if (host.isEmpty() || !route.startsWith("/")) null else host.lowercase() to route
}.toMap()

/** Null (disabled) unless RSSHUB_BASE_URL is an http(s) URL and RSSHUB_ROUTES lists at least one route. */
fun rssHubFeeds(baseUrl: String?, routes: String?, fetcher: ArticleFeedFetcher): RssHubFeeds? {
    val base = baseUrl?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
    return parseRssHubRoutes(routes).takeIf { it.isNotEmpty() }?.let { RssHubFeeds(base, it, fetcher) }
}
