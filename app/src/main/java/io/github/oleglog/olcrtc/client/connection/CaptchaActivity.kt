package io.github.oleglog.olcrtc.client.connection

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContract
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import io.github.oleglog.olcrtc.client.R
import io.github.oleglog.olcrtc.client.diagnostics.DiagnosticsLogStore
import io.github.oleglog.olcrtc.client.ui.showMessage

/**
 * Passes a Yandex SmartCaptcha in place: a WebView card that slides over the
 * connection screen instead of handing the challenge to another app.
 *
 * Why this exists: the in-core solver handles the PoW challenge, but Yandex
 * escalates to an interactive checkbox (`/showcaptcha?cc=1`) that needs the
 * browser's own attestation — rdata/tdata/picasso computed by PGreed. That
 * cannot be forged from Go, so the only honest path is to let the page run,
 * have the user tick the box, and keep the cookies the browser earned.
 *
 * Three lessons from the field are baked in here:
 *
 * 1. Opening the document is not the same as opening the challenge. A browser
 *    on the document page usually gets served the document and never sees a
 *    captcha at all, so the caller may hand over the challenge URL returned by
 *    the core (`CaptchaError`) instead.
 * 2. The cookie jar is worth keeping even when no challenge appeared: a normal
 *    browser session carries the reputation cookies the transport needs, so
 *    "Done" is always available and always returns the jar. The sheet only
 *    closes by itself when it saw the challenge and came back to the document.
 * 3. A silent empty result is worse than no feature: every step — the page
 *    that loaded, the failure that stopped it, the per-host cookie counts and
 *    the names handed back — is logged (logcat tag "olcrtc"), so a live run can
 *    be diagnosed instead of guessed. Values are never logged.
 *
 * Nothing is scraped out of the page and no page script is injected.
 */
