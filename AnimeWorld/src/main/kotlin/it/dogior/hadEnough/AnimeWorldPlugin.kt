package it.dogior.hadEnough

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AnimeWorldPlugin — the .cs3 entry point.
 *
 * Exposes `prefs` as a companion-object property so the provider classes can
 * read user preferences at request time (logo enabled, logo language,
 * AniList enrichment enabled, etc.) without needing a Context reference.
 */
@CloudstreamPlugin
class AnimeWorldPlugin : Plugin() {

    override fun load(context: Context) {
        // Initialise the shared preferences holder BEFORE registering providers
        // — the providers read it lazily on every request.
        PrefsHolder.init(context)

        // Pre-warm the AnimeWorld security cookie in background so the first
        // homepage load doesn't pay the cookie-bootstrap latency (~500ms).
        CoroutineScope(Dispatchers.IO).launch {
            try { AnimeWorldCore.prewarmCookie() } catch (_: Exception) {}
        }

        val isSplit = PrefsHolder.isSplit
        val dubEnabled = PrefsHolder.dubEnabled
        val subEnabled = PrefsHolder.subEnabled

        // Always register the Vidguard extractor — even when the Core variant is
        // active, episode info may delegate to a listeamed.net URL.
        registerExtractorAPI(VidguardExtractor())

        if (isSplit) {
            if (dubEnabled) registerMainAPI(AnimeWorldDub(isSplit))
            if (subEnabled) registerMainAPI(AnimeWorldSub(isSplit))
        } else {
            registerMainAPI(AnimeWorldCore(isSplit))
        }

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            Settings(this).show(activity.supportFragmentManager, "AnimeWorldSettings")
        }
    }
}

/**
 * Singleton holding the SharedPreferences reference + typed accessors.
 *
 * Initialised once in Plugin.load() — every provider request reads from here.
 * The provider doesn't need a Context, just `PrefsHolder.logoEnabled` etc.
 */
object PrefsHolder {
    private const val PREFS_NAME = "AnimeWorldIT"

    private var _rawPrefs: SharedPreferences? = null
    val rawPrefs: SharedPreferences? get() = _rawPrefs

    val isInitialized: Boolean get() = _rawPrefs != null

    fun init(context: Context) {
        if (_rawPrefs != null) return
        _rawPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun prefs(): SharedPreferences =
        _rawPrefs ?: throw IllegalStateException("PrefsHolder not initialised")

    // ---- Provider split ----
    val isSplit: Boolean get() = prefs().getBoolean("isSplit", false)
    val dubEnabled: Boolean get() = prefs().getBoolean("dubEnabled", false)
    val subEnabled: Boolean get() = prefs().getBoolean("subEnabled", false)

    // ---- Logo enrichment ----
    val logoEnabled: Boolean get() = prefs().getBoolean("logoEnabled", true)
    val logoLanguage: String get() = prefs().getString("logoLanguage", "it") ?: "it"
}
