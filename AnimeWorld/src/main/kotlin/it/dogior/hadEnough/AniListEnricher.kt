package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ActorRole
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * AniListEnricher
 * ================
 * Queries the public AniList GraphQL endpoint (no API key required — AniList
 * allows unauthenticated reads up to a generous rate limit, 90 req/min) to
 * enrich a LoadResponse with data that AnimeWorld doesn't expose:
 *
 *   - Studio (animation studio)
 *   - Voice actors (with role + image)
 *   - Background banner image (high-res)
 *   - Synonyms (alternative titles)
 *   - AniList score (more reliable than AnimeWorld's user-vote score)
 *   - Trailer (YouTube id)
 *   - Tags / genres (AniList has ~2000 tags vs AnimeWorld's ~30 genres)
 *   - Episode count + duration (used for next-airing extrapolation)
 *   - Status (FINISHED/RELEASING/...) — more reliable than AnimeWorld's IT status
 *
 * All fields are OPTIONAL — a failure to fetch any of them never propagates.
 *
 * Caching:
 *   - Results cached by AniList id for 7 days (AniList data is stable).
 *   - Negative results cached too.
 *
 * Best-practice: this runs entirely in parallel with the existing TMDB logo
 * fetch — both fire concurrently when load() is called.
 */
object AniListEnricher {

    private const val TAG = "AnimeWorld:AniListEnricher"
    private const val ANILIST_GRAPHQL = "https://graphql.anilist.co"
    private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000  // 7 days

    private val cache = ConcurrentHashMap<Int, CacheEntry>()

    private data class CacheEntry(val timestamp: Long, val data: AniListMediaData?)

    /** Enrichment result — every field is nullable because every source field is optional. */
    data class AniListMediaData(
        val anilistId: Int,
        val malId: Int? = null,
        val titleRomaji: String? = null,
        val titleEnglish: String? = null,
        val titleNative: String? = null,
        val synonyms: List<String> = emptyList(),
        val bannerImage: String? = null,
        val coverImage: String? = null,
        val score: Double? = null,             // AniList average score (0-100)
        val meanScore: Double? = null,
        val popularity: Int? = null,
        val favourites: Int? = null,
        val episodes: Int? = null,
        val duration: Int? = null,             // minutes per episode
        val status: String? = null,            // FINISHED | RELEASING | NOT_YET_RELEASED | CANCELLED | HIATUS
        val season: String? = null,
        val seasonYear: Int? = null,
        val format: String? = null,            // TV | TV_SHORT | MOVIE | SPECIAL | OVA | ONA | MUSIC
        val source: String? = null,            // MANGA | LIGHT_NOVEL | etc.
        val trailerYoutubeId: String? = null,
        val studio: String? = null,
        val genres: List<String> = emptyList(),
        val tags: List<AniListTag> = emptyList(),
        val voiceActors: List<ActorData> = emptyList(),
        val description: String? = null,
        /** Episode number -> Unix timestamp (seconds) of airing. Used for Episode.addDate(). */
        val airingSchedule: Map<Int, Long> = emptyMap(),
    )

    data class AniListTag(val name: String, val rank: Int? = null)

    /** GraphQL query — single request, all fields, no nested fragments. */
    private val QUERY = """
        query (id: Int) {
          Media(id: id, type: ANIME) {
            id
            idMal
            title { romaji english native }
            synonyms
            bannerImage
            coverImage { extraLarge large }
            averageScore
            meanScore
            popularity
            favourites
            episodes
            duration
            status
            season
            seasonYear
            format
            source
            trailer { id site }
            studios(isMain: true) { nodes { name } }
            genres
            tags { name rank }
            characters(role: MAIN) {
              nodes {
                name { full native }
                image { large }
                voiceActors(language: JAPANESE) {
                  name { full }
                  image { large }
                }
              }
            }
            description
            airingSchedule(perPage: 200) {
              nodes { episode airingAt }
            }
          }
        }
    """.trimIndent()

    /**
     * Fetch the full AniList media data for an AniList id.
     * Returns null on any failure (cached negative result).
     */
    suspend fun fetch(anilistId: Int?): AniListMediaData? {
        if (anilistId == null || anilistId <= 0) return null

        cache[anilistId]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < CACHE_TTL_MS) {
                return entry.data
            }
        }

        val data = withContext(Dispatchers.IO) {
            try {
                // AniList GraphQL accepts form-encoded POST with `query` and
                // `variables` fields — avoids needing a RequestBody, which
                // NiceHttp's `data` parameter doesn't accept.
                val response = app.post(
                    ANILIST_GRAPHQL,
                    headers = mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "Accept" to "application/json",
                    ),
                    data = mapOf(
                        "query" to QUERY,
                        "variables" to """{"id":$anilistId}""",
                    ),
                )
                if (!response.okhttpResponse.isSuccessful) {
                    Log.w(TAG, "AniList GraphQL non-OK status: ${response.okhttpResponse.code}")
                    return@withContext null
                }
                parseAniListResponse(response.text, anilistId)
            } catch (e: Exception) {
                Log.w(TAG, "AniList fetch failed for id=$anilistId: ${e.message}")
                null
            }
        }

        cache[anilistId] = CacheEntry(System.currentTimeMillis(), data)
        return data
    }

    /** Parse the GraphQL JSON response into our typed data class. */
    private fun parseAniListResponse(text: String, anilistId: Int): AniListMediaData? {
        val root = tryParseJson<AniListGraphQLResponse>(text) ?: return null
        val media = root.data?.media ?: return null

        val voiceActors = media.characters?.nodes.orEmpty().mapNotNull { ch ->
            val charName = ch.name?.full ?: return@mapNotNull null
            val charImage = ch.image?.large
            val va = ch.voiceActors?.firstOrNull()
            val vaActor = va?.let { Actor(it.name?.full ?: "", it.image?.large) }
            ActorData(
                actor = Actor(charName, charImage),
                role = ActorRole.Main,
                voiceActor = vaActor
            )
        }

        return AniListMediaData(
            anilistId = media.id ?: anilistId,
            malId = media.idMal,
            titleRomaji = media.title?.romaji,
            titleEnglish = media.title?.english,
            titleNative = media.title?.native,
            synonyms = media.synonyms.orEmpty().filter { it.isNotBlank() },
            bannerImage = media.bannerImage,
            coverImage = media.coverImage?.extraLarge ?: media.coverImage?.large,
            score = media.averageScore?.toDouble(),
            meanScore = media.meanScore?.toDouble(),
            popularity = media.popularity,
            favourites = media.favourites,
            episodes = media.episodes,
            duration = media.duration,
            status = media.status,
            season = media.season,
            seasonYear = media.seasonYear,
            format = media.format,
            source = media.source,
            trailerYoutubeId = media.trailer?.takeIf { it.site == "youtube" }?.id,
            studio = media.studios?.nodes?.firstOrNull()?.name,
            genres = media.genres.orEmpty(),
            tags = media.tags.orEmpty().map { AniListTag(it.name, it.rank) },
            voiceActors = voiceActors,
            description = media.description?.let(::stripHtml),
            airingSchedule = media.airingSchedule?.nodes.orEmpty()
                .mapNotNull { node ->
                    val ep = node.episode ?: return@mapNotNull null
                    val ts = node.airingAt ?: return@mapNotNull null
                    ep to ts.toLong()
                }
                .toMap(),
        )
    }

    /** Naive HTML stripper — AniList descriptions are HTML-formatted. */
    private fun stripHtml(html: String): String {
        return html
            .replace("<br>", "\n")
            .replace("<br/>", "\n")
            .replace("<br />", "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .trim()
    }

    fun evict(anilistId: Int) { cache.remove(anilistId) }
    fun evictAll() { cache.clear() }
}

