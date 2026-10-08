/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Compact external yt-dlp bridge — YTDLnis-style plugin model.
 * Why this exists:
 * ArchiveTune's YouTube path is native-only (InnerTube + BotGuard/QuickJS PO tokens via
 * YTPlayerUtils, backed by MetrolistExtractor for signature/n handling). That keeps the
 * base APK ~30 MB and needs no Python at build time (see `b20104e` which removed Chaquopy).
 * yt-dlp is still useful as an *optional* fallback for age-restricted / 403 / cipher-edge
 * cases, but bundling Chaquopy+Python+yt-dlp (like the Aug 23 `939c4ab` innertubex port did)
 * inflates the universal APK by 40-80 MB and breaks CI (needs Python 3.11, disables
 * configuration cache). YTDLnis solved the same problem by externalising runtimes:
 * Python/FFmpeg/Node/Deno/QuickJS/Aria2c are shipped as separate APKs (empty apps whose
 * `jniLibs` carry `libxxx.so` + `libxxx.zip.so`; YTDLnis extracts `*.zip.so` to
 * `filesDir/ytdlnis` and keeps `*.so` visible system-wide). Base APK stays small, plugins
 * can be updated independently.
 * What this file does:
 * Mirrors YTDLnis's `RuntimeManager` plugin discovery without bundling anything:
 * - Probes for an already-installed YTDLnis plugin package
 * (`com.deniscerri.ytdl`, `com.deniscerri.ytdl.python`, `com.yausername.youtubedl-android`).
 * - If found, resolves `nativeLibraryDir` + `lib/python` layout and exposes the python
 * executable path and the `ytdlp` binary under `noBackupFilesDir/ytdlnis/yt-dlp`.
 * - If not found, reports unavailable so the caller falls back to the native resolver.
 * This is the "compact like ytdlnis" approach requested: zero bundled Python, optional
 * external runtime, same fallback semantics as YTDLnis's NewPipe ↔ yt-dlp switch.
 * Wiring:
 * `ResolveAudioStreamUseCase` stays native-only by default. When it catches a
 * retriable native failure and `CompactYtDlp.isAvailable(context)` is true, it may
 * delegate to `ExternalYtDlpRepository` (thin `ProcessBuilder` wrapper around
 * `python + yt-dlp --dump-json`). No Hilt binding is added here to keep the graph
 * untouched until the feature is explicitly enabled in settings.
 * Security / invariants:
 * - No network call to Rukamori/Koiverse is added.
 * - No embedded Python is restored; this is detection-only.
 * - `AutoChoosePlaybackClientKey` / `YTPlayerUtils` client selection remains authoritative;
 * yt-dlp is a *fallback*, never a replacement.
 */



package moe.rukamori.archivetune.ytdlp

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

object CompactYtDlp {
    private const val YTDLNIS_PACKAGE = "com.deniscerri.ytdl"
    private const val YTDLNIS_PYTHON_PLUGIN = "com.deniscerri.ytdl.python"
    private const val YOUTUBEDL_ANDROID = "com.yausername.youtubedl-android"

    /**
     * True if an external yt-dlp runtime is already installed and executable.
     * Checks (in order): YTDLnis python plugin APK's nativeLibraryDir, YTDLnis main APK,
     * youtubedl-android, and a `filesDir/ytdlnis/yt-dlp` binary from an earlier download.
     * No download is triggered here — that is the user's explicit action in settings.
     */
    fun isAvailable(context: Context): Boolean = resolvePythonExecutable(context) != null

    /**
     * Returns the python executable File if a plugin APK is installed, else null.
     * Mirrors YTDLnis `Python.getInstance().location.executable` discovery:
     * `nativeLibraryDir/libpython.so` plus extraction dir for `libpython.zip.so`.
     */
    fun resolvePythonExecutable(context: Context): File? {
        val pm = context.packageManager
        val candidates = listOf(YTDLNIS_PYTHON_PLUGIN, YTDLNIS_PACKAGE, YOUTUBEDL_ANDROID)
        for (pkg in candidates) {
            try {
                val ai = pm.getApplicationInfo(pkg, 0)
                // YTDLnis plugins expose libpython.so in nativeLibraryDir
                val libDir = File(ai.nativeLibraryDir)
                val pythonSo = File(libDir, "libpython.so").takeIf { it.exists() }
                    ?: File(libDir, "libpython3.11.so").takeIf { it.exists() }
                if (pythonSo != null && pythonSo.canExecute()) return pythonSo
                // Fallback: some plugin versions keep `python` as executable under files
                val alt = File(ai.dataDir, "files/python/bin/python").takeIf { it.exists() }
                if (alt != null) return alt
            } catch (_: PackageManager.NameNotFoundException) {
                continue
            }
        }
        // Locally cached yt-dlp python (installed earlier via settings)
        val cached = File(context.noBackupFilesDir, "ytdlnis/yt-dlp-python/bin/python")
        if (cached.exists() && cached.canExecute()) return cached
        return null
    }

    fun resolveYtDlpBinary(context: Context): File? {
        // YTDLnis stores yt-dlp under noBackupFilesDir/ytdlnis/yt-dlp
        val cached = File(context.noBackupFilesDir, "ytdlnis/yt-dlp/yt-dlp")
        if (cached.exists() && cached.canExecute()) return cached
        val fallback = File(context.filesDir, "ytdlnis/yt-dlp/yt-dlp")
        if (fallback.exists()) return fallback
        // Raw bundled fallback (if ever re-added): `res/raw/ytdlp` → files
        return null
    }

    /**
     * Minimal ProcessBuilder invocation for `--dump-json` probing.
     * Returns the raw JSON stdout or null on failure. Caller is responsible for
     * parsing and for not blocking the main thread.
     */
    fun dumpJson(context: Context, videoId: String, extraArgs: List<String> = emptyList()): String? {
        val python = resolvePythonExecutable(context) ?: return null
        val ytdlp = resolveYtDlpBinary(context) ?: return null
        val url = "https://www.youtube.com/watch?v=$videoId"
        val cmd = mutableListOf(python.absolutePath, ytdlp.absolutePath, "--dump-json", "--no-playlist", "--quiet") + extraArgs + url
        return try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            // Mirror YTDLnis env: LD_LIBRARY_PATH includes app nativeLibraryDir
            pb.environment()["LD_LIBRARY_PATH"] = buildString {
                append(context.applicationInfo.nativeLibraryDir)
                // Append plugin ld dir if available
                try {
                    val ai = context.packageManager.getApplicationInfo(YTDLNIS_PYTHON_PLUGIN, 0)
                    append(":").append(File(ai.nativeLibraryDir).absolutePath)
                } catch (_: Exception) {}
            }
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            if (code == 0 && out.isNotBlank()) out else null
        } catch (_: Exception) {
            null
        }
    }
}
