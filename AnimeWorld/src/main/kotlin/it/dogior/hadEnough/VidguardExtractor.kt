package it.dogior.hadEnough

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable

/**
 * Vidguard (listeamed.net) extractor — two-stage resolver.
 *
 * Stage 1 — Rhino eval (primary):
 *   The embed page contains an obfuscated `eval(...)` script that, when run
 *   under Rhino, populates a global `svg` object whose `stream` field is a
 *   sig-encoded m3u8 URL. We sigDecode() it to recover the real URL.
 *
 * Stage 2 — WebViewResolver fallback:
 *   If Rhino fails (script pattern changed, stack overflow, etc.), we fall
 *   back to a real Android WebView that executes the page's JS natively and
 *   intercepts any outgoing m3u8 request. This is more reliable but slower
 *   (~3-5s vs <500ms for Rhino).
 *
 * Final step:
 *   M3u8Helper.generateM3u8 expands the master playlist into one
 *   ExtractorLink per quality variant (1080p, 720p, 480p…).
 */
class VidguardExtractor : ExtractorApi() {
    override val mainUrl = "https://listeamed.net/"
    override val name = "VidGuard"
    override val requiresReferer = false

    private companion object {
        const val TAG = "AnimeWorld:VidGuard"
        const val RHINO_STACK_BYTES = 3_000_000L
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val playlistUrl = resolveViaRhino(url) ?: resolveViaWebView(url) ?: run {
            Log.w(TAG, "Both Rhino and WebView fallback failed for $url")
            return
        }

        // Expand the master m3u8 into multiple quality variants.
        try {
            val links = M3u8Helper.generateM3u8(
                source = name,
                streamUrl = playlistUrl,
                referer = mainUrl,
                quality = null,              // resolved per-variant from RESOLUTION
                headers = mapOf("User-Agent" to USER_AGENT),
                name = name,
            )
            if (links.isEmpty()) {
                callback.invoke(
                    newExtractorLink(name, name, playlistUrl, type = ExtractorLinkType.M3U8) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else {
                links.forEach(callback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "M3u8Helper failed, falling back to raw URL: ${e.message}")
            callback.invoke(
                newExtractorLink(name, name, playlistUrl, type = ExtractorLinkType.M3U8) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }

    /* ---------------------------------------------------------------- */
    /*  Stage 1: Rhino eval                                             */
    /* ---------------------------------------------------------------- */

    private suspend fun resolveViaRhino(url: String): String? {
        return try {
            val document = app.get(url).document
            val script = document.selectFirst("script:containsData(eval)")?.data() ?: run {
                Log.w(TAG, "Rhino: no eval() script found on page")
                return null
            }
            val decodedScript = runJS(script) ?: run {
                Log.w(TAG, "Rhino: eval returned empty result")
                return null
            }
            val json = tryParseJson<SvgObject>(decodedScript) ?: run {
                Log.w(TAG, "Rhino: could not parse svg JSON")
                return null
            }
            sigDecode(json.stream) ?: run {
                Log.w(TAG, "Rhino: sigDecode returned null")
                return null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Rhino stage failed: ${e.message}")
            null
        }
    }

    private fun sigDecode(url: String): String? {
        return try {
            val sig = url.substringAfter("sig=", "").substringBefore('&')
            if (sig.isEmpty()) return null

            val xored = sig.chunked(2).joinToString("") { chunk ->
                (Integer.parseInt(chunk, 16) xor 2).toChar().toString()
            }

            val padding = when (xored.length % 4) {
                2 -> "=="
                3 -> "="
                else -> ""
            }
            val decoded = String(
                Base64.decode((xored + padding).toByteArray(Charsets.UTF_8), Base64.DEFAULT)
            )

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

    private fun runJS(hideMyHtmlContent: String): String {
        var result = ""
        val runnable = Runnable {
            val rhino = Context.enter()
            try {
                rhino.optimizationLevel = -1
                val scope: Scriptable = rhino.initSafeStandardObjects()
                scope.put("window", scope, scope)
                rhino.evaluateString(
                    scope, hideMyHtmlContent, "JavaScript", 1, null,
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

    /* ---------------------------------------------------------------- */
    /*  Stage 2: WebViewResolver fallback                               */
    /* ---------------------------------------------------------------- */

    /**
     * If Rhino can't decode the script (script pattern changed, JS uses
     * APIs Rhino doesn't support, etc.), spin up a real Android WebView
     * that executes the page's JS natively and intercept any outgoing
     * m3u8 request. Slower (~3-5s) but more reliable.
     *
     * Requires `usesWebView = true` on the provider OR running on a
     * device that has a WebView component (all Android phones do).
     */
    private suspend fun resolveViaWebView(url: String): String? {
        return try {
            Log.i(TAG, "Falling back to WebViewResolver for $url")
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(m3u8|master\.txt|\.m3u8)"""),
                additionalUrls = emptyList(),
                userAgent = USER_AGENT,
                useOkhttp = false,           // required for JS-protected pages
                timeout = 15_000L,
            )
            val (matched, _) = resolver.resolveUsingWebView(url, referer = mainUrl)
            val streamUrl = matched?.url?.toString()
            if (streamUrl.isNullOrBlank()) {
                Log.w(TAG, "WebViewResolver returned no m3u8 URL")
                null
            } else {
                Log.i(TAG, "WebViewResolver intercepted: $streamUrl")
                streamUrl
            }
        } catch (e: Exception) {
            Log.w(TAG, "WebView fallback failed: ${e.message}")
            null
        }
    }

    data class SvgObject(
        val stream: String,
        val hash: String? = null,
    )
}
