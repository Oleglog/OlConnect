package io.github.oleglog.olcrtc.client.connection

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContract

/**
 * Passes a Yandex SmartCaptcha the way a person would: in a real browser.
 *
 * Why this exists: the in-core solver handles the PoW challenge, but Yandex
 * escalates to an interactive checkbox (`/showcaptcha?cc=1`) that needs the
 * browser's own attestation — rdata/tdata/picasso computed by PGreed. That
 * cannot be forged from Go, so the only honest path is to let the page run,
 * have the user tick the box, and keep the cookies the browser earned.
 *
 * The activity stays thin: it opens the document, notices the captcha host,
 * and reports the cookie jar once the browser is back on the document. Nothing
 * is scraped out of the page and no page script is injected.
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
        setContentView(view)

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

    private fun finishWithCookies() {
        val header = CookieManager.getInstance().getCookie(COOKIE_DOMAIN).orEmpty()
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
        private const val COOKIE_DOMAIN = "https://yandex.ru"
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
