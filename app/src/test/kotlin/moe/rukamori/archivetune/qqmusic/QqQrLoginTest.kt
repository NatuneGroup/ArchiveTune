/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Pins the QR-login state machine and the `ptuiCB` reply it reads.
 * The login chain branches on a two-digit code buried in a JavaScript call, and every branch means
 * something different to the user: keep waiting, the code is dead, they declined, or they are in.
 * Getting that mapping wrong either burns a scanned code or, worse, treats a refusal as a success —
 * so it is pinned against the literal payloads the server sends, including the CJK status strings
 * that sit next to the codes.
 */



package moe.rukamori.archivetune.qqmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QqQrLoginTest {
    /**
     * The five states the poll reports. `0` and `68` are the two that must never be confused: one
     * finishes a sign-in, the other says the user declined it.
     */
    @Test
    fun everyPollCodeMapsToItsState() {
        assertEquals(QqQrStatus.CONFIRMED, QqQrProtocol.statusOf("0"))
        assertEquals(QqQrStatus.WAITING, QqQrProtocol.statusOf("66"))
        assertEquals(QqQrStatus.SCANNED, QqQrProtocol.statusOf("67"))
        assertEquals(QqQrStatus.EXPIRED, QqQrProtocol.statusOf("65"))
        assertEquals(QqQrStatus.REFUSED, QqQrProtocol.statusOf("68"))
    }

    /** An unrecognised code is a failure, not a silent "keep polling" that never ends. */
    @Test
    fun anUnknownCodeIsAFailure() {
        assertEquals(QqQrStatus.FAILED, QqQrProtocol.statusOf("99"))
        assertEquals(QqQrStatus.FAILED, QqQrProtocol.statusOf(""))
        assertEquals(QqQrStatus.FAILED, QqQrProtocol.statusOf(" 405 "))
    }

    /** A poll that has not been scanned yet, exactly as the server writes it. */
    @Test
    fun anUnscannedPollIsWaiting() {
        val callback = QqQrProtocol.parseCallback("ptuiCB('66','0','','0','二维码未失效。','')")
        assertEquals(QqQrStatus.WAITING, callback?.status)
        assertEquals("二维码未失效。", callback?.message)
        assertNull(callback?.uin)
        assertNull(callback?.ptsigx)
    }

    /** A scan that is waiting for the user to confirm on their phone. */
    @Test
    fun aScannedPollIsWaitingForConfirmation() {
        val callback = QqQrProtocol.parseCallback("ptuiCB('67','0','','0','已扫描，等待确认','李四')")
        assertEquals(QqQrStatus.SCANNED, callback?.status)
        assertEquals("李四", callback?.nickname)
    }

    /** An expired code must be reported as expired so the screen fetches a new one. */
    @Test
    fun anExpiredPollIsReported() {
        val callback = QqQrProtocol.parseCallback("ptuiCB('65','0','','0','二维码已失效。','')")
        assertEquals(QqQrStatus.EXPIRED, callback?.status)
    }

    /**
     * The success reply, with the signed pair pulled out of the redirect URL it carries. Without
     * these two values the remaining three hops cannot run at all.
     */
    @Test
    fun aConfirmedPollYieldsTheSignedPairAndNickname() {
        val body =
            "ptuiCB('0','0','https://ptlogin2.graph.qq.com/check_sig?uin=2363310076&service=ptqrlogin" +
                "&ptsigx=1a2b3c4d5e&s_url=https%3A%2F%2Fgraph.qq.com%2Foauth2.0%2Flogin_jump','0'," +
                "'登录成功！','张三')"

        val callback = QqQrProtocol.parseCallback(body)

        assertEquals(QqQrStatus.CONFIRMED, callback?.status)
        assertEquals("2363310076", callback?.uin)
        assertEquals("1a2b3c4d5e", callback?.ptsigx)
        assertEquals("张三", callback?.nickname)
    }

    /** A truncated or unrelated body is a parse failure rather than a made-up state. */
    @Test
    fun aMalformedReplyParsesToNothing() {
        assertNull(QqQrProtocol.parseCallback(""))
        assertNull(QqQrProtocol.parseCallback("<html>not a callback</html>"))
        assertNull(QqQrProtocol.parseCallback("ptuiCB('0')"))
    }

    /** The redirect's parameters are read whichever order the server puts them in. */
    @Test
    fun redirectParametersAreReadInAnyOrder() {
        assertEquals("99", QqQrProtocol.queryParameter("https://h/check_sig?uin=99&ptsigx=a", "uin"))
        assertEquals("a", QqQrProtocol.queryParameter("https://h/check_sig?uin=99&ptsigx=a", "ptsigx"))
        assertEquals("a", QqQrProtocol.queryParameter("https://h/check_sig?ptsigx=a&uin=99", "ptsigx"))
        assertEquals("7", QqQrProtocol.queryParameter("https://h/x?code=7&state=state", "code"))
        assertNull(QqQrProtocol.queryParameter("https://h/check_sig?uin=99", "ptsigx"))
    }
}
