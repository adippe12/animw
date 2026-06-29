package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.NextAiring
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addPoster
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * AnimeWorldCore — shared scraping logic for the Core / Sub / Dub variants.
 *
 * Best-practice refactor notes
 * ----------------------------
 *  • HTTP layer: kept inside the companion so the security-cookie bootstrap runs
 *    exactly once per provider instance. Cookie + headers are reused across calls.
 *  • Logging: Log.d used consistently (no commented-out prints).
 *  • No GlobalScope.async / runBlocking — `amap` is the library-sanctioned way
 *    to do parallel extractor fetches inside `loadLinks`.
 *  • Element.toSearchResult() extension convention (§8.3 of the CloudStream guide).
 *  • `load()` integrates TmdbLogoProvider: AniList id -> ARM -> TMDB -> transparent
 *    PNG logo URL, then sets `logoUrl` on the AnimeLoadResponse. A failure in the
 *    logo lookup is logged but never propagates — load() still returns a usable
 *    response. TMDB id is also propagated via `addTMDbId()` for sync enrichment.
 *  • Dub/Sub split is implemented by subclassing (see AnimeWorldDub / AnimeWorldSub)
 *    rather than by reading flags at runtime — this matches the §8.12 open-class
 *    subclassing pattern (IptvOrg) used by Bnyro's GermanProviders.
 */
open class AnimeWorldCore(isSplit: Boolean = false) : MainAPI() {
    final override var mainUrl = Companion.mainUrl
    override var name = "AnimeWorld"
    override var lang = "it"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var sequentialMainPage = true

    open val currentExtension = CurrentExtension.CORE

    override val mainPage = if (isSplit) {
        emptyList()
    } else {
        mainPageOf(
            "$mainUrl/filter?status=0&sort=1" to "In Corso",
            "$mainUrl/filter?sort=1" to "Ultimi aggiunti",
            "$mainUrl/filter?sort=6" to "Più Visti",
            "$mainUrl/tops/all?sort=1" to "Top 100 Anime",
        )
    }

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    /**
     * Which extension variant am I? Used by filterByDubStatus() to decide which
     * entries to keep on the home page / search results.
     */
    enum class CurrentExtension { DUB, SUB, CORE }

    companion object {
        private const val TAG = "AnimeWorld"
        private var mainUrl = "https://www.animeworld.ac"

        // Cookie + headers are mutable so they can be lazily bootstrapped on the
        // first request and reused on every subsequent one.
        private var cookies = mutableMapOf<String, String>()
        private var headers = mutableMapOf<String, String>()

        /**
         * GET helper that injects the AnimeWorld security cookie on first call.
         * The site sets a JS-generated cookie (`document.cookie="..."`) that
         * must be echoed back or every request 403s.
         */
        private suspend fun request(url: String): NiceResponse {
            if (!headers.containsKey("Cookie")) {
                getSecurityCookie()?.let { headers["Cookie"] = it }
            }
            return app.get(url, headers = headers)
        }

        /** Parse the JS-generated security cookie out of the first <script> tag. */
        private suspend fun getSecurityCookie(): String? {
            return try {
                val document = app.get(mainUrl).document
                val script = document.selectFirst("script") ?: return null
                script.data()
                    .substringAfter("document.cookie=\"")
                    .substringBefore(";  path")
                    .takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                Log.w(TAG, "getSecurityCookie failed: ${e.message}")
                null
            }
        }
    }

    /* ---------------------------------------------------------------- */
    /*  Mapping helpers                                                 */
    /* ---------------------------------------------------------------- */

    private fun getType(t: String?): TvType = when (t?.lowercase()) {
        "movie" -> TvType.AnimeMovie
        "ova" -> TvType.OVA
        else -> TvType.Anime
    }

    private fun getStatus(t: String?): ShowStatus? = when (t?.lowercase()) {
        "finito" -> ShowStatus.Completed
        "in corso" -> ShowStatus.Ongoing
        else -> null
    }

