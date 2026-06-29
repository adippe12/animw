package it.dogior.hadEnough

import com.lagradost.cloudstream3.mainPageOf

/**
 * AnimeWorld Sub — Japanese-audio (subbed) anime only.
 *
 * `lang = "ja"` per §9.9 of the CloudStream guide — the ISO 639-1 code for
 * Japanese is `ja`, and the language filter in CloudStream expects a 2-letter
 * code. (`jp` is a country code, not a language code.)
 */
class AnimeWorldSub(isSplit: Boolean) : AnimeWorldCore(isSplit) {
    override var name = "AnimeWorld Sub"
    override var lang = "ja"
    override val currentExtension = CurrentExtension.SUB

    override val mainPage = super.mainPage + mainPageOf(
        "$mainUrl/filter?status=0&language=jp&sort=1" to "In Corso",
        "$mainUrl/filter?language=jp&sort=1" to "Ultimi aggiunti",
        "$mainUrl/filter?language=jp&sort=6" to "Più Visti",
        "$mainUrl/tops/all?sort=1" to "Top Anime",
    )
}
