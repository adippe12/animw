package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
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
 * Two preferences:
 *   - "Divisione plugin" (isSplit): when ON, the plugin registers two
 *     separate providers (AnimeWorld Dub / AnimeWorld Sub) instead of one
 *     unified AnimeWorld Core.
 *   - When isSplit is ON, two sub-switches appear: "AnimeWorld Dub" and
 *     "AnimeWorld Sub" — each controls whether the corresponding provider
 *     is registered.
 *
 * Toggling any preference requires a restart — we prompt the user with an
 * AlertDialog and, on confirmation, restart the host process.
 */
class Settings(private val plugin: AnimeWorldPlugin) : BottomSheetDialogFragment() {
    private val sharedPref = plugin.sharedPref

    /** Add TV-friendly padding + focus outline to a view. */
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

        val headerTw: TextView? = view.findViewByName("header_tw")
        headerTw?.text = getString("header_tw")

        val saveBtn: ImageButton? = view.findViewByName("save_btn")
        saveBtn?.makeTvCompatible()
        saveBtn?.setImageDrawable(getDrawable("save_icon"))

        val splitSwitch: Switch? = view.findViewByName("unique_switch")
        splitSwitch?.text = getString("unique_switch_text")
        val dubSwitch: Switch? = view.findViewByName("dub_switch")
        dubSwitch?.text = getString("dub_switch_text")
        val subSwitch: Switch? = view.findViewByName("sub_switch")
        subSwitch?.text = getString("sub_switch_text")

        val secondarySwitches: LinearLayout? = view.findViewByName("secondary_switches")

        splitSwitch?.isChecked = sharedPref?.getBoolean("isSplit", false) ?: false
        dubSwitch?.isChecked = sharedPref?.getBoolean("dubEnabled", false) ?: false
        subSwitch?.isChecked = sharedPref?.getBoolean("subEnabled", false) ?: false

        secondarySwitches?.visibility =
            if (splitSwitch?.isChecked == true) View.VISIBLE else View.GONE

        splitSwitch?.setOnCheckedChangeListener { _, b ->
            secondarySwitches?.visibility = if (b) View.VISIBLE else View.GONE
        }

        saveBtn?.setOnClickListener {
            with(sharedPref?.edit()) {
                // If isSplit is ON but neither Dub nor Sub is enabled, fall back
                // to the unified Core provider — otherwise the user would have
                // zero providers after restart.
                val effectiveSplit = splitSwitch?.isChecked == true &&
                    (dubSwitch?.isChecked == true || subSwitch?.isChecked == true)
                this?.putBoolean("isSplit", effectiveSplit)
                this?.putBoolean("dubEnabled", dubSwitch?.isChecked ?: false)
                this?.putBoolean("subEnabled", subSwitch?.isChecked ?: false)
                this?.apply()
            }

            // Clear the TMDB logo cache so the new provider variant re-fetches
            // logos from scratch (useful if we ever switch language defaults).
            TmdbLogoProvider.evictAll()

            AlertDialog.Builder(requireContext())
                .setTitle("Save & Reload")
                .setMessage("Changes have been saved. Do you want to restart the app to apply them?")
                .setPositiveButton("Yes") { _, _ ->
                    dismiss()
                    restartApp()
                }
                .setNegativeButton("No") { _, _ ->
                    showToast("Settings saved. Restart manually to apply.")
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
                showToast("Could not restart app")
            }
        } catch (e: Exception) {
            showToast("Restart error: ${e.message}")
        }
    }
}
