package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import java.util.concurrent.ConcurrentHashMap

/**
 * TmdbEpisodeProvider
 * ===================
 * Scrapes public TMDB pages (no auth, no key) to extract per-episode metadata:
 *   - Episode title
 *   - Episode overview (description)
 *   - Episode still image (transparent, high-res)
 *   - Air date (ISO 8601)
 *
 * **Season detection** — the hard problem:
 *   AnimeWorld may split a franchise into multiple pages (JJK, JJK 2, JJK 3),
 *   each with its own AniList ID. ARM maps AniList ID → TMDB show ID, but does
 *   NOT tell us which TMDB season corresponds to which AniList ID.
 *
 *   Example: JJK S1 (AniList 40748) and JJK S2 (AniList 145070) both map to
 *   TMDB show 95479, but S1 = season 1 and S2 = season 2.
 *
 *   Solution: fetch ALL seasons, then match by air date. AniList gives us the
 *   airing timestamp of each episode; TMDB gives us the air date of each
 *   episode per season. We match episodes whose air dates align.
 *
 * Endpoints (all public HTML, no auth):
 *   - https://www.themoviedb.org/tv/{tmdb_id}/season/{season_num}?language={lang}
 *   - Show page (to discover how many seasons exist): /tv/{tmdb_id}?language={lang}
 *
 * Caching:
 *   - Per (tmdbId, language) for 7 days — caches ALL seasons in one entry.
 *   - Keyed by air date for O(1) episode lookup.
 */
object TmdbEpisodeProvider {

    private const val TAG = "AnimeWorld:TmdbEpisodes"
    private const val TMDB_SHOW_PAGE = "https://www.themoviedb.org/tv/{id}?language={lang}"
    private const val TMDB_SEASON_PAGE = "https://www.themoviedb.org/tv/{id}/season/{season}?language={lang}"
    private const val TMDB_IMAGE_CDN = "https://image.tmdb.org/t/p/original"
    private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000  // 7 days
    private const val MAX_SEASONS = 20  // safety cap

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(val timestamp: Long, val data: SeasonData)

    /** All episode data for a TMDB show, indexed two ways. */
    data class SeasonData(
        /** All episodes across all seasons, in TMDB order. */
        val allEpisodes: List<EpisodeData> = emptyList(),
        /** Lookup by (season, episode) — for when we know the exact TMDB coordinates. */
        val bySeasonEpisode: Map<Pair<Int, Int>, EpisodeData> = emptyMap(),
        /** Lookup by air date (ISO "YYYY-MM-DD") — for cross-source matching. */
        val byAirDate: Map<String, EpisodeData> = emptyMap(),
        /** Season numbers that exist on TMDB (e.g. [1, 2, 3]). */
        val seasonNumbers: List<Int> = emptyList(),
    )

    data class EpisodeData(
        val season: Int? = null,
        val episode: Int,
        val title: String? = null,
        val overview: String? = null,
        val stillUrl: String? = null,
        val airDate: String? = null,  // ISO 8601: "2024-01-15"
    )

