package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * TmdbLogoProvider
 * =================
 * Kotlin port of the original `anilist_title_logo.js` flow:
 *
 *   1. AniList ID  ->  ARM API  (mapping to TMDB id)
 *   2. TMDB id     ->  TMDB public HTML page `/tv/{id}/images/logos?image_language={lang}`
 *                      (no auth, no key — just plain HTML scrape)
 *   3. Extract every transparent PNG logo URL embedded in that page
 *   4. Returned URL points directly to `https://image.tmdb.org/t/p/original/<hash>.png` (no auth)
 *
 * All endpoints used are 100% public — ZERO API keys.
 *
 * Improvements over the original JS:
 *   - **Parallel language fetch**: fires `en` + `ja` + `null` concurrently instead of
 *     sequentially. Total latency = max(langs) instead of sum(langs).
 *   - **TMDB type detection**: ARM returns `themoviedb_type` ("tv" | "movie") —
 *     we use it to hit `/movie/{id}/images/logos` for movies instead of always `/tv/`.
 *   - **24h in-memory cache** with negative-result caching.
 *   - **Bulletproof error handling**: every step wrapped in try/catch — a logo
 *     failure never propagates to the parent load().
 */
object TmdbLogoProvider {

    private const val TAG = "AnimeWorld:LogoProvider"

    private const val ARM_API = "https://arm.haglund.dev/api/v2/ids"
    private const val TMDB_TV_LOGO_PAGE = "https://www.themoviedb.org/tv/{id}/images/logos"
    private const val TMDB_MOVIE_LOGO_PAGE = "https://www.themoviedb.org/movie/{id}/images/logos"
    private const val TMDB_IMAGE_CDN = "https://image.tmdb.org/t/p/original"

    /** Cache TTL — 24h. Logos essentially never change. */
    private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000

    private val cache = ConcurrentHashMap<Int, CacheEntry>()

    private data class CacheEntry(val timestamp: Long, val result: LogoResult)

    data class LogoResult(
        val anilistId: Int,
        val tmdbId: Int? = null,
        val tmdbType: String? = null,
        val language: String? = null,
        val logoUrl: String? = null,
        val allLogos: List<String> = emptyList(),
    )

    /**
     * End-to-end lookup: AniList ID -> first available transparent PNG logo URL.
     *
     * Strategy:
     *   1. Check cache (24h TTL, includes negative results).
     *   2. ARM: AniList id -> TMDB id + type.
     *   3. Fire ALL candidate languages CONCURRENTLY (en, ja, null).
     *   4. Pick the first language (in priority order) that has logos.
     */
    suspend fun fetchLogo(
        anilistId: Int?,
        language: String = "en",
        fallback: List<String?> = listOf("ja", null),
    ): LogoResult {
        if (anilistId == null || anilistId <= 0) return LogoResult(anilistId = anilistId ?: -1)

        // 1. Cache check
        cache[anilistId]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < CACHE_TTL_MS) {
                return entry.result
            }
        }

        // 2. AniList -> TMDB id + type (via ARM)
        val mappings = anilistToMappings(anilistId)
        val tmdbId = mappings?.themoviedb
        if (tmdbId == null) {
            val miss = LogoResult(anilistId = anilistId)
            cache[anilistId] = CacheEntry(System.currentTimeMillis(), miss)
            return miss
        }

        val tmdbType = mappings.themoviedbType ?: "tv"

        // 3. Build priority-ordered language list (no duplicates, preserves order)
        val languagesToTry = buildList {
            add(language)
            fallback.filter { it != language }.forEach(::add)
        }

        // 4. Fetch ALL languages concurrently — total latency = max(langs), not sum(langs).
        val resultsByLang: Map<String?, List<String>> = coroutineScope {
            languagesToTry.map { lang ->
                async { lang to tmdbIdToLogoUrls(tmdbId, tmdbType, lang) }
            }.awaitAll().toMap()
        }

        // 5. Pick the first language (in priority order) that has logos.
        var resolved = LogoResult(anilistId = anilistId, tmdbId = tmdbId, tmdbType = tmdbType)
        for (lang in languagesToTry) {
            val logos = resultsByLang[lang].orEmpty()
            if (logos.isNotEmpty()) {
                resolved = resolved.copy(
                    language = lang ?: "any",
                    logoUrl = logos.first(),
                    allLogos = logos,
                )
                break
            }
        }

        cache[anilistId] = CacheEntry(System.currentTimeMillis(), resolved)
        return resolved
    }

    private suspend fun anilistToMappings(anilistId: Int): ArmMappings? {
        return try {
            val url = "$ARM_API?source=anilist&id=$anilistId"
            val response = app.get(url, headers = mapOf("Accept" to "application/json"))
            if (!response.okhttpResponse.isSuccessful) {
                Log.w(TAG, "ARM API non-OK status: ${response.okhttpResponse.code}")
                return null
            }
            val body = response.text
            if (body.isBlank() || body.trim() == "null") return null
            tryParseJson<ArmMappings>(body)
        } catch (e: Exception) {
            Log.w(TAG, "ARM API error for anilist=$anilistId: ${e.message}")
            null
        }
    }

    /**
     * Scrape the public TMDB `/images/logos` HTML page for transparent PNG logo URLs.
     *
     * @param tmdbId   TMDB show/movie id
     * @param tmdbType "tv" or "movie" — determines the URL path
     * @param language ISO 639-1 code, or null for the unfiltered default page
     */
    private suspend fun tmdbIdToLogoUrls(tmdbId: Int, tmdbType: String, language: String?): List<String> {
        val template = if (tmdbType == "movie") TMDB_MOVIE_LOGO_PAGE else TMDB_TV_LOGO_PAGE
        val pageUrl = buildString {
            append(template.replace("{id}", tmdbId.toString()))
            if (!language.isNullOrBlank()) {
                append("?image_language=")
                append(URLEncoder.encode(language, "UTF-8"))
            }
        }

        val html = try {
            val response = app.get(
                url = pageUrl,
                headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "User-Agent" to com.lagradost.cloudstream3.USER_AGENT,
                )
            )
            if (!response.okhttpResponse.isSuccessful) {
                if (response.okhttpResponse.code != 404) {
                    Log.w(TAG, "TMDB page non-OK status: ${response.okhttpResponse.code}")
                }
                return emptyList()
            }
            response.text
        } catch (e: Exception) {
            Log.w(TAG, "TMDB page error for tmdb=$tmdbId lang=$language: ${e.message}")
            return emptyList()
        }

        val regex = Regex(
            """image\.tmdb\.org/t/p/(?:original|w500|w300)/([a-zA-Z0-9]+)\.png"""
        )
        val hashes = LinkedHashSet<String>()
        regex.findAll(html).forEach { hashes.add(it.groupValues[1]) }
        return hashes.map { "$TMDB_IMAGE_CDN/$it.png" }
    }

    fun evict(anilistId: Int) { cache.remove(anilistId) }
    fun evictAll() { cache.clear() }
}
