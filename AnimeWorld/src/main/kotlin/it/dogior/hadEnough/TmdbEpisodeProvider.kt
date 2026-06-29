package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * TmdbEpisodeProvider
 * ===================
 * Scrapes public TMDB season pages (no auth, no key) to extract per-episode:
 *   - Title
 *   - Overview (description)
 *   - Still image URL
 *   - Air date (parsed to Unix timestamp for cross-source matching)
 *
 * **HTML structure** (verified June 2026):
 * TMDB renders season pages server-side. Each episode is in a <div class="card">
 * block with this structure:
 *
 *   <div class="card" data-url="/tv/{id}/season/{s}/episode/{n}">
 *     <div class="episode closed">
 *       <div class="image">
 *         <img src="https://media.themoviedb.org/t/p/w227_and_h127_face/{hash}.jpg">
 *       </div>
 *       <div class="info">
 *         <span class="episode_number">{n}</span>
 *         <div class="episode_title"><h3><a>{title}</a></h3></div>
 *         <span class="date">{Month DD, YYYY}</span>
 *         <div class="overview"><p>{overview}</p></div>
 *       </div>
 *     </div>
 *   </div>
 *
 * **Season detection**:
 *   ARM API returns `themoviedb-season` for each AniList ID — but this can be
 *   WRONG (e.g. JJK S2 has season=2 in ARM, but TMDB only has season 1 with
 *   all 59 episodes collapsed together).
 *
 *   Solution: try ARM's season first; if 404, fall back to season 1.
 *
 * **Episode offset detection**:
 *   When TMDB collapses multiple anime seasons into one TMDB season (JJK case),
 *   we use air dates to find the offset: AniList gives us airing timestamps,
 *   TMDB gives us air dates. We match AniList ep 1's date to a TMDB episode,
 *   then offset all episodes from there.
 *
 * Caching: per (tmdbId, season, language) for 7 days.
 */
object TmdbEpisodeProvider {

    private const val TAG = "AnimeWorld:TmdbEpisodes"
    private const val TMDB_SEASON_PAGE = "https://www.themoviedb.org/tv/{id}/season/{season}?language={lang}"
    private const val TMDB_IMAGE_CDN = "https://image.tmdb.org/t/p/original"
    private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_SEASONS = 20

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(val timestamp: Long, val data: SeasonData)

    data class SeasonData(
        val allEpisodes: List<EpisodeData> = emptyList(),
        val bySeasonEpisode: Map<Pair<Int, Int>, EpisodeData> = emptyMap(),
        val byAirDate: Map<String, EpisodeData> = emptyMap(),
        val seasonNumbers: List<Int> = emptyList(),
    )

    data class EpisodeData(
        val season: Int? = null,
        val episode: Int,
        val title: String? = null,
        val overview: String? = null,
        val stillUrl: String? = null,
        val airDate: String? = null,  // ISO 8601: "2024-01-15" (normalized)
        val airDateUnix: Long? = null, // Unix timestamp (seconds), for offset matching
    )

