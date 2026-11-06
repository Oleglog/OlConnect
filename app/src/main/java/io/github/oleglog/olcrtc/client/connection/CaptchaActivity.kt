package io.github.oleglog.olcrtc.client.connection

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContract
import io.github.oleglog.olcrtc.client.R

/**
 * Passes a Yandex SmartCaptcha the way a person would: in a real browser.
 *
 * Why this exists: the in-core solver handles the PoW challenge, but Yandex
 * escalates to an interactive checkbox (`/showcaptcha?cc=1`) that needs the
 * browser's own attestation — rdata/tdata/picasso computed by PGreed. That
 * cannot be forged from Go, so the only honest path is to let the page run,
 * have the user tick the box, and keep the cookies the browser earned.
 *
 * Two lessons from the field are baked in here:
 *
 * 1. Opening the document is not the same as opening the challenge. A browser
 *    on the document page usually gets served the document and never sees a
 *    captcha at all, so the caller may hand over the challenge URL returned by
 *    the core (`CaptchaError`) instead.
 * 2. The cookie jar is worth keeping even when no challenge appeared: a normal
 *    browser session carries the reputation cookies the transport needs, so
 *    the page is never a dead end. "Done" is always available and always
 *    returns the jar; the activity only finishes by itself when it saw the
 *    challenge and the browser came back to the document.
 *
 * Nothing is scraped out of the page and no page script is injected.
 */
internal class CaptchaActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var sawCaptcha = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val documentUrl = intent.getStringExtra(EXTRA_DOCUMENT_URL).orEmpty()
        if (documentUrl.isBlank()) {
            finishCancelled()
            return
        }

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
                    // Done when the browser is back on the document after
                    // having seen the challenge.
                    if (sawCaptcha && !isCaptchaHost(url)) finishWithCookies()
                }
            }
            loadUrl(documentUrl)
        }
        webView = view
        cookieManager.setAcceptThirdPartyCookies(view, true)

        val done = Button(this).apply {
            setText(R.string.captcha_done)
            setOnClickListener { finishWithCookies() }
        }
        val root = FrameLayout(this)
        root.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            done,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, dp(16), dp(16))
            },
        )
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishCancelled()
        })
    }

    private fun observe(url: String): Boolean {
        if (isCaptchaHost(url)) sawCaptcha = true
        return false
    }

    private fun isCaptchaHost(url: String): Boolean =
        url.contains("showcaptcha") || url.contains("/checkcaptcha")

    /**
     * Collects the cookies the browser earned across every host the transport
     * touches. The core applies them to the document's parent domain, so a
     * header taken from one host alone misses what the other one set — and a
     * jar that is missing `Session_id` is the same as no jar at all.
     */
    private fun harvestCookies(): String {
        val manager = CookieManager.getInstance()
        val byName = LinkedHashMap<String, String>()
        for (host in COOKIE_HOSTS) {
            manager.getCookie(host).orEmpty()
                .split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.contains('=') }
                .forEach { pair -> byName[pair.substringBefore('=')] = pair }
        }
        return byName.values.joinToString("; ")
    }

    private fun finishWithCookies() {
        val header = harvestCookies()
        if (header.isBlank()) {
            finishCancelled()
            return
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_COOKIES, header))
        finish()
    }

    private fun finishCancelled() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        private val COOKIE_HOSTS = listOf(
            "https://yandex.ru",
            "https://docviewer.yandex.ru",
            "https://docs.yandex.ru",
        )
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
