package io.github.oleglog.olcrtc.client.connection

import io.github.oleglog.olcrtc.client.data.ProfileConfig
import io.github.oleglog.olcrtc.client.profile.openflux.OpenFluxProfile
import io.github.oleglog.olcrtc.client.profile.standard.StandardProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptchaRecoveryTest {

    private val yandexProfile = ProfileConfig.OpenFlux(
        OpenFluxProfile(
            name = "Docs",
            documentUrl = "https://docs.yandex.ru/docs/view?url=abc",
            transport = OpenFluxProfile.Transport.VYANDEX,
            cookies = "Session_id=old; uid=1",
        ),
    )

    @Test
    fun challengeUrlIsTakenFromTheCoreError() {
        val message = "start olcRTC: yandex docs: captcha required: " +
            "smartcaptcha checkbox: https://yandex.ru/showcaptcha?cc=1&mt=abc&s=xyz"
        assertEquals(
            "https://yandex.ru/showcaptcha?cc=1&mt=abc&s=xyz",
            captchaChallengeUrl(message),
        )
    }

    @Test
    fun challengeUrlWinsOverOtherUrlsInTheLogTail() {
        val message = """
            [ANDROID] Ошибка запуска: yandex docs: captcha required: smartcaptcha checkbox: https://yandex.ru/showcaptcha?cc=1&s=1
            [ANDROID] document https://docs.yandex.ru/docs/view?url=abc
        """.trimIndent()
        assertEquals("https://yandex.ru/showcaptcha?cc=1&s=1", captchaChallengeUrl(message))
    }

    @Test
    fun challengeUrlIgnoresTrailingPunctuation() {
        val message = "failed (yandex docs: captcha required: page https://yandex.ru/checkcaptcha?cc=1)."
        assertEquals("https://yandex.ru/checkcaptcha?cc=1", captchaChallengeUrl(message))
    }

    @Test
    fun challengeUrlFallsBackToYandexHomeWithoutAChallengePage() {
        assertEquals(
            YANDEX_HOME_URL,
            captchaChallengeUrl("yandex docs: captcha required: smartcaptcha checkbox"),
        )
    }

    @Test
    fun nonCaptchaFailureHasNoChallengePage() {
        assertNull(captchaChallengeUrl("open connection: connection refused"))
        assertNull(captchaChallengeUrl(null))
        assertNull(captchaChallengeUrl(""))
    }

    @Test
    fun mergeAppendsFreshCookiesAfterStoredOnesSoTheyWin() {
        val merged = requireNotNull(
            mergeCookieHeaders("Session_id=old; uid=1", "Session_id=new; cookie_cap=1"),
        )
        val lines = merged.split('\n')
        assertEquals("Session_id=old; uid=1", lines.first())
        assertEquals("Session_id=new; cookie_cap=1", lines.last())
    }

    @Test
    fun mergeKeepsWhicheverSideIsNotEmpty() {
        assertEquals("a=1", mergeCookieHeaders(null, "a=1"))
        assertEquals("a=1", mergeCookieHeaders("a=1", " "))
        assertEquals("a=1", mergeCookieHeaders("a=1", null))
        assertNull(mergeCookieHeaders(null, null))
        assertNull(mergeCookieHeaders(" ", "\n"))
    }

    @Test
    fun planStoresTheJarInTheYandexProfileThatFailed() {
        val plan = requireNotNull(
            captchaRecoveryPlan("local:7", yandexProfile, "Session_id=new"),
        )
        assertEquals("local:7", plan.reference)
        assertEquals(
            "Session_id=old; uid=1\nSession_id=new",
            plan.profile.cookies,
        )
    }

    @Test
    fun planCoversEveryYandexCarrier() {
        for (transport in OpenFluxProfile.Transport.entries) {
            val config = ProfileConfig.OpenFlux(
                OpenFluxProfile(
                    name = "Docs",
                    documentUrl = "https://docs.yandex.ru/docs/view?url=abc",
                    transport = transport,
                ),
            )
            val expected = transport.value in setOf("vyandex", "yandex", "volga", "auto")
            assertEquals(
                transport.value,
                expected,
                captchaRecoveryPlan("local:1", config, "Session_id=new") != null,
            )
        }
        assertTrue(isYandexCarrier(" Vyandex "))
        assertFalse(isYandexCarrier("mailru"))
    }

    @Test
    fun planSkipsWhatItCannotSave() {
        assertNull(captchaRecoveryPlan(null, yandexProfile, "Session_id=new"))
        assertNull(captchaRecoveryPlan("local:7", yandexProfile, " "))
        assertNull(captchaRecoveryPlan("local:7", null, "Session_id=new"))
        assertNull(
            captchaRecoveryPlan(
                "local:7",
                ProfileConfig.Standard(
                    StandardProfile(
                        name = "Node",
                        protocol = StandardProfile.Protocol.VLESS,
                        address = "example.com",
                        port = 443,
                        uuid = "6b3f0b0d-0000-0000-0000-000000000000",
                    ),
                ),
                "Session_id=new",
            ),
        )
        assertNull(
            captchaRecoveryPlan(
                "local:7",
                ProfileConfig.OpenFlux(
                    OpenFluxProfile(
                        name = "Docs",
                        documentUrl = "https://docs.mail.ru/doc/1",
                        transport = OpenFluxProfile.Transport.MAILRU,
                    ),
                ),
                "Session_id=new",
            ),
        )
    }
}