    /**
     * Fetch a specific TMDB season's episode data.
     *
     * @param tmdbId   TMDB show id (from ARM)
     * @param season   Season number (from ARM's themoviedb-season, or 1 as fallback)
     * @param language ISO 639-1 code
     */
    suspend fun fetchSeasonData(
        tmdbId: Int?,
        season: Int = 1,
        language: String = "it",
    ): SeasonData {
        if (tmdbId == null || tmdbId <= 0) return SeasonData()

        val cacheKey = "$tmdbId|$season|$language"
        cache[cacheKey]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < CACHE_TTL_MS) {
                return entry.data
            }
        }

        val episodes = fetchSeasonEpisodes(tmdbId, season, language)
        Log.i(TAG, "TMDB $tmdbId season $season: parsed ${episodes.size} episodes")

        val bySeasonEpisode = episodes.map { (it.season to it.episode) to it }.toMap()
        val byAirDate = episodes.mapNotNull { ep ->
            ep.airDate?.let { it to ep }
        }.toMap()

        val data = SeasonData(
            allEpisodes = episodes,
            bySeasonEpisode = bySeasonEpisode,
            byAirDate = byAirDate,
            seasonNumbers = if (episodes.isNotEmpty()) listOf(season) else emptyList(),
        )
        cache[cacheKey] = CacheEntry(System.currentTimeMillis(), data)
        return data
    }

    /** Scrape a single season page. */
    private suspend fun fetchSeasonEpisodes(
        tmdbId: Int,
        season: Int,
        language: String,
    ): List<EpisodeData> {
        val pageUrl = TMDB_SEASON_PAGE
            .replace("{id}", tmdbId.toString())
            .replace("{season}", season.toString())
            .replace("{lang}", language)

        val html = try {
            val response = app.get(
                url = pageUrl,
                headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "User-Agent" to USER_AGENT,
                )
            )
            if (!response.okhttpResponse.isSuccessful) {
                if (response.okhttpResponse.code == 404) {
                    Log.i(TAG, "TMDB $tmdbId season $season: 404 (season doesn't exist)")
                } else {
                    Log.w(TAG, "TMDB $tmdbId season $season: HTTP ${response.okhttpResponse.code}")
                }
                return emptyList()
            }
            response.text
        } catch (e: Exception) {
            Log.w(TAG, "TMDB $tmdbId season $season error: ${e.message}")
            return emptyList()
        }

        return parseSeasonHtml(html, season, language)
    }

    /**
     * Parse the TMDB season HTML page.
     *
     * Each episode is wrapped in:
     *   <div class="card" data-url="/tv/{id}/season/{s}/episode/{n}">...</div>
     */
    private fun parseSeasonHtml(html: String, season: Int, language: String): List<EpisodeData> {
        // Split by episode card blocks. The data-url attribute gives us the ep number.
        val cardRegex = Regex(
            """<div class="card"[^>]*data-url="/tv/\d+/season/\d+/episode/(\d+)[^"]*"[^>]*>([\s\S]*?)(?=<div class="card"[^>]*data-url=|<div class="expand_pagination"|$)"""
        )

        val titleRegex = Regex("""<div class="episode_title">\s*<h3><a[^>]*>([^<]+)</a></h3>""")
        val overviewRegex = Regex("""<div class="overview">\s*<p>([^<]+)</p>""")
        val stillRegex = Regex("""media\.themoviedb\.org/t/p/(?:w\d+_[^/]+|original|w\d+)/([a-zA-Z0-9]+)\.jpg""")
        val dateTextRegex = Regex("""<span class="date">([^<]+)</span>""")

        val result = mutableListOf<EpisodeData>()
        for (match in cardRegex.findAll(html)) {
            val epNum = match.groupValues[1].toIntOrNull() ?: continue
            val block = match.groupValues[2]

            val title = titleRegex.find(block)?.groupValues?.get(1)
                ?.trim()?.takeIf { it.isNotBlank() }
                // TMDB sometimes HTML-encodes quotes as &#39;
                ?.replace("&#39;", "'")
                ?.replace("&amp;", "&")
                ?.replace("&quot;", "\"")

            val overview = overviewRegex.find(block)?.groupValues?.get(1)
                ?.trim()?.takeIf { it.isNotBlank() }
                ?.replace("&#39;", "'")
                ?.replace("&amp;", "&")
                ?.replace("&quot;", "\"")

            val stillUrl = stillRegex.find(block)?.let {
                "$TMDB_IMAGE_CDN/${it.groupValues[1]}.jpg"
            }

            val dateText = dateTextRegex.find(block)?.groupValues?.get(1)?.trim()
            val (isoDate, unixTs) = parseAirDate(dateText, language)

            result.add(EpisodeData(
                season = season,
                episode = epNum,
                title = title,
                overview = overview,
                stillUrl = stillUrl,
                airDate = isoDate,
                airDateUnix = unixTs,
            ))
        }

        return result
    }

    /**
     * Parse TMDB's localized date text into ISO 8601 + Unix timestamp.
     *
     * TMDB dates are localized:
     *   en: "October 3, 2020"
     *   it: "3 ottobre, 2020"
     *   ja: "2020年10月3日"
     *
     * Returns (isoDateString, unixTimestampSeconds) or (null, null) on failure.
     */
    private fun parseAirDate(dateText: String?, language: String): Pair<String?, Long?> {
        if (dateText.isNullOrBlank()) return null to null

        val patterns = when (language) {
            "it" -> listOf("d MMMM, yyyy" to Locale.ITALIAN, "d MMMM yyyy" to Locale.ITALIAN)
            "ja" -> listOf("yyyy年M月d日" to Locale.JAPANESE)
            "de" -> listOf("d. MMMM yyyy" to Locale.GERMAN)
            "fr" -> listOf("d MMMM yyyy" to Locale.FRENCH)
            "es" -> listOf("d 'de' MMMM 'de' yyyy" to Locale("es"))
            else -> listOf("MMMM d, yyyy" to Locale.ENGLISH, "d MMMM yyyy" to Locale.ENGLISH)
        }

        for ((pattern, locale) in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, locale)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val date = sdf.parse(dateText) ?: continue
                val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(date)
                return iso to (date.time / 1000)
            } catch (_: Exception) {
                // try next pattern
            }
        }
        return null to null
    }

    fun evictAll() { cache.clear() }
}
