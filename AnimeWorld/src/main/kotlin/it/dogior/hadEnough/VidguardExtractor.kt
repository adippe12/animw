package it.dogior.hadEnough

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable

/**
 * Vidguard (listeamed.net) extractor.
 *
 * The embed page contains an obfuscated `eval(...)` script that, when run,
 * populates a global `svg` object whose `stream` field is a sig-encoded m3u8
 * URL. We:
 *   1. Fetch the page HTML.
 *   2. Find the script tag containing `eval`.
 *   3. Run it under Rhino (with a 3 MB stack — Rhino needs the headroom).
 *   4. Read back the `svg` object as JSON.
 *   5. sigDecode() the `stream` field to recover the real m3u8 URL.
 *
 * The original implementation logged the full obfuscated script with Log.d —
 * removed because it pollutes logcat with kilobytes of noise per request.
 */
class VidguardExtractor : ExtractorApi() {
    override val mainUrl = "https://listeamed.net/"
    override val name = "VidGuard"
    override val requiresReferer = false

    private companion object {
        const val TAG = "AnimeWorld:VidGuard"
        // Rhino needs a larger-than-default stack for the Vidguard eval payload.
        const val RHINO_STACK_BYTES = 3_000_000L
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            Log.w(TAG, "GET $url failed: ${e.message}")
            return
        }

        val script = document.selectFirst("script:containsData(eval)")?.data() ?: run {
            Log.w(TAG, "No eval() script found on page")
            return
        }

        val decodedScript = runJS(script) ?: run {
            Log.w(TAG, "Rhino eval returned empty result")
            return
        }

        val json = tryParseJson<SvgObject>(decodedScript) ?: run {
            Log.w(TAG, "Could not parse svg JSON: $decodedScript")
            return
        }

        val playlistUrl = sigDecode(json.stream) ?: run {
            Log.w(TAG, "sigDecode returned null for stream=${json.stream}")
            return
        }

        callback.invoke(
            newExtractorLink(
                name,
                name,
                playlistUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }

    /**
     * sigDecode — Vidguard's URL signature is a base64-of-xor-with-2 payload
     * that's been character-swapped and reversed twice. We undo all of that.
     *
     * Returns the input URL with its `sig=...` parameter replaced by the
     * decoded signature, or null on any failure.
     */
    private fun sigDecode(url: String): String? {
        return try {
            val sig = url.substringAfter("sig=", "").substringBefore('&')
            if (sig.isEmpty()) return null

            // Step 1: hex-decode 2-bytes-at-a-time, XOR each byte with 2.
            val xored = sig.chunked(2).joinToString("") { chunk ->
                (Integer.parseInt(chunk, 16) xor 2).toChar().toString()
            }

            // Step 2: pad and base64-decode.
            val padding = when (xored.length % 4) {
                2 -> "=="
                3 -> "="
                else -> ""
            }
            val decoded = String(
                Base64.decode((xored + padding).toByteArray(Charsets.UTF_8), Base64.DEFAULT)
            )

            // Step 3: drop last 5 chars, reverse, swap every adjacent char pair,
            //         then drop last 5 chars again.
            val swapped = decoded.dropLast(5).reversed().toCharArray().apply {
                for (i in indices step 2) {
                    if (i + 1 < size) {
                        this[i] = this[i + 1].also { this[i + 1] = this[i] }
                    }
                }
            }.concatToString().dropLast(5)

            url.replace(sig, swapped)
        } catch (e: Exception) {
            Log.w(TAG, "sigDecode failed: ${e.message}")
            null
        }
    }

    /**
     * Run an obfuscated JS string under Rhino and return whatever the global
     * `svg` variable holds (serialized to JSON if it's an object).
     *
     * Rhino is single-threaded per Context — we run it on a dedicated thread
     * with a larger stack to avoid blowing the default 512 KB stack on
     * deeply-nested eval payloads.
     */
    private fun runJS(hideMyHtmlContent: String): String {
        var result = ""
        val runnable = Runnable {
            val rhino = Context.enter()
            try {
                rhino.optimizationLevel = -1
                val scope: Scriptable = rhino.initSafeStandardObjects()
                // Many packers reference `window` — alias it to the global scope.
                scope.put("window", scope, scope)
                rhino.evaluateString(
                    scope,
                    hideMyHtmlContent,
                    "JavaScript",
                    1,
                    null,
                )
                val svgObject = scope.get("svg", scope)
                result = if (svgObject is NativeObject) {
                    NativeJSON.stringify(
                        Context.getCurrentContext(), scope, svgObject, null, null
                    ).toString()
                } else {
                    Context.toString(svgObject)
                }
            } catch (e: Throwable) {
                // Catch Throwable — Rhino throws both Error and Exception subtypes.
                Log.w(TAG, "Rhino eval failed: ${e.message}")
            } finally {
                Context.exit()
            }
        }

        val thread = Thread(
            ThreadGroup("VidguardRhino"),
            runnable,
            "thread_rhino_vidguard",
            RHINO_STACK_BYTES
        )
        thread.start()
        thread.join()
        thread.interrupt()
        return result
    }

    /** Subset of the JS `svg` object we care about. */
    data class SvgObject(
        val stream: String,
        val hash: String? = null,
    )
}