internal class CaptchaActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var sawCaptcha = false
    private val diagnostics by lazy { DiagnosticsLogStore.open(applicationContext) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val challengeUrl = intent.getStringExtra(EXTRA_DOCUMENT_URL).orEmpty()
        if (challengeUrl.isBlank()) {
            log("open: no challenge url, nothing to show")
            finishCancelled("no url")
            return
        }
        log("open: url=$challengeUrl")

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            // The same desktop UA the carrier presents, so the challenge the
            // user solves here is the challenge the transport will then face.
            settings.userAgentString = DESKTOP_USER_AGENT
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = observe(request.url.toString())

                @Deprecated("Required for API < 24")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                    observe(url)

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    observe(url)
                    log("page finished: ${hostOf(url)} seenChallenge=$sawCaptcha ${harvestLine()}")
                    // Done when the browser is back on the document after
                    // having seen the challenge.
                    if (sawCaptcha && !isCaptchaHost(url)) finishWithCookies("back on the document")
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    super.onReceivedError(view, request, error)
                    if (!request.isForMainFrame) return
                    log(
                        "load failed: ${request.url} code=${error.errorCode} " +
                            "reason=${error.description}",
                    )
                }
            }
            loadUrl(challengeUrl)
        }
        webView = view
        cookieManager.setAcceptThirdPartyCookies(view, true)
        setContentView(sheet(view))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishCancelled("back")
        })
    }

    /** The WebView card itself: rounded on top, pinned to the bottom. */
    private fun sheet(view: WebView): View {
        val card = MaterialCardView(this).apply {
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setTopLeftCorner(CornerFamily.ROUNDED, dp(SHEET_CORNER_DP).toFloat())
                .setTopRightCorner(CornerFamily.ROUNDED, dp(SHEET_CORNER_DP).toFloat())
                .build()
            setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurface))
            cardElevation = 0f
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(12), dp(4))
        }
        header.addView(
            TextView(this).apply {
                setText(R.string.captcha_sheet_title)
                setTextAppearance(R.style.TextAppearance_Olcrtc_TitleMedium)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            MaterialButton(this).apply {
                setText(R.string.captcha_reload)
                backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                setTextColor(resolveColor(androidx.appcompat.R.attr.colorPrimary))
                setOnClickListener { view.reload() }
            },
        )
        header.addView(
            MaterialButton(this).apply {
                setText(R.string.captcha_done)
                setOnClickListener { finishWithCookies("done tapped") }
            },
        )
        card.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(header)
                addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val root = FrameLayout(this)
        root.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply { topMargin = dp(SHEET_TOP_GAP_DP) },
        )
        return root
    }

    private fun observe(url: String): Boolean {
        if (isCaptchaHost(url)) sawCaptcha = true
        return false
    }

    private fun isCaptchaHost(url: String): Boolean =
        url.contains("showcaptcha") || url.contains("/checkcaptcha")

    private data class Harvest(val header: String, val perHost: List<Pair<String, Int>>)

    /**
     * Collects the cookies the browser earned across every host the transport
     * touches. The core applies them to the document's parent domain, so a
     * header taken from one host alone misses what the other one set — and a
     * jar that is missing `Session_id` is the same as no jar at all.
     */
    private fun harvest(): Harvest {
        val manager = CookieManager.getInstance()
        val byName = LinkedHashMap<String, String>()
        val perHost = ArrayList<Pair<String, Int>>(CAPTCHA_COOKIE_HOSTS.size)
        for (host in CAPTCHA_COOKIE_HOSTS) {
            val pairs = manager.getCookie(host).orEmpty()
                .split(';')
                .map(String::trim)
                .filter { it.contains('=') }
            perHost += host.removePrefix("https://") to pairs.size
            for (pair in pairs) byName[pair.substringBefore('=').trim()] = pair
        }
        return Harvest(byName.values.joinToString("; "), perHost)
    }

    private fun harvestLine(): String {
        val harvest = harvest()
        return harvestLine(harvest)
    }

    /** One logcat line: which hosts answered, how many cookies and which names. */
    private fun harvestLine(harvest: Harvest): String {
        val hosts = harvest.perHost.joinToString(",") { (host, count) -> "$host=$count" }
        return "hosts=$hosts ${describeCookieJar(harvest.header)}"
    }

    private fun finishWithCookies(reason: String) {
        val harvest = harvest()
        if (harvest.header.isBlank()) {
            // Keep the sheet open: closing with nothing is exactly the silent
            // dead end this screen exists to remove.
            log("finish($reason): jar is empty, staying open ${harvestLine(harvest)}")
            showMessage(getString(R.string.captcha_harvest_empty))
            return
        }
        log("finish($reason): ${harvestLine(harvest)}")
        setResult(RESULT_OK, Intent().putExtra(EXTRA_COOKIES, harvest.header))
        finish()
    }

    private fun finishCancelled(reason: String) {
        log("cancel: $reason")
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: url

    private fun log(message: String) {
        diagnostics.append("info", "captcha: $message")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun resolveColor(attr: Int): Int {
        val typed = TypedValue()
        theme.resolveAttribute(attr, typed, true)
        return typed.data
    }

    override fun onDestroy() {
        webView?.let { view ->
            runCatching { view.stopLoading() }
            runCatching { view.destroy() }
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_DOCUMENT_URL = "document_url"
        const val EXTRA_COOKIES = "cookies"
        private const val SHEET_CORNER_DP = 20
        private const val SHEET_TOP_GAP_DP = 56
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }
}

/** Result of a captcha pass: the cookie header, or null when the user backed out. */
internal class CaptchaResult(val cookies: String?)

/**
 * Launches [CaptchaActivity] and hands back the cookie header it collected.
 *
 * A contract rather than a plain startActivityForResult so the calling fragment
 * gets a typed result and no request codes leak into the editor code.
 */
internal class CaptchaContract : ActivityResultContract<String, CaptchaResult>() {
    override fun createIntent(context: Context, input: String): Intent =
        Intent(context, CaptchaActivity::class.java)
            .putExtra(CaptchaActivity.EXTRA_DOCUMENT_URL, input)

    override fun parseResult(resultCode: Int, intent: Intent?): CaptchaResult =
        CaptchaResult(
            if (resultCode == Activity.RESULT_OK) {
                intent?.getStringExtra(CaptchaActivity.EXTRA_COOKIES)
            } else {
                null
            },
        )
}
