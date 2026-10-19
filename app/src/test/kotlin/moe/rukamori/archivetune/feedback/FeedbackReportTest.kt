/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackReportTest {
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

    @Test
    fun envelopeCarriesSchemaKindAndTrimmedIdentities() {
        val json =
            FeedbackReport.json(
                kind = FeedbackReport.KIND_CRASH,
                sentAt = "2026-10-19T00:00:00Z",
                app = app,
                device = device,
                note = "note one",
                report = "report body",
            )
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
            assertTrue("missing: $fragment — in: $json", json.contains(fragment))
        }
    }

    @Test
    fun reportTextEscapesSpecialCharacters() {
        val report =
            buildString {
                append("quote\" backslash\\ newline")
                append('\n')
                append("tab\t")
                append('\u000C')
                append(" formfeed")
            }
        val json =
            FeedbackReport.json(
                kind = FeedbackReport.KIND_PLAYBACK,
                sentAt = "",
                app = app,
                device = device,
                note = "",
                report = report,
            )
        // The encoded report must be one JSON line: every control character escaped.
        assertTrue(json.contains("quote\\\" backslash\\\\ newline\\n tab\\t\\u000c formfeed"))
        assertFalse(json.contains('\n'))
    }

    @Test
    fun longReportAndNoteAreTruncatedToServerBounds() {
        val longReport = "a".repeat(FeedbackReport.MAX_REPORT_CHARS + 5_000)
        val longNote = "n".repeat(FeedbackReport.MAX_NOTE_CHARS + 10)
        val json =
            FeedbackReport.json(
                kind = FeedbackReport.KIND_CRASH,
                sentAt = "",
                app = app,
                device = device,
                note = longNote,
                report = longReport,
            )
        val reportValueLength =
            json
                .substringAfter("\"report\":\"")
                .substringBefore("\"")
                .length
        assertEquals(FeedbackReport.MAX_REPORT_CHARS, reportValueLength)
        val noteValueLength =
            json
                .substringAfter("\"note\":\"")
                .substringBefore("\"")
                .length
        assertEquals(FeedbackReport.MAX_NOTE_CHARS, noteValueLength)
    }

    @Test
    fun bodyHeaderDescribesAppVariantAndDevice() {
        val header =
            FeedbackReport.bodyHeader(
                versionName = "1.2.3",
                versionCode = 42,
                forkRevision = "rev123",
                forkBranch = "dev",
                forkDirty = true,
                distribution = "gms",
                device = "mobile",
                architecture = "universal",
                buildType = "debug",
                osRelease = "15",
                sdkInt = 35,
                manufacturer = "Google",
                model = "Pixel 8",
            )
        assertTrue(header.contains("Revision: rev123 (dev) (modified)"))
        assertTrue(header.contains("Version: 1.2.3 (42)"))
        assertTrue(header.contains("Variant: gms/mobile/universal/debug"))
        assertTrue(header.contains("Android: 15 (API 35)"))
        assertTrue(header.contains("Device: Google Pixel 8"))
    }

    @Test
    fun bodyHeaderMarksModifiedTreesOnlyWhenDirty() {
        val clean =
            FeedbackReport.bodyHeader(
                versionName = "1",
                versionCode = 1,
                forkRevision = "r",
                forkBranch = "dev",
                forkDirty = false,
                distribution = "foss",
                device = "mobile",
                architecture = "universal",
                buildType = "debug",
                osRelease = "14",
                sdkInt = 34,
                manufacturer = "Google",
                model = "Pixel 6",
            )
        assertFalse(clean.contains("modified"))
    }
}
