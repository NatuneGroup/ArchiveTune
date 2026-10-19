/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.feedback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackReportTest {
    /** The server's body cap (api/report.js INGEST_MAX_BODY). */
    private val serverBodyLimit = 256 * 1024

    private val app =
        FeedbackReport.AppIdentity(
            versionName = "1.2.3",
            versionCode = 42,
            forkRevision = "rev123",
            forkBranch = "dev",
            variant = "gms/mobile/universal/debug",
        )
    private val device =
        FeedbackReport.DeviceIdentity(
            manufacturer = "Google",
            model = "Pixel 8",
            osRelease = "15",
            sdkInt = 35,
        )

    private fun envelope(
        report: String,
        note: String = "",
        kind: String = FeedbackReport.KIND_PLAYBACK,
    ): String =
        FeedbackReport.json(
            kind = kind,
            sentAt = "2026-10-19T00:00:00Z",
            app = app,
            device = device,
            note = note,
            report = report,
        )

    /** Same layout the drafts use: the head, the log heading, then the diagnostic log. */
    private fun draft(
        head: String,
        log: String,
    ): String = head + "\n" + FeedbackReport.LOG_HEADING + "\n" + log

    @Test
    fun envelopeCarriesSchemaKindAndIdentities() {
        val json = envelope(report = "report body", note = "note one", kind = FeedbackReport.KIND_CRASH)
        listOf(
            """"schema":"natune-feedback/1"""",
            """"kind":"crash"""",
            """"sentAt":"2026-10-19T00:00:00Z"""",
            """"version":"1.2.3"""",
            """"code":42""",
            """"revision":"rev123"""",
            """"branch":"dev"""",
            """"variant":"gms/mobile/universal/debug"""",
            """"manufacturer":"Google"""",
            """"model":"Pixel 8"""",
            """"os":"15"""",
            """"sdk":35""",
            """"note":"note one"""",
            """"report":"report body"""",
        ).forEach { fragment ->
            assertTrue("missing: $fragment in: $json", json.contains(fragment))
        }
    }

    @Test
    fun reportAndNoteRoundTripThroughTheJsonParser() {
        val report = "quote\" backslash\\ newline\ntab\t\u000C form feed \u0001 中文 😀"
        val note = "note \"quoted\"\nline"
        val json = envelope(report, note)
        // One line on the wire: control characters are escaped, never sent raw.
        assertFalse(json.contains('\n'))
        assertFalse(json.contains('\t'))
        assertFalse(json.contains('\u000C'))
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals(report, parsed.getValue("report").jsonPrimitive.content)
        assertEquals(note, parsed.getValue("note").jsonPrimitive.content)
        assertEquals(FeedbackReport.SCHEMA, parsed.getValue("schema").jsonPrimitive.content)
    }

    @Test
    fun escapedUtf8BytesMatchTheEncodedEnvelope() {
        val base = envelope("").toByteArray().size
        listOf(
            "plain",
            "quote\" slash\\ \n\r\t\b\u000C\u0001 \u007F",
            "中文歌曲",
            "emoji 😀 pair",
        ).forEach { sample ->
            assertEquals(sample, FeedbackReport.escapedUtf8Bytes(sample), envelope(sample).toByteArray().size - base)
        }
    }

    @Test
    fun finishLeavesAShortDraftUntouched() {
        assertEquals(
            "head\n${FeedbackReport.LOG_HEADING}\nline one",
            FeedbackReport.finish(draft("head", "line one")),
        )
    }

    @Test
    fun finishRedactsErrorDetailsAndLogAsOneDraft() {
        val details =
            "Could not open telegram://chat-42/msg-7/file-9 via " +
                "http://203.0.113.9/videoplayback?sig=abc&ip=198.51.100.7 SAPISID=sapsecret"
        val report = FeedbackReport.finish(draft("Playback error details:\n$details", "status=READY addr=2001:db8::1"))
        listOf(
            "chat-42",
            "msg-7",
            "file-9",
            "203.0.113.9",
            "198.51.100.7",
            "videoplayback",
            "sig=abc",
            "sapsecret",
            "2001:db8",
        ).forEach { leaked -> assertFalse(leaked, report.contains(leaked)) }
        assertTrue(report.contains("status=READY"))
    }

    @Test
    fun boundedDraftKeepsTheHeaderAndTheNewestLogLines() {
        val head = "ArchiveTune feedback report\nError details: boom"
        val log = (1..20_000).joinToString("\n") { "diagnostic line $it, padded so the log overruns the budget" }
        val report = FeedbackReport.finish(draft(head, log))
        assertTrue(report.startsWith(head))
        assertTrue(report.contains("\n${FeedbackReport.TRUNCATED_MARKER}\n"))
        assertTrue(report.endsWith("diagnostic line 20000, padded so the log overruns the budget"))
        assertFalse(report.contains("diagnostic line 1,"))
        assertTrue(report.toByteArray().size <= FeedbackReport.MAX_REPORT_BYTES)
    }

    @Test
    fun multibyteAndEscapeHeavyDraftsStayWithinTheServerBody() {
        val cjkLog = (1..40_000).joinToString("\n") { "中文歌曲 $it" }
        val controlLog = (1..40_000).joinToString("\n") { "\u0001\u0002\u0003" }
        // A maximal note is the worst case the envelope has to absorb beside the bounded report.
        val worstNote = "\u0001".repeat(FeedbackReport.MAX_NOTE_CHARS)
        listOf(cjkLog, controlLog).forEach { log ->
            val report = FeedbackReport.finish(draft("header", log))
            assertTrue(report.contains(FeedbackReport.TRUNCATED_MARKER))
            val size = envelope(report, worstNote).toByteArray().size
            assertTrue("envelope is $size bytes", size <= serverBodyLimit)
        }
    }

    @Test
    fun headerMarksModifiedTreesOnlyWhenDirty() {
        val header = FeedbackReport.header(app, device, forkDirty = true)
        assertTrue(header.contains("Revision: rev123 (dev) (modified)"))
        assertTrue(header.contains("Version: 1.2.3 (42)"))
        assertTrue(header.contains("Variant: gms/mobile/universal/debug"))
        assertTrue(header.contains("Android: 15 (API 35)"))
        assertTrue(header.contains("Device: Google Pixel 8"))
        assertFalse(FeedbackReport.header(app, device, forkDirty = false).contains("modified"))
    }
}