    /* ---------------------------------------------------------------- */
    /*  Home page                                                       */
    /* ---------------------------------------------------------------- */

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            request.data + "&page=$page&d=1"
        } else {
            request.data + "&d=1"
        }

        val document = request(url).document
        val list = ArrayList<AnimeSearchResponse>()
        var hasNextPage = false

        if (request.name.contains("Top")) {
            document.select("div.row .content").mapTo(list) { it.toSearchResult(isTopPage = true) }
        } else {
            document.select("div.film-list > .item").mapTo(list) { it.toSearchResult(isTopPage = false) }
            val totalPages = document
                .select("#paging-form span.total")
                .firstOrNull()
                ?.text()
                ?.toIntOrNull()
            hasNextPage = totalPages != null && (page + 1) < totalPages
        }

        val finalList = list.filter(::filterByDubStatus)
        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = finalList,
                isHorizontalImages = false
            ),
            hasNextPage
        )
    }

    /**
     * Jsoup extension — the canonical `Element.toSearchResult()` convention
     * recommended in §8.3 of the CloudStream guide.
     */
    private fun Element.toSearchResult(isTopPage: Boolean): AnimeSearchResponse {
        // href on AnimeWorld looks like "/play/slug.123" — we strip the slug tail
        // so the canonical "id.url" form survives.
        fun String.parseHref(): String {
            val parts = this.split('.').toMutableList()
            if (parts.size > 1) {
                parts[1] = parts[1].substringBeforeLast('/')
            }
            return parts.joinToString(".")
        }

        val anchor = selectFirst(if (isTopPage) "a" else "a.name")
            ?: throw ErrorLoadingException("Error parsing the page")

        val url = fixUrl(anchor.attr("href").parseHref())

        val titleText = if (isTopPage) {
            select("div.info > div.main > a").text()
        } else {
            anchor.text()
        }
        val title = titleText.replace(" (ITA)", "")
        val otherTitle = if (currentExtension == CurrentExtension.DUB) {
            anchor.attr("data-jtitle").replace(" (ITA)", "")
        } else {
            titleText
        }

        val poster = when {
            isTopPage -> anchor.select("img").attr("src")
            else -> select("a.poster img").attr("src")
        }

        val typeElement = select(if (isTopPage) "div.type" else "div.status")
        val dub = typeElement.select(".dub").isNotEmpty()
        val type = when {
            typeElement.select(".movie").isNotEmpty() -> TvType.AnimeMovie
            typeElement.select(".ova").isNotEmpty() -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title, url, type) {
            addDubStatus(dub)
            this.otherName = otherTitle
            this.posterUrl = poster
        }
    }

    /* ---------------------------------------------------------------- */
    /*  Search                                                          */
    /* ---------------------------------------------------------------- */

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val text = app.post(
            "$mainUrl/api/search/v2?keyword=$query",
            referer = mainUrl,
            cookies = cookies
        ).text

        val animes = tryParseJson<SearchJson>(text)?.animes ?: return emptyList()
        return animes.mapNotNull { anime ->
            val type = when (anime.type?.lowercase()) {
                "movie" -> TvType.AnimeMovie
                "ova" -> TvType.OVA
                else -> TvType.Anime
            }
            val dub = anime.language?.lowercase() == "it"
            newAnimeSearchResponse(
                anime.name,
                "$mainUrl/play/${anime.link}.${anime.id}",
                type
            ) {
                addDubStatus(dub)
                this.otherName = anime.otherTitle
                this.posterUrl = anime.image
            }
        }.filter(::filterByDubStatus)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val pageParam = if (page <= 1) "" else "&page=$page"
        val document = request("$mainUrl/filter?sort=0&keyword=${query.trim()}$pageParam").document

        val list = document.select(".film-list > .item").map { it.toSearchResult(isTopPage = false) }
        val totalPages = document.select("#paging-form span.total").firstOrNull()?.text()?.toIntOrNull()
        val hasNextPage = totalPages != null && (page + 1) < totalPages

        return newSearchResponseList(list.filter(::filterByDubStatus), hasNextPage)
    }

    private fun filterByDubStatus(anime: AnimeSearchResponse): Boolean {
        val statuses = anime.dubStatus ?: return true
        return statuses.any {
            when (currentExtension) {
                CurrentExtension.DUB -> it == DubStatus.Dubbed
                CurrentExtension.SUB -> it == DubStatus.Subbed
                CurrentExtension.CORE -> true
            }
        }
    }

    /* ---------------------------------------------------------------- */
    /*  Load                                                            */
    /* ---------------------------------------------------------------- */

    override suspend fun load(url: String): LoadResponse {
        // Allow cloned/mirrored URLs by normalising the host back to the current mainUrl.
        val actualUrl = url.replace(Regex("""www\.animeworld\.\w{2,}"""), mainUrl.toHttpUrl().host)
        val document = request(actualUrl).document

        val widget = document.select("div.widget.info")
        val title = widget.select(".info .title").text().removeSuffix(" (ITA)")
        val otherTitle = widget.select(".info .title").attr("data-jtitle").removeSuffix(" (ITA)")
        val description = widget.select(".desc .long").firstOrNull()?.text()
            ?: widget.select(".desc").text()
        val poster = document.select(".thumb img").attr("src")

        val type = getType(widget.select("dd").firstOrNull()?.text())
        val genres = widget.select(".meta").select("a[href*=\"/genre/\"]").map { it.text() }
        val rating = widget.select("#average-vote").text()
        val trailerUrl = document.select(".trailer[data-url]").attr("data-url")

        // External IDs — used for sync enrichment AND for the TMDB logo lookup.
        val malId = document.select("#mal-button").attr("href").split('/').lastOrNull()?.toIntOrNull()
        val anlId = document.select("#anilist-button").attr("href").split('/').lastOrNull()?.toIntOrNull()

        var dub = false
        var year: Int? = null
        var status: ShowStatus? = null
        var duration: String? = null

        for (meta in document.select(".meta dt, .meta dd")) {
            val text = meta.text()
            when {
                text.contains("Audio") ->
                    dub = meta.nextElementSibling()?.text() == "Italiano"
                year == null && text.contains("Data") ->
                    year = meta.nextElementSibling()?.text()?.split(' ')?.lastOrNull()?.toIntOrNull()
                status == null && text.contains("Stato") ->
                    status = getStatus(meta.nextElementSibling()?.text())
                duration == null && text.contains("Durata") ->
                    duration = meta.nextElementSibling()?.text()
            }
        }
        duration = normalizeDuration(duration)

        // Episodes — we use server "9" (AnimeWorld native) as the canonical source.
        val servers = document.select(".widget.servers > .widget-body")
        val episodes = servers.select(".server[data-name=\"9\"] .episode").map {
            val number = it.select("a").attr("data-episode-num").toIntOrNull()
            newEpisode("$number¿$actualUrl") {
                this.episode = number
            }
        }
        val comingSoon = episodes.isEmpty()

        // Next-airing info — parsed from data-calendar-date / data-calendar-time.
        val nextAiringDate = document.select("#next-episode").attr("data-calendar-date")
        val nextAiringTime = document.select("#next-episode").attr("data-calendar-time")
        val nextAiringUnix = try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())
                .parse("$nextAiringDate'T$nextAiringTime")?.time?.div(1000)
        } catch (_: Exception) {
            null
        }

        val recommendations = document.select(".film-list.interesting .item").map {
            it.toSearchResult(isTopPage = false)
        }

        /* -------------------------------------------------------------- */
        /*  Title logo enrichment (AniList → ARM → TMDB → PNG)            */
        /* -------------------------------------------------------------- */
        // The lookup is fully cached (24h TTL) and any failure is swallowed
        // so it can never break the parent load().
        val logoResult = TmdbLogoProvider.fetchLogo(anlId)

        return newAnimeLoadResponse(title, actualUrl, type) {
            engName = title
            japName = otherTitle
            addPoster(poster)
            this.year = year
            addEpisodes(if (dub) DubStatus.Dubbed else DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            tags = genres
            addMalId(malId)
            addAniListId(anlId)
            // TMDB id is now available via ARM — surface it so the sync layer
            // can match the user's watch history against TMDB-backed trackers.
            logoResult.tmdbId?.let { addTMDbId(it.toString()) }
            addScore(rating)
            duration?.let { addDuration(it) }
            addTrailer(trailerUrl)
            // *** THE LOGO ***
            // logoUrl is rendered by the CloudStream UI as a transparent title
            // image overlay on the detail page (just below the poster).
            logoResult.logoUrl?.let { this.logoUrl = it }
            this.recommendations = recommendations
            this.comingSoon = comingSoon
            if (episodes.isNotEmpty() && nextAiringUnix != null && episodes.last().episode != null) {
                this.nextAiring = NextAiring(episodes.last().episode!! + 1, nextAiringUnix, null)
            }
        }
    }

    /** AnimeWorld formats durations as either "24min/ep", "1h e 30 min", or plain "min". */
    private fun normalizeDuration(input: String?): String? {
        if (input.isNullOrBlank()) return null
        var duration = input
        if (duration.contains("/ep")) {
            duration = duration.replace("/ep", "")
        } else if (duration.contains("h e ")) {
            val parts = duration.split("h e ")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: return duration
            val m = parts.getOrNull(1)?.removeSuffix(" min")?.toIntOrNull() ?: 0
            duration = "${h * 60 + m} min"
        }
        return duration
    }

    /* ---------------------------------------------------------------- */
    /*  loadLinks                                                       */
    /* ---------------------------------------------------------------- */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // `data` was packed in load() as "$epNumber¿$pageUrl".
        val d = data.substringAfter("$mainUrl/")
        val epNumber = d.substringBefore('¿').toIntOrNull() ?: return false
        val pageUrl = d.substringAfter('¿')
        if (pageUrl.isBlank()) return false

        val serverElem = request(pageUrl).document.select(".widget.servers")
        val epElems = serverElem.select(".widget-body > .server")
            .select("a[data-episode-num=\"$epNumber\"]")

        val apiLinks = epElems.map { "${mainUrl}/api/episode/info?id=" + it.attr("data-id") }
        val apiResults = apiLinks.mapNotNull {
            tryParseJson<EpisodeInfoJson>(request(it).text)
        }
        if (apiResults.isEmpty()) return false

        // amap = async map — runs every extractor fetch concurrently without
        // leaking GlobalScope coroutines (§9.3 anti-pattern: don't use GlobalScope).
        apiResults.amap { info ->
            val target = info.target.orEmpty()
            val grabber = info.grabber
            when {
                target.contains("AnimeWorld") -> {
                    val link = newExtractorLink(
                        name,
                        "AnimeWorld",
                        grabber,
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                    callback.invoke(link)
                }

                target.contains("listeamed.net") -> {
                    // Delegate to the framework's extractor dispatcher — if a
                    // VidguardExtractor is registered (see AnimeWorldPlugin),
                    // it will pick up the URL via mainUrl prefix match.
                    loadExtractor(grabber, null, subtitleCallback, callback)
                }

                else -> Unit
            }
        }
        return true
    }
}