/* ---------- AniList GraphQL DTOs (only what we need) ---------- */

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListGraphQLResponse(
    @JsonProperty("data") val data: AniListGraphQLData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListGraphQLData(
    @JsonProperty("Media") val media: AniListMedia? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListMedia(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("idMal") val idMal: Int? = null,
    @JsonProperty("title") val title: AniListTitleText? = null,
    @JsonProperty("synonyms") val synonyms: List<String>? = null,
    @JsonProperty("bannerImage") val bannerImage: String? = null,
    @JsonProperty("coverImage") val coverImage: AniListCover? = null,
    @JsonProperty("averageScore") val averageScore: Int? = null,
    @JsonProperty("meanScore") val meanScore: Int? = null,
    @JsonProperty("popularity") val popularity: Int? = null,
    @JsonProperty("favourites") val favourites: Int? = null,
    @JsonProperty("episodes") val episodes: Int? = null,
    @JsonProperty("duration") val duration: Int? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("season") val season: String? = null,
    @JsonProperty("seasonYear") val seasonYear: Int? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("source") val source: String? = null,
    @JsonProperty("trailer") val trailer: AniListTrailer? = null,
    @JsonProperty("studios") val studios: AniListStudioConn? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("tags") val tags: List<AniListTagNode>? = null,
    @JsonProperty("characters") val characters: AniListCharacterConn? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("airingSchedule") val airingSchedule: AniListAiringConn? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListTitleText(
    @JsonProperty("romaji") val romaji: String? = null,
    @JsonProperty("english") val english: String? = null,
    @JsonProperty("native") val native: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListCover(
    @JsonProperty("extraLarge") val extraLarge: String? = null,
    @JsonProperty("large") val large: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListTrailer(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("site") val site: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListStudioConn(
    @JsonProperty("nodes") val nodes: List<AniListStudio>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListStudio(
    @JsonProperty("name") val name: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListTagNode(
    @JsonProperty("name") val name: String,
    @JsonProperty("rank") val rank: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListCharacterConn(
    @JsonProperty("nodes") val nodes: List<AniListCharacterNode>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListCharacterNode(
    @JsonProperty("name") val name: AniListCharacterName? = null,
    @JsonProperty("image") val image: AniListCharacterImage? = null,
    @JsonProperty("voiceActors") val voiceActors: List<AniListVoiceActor>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListCharacterName(
    @JsonProperty("full") val full: String? = null,
    @JsonProperty("native") val native: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListCharacterImage(
    @JsonProperty("large") val large: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListVoiceActor(
    @JsonProperty("name") val name: AniListCharacterName? = null,
    @JsonProperty("image") val image: AniListCharacterImage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListAiringConn(
    @JsonProperty("nodes") val nodes: List<AniListAiringNode>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListAiringNode(
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("airingAt") val airingAt: Int? = null,  // Unix timestamp (seconds)
)
