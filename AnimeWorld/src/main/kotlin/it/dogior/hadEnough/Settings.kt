package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast

/**
 * Settings bottom-sheet.
 *
 * Four preference groups:
 *   1. Provider split (isSplit + dub/sub toggles)
 *   2. Logo enrichment (enabled toggle + language preference)
 *   3. AniList enrichment (cast, studio, banner, score, etc.)
 *   4. Cache management (clear logo + AniList cache)
 *
 * The preferences are read by the provider at request time via
 * `settingsForProvider` — so toggling doesn't require a restart (except for
 * the provider split, which changes which providers are registered).
 */
class Settings(private val plugin: AnimeWorldPlugin) : BottomSheetDialogFragment() {

    /** Logo language options exposed in the UI. */
    private val logoLanguages = listOf("en", "ja", "it", "fr", "de", "es", "pt", "ko", "zh")
    private val logoLanguageLabels = listOf(
        "Inglese (en)",
        "Giapponese (ja)",
        "Italiano (it)",
        "Francese (fr)",
        "Tedesco (de)",
        "Spagnolo (es)",
        "Portoghese (pt)",
        "Coreano (ko)",
        "Cinese (zh)",
    )

    private fun View.makeTvCompatible() {
        this.setPadding(
            this.paddingLeft + 10,
            this.paddingTop + 10,
            this.paddingRight + 10,
            this.paddingBottom + 10
        )
        this.background = getDrawable("outline")
    }

    @SuppressLint("DiscouragedApi")
    @Suppress("SameParameterValue")
    private fun getDrawable(name: String): Drawable? {
        val id = plugin.resources?.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { ResourcesCompat.getDrawable(plugin.resources ?: return null, it, null) }
    }

    @SuppressLint("DiscouragedApi")
    @Suppress("SameParameterValue")
    private fun getString(name: String): String? {
        val id = plugin.resources?.getIdentifier(name, "string", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { plugin.resources?.getString(it) }
    }

    @SuppressLint("DiscouragedApi")
    private fun <T : View> View.findViewByName(name: String): T? {
        val id = plugin.resources?.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return findViewById(id ?: return null)
    }

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val layoutId =
            plugin.resources?.getIdentifier("settings", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
                ?: return null
        val res = plugin.resources ?: return null
        return inflater.inflate(res.getLayout(layoutId), container, false)
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ---- Header ----
        val headerTw: TextView? = view.findViewByName("header_tw")
        headerTw?.text = getString("header_tw")

        val saveBtn: ImageButton? = view.findViewByName("save_btn")
        saveBtn?.makeTvCompatible()
        saveBtn?.setImageDrawable(getDrawable("save_icon"))

        // ---- Provider split ----
        val splitSwitch: Switch? = view.findViewByName("unique_switch")
        splitSwitch?.text = getString("unique_switch_text")
        val dubSwitch: Switch? = view.findViewByName("dub_switch")
        dubSwitch?.text = getString("dub_switch_text")
        val subSwitch: Switch? = view.findViewByName("sub_switch")
        subSwitch?.text = getString("sub_switch_text")
        val secondarySwitches: LinearLayout? = view.findViewByName("secondary_switches")

        splitSwitch?.isChecked = PrefsHolder.isSplit
        dubSwitch?.isChecked = PrefsHolder.dubEnabled
        subSwitch?.isChecked = PrefsHolder.subEnabled

        secondarySwitches?.visibility =
            if (splitSwitch?.isChecked == true) View.VISIBLE else View.GONE

        splitSwitch?.setOnCheckedChangeListener { _, b ->
            secondarySwitches?.visibility = if (b) View.VISIBLE else View.GONE
        }

        // ---- Logo enrichment ----
        val logoEnabledSwitch: Switch? = view.findViewByName("logo_enabled_switch")
        logoEnabledSwitch?.text = getString("logo_enabled_switch_text")
        logoEnabledSwitch?.isChecked = PrefsHolder.logoEnabled

        val logoLangLabel: TextView? = view.findViewByName("logo_lang_label")
        logoLangLabel?.text = getString("logo_lang_label")

        val logoLangSpinner: Spinner? = view.findViewByName("logo_lang_spinner")
        val currentLang = PrefsHolder.logoLanguage
        val currentLangIndex = logoLanguages.indexOf(currentLang).coerceAtLeast(0)
        logoLangSpinner?.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            logoLanguageLabels
        )
        logoLangSpinner?.setSelection(currentLangIndex)

        // ---- AniList enrichment ----
        val anilistSwitch: Switch? = view.findViewByName("anilist_enricher_switch")
        anilistSwitch?.text = getString("anilist_enricher_switch_text")
        anilistSwitch?.isChecked = PrefsHolder.anilistEnricherEnabled

        // ---- Cache management ----
        val clearCacheBtn: Button? = view.findViewByName("clear_cache_btn")
        clearCacheBtn?.text = getString("clear_cache_btn_text")
        clearCacheBtn?.setOnClickListener {
            TmdbLogoProvider.evictAll()
            AniListEnricher.evictAll()
            showToast("Cache logo + AniList pulita")
        }

        // ---- Save button ----
        saveBtn?.setOnClickListener {
            val sp = PrefsHolder.rawPrefs
            if (sp == null) {
                showToast("Errore: SharedPreferences non disponibili")
                return@setOnClickListener
            }
            val editor = sp.edit()
            val effectiveSplit = splitSwitch?.isChecked == true &&
                (dubSwitch?.isChecked == true || subSwitch?.isChecked == true)
            editor.putBoolean("isSplit", effectiveSplit)
            editor.putBoolean("dubEnabled", dubSwitch?.isChecked ?: false)
            editor.putBoolean("subEnabled", subSwitch?.isChecked ?: false)
            editor.putBoolean("logoEnabled", logoEnabledSwitch?.isChecked ?: true)
            editor.putBoolean("anilistEnricherEnabled", anilistSwitch?.isChecked ?: true)
            logoLangSpinner?.let { spinner ->
                val selected = logoLanguages.getOrNull(spinner.selectedItemPosition) ?: "en"
                editor.putString("logoLanguage", selected)
            }
            editor.apply()

            AlertDialog.Builder(requireContext())
                .setTitle("Save & Reload")
                .setMessage("Modifiche salvate. Vuoi riavviare l'app per applicarle?")
                .setPositiveButton("Sì") { _, _ ->
                    dismiss()
                    restartApp()
                }
                .setNegativeButton("No") { _, _ ->
                    showToast("Impostazioni salvate. Riavvia manualmente per applicarle.")
                    dismiss()
                }
                .show()
        }
    }

    private fun restartApp() {
        try {
            val context = requireContext().applicationContext
            val packageManager = context.packageManager
            val intent = packageManager.getLaunchIntentForPackage(context.packageName)
            val componentName = intent?.component

            if (componentName != null) {
                val restartIntent = Intent.makeRestartActivityTask(componentName)
                context.startActivity(restartIntent)
                Runtime.getRuntime().exit(0)
            } else {
                showToast("Impossibile riavviare l'app")
            }
        } catch (e: Exception) {
            showToast("Errore riavvio: ${e.message}")
        }
    }
}
