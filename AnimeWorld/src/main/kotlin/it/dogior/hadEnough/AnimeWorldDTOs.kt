package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * All DTOs used by AnimeWorldCore + TmdbLogoProvider.
 *
 * NOTE: AniList GraphQL DTOs live in AniListEnricher.kt to avoid cluttering
 * this file (they're tightly coupled to the enricher's query shape).
 *
 * Best-practice: every DTO is annotated with @JsonIgnoreProperties(ignoreUnknown = true)
 * so a new field added upstream by AnimeWorld / ARM / TMDB will never break parsing.
 */

/* ---------- AnimeWorld quick-search API (/api/search/v2) ---------- */

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchJson(
    @JsonProperty("animes") val animes: List<AnimeJson> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnimeJson(
    @JsonProperty("name") val name: String,
    @JsonProperty("image") val image: String? = null,
    @JsonProperty("link") val link: String,
    @JsonProperty("animeTypeName") val type: String? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("jtitle") val otherTitle: String? = null,
    @JsonProperty("identifier") val id: String
)

/* ---------- AnimeWorld episode-info API (/api/episode/info) ---------- */

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeInfoJson(
    @JsonProperty("grabber") val grabber: String,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("target") val target: String? = null,
    /** Some AnimeWorld servers return subtitle tracks alongside the video URL. */
    @JsonProperty("subtitles") val subtitles: List<SubtitleJson>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SubtitleJson(
    @JsonProperty("url") val url: String,
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("label") val label: String? = null,
)

/* ---------- ARM (Anime Repository Mapping) API ---------- */
/* Endpoint: https://arm.haglund.dev/api/v2/ids?source=anilist&id={ID} */
/* ARM returns the same shape for every source — we only use a subset.     */

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArmMappings(
    @JsonProperty("anilist") val anilist: Int? = null,
    @JsonProperty("myanimelist") val myanimelist: Int? = null,
    @JsonProperty("anidb") val anidb: Int? = null,
    @JsonProperty("kitsu") val kitsu: Int? = null,
    @JsonProperty("anizip") val anizip: Int? = null,
    @JsonProperty("thetvdb") val thetvdb: Int? = null,
    @JsonProperty("imdb") val imdb: String? = null,        // "tt1234567"
    @JsonProperty("themoviedb") val themoviedb: Int? = null,
    @JsonProperty("themoviedb_type") val themoviedbType: String? = null, // "tv" | "movie"
)
