package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
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
 * Verified endpoints:
 *   - https://arm.haglund.dev/api/v2/ids?source=anilist&id={ID}
 *   - https://www.themoviedb.org/tv/{tmdb_id}/images/logos?image_language={lang}
 *   - https://image.tmdb.org/t/p/original/{path}.png
 *
 * Caching:
 *   - Result is cached in-memory keyed by AniList id for [CACHE_TTL_MS].
 *   - A negative result (no logo found) is also cached, so we don't hammer ARM/TMDB
 *     on every `load()` call for obscure anime.
 *
 * Error handling:
 *   - Every step is wrapped in try/catch — a logo lookup failure must NEVER
 *     break the parent `load()` flow. The provider still returns a perfectly
 *     usable LoadResponse, just without a logoUrl.
 */
object TmdbLogoProvider {

    private const val TAG = "AnimeWorld:LogoProvider"

    private const val ARM_API = "https://arm.haglund.dev/api/v2/ids"
    private const val TMDB_LOGO_PAGE = "https://www.themoviedb.org/tv/{id}/images/logos"
    private const val TMDB_IMAGE_CDN = "https://image.tmdb.org/t/p/original"

    /** Cache TTL — 24h. Logos essentially never change, but TMDB/ARM can have outages. */
    private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000

    /** In-memory cache: AniList id -> (timestamp, result). */
    private val cache = ConcurrentHashMap<Int, CacheEntry>()

    private data class CacheEntry(val timestamp: Long, val result: LogoResult)

    /**
     * Final result returned to the provider.
     *
     * @property anilistId     the input AniList id
     * @property tmdbId        TMDB id resolved via ARM, or null if not mapped
     * @property language      the language code that actually yielded a logo (e.g. "en", "ja", "any")
     * @property logoUrl       first available transparent PNG URL, or null if none found
     * @property allLogos      every logo URL discovered in the resolved language
     */
    data class LogoResult(
        val anilistId: Int,
        val tmdbId: Int? = null,
        val language: String? = null,
        val logoUrl: String? = null,
        val allLogos: List<String> = emptyList(),
    )

    /**
     * End-to-end lookup: AniList ID -> first available transparent PNG logo URL.
     *
     * Strategy mirrors the original JS:
     *   1. Try the requested [language] (default "en").
     *   2. If empty, try every language in [fallback] (default ["ja"]).
     *   3. As a last resort, request the page with no `image_language` filter (`null`).
     *
     * @param anilistId the AniList id parsed from the AnimeWorld detail page
     * @param language  preferred language (ISO 639-1)
     * @param fallback  languages to try if the preferred one has no logos
     */
    suspend fun fetchLogo(
        anilistId: Int?,
        language: String = "en",
        fallback: List<String?> = listOf("ja", null),
    ): LogoResult {
        if (anilistId == null || anilistId <= 0) return LogoResult(anilistId = anilistId ?: -1)

        // 1. Check cache
        cache[anilistId]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < CACHE_TTL_MS) {
                return entry.result
            }
        }

        // 2. AniList -> TMDB id (via ARM)
        val mappings = anilistToMappings(anilistId)
        val tmdbId = mappings?.themoviedb
        if (tmdbId == null) {
            val miss = LogoResult(anilistId = anilistId)
            cache[anilistId] = CacheEntry(System.currentTimeMillis(), miss)
            return miss
        }

        // 3. Try every language in order until we find at least one logo.
        val languagesToTry = buildList {
            add(language)
            fallback.filter { it != language }.forEach(::add)
        }

        var resolved: LogoResult = LogoResult(anilistId = anilistId, tmdbId = tmdbId)
        for (lang in languagesToTry) {
            val logos = tmdbIdToLogoUrls(tmdbId, lang)
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

    /**
     * ARM API call: AniList id -> full mapping (MAL/AniDB/Kitsu/TVDB/IMDB/TMDB).
     * Returns null if the AniList id isn't in ARM's database (rare anime) or
     * if the request fails for any reason.
     */
    private suspend fun anilistToMappings(anilistId: Int): ArmMappings? {
        return try {
            val url = "$ARM_API?source=anilist&id=$anilistId"
            val response = app.get(
                url = url,
                headers = mapOf("Accept" to "application/json")
            )
            if (!response.okhttpResponse.isSuccessful) {
                Log.w(TAG, "ARM API non-OK status: ${response.okhttpResponse.code}")
                return null
            }
            // ARM returns `null` (HTTP 200 with body "null") when the id isn't mapped.
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
     * @param tmdbId   TMDB show id
     * @param language ISO 639-1 code, or null to fetch the default (unfiltered) page
     */
    private suspend fun tmdbIdToLogoUrls(tmdbId: Int, language: String?): List<String> {
        val pageUrl = buildString {
            append(TMDB_LOGO_PAGE.replace("{id}", tmdbId.toString()))
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
                // 404 means "this anime isn't on TMDB" — not an error, just no logos.
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

        // Pattern: image.tmdb.org/t/p/{original|w500|w300}/<hash>.png
        // Hashes are alphanumeric (no dots, no slashes) — TMDB uses this format for poster/logo files.
        val regex = Regex(
            """image\.tmdb\.org/t/p/(?:original|w500|w300)/([a-zA-Z0-9]+)\.png"""
        )
        // LinkedHashSet preserves insertion order while deduplicating.
        val hashes = LinkedHashSet<String>()
        regex.findAll(html).forEach { hashes.add(it.groupValues[1]) }
        return hashes.map { "$TMDB_IMAGE_CDN/$it.png" }
    }

    /** Drop the cached entry for an AniList id (used by the Settings "clear cache" action). */
    fun evict(anilistId: Int) {
        cache.remove(anilistId)
    }

    /** Drop every cached entry. */
    fun evictAll() {
        cache.clear()
    }
}