    /**
     * Fetch ALL seasons of a TMDB show and build lookup indexes.
     *
     * @param tmdbId   TMDB show id (from ARM)
     * @param language ISO 639-1 code for titles/overviews
     */
    suspend fun fetchShowData(
        tmdbId: Int?,
        language: String = "it",
    ): SeasonData {
        if (tmdbId == null || tmdbId <= 0) return SeasonData()

        val cacheKey = "$tmdbId|$language"
        cache[cacheKey]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < CACHE_TTL_MS) {
                return entry.data
            }
        }

        // 1. Discover which seasons exist by scraping the show page.
        val seasonNumbers = discoverSeasons(tmdbId, language)
        if (seasonNumbers.isEmpty()) {
            val empty = SeasonData()
            cache[cacheKey] = CacheEntry(System.currentTimeMillis(), empty)
            return empty
        }

        // 2. Fetch each season's episode data.
        val allEpisodes = mutableListOf<EpisodeData>()
        for (seasonNum in seasonNumbers.take(MAX_SEASONS)) {
            val eps = fetchSeasonEpisodes(tmdbId, seasonNum, language)
            allEpisodes.addAll(eps)
        }

        // 3. Build lookup indexes.
        val bySeasonEpisode = allEpisodes.mapNotNull { ep ->
            val s = ep.season ?: return@mapNotNull null
            (s to ep.episode) to ep
        }.toMap()

        val byAirDate = allEpisodes.mapNotNull { ep ->
            val date = ep.airDate ?: return@mapNotNull null
            date to ep
        }.toMap()

        val data = SeasonData(
            allEpisodes = allEpisodes,
            bySeasonEpisode = bySeasonEpisode,
            byAirDate = byAirDate,
            seasonNumbers = seasonNumbers,
        )
        cache[cacheKey] = CacheEntry(System.currentTimeMillis(), data)
        return data
    }

    /**
     * Legacy single-season API — kept for backwards compatibility.
     * Delegates to fetchShowData and filters to one season.
     */
    suspend fun fetchEpisodeData(
        tmdbId: Int?,
        season: Int = 1,
        language: String = "it",
    ): Map<Int, EpisodeData> {
        val showData = fetchShowData(tmdbId, language)
        return showData.allEpisodes
            .filter { it.season == season }
            .associateBy { it.episode }
    }

    /* ---------------------------------------------------------------- */
    /*  Internal scrapers                                               */
    /* ---------------------------------------------------------------- */

    /** Discover season numbers by scraping the show's main page. */
    private suspend fun discoverSeasons(tmdbId: Int, language: String): List<Int> {
        val pageUrl = TMDB_SHOW_PAGE
            .replace("{id}", tmdbId.toString())
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
                Log.w(TAG, "TMDB show page non-OK: ${response.okhttpResponse.code}")
                return listOf(1)  // fallback: assume season 1 exists
            }
            response.text
        } catch (e: Exception) {
            Log.w(TAG, "TMDB show page error: ${e.message}")
            return listOf(1)
        }

        // Season links look like: /tv/{tmdbId}/season/{n}
        val seasonRegex = Regex("""/tv/$tmdbId/season/(\d+)""")
        val seasons = seasonRegex.findAll(html)
            .map { it.groupValues[1].toIntOrNull() }
            .filterNotNull()
            .filter { it in 0..MAX_SEASONS }  // exclude "season 0" (specials) usually
            .distinct()
            .sorted()
            .toList()

        return seasons.ifEmpty { listOf(1) }
    }

    /** Scrape a single season page for episode data. */
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
                if (response.okhttpResponse.code != 404) {
                    Log.w(TAG, "TMDB season $season non-OK: ${response.okhttpResponse.code}")
                }
                return emptyList()
            }
            response.text
        } catch (e: Exception) {
            Log.w(TAG, "TMDB season $season error: ${e.message}")
            return emptyList()
        }

        val stillRegex = Regex(
            """image\.tmdb\.org/t/p/(?:original|w300|w500)/([a-zA-Z0-9]+)\.jpg"""
        )
        val episodeBlockRegex = Regex(
            """<div[^>]*class="[^"]*episode[^"]*"[^>]*>([\s\S]*?)(?=<div[^>]*class="[^"]*episode[^"]*"|</section>|</article>|$)""",
            RegexOption.IGNORE_CASE
        )

        val result = mutableListOf<EpisodeData>()
        for (blockMatch in episodeBlockRegex.findAll(html)) {
            val block = blockMatch.groupValues[1]
            val epNum = extractEpisodeNumber(block) ?: continue

            result.add(EpisodeData(
                season = season,
                episode = epNum,
                title = extractEpisodeTitle(block),
                overview = extractEpisodeOverview(block),
                stillUrl = stillRegex.find(block)?.let { "$TMDB_IMAGE_CDN/${it.groupValues[1]}.jpg" },
                airDate = extractAirDate(block),
            ))
        }

        // Fallback: positional pairing of stills + episode numbers.
        if (result.isEmpty()) {
            val stills = stillRegex.findAll(html).toList()
            val epNumRegex = Regex("""(?:S\d+E(\d+)|"episode_number"\s*:\s*(\d+)|episode/(\d+))""", RegexOption.IGNORE_CASE)
            val epNums = epNumRegex.findAll(html).mapNotNull { m ->
                m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull()
            }.toList()
            if (stills.isNotEmpty() && epNums.isNotEmpty()) {
                val minLen = minOf(stills.size, epNums.size)
                for (i in 0 until minLen) {
                    result.add(EpisodeData(
                        season = season,
                        episode = epNums[i],
                        stillUrl = "$TMDB_IMAGE_CDN/${stills[i].groupValues[1]}.jpg",
                    ))
                }
            }
        }

        return result
    }

    /* ---------------------------------------------------------------- */
    /*  Regex helpers                                                   */
    /* ---------------------------------------------------------------- */

    private fun extractEpisodeNumber(block: String): Int? {
        val patterns = listOf(
            Regex("""S\d+E(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""data-episode[^"]*"(\d+)"""", RegexOption.IGNORE_CASE),
            Regex("""/episode/(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""episode_number["\s:]+(\d+)""", RegexOption.IGNORE_CASE),
        )
        for (p in patterns) {
            val match = p.find(block)
            if (match != null) return match.groupValues[1].toIntOrNull()
        }
        return null
    }

    private fun extractEpisodeTitle(block: String): String? {
        val patterns = listOf(
            Regex("""<h2[^>]*>([^<]+)</h2>"""),
            Regex("""<a[^>]*class="[^"]*title[^"]*"[^>]*>([^<]+)</a>"""),
            Regex("""<h3[^>]*>([^<]+)</h3>"""),
            Regex(""""name"\s*:\s*"([^"]+)""""),
        )
        for (p in patterns) {
            val match = p.find(block)
            val title = match?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
            if (title != null) return title
        }
        return null
    }

    private fun extractEpisodeOverview(block: String): String? {
        val patterns = listOf(
            Regex("""<p[^>]*class="[^"]*overview[^"]*"[^>]*>([\s\S]*?)</p>"""),
            Regex("""<p[^>]*>([\s\S]*?)</p>"""),
            Regex(""""overview"\s*:\s*"([^"]+)""""),
        )
        for (p in patterns) {
            val match = p.find(block)
            val text = match?.groupValues?.getOrNull(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.trim()
                ?.ifBlank { null }
            if (text != null && text.length > 10) return text
        }
        return null
    }

    private fun extractAirDate(block: String): String? {
        val dateRegex = Regex("""\b(\d{4}-\d{2}-\d{2})\b""")
        return dateRegex.find(block)?.groupValues?.getOrNull(1)
    }

    fun evictAll() { cache.clear() }
}
