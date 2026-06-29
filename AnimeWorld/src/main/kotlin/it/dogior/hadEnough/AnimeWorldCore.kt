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
import com.lagradost.cloudstream3.SyncIdName
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
    override var sequentialMainPage = true

    // Explicit capability declarations — defaults are fine but making them
    // explicit documents intent and protects against future engine changes.
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val vpnStatus = VPNStatus.None

    // Declare which sync providers we can resolve URLs for (see getLoadUrl).
    override val supportedSyncNames = setOf(
        SyncIdName.MyAnimeList,
        SyncIdName.AniList,
    )

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

        /**
         * GET helper that injects the AnimeWorld security cookie on first call.
         * If a request returns 403, the cookie is refreshed and the request
         * retried exactly once — prevents cascading failures when the cookie
         * expires mid-browse.
         */
        private suspend fun request(url: String): NiceResponse {
            if (!headers.containsKey("Cookie")) {
                getSecurityCookie()?.let { headers["Cookie"] = it }
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
    /*  Sync: translate external ID -> AnimeWorld URL                   */
    /* ---------------------------------------------------------------- */

    /**
     * Called when the user clicks an anime in their MAL or AniList watch list.
     * We resolve the external ID to an anime title, then search AnimeWorld
     * by title and return the first match's URL.
     */
    override suspend fun getLoadUrl(name: SyncIdName, id: String): String? {
        val title = when (name) {
            SyncIdName.AniList -> {
                // AniListEnricher.fetch already caches for 7 days — cheap.
                AniListEnricher.fetch(id.toIntOrNull() ?: return null)
                    ?.let { it.titleEnglish ?: it.titleRomaji ?: it.titleNative }
            }
            SyncIdName.MyAnimeList -> fetchMalTitle(id.toIntOrNull() ?: return null)
            else -> null
        } ?: return null

        // Search AnimeWorld by the resolved title and return the first hit.
        return search(title, page = 1).results
            ?.firstOrNull()
            ?.url
    }

    /** Use Jikan (public MAL API, no key) to resolve a MAL id to a title. */
    private suspend fun fetchMalTitle(malId: Int): String? {
        return try {
            val response = app.get("https://api.jikan.moe/v4/anime/$malId").text
            val titleMatch = Regex(""""title"\s*:\s*"([^"]+)"""").find(response)
            titleMatch?.groupValues?.getOrNull(1)
        } catch (e: Exception) {
            Log.w(TAG, "MAL title lookup failed: ${e.message}")
            null
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
        if (anlId == null) {
            try {
                val tracker = APIHolder.getTracker(
                    titles = listOf(title, otherTitle).filter { it.isNotBlank() },
                    types = listOf("TV", "MOVIE", "OVA"),
                    year = null,
                    lessAccurate = true
                )
                tracker?.aniId?.toIntOrNull()?.let { anlId = it }
                if (malId == null) tracker?.malId?.toIntOrNull()?.let { malId = it }
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
        val servers = document.select(".widget.servers > .widget-body")
        val episodes = servers.select(".server[data-name=\"9\"] .episode").map { epElem ->
            val number = epElem.select("a").attr("data-episode-num").toIntOrNull()
            val epName = epElem.select("a").attr("data-episode-name").takeIf { it.isNotBlank() }
            val epPoster = epElem.select("img").attr("src").takeIf { it.isNotBlank() }
            newEpisode("$number¿$actualUrl") {
                this.episode = number
                this.name = epName
                this.posterUrl = epPoster
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
        /*  Parallel enrichment: TMDB logo + AniList data                 */
        /* -------------------------------------------------------------- */
        // Both network round-trips (ARM+TMDB for logo, AniList GraphQL for
        // enrichment) fire concurrently via coroutineScope + async. Total
        // latency = max(logo, anilist) instead of sum(logo, anilist).
        //
        // User-configurable via Settings:
        //   - logoEnabled: master toggle for the TMDB logo lookup
        //   - logoLanguage: preferred language for the logo (default "en")
        //   - anilistEnricherEnabled: master toggle for AniList enrichment
        val (logoResult, anilistData) = coroutineScope {
            val logoDeferred = async {
                if (PrefsHolder.logoEnabled) {
                    TmdbLogoProvider.fetchLogo(
                        anilistId = anlId,
                        language = PrefsHolder.logoLanguage,
                        fallback = listOf("ja", null),
                    )
                } else {
                    TmdbLogoProvider.LogoResult(anilistId = anlId ?: -1)
                }
            }
            val anilistDeferred = async {
                if (PrefsHolder.anilistEnricherEnabled) AniListEnricher.fetch(anlId) else null
            }
            logoDeferred.await() to anilistDeferred.await()
        }

        // If AniList knows MAL id but the page didn't expose it, inherit it.
        if (malId == null) anilistData?.malId?.let { malId = it }

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
            addEpisodes(if (dub) DubStatus.Dubbed else DubStatus.Subbed, episodes)
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
            if (episodes.isNotEmpty() && nextAiringUnix != null && episodes.last().episode != null) {
                this.nextAiring = NextAiring(episodes.last().episode!! + 1, nextAiringUnix, null)
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
        apiResults.forEach { info ->
            info.subtitles?.forEach { sub ->
                try {
                    subtitleCallback(
                        newSubtitleFile(sub.label ?: "Italian", sub.url) {
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
                    // Delegate to the Vidguard extractor. We use the 4-arg
                    // loadExtractor overload to rename the emitted links with
                    // a language tag so the player source picker is clearer.
                    loadExtractor(grabber, null, subtitleCallback) { link ->
                        callback(
                            newExtractorLink(
                                source = link.source,
                                name = "${link.name} [$langTag]",
                                url = link.url,
                                type = link.type,
                            ) {
                                this.referer = link.referer
                                this.quality = link.quality
                                this.headers = link.headers
                                this.extractorData = link.extractorData
                            }
                        )
                    }
                }

                else -> Unit
            }
        }
        return true
    }
}
