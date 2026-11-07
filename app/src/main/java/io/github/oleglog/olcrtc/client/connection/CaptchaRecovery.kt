package io.github.oleglog.olcrtc.client.connection

import io.github.oleglog.olcrtc.client.data.ProfileConfig
import io.github.oleglog.olcrtc.client.profile.openflux.OpenFluxProfile
import java.util.Locale

/** Page the browser opens when the core named no challenge URL of its own. */
internal const val YANDEX_HOME_URL = "https://yandex.ru/"

/**
 * Carriers that answer with a Yandex `showcaptcha` challenge. "auto" is in the
 * set because the core resolves a Yandex document link to a Yandex carrier even
 * when the user never picked the transport explicitly.
 */
internal fun isYandexCarrier(transport: String): Boolean =
    transport.trim().lowercase(Locale.ROOT) in setOf("vyandex", "yandex", "volga", "auto")

private val URL_PATTERN = Regex("""https?://\S+""")
private val URL_TAIL = charArrayOf('.', ',', ';', ':', ')', ']', '"', '\'')

/**
 * Challenge page to open after a failed connect attempt, or null when the
 * failure was not a captcha at all.
 *
 * The core renders a captcha as `CaptchaError.Error()` —
 * "yandex docs: captcha required: <reason>: <url>" — and the transport usually
 * buries it in a log tail, so the whole message is scanned instead of a prefix.
 */
internal fun captchaChallengeUrl(message: String?): String? {
    if (message.isNullOrBlank() || !message.contains("captcha", ignoreCase = true)) return null
    return URL_PATTERN.findAll(message)
        .map { match -> match.value.trimEnd(*URL_TAIL) }
        .firstOrNull { it.contains("/showcaptcha") || it.contains("/checkcaptcha") }
        ?: YANDEX_HOME_URL
}

/**
 * Appends the freshly earned jar to whatever the profile already carried.
 *
 * The core parses cookies line by line and the last occurrence of a name wins,
 * so appending lets the browser override stale values without dropping anything
 * else — including cookies.txt rows the user pasted by hand.
 */
internal fun mergeCookieHeaders(existing: String?, fresh: String?): String? =
    listOfNotNull(
        existing?.trim()?.takeIf(String::isNotEmpty),
        fresh?.trim()?.takeIf(String::isNotEmpty),
    ).joinToString("\n").takeIf(String::isNotEmpty)

/** A profile the returned cookies belong to, ready to be written back. */
internal data class CaptchaRecovery(val reference: String, val profile: OpenFluxProfile)

/**
 * Decides what to do with a returned jar: which profile gets it, and by
 * extension whether reconnecting is worth it. Null means "nothing to save".
 */
internal fun captchaRecoveryPlan(
    reference: String?,
    config: ProfileConfig?,
    cookies: String?,
): CaptchaRecovery? {
    if (reference.isNullOrBlank()) return null
    val openflux = (config as? ProfileConfig.OpenFlux)?.value ?: return null
    if (!isYandexCarrier(openflux.transport.value)) return null
    val merged = mergeCookieHeaders(openflux.cookies, cookies) ?: return null
    return CaptchaRecovery(reference = reference, profile = openflux.copy(cookies = merged))
}
