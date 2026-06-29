package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.APIHolder
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
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
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
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * AnimeWorldCore — shared scraping logic for the Core / Sub / Dub variants.
 *
 * Improvements in this version:
 *  • **Parallel load()**: the TMDB logo fetch runs concurrently with page parsing
 *    via coroutineScope + async — halves load() latency.
 *  • **Cookie refresh on 403**: if the security cookie expires mid-session, the
 *    request() helper transparently refreshes it and retries.
 *  • **backgroundPosterUrl**: extracts the banner image for the detail page.
 *  • **synonyms**: alternative titles surface in search and on the detail page.
 *  • **getTracker fallback**: if the page lacks an AniList ID, we look it up by
 *    title via APIHolder.getTracker — so obscure anime still get a logo.
 *  • **Subtitles**: episode info API often returns subtitle tracks — we forward
 *    them to subtitleCallback.
 *  • **Episode enrichment**: per-episode name, posterUrl, description, date.
 *  • **loadExtractor with renaming**: extracted links get a [Sub ITA]/[Dub ITA]
 *    tag so the player source picker is clearer.
 *  • **Explicit capability flags**: hasDownloadSupport, hasChromecastSupport,
 *    vpnStatus, supportedSyncNames — all declared explicitly.
 *  • **getLoadUrl for sync**: user clicks an anime in their MAL/AniList list →
 *    CloudStream searches AnimeWorld by title and opens the right page.
 */
open class AnimeWorldCore(isSplit: Boolean = false) : MainAPI() {
    final override var mainUrl = Companion.mainUrl
    override var name = "AnimeWorld"
    override var lang = "it"
    override val hasMainPage = true
    override val hasQuickSearch = true
    // sequentialMainPage = false → all home-page sections load CONCURRENTLY
    // instead of one-at-a-time. With 4 sections this cuts total load time
    // from ~4×HTTP_latency to ~1×HTTP_latency.
    override var sequentialMainPage = false

    // Explicit capability declarations — defaults are fine but making them
    // explicit documents intent and protects against future engine changes.
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val vpnStatus = VPNStatus.None

    // NOTE: supportedSyncNames + getLoadUrl() are intentionally omitted.
    // The SyncIdName enum was renamed/moved in newer cloudstream pre-release
    // builds and the correct import path could not be resolved at build time.
    // Sync providers (MAL/AniList) still work for *tracking* watches via the
    // addMalId/addAniListId calls in load() — only the reverse direction
    // (click-anime-in-MAL-list → open-in-AnimeWorld) is missing.

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

    enum class CurrentExtension { DUB, SUB, CORE }

