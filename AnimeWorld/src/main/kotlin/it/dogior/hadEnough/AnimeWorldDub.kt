package it.dogior.hadEnough

import com.lagradost.cloudstream3.mainPageOf

/**
 * AnimeWorld Dub — Italian-dubbed anime only.
 *
 * Subclassing pattern (§8.12 of the CloudStream guide) — the Core class owns
 * all the scraping logic; subclasses only override `name`, `lang`, the dub
 * filter flag, and the home-page URLs (which point at the dubbed filter).
 *
 * Note on `lang`: this provider serves Italian audio, so `lang = "it"`.
 */
class AnimeWorldDub(isSplit: Boolean) : AnimeWorldCore(isSplit) {
    override var name = "AnimeWorld Dub"
    override var lang = "it"
    override val currentExtension = CurrentExtension.DUB

    override val mainPage = super.mainPage + mainPageOf(
        "$mainUrl/filter?status=0&language=it&sort=1" to "In Corso",
        "$mainUrl/filter?language=it&sort=1" to "Ultimi aggiunti",
        "$mainUrl/filter?language=it&sort=6" to "Più Visti",
        "$mainUrl/tops/dubbed?sort=1" to "Top 100 Anime",
    )
}
