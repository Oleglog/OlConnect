package io.github.oleglog.olcrtc.client.connection

import io.github.oleglog.olcrtc.client.data.ProfileConfig
import io.github.oleglog.olcrtc.client.profile.openflux.OpenFluxProfile
import java.util.Locale

/** Page the browser opens when the core named no challenge URL of its own. */
internal const val YANDEX_HOME_URL = "https://yandex.ru/"

/**
 * Hosts a Yandex carrier earns its cookies on. `CookieManager.getCookie` matches
 * domain cookies too, so `.yandex.ru` / `.yandex.net` arrive through their bare
 * host; the document hosts are listed because the post-challenge redirect sets
 * the reputation cookies there.
 */
internal val CAPTCHA_COOKIE_HOSTS = listOf(
    "https://yandex.ru",
    "https://docs.yandex.ru",
    "https://docviewer.yandex.ru",
    "https://volga.yandex.ru",
    "https://yandex.net",
    "https://docs.yandex.net",
)

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

/**
 * `name=value` pairs of either a plain Cookie header or a cookies.txt blob, in
 * the order the core would read them (last occurrence of a name wins).
 */
internal fun cookiePairs(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    val pairs = LinkedHashMap<String, String>()
    for (line in raw.split('\n')) {
        val fields = line.trim().split('\t')
        if (fields.size >= COOKIES_TXT_FIELDS) {
            // Netscape row: domain, flag, path, secure, expiry, name, value.
            val name = fields[5].trim()
            if (name.isNotEmpty()) pairs[name] = fields[6].trim()
            continue
        }
        for (part in line.split(';')) {
            if ('=' !in part) continue
            val name = part.substringBefore('=').trim()
            if (name.isEmpty()) continue
            pairs[name] = part.substringAfter('=').trim()
        }
    }
    return pairs
}

/** Domains named by cookies.txt rows; empty for a plain `name=value` header. */
internal fun cookieDomains(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split('\n')
        .map { it.trim().split('\t') }
        .filter { it.size >= COOKIES_TXT_FIELDS }
        .map { fields -> fields[0].trim().removePrefix("#HttpOnly_").trimStart('.') }
        .filter(String::isNotEmpty)
        .distinct()
}

/**
 * One logcat-safe line about a jar: how many pairs, their names and domains.
 * Values are never rendered — a log line must stay safe to paste into a chat.
 */
internal fun describeCookieJar(raw: String?): String {
    val names = cookiePairs(raw).keys.toList()
    if (names.isEmpty()) return "pairs=0"
    val domains = cookieDomains(raw)
    val domainPart = if (domains.isEmpty()) "" else " domains=${domains.joinToString(",")}"
    return "pairs=${names.size} names=${names.joinToString(",")}$domainPart"
}

private const val COOKIES_TXT_FIELDS = 7

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
    // Nothing new came back, so there is nothing to write and nothing to retry.
    if (reference.isNullOrBlank() || cookies.isNullOrBlank()) return null
    val openflux = (config as? ProfileConfig.OpenFlux)?.value ?: return null
    if (!isYandexCarrier(openflux.transport.value)) return null
    val merged = mergeCookieHeaders(openflux.cookies, cookies) ?: return null
    return CaptchaRecovery(reference = reference, profile = openflux.copy(cookies = merged))
}
