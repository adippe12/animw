package it.dogior.hadEnough

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * AnimeWorldPlugin — the .cs3 entry point.
 *
 * Exactly one class per module must be annotated with @CloudstreamPlugin —
 * the gradle plugin ASM-scans for it and the build fails otherwise.
 *
 * We extend `Plugin` (Android-only) rather than `BasePlugin` because we ship
 * resources (the settings layout/drawables/strings) and need a SharedPreferences
 * handle for the "Split Core/Dub/Sub" user preference.
 *
 * Per §9.8 of the CloudStream guide: setting `requiresResources = true` while
 * subclassing `BasePlugin` is a pitfall — we avoid it by subclassing `Plugin`.
 */
@CloudstreamPlugin
class AnimeWorldPlugin : Plugin() {
    val sharedPref = activity?.getSharedPreferences("AnimeWorldIT", Context.MODE_PRIVATE)

    override fun load(context: Context) {
        val isSplit = sharedPref?.getBoolean("isSplit", false) ?: false
        val dubEnabled = sharedPref?.getBoolean("dubEnabled", false) ?: false
        val subEnabled = sharedPref?.getBoolean("subEnabled", false) ?: false

        // Always register the Vidguard extractor — even when the Core variant is
        // active, episode info may delegate to a listeamed.net URL.
        registerExtractorAPI(VidguardExtractor())

        // Register providers. We never edit the global providers list directly —
        // registerMainAPI() is the only sanctioned way (§5.5 of the CloudStream guide).
        if (isSplit) {
            if (dubEnabled) registerMainAPI(AnimeWorldDub(isSplit))
            if (subEnabled) registerMainAPI(AnimeWorldSub(isSplit))
        } else {
            registerMainAPI(AnimeWorldCore(isSplit))
        }

        // Wire up the settings button shown in the plugin list.
        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            Settings(this).show(activity.supportFragmentManager, "AnimeWorldSettings")
        }
    }
}
