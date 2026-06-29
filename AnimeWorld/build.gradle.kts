// use an integer for version numbers (non-integer values log a warning and fall back to -1)
version = 21

cloudstream {
    language = "it"
    authors = listOf("doGior", "DieGon", "adippe12")
    requiresResources = true
    description = "Anime da AnimeWorld con logo PNG trasparente (AniList → ARM → TMDB)."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1
    tvTypes = listOf(
        "AnimeMovie",
        "Anime",
        "OVA",
    )

    iconUrl = "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/AnimeWorld/animeworld_icon.png"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    // Material Components is required by the BottomSheetDialogFragment used in Settings.kt.
    implementation("com.google.android.material:material:1.12.0")
    // Rhino is required by VidguardExtractor to eval the obfuscated player JS.
    implementation("org.mozilla:rhino:1.7.15")
}