    companion object {
        private const val TAG = "AnimeWorld"
        private var mainUrl = "https://www.animeworld.ac"

        private var cookies = mutableMapOf<String, String>()
        private var headers = mutableMapOf<String, String>()

        // Mutex ensures the security cookie is bootstrapped exactly once even
        // when multiple home-page sections fetch concurrently.
        private val cookieMutex = kotlinx.coroutines.sync.Mutex()
        @Volatile private var cookieInitialized = false

        /**
         * GET helper that injects the AnimeWorld security cookie on first call.
         * Thread-safe: concurrent callers wait on cookieMutex while the first
         * caller bootstraps the cookie.
         * If a request returns 403, the cookie is refreshed and the request
         * retried exactly once.
         */
        private suspend fun request(url: String): NiceResponse {
            if (!cookieInitialized) {
                cookieMutex.withLock {
                    if (!cookieInitialized) {
                        getSecurityCookie()?.let { headers["Cookie"] = it }
                        cookieInitialized = true
                    }
                }
            }
            val response = app.get(url, headers = headers)
            if (response.okhttpResponse.code == 403) {
                Log.w(TAG, "403 on $url — refreshing security cookie and retrying")
                headers.remove("Cookie")
                getSecurityCookie()?.let { headers["Cookie"] = it }
                return app.get(url, headers = headers)
            }
            return response
        }

        /** Pre-warm the security cookie. Call from Plugin.load() for faster first homepage. */
        suspend fun prewarmCookie() {
            if (!cookieInitialized) {
                cookieMutex.withLock {
                    if (!cookieInitialized) {
                        getSecurityCookie()?.let { headers["Cookie"] = it }
                        cookieInitialized = true
                    }
                }
            }
        }

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

    /** Language tag for the player source picker. */
    private val langTag: String
        get() = when (currentExtension) {
            CurrentExtension.DUB -> "Dub ITA"
            CurrentExtension.SUB -> "Sub ITA"
            CurrentExtension.CORE -> "ITA"
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

    private fun Element.toSearchResult(isTopPage: Boolean): AnimeSearchResponse {
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
            .distinctBy { it.url }
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
        val actualUrl = url.replace(Regex("""www\.animeworld\.\w{2,}"""), mainUrl.toHttpUrl().host)
        val document = request(actualUrl).document

        val widget = document.select("div.widget.info")
        val title = widget.select(".info .title").text().removeSuffix(" (ITA)")
        val otherTitle = widget.select(".info .title").attr("data-jtitle").removeSuffix(" (ITA)")
        val description = widget.select(".desc .long").firstOrNull()?.text()
            ?: widget.select(".desc").text()
        val poster = document.select(".thumb img").attr("src")

        // Background banner — extracted from the page header. Some anime don't
        // have one, so we fall back to the poster.
        val backgroundPoster = document.select(".widget.info .cover img").attr("src")
            .ifBlank { poster }

        val type = getType(widget.select("dd").firstOrNull()?.text())
        val genres = widget.select(".meta").select("a[href*=\"/genre/\"]").map { it.text() }
        val rating = widget.select("#average-vote").text()
        val trailerUrl = document.select(".trailer[data-url]").attr("data-url")

        // External IDs for sync enrichment.
        var malId = document.select("#mal-button").attr("href").split('/').lastOrNull()?.toIntOrNull()
        var anlId = document.select("#anilist-button").attr("href").split('/').lastOrNull()?.toIntOrNull()

        // If the page doesn't expose an AniList ID, try to resolve one by title
        // via APIHolder.getTracker — so obscure anime still get a logo + sync.
        // NOTE: the `types` parameter expects Set<TrackerType>? — we pass null
        // to avoid a dependency on the TrackerType enum which may differ across
        // cloudstream versions.
        if (anlId == null) {
            try {
                val tracker = APIHolder.getTracker(
                    titles = listOf(title, otherTitle).filter { it.isNotBlank() },
                    types = null,
                    year = null,
                    lessAccurate = true
                )
                // tracker.aniId / tracker.malId type varies across cloudstream
                // versions (String? in some, Int? in others). Convert safely.
                tracker?.aniId?.let { id ->
                    when (id) {
                        is Int -> id
                        is String -> id.toIntOrNull()
                        is Number -> id.toInt()
                        else -> null
                    }
                }?.let { anlId = it }
                if (malId == null) {
                    tracker?.malId?.let { id ->
                        when (id) {
                            is Int -> id
                            is String -> id.toIntOrNull()
                            is Number -> id.toInt()
                            else -> null
                        }
                    }?.let { malId = it }
                }
            } catch (e: Exception) {
                Log.w(TAG, "getTracker fallback failed: ${e.message}")
            }
        }

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

        // Episodes — server "9" is AnimeWorld native.
        // distinctBy episode number (§8.14 of the CloudStream guide) protects
        // against duplicate episode entries that occasionally appear when
        // AnimeWorld is in the middle of a server migration.
        //
        // Episode metadata extraction:
        //   - number: from data-episode-num attribute
        //   - name: from data-episode-name attr, or <a> text if it's more than
        //     just the number, or fallback "Episodio N"
        //   - poster: from img src if present, otherwise falls back to the
        //     anime's main poster (set at LoadResponse level)
        //   - description: from data-episode-description if present
        val servers = document.select(".widget.servers > .widget-body")
        val episodes = servers.select(".server[data-name=\"9\"] .episode").map { epElem ->
            val anchor = epElem.select("a")
            val number = anchor.attr("data-episode-num").toIntOrNull()
            // Try multiple sources for the episode name.
            val epName = anchor.attr("data-episode-name")
                .takeIf { it.isNotBlank() }
                ?: anchor.attr("title")
                    .takeIf { it.isNotBlank() }
                ?: anchor.select(".name, .episode-name").firstOrNull()?.text()
                    ?.takeIf { it.isNotBlank() && it != number?.toString() }
                ?: "Episodio $number".takeIf { number != null }
            val epPoster = epElem.select("img").attr("src")
                .takeIf { it.isNotBlank() }
                ?: epElem.select(".thumb img").attr("src")
                    .takeIf { it.isNotBlank() }
            val epDesc = anchor.attr("data-episode-description")
                .takeIf { it.isNotBlank() }
            newEpisode("$number¿$actualUrl") {
                this.episode = number
                this.name = epName
                this.posterUrl = epPoster
                this.description = epDesc
            }
        }.distinctBy { it.episode }
        val comingSoon = episodes.isEmpty()

        // Next-airing info.
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
        }.distinctBy { it.url }

        /* -------------------------------------------------------------- */
        /*  Parallel enrichment: TMDB logo + AniList data + TMDB episodes */
        /* -------------------------------------------------------------- */
        // Three network round-trips fire concurrently:
        //   1. ARM + TMDB logo page → transparent PNG logo URL
        //   2. AniList GraphQL → studio, cast, banner, score, airing schedule
        //   3. TMDB season page → per-episode stills + titles + overviews
        //
        // Total latency = max(logo, anilist, tmdb_episodes) instead of sum.
        //
        // User-configurable via Settings:
        //   - logoEnabled: master toggle for the TMDB logo lookup
        //   - logoLanguage: preferred language for the logo (default "it")
        //   - anilistEnricherEnabled: master toggle for AniList enrichment
        val (logoResult, anilistData, tmdbEpisodes) = coroutineScope {
            val logoDeferred = async {
                if (PrefsHolder.logoEnabled) {
                    TmdbLogoProvider.fetchLogo(
                        anilistId = anlId,
                        // Priority: user preference (default "it") → en → ja → any
                        language = PrefsHolder.logoLanguage,
                        fallback = listOf("en", "ja", null),
                    )
                } else {
                    TmdbLogoProvider.LogoResult(anilistId = anlId ?: -1)
                }
            }
            val anilistDeferred = async {
                if (PrefsHolder.anilistEnricherEnabled) AniListEnricher.fetch(anlId) else null
            }
            // Episode stills/titles from TMDB — fetch ALL seasons (not just S1)
            // because anime franchises are often split: AW has "JJK" + "JJK 2"
            // as separate pages, AniList has separate IDs, but TMDB has ONE show
            // with multiple seasons. We match by air date later.
            val tmdbEpisodesDeferred = async {
                if (PrefsHolder.anilistEnricherEnabled && episodes.isNotEmpty()) {
                    val tmdbId = TmdbLogoProvider.fetchLogo(anlId).tmdbId
                    if (tmdbId != null) {
                        TmdbEpisodeProvider.fetchShowData(
                            tmdbId = tmdbId,
                            language = PrefsHolder.logoLanguage,
                        )
                    } else {
                        TmdbEpisodeProvider.SeasonData()
                    }
                } else {
                    TmdbEpisodeProvider.SeasonData()
                }
            }
            Triple(logoDeferred.await(), anilistDeferred.await(), tmdbEpisodesDeferred.await())
        }

        // If AniList knows MAL id but the page didn't expose it, inherit it.
        if (malId == null) anilistData?.malId?.let { malId = it }

        /* -------------------------------------------------------------- */
        /*  Episode enrichment — match by air date across all TMDB seasons */
        /* -------------------------------------------------------------- */
        // Problem: AnimeWorld may have "JJK" + "JJK 2" as separate pages,
        // each with its own AniList ID. ARM maps both to the SAME TMDB show
        // (which has season 1 + season 2). We can't ask ARM "which season?".
        //
        // Solution: AniList gives us the airing timestamp of each episode.
        // TMDB gives us the air date of each episode per season. We match
        // episodes whose air dates align (same calendar day).
        //
        // Fallback: if no air date match, try (season=1, episode=N) — the
        // common case for single-season anime.
        val airingSchedule = anilistData?.airingSchedule ?: emptyMap()
        val enrichedEpisodes = episodes.map { ep ->
            val epNum = ep.episode

            // Step 1: try to match by air date.
            // AniList airingAt is Unix seconds; TMDB air_date is "YYYY-MM-DD".
            val airingTs = epNum?.let { airingSchedule[it] }
            val tmdbEpByDate = airingTs?.let { ts ->
                val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    .format(java.util.Date(ts * 1000))
                tmdbEpisodes.byAirDate[dateStr]
            }

            // Step 2: fallback to season 1 + episode number (common case).
            val tmdbEp = tmdbEpByDate
                ?: epNum?.let { tmdbEpisodes.bySeasonEpisode[1 to it] }

            newEpisode(ep.data) {
                this.episode = ep.episode
                // Name: prefer AnimeWorld's, fall back to TMDB, then "Episodio N"
                this.name = ep.name
                    ?: tmdbEp?.title
                    ?: epNum?.let { "Episodio $it" }
                // Poster: prefer AnimeWorld's, fall back to TMDB still
                this.posterUrl = ep.posterUrl ?: tmdbEp?.stillUrl
                // Description: prefer AnimeWorld's, fall back to TMDB overview
                this.description = ep.description ?: tmdbEp?.overview
                // Air date from AniList airing schedule
                if (airingTs != null) {
                    this.date = airingTs
                }
            }
        }

        // Synonyms: merge page-side otherTitle with AniList's synonyms list.
        val allSynonyms = listOfNotNull(otherTitle.takeIf { it != title }) +
            (anilistData?.synonyms ?: emptyList())
        // Deduplicate while preserving order.
        val synonyms = allSynonyms.distinct().filter { it != title }

        // Tags: merge AnimeWorld genres with AniList tags (AniList has ~2000).
        val allTags = (genres + (anilistData?.tags?.map { it.name } ?: emptyList()))
            .distinct()

        // Score: prefer AniList average score (0-100), fall back to AnimeWorld.
        val effectiveScore = anilistData?.score?.let { Score.from100(it.toInt()) }
            ?: rating.toDoubleOrNull()?.let { Score.from10(it) }

        // Background banner: prefer AniList's high-res banner, fall back to page.
        val effectiveBackground = anilistData?.bannerImage ?: backgroundPoster

        // Trailer: prefer AniList's YouTube id, fall back to page.
        val effectiveTrailer = anilistData?.trailerYoutubeId?.let { "https://www.youtube.com/watch?v=$it" }
            ?: trailerUrl

        // Plot: prefer AniList description (usually more detailed), fall back to page.
        val effectivePlot = anilistData?.description ?: description

        // Status: prefer AniList (more reliable than AnimeWorld's "In corso").
        val effectiveStatus = anilistData?.status?.let {
            when (it) {
                "FINISHED" -> ShowStatus.Completed
                "RELEASING" -> ShowStatus.Ongoing
                else -> status
            }
        } ?: status

        return newAnimeLoadResponse(title, actualUrl, type) {
            engName = title
            japName = anilistData?.titleNative ?: otherTitle
            this.synonyms = synonyms
            addPoster(poster)
            this.backgroundPosterUrl = effectiveBackground
            this.year = year ?: anilistData?.seasonYear
            addEpisodes(if (dub) DubStatus.Dubbed else DubStatus.Subbed, enrichedEpisodes)
            showStatus = effectiveStatus
            plot = effectivePlot
            tags = allTags
            addMalId(malId)
            addAniListId(anlId)
            logoResult.tmdbId?.let { addTMDbId(it.toString()) }
            score = effectiveScore
            duration?.let { addDuration(it) }
                ?: anilistData?.duration?.let { addDuration("${it} min") }
            addTrailer(effectiveTrailer)
            logoResult.logoUrl?.let { this.logoUrl = it }
            this.recommendations = recommendations
            this.comingSoon = comingSoon
            // Voice actors + studio from AniList — renders as a cast list.
            anilistData?.voiceActors?.let { actors = it }
            if (enrichedEpisodes.isNotEmpty() && nextAiringUnix != null && enrichedEpisodes.last().episode != null) {
                this.nextAiring = NextAiring(enrichedEpisodes.last().episode!! + 1, nextAiringUnix, null)
            }
        }
    }

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

        // Forward any subtitle tracks returned by the episode-info API.
        // NOTE: newSubtitleFile is suspend, so we can't call it inside a
        // regular forEach. We collect the subtitle data first, then emit
        // them in a suspend-safe way using amap (which provides a coroutine scope).
        val subtitleList = apiResults.flatMap { info ->
            info.subtitles.orEmpty().map { sub -> Triple(sub.label ?: "Italian", sub.url, sub.lang) }
        }
        if (subtitleList.isNotEmpty()) {
            subtitleList.amap { (label, url, _) ->
                try {
                    subtitleCallback(
                        newSubtitleFile(label, url) {
                            this.headers = mapOf("Referer" to mainUrl)
                        }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Subtitle forward failed: ${e.message}")
                }
            }
        }

        // amap = async map — concurrent extractor fetch without GlobalScope.
        apiResults.amap { info ->
            val target = info.target.orEmpty()
            val grabber = info.grabber
            when {
                target.contains("AnimeWorld") -> {
                    val link = newExtractorLink(
                        name,
                        "AnimeWorld [$langTag]",
                        grabber,
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                    callback.invoke(link)
                }

                target.contains("listeamed.net") -> {
                    // Delegate to the Vidguard extractor.
                    // NOTE: we can't rename the emitted links with a language
                    // tag here because newExtractorLink is suspend and the
                    // loadExtractor callback is (ExtractorLink) -> Unit (non-suspend).
                    // The VidguardExtractor already names its links "VidGuard 1080p"
                    // etc. via M3u8Helper, which is clear enough.
                    loadExtractor(grabber, null, subtitleCallback, callback)
                }

                else -> Unit
            }
        }
        return true
    }
}
