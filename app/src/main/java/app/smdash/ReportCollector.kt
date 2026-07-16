package app.smdash

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import app.smdash.model.DashStore
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Builds a small, PII-free diagnostic report and POSTs it to the SM Dash report endpoint.
 * Triggered by the SM DASH panel's "Отправить отчёт" button (an `app.smdash.SENDREPORT`
 * broadcast handled in [OverlayService]). The endpoint returns a short id the user then sees
 * in the panel and can quote in Discord.
 *
 * What we send (nothing personal): app version, whether real vehicle data has arrived
 * (realSeen — the "stuck in demo" tell), patch mount status + md5, stock version, whether our
 * STATE broadcast is in logcat, Settings.Global flags, granted permissions, device model/Android,
 * and a short logcat tail for our own tags. No location, VIN, or media.
 */
object ReportCollector {
    private const val TAG = "SMREPORT"
    private const val ENDPOINT = "https://smdash-reports.vercel.app/api/report"
    // Spam gate the endpoint checks — NOT a secret (it ships in every public APK, same as the
    // approach in the LumaDICOM error-report). Rotating it just means rebuilding the app.
    private const val REPORT_KEY = "1dcb2d3af44b92e5993b33d5607ab7aa"
    // Unambiguous id alphabet (no O/0/I/1/L) so a user can read it off the screen into Discord.
    private const val ID_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /** Global keys the panel reads to reflect progress + the resulting id. */
    const val GLOBAL_REPORT_ID = "smdash_report_id"
    const val GLOBAL_REPORT_STATUS = "smdash_report_status" // "sending" | "ok" | "err"

    fun newId(): String {
        val r = SecureRandom()
        return buildString { repeat(6) { append(ID_ALPHABET[r.nextInt(ID_ALPHABET.length)]) } }
    }

    /**
     * Collect diagnostics, POST them, and return the report id on success (null on failure).
     * Blocking (network + local adbd) — call OFF the main thread.
     */
    fun send(ctx: Context, comment: String?): String? {
        val id = newId()
        val body = runCatching { buildReport(ctx, id, comment) }.getOrElse {
            Log.e(TAG, "build failed", it); JSONObject().put("reportId", id).put("buildError", it.message)
        }
        return if (post(body.toString())) id else null
    }

    private fun g(ctx: Context, key: String): String? =
        runCatching { Settings.Global.getString(ctx.contentResolver, key) }.getOrNull()

    private fun buildReport(ctx: Context, id: String, comment: String?): JSONObject {
        val pm = ctx.packageManager
        val pkg = runCatching { pm.getPackageInfo(ctx.packageName, 0) }.getOrNull()

        val data = JSONObject()
            .put("realSeen", OverlayService.realSeen)
            .put("mockRunning", OverlayService.mockActive)
            .put("style", DashStore.style.value.key)
        val ageBase = OverlayService.lastRealAtMs
        if (OverlayService.realSeen && ageBase > 0) {
            data.put("lastRealAgeMs", SystemClock.elapsedRealtime() - ageBase)
        }

        val device = JSONObject()
            .put("model", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("androidVersion", Build.VERSION.RELEASE)
            .put("build", Build.DISPLAY)

        val perms = JSONObject()
            .put("writeSecureSettings",
                ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED)
            .put("overlay", Settings.canDrawOverlays(ctx))

        val globals = JSONObject()
        for (k in listOf("smdash_hide", "smdash_style", "smdash_master", "smdash_ver", "smdash_transp")) {
            g(ctx, k)?.let { globals.put(k, it) }
        }

        // Root-adbd diagnostics (patch mount / stock version / logcat). Best-effort; if adbd is
        // unreachable the map still carries nulls + an "error" and the rest of the report is fine.
        val diag = runCatching { Patcher.collectPatchDiag(ctx) }.getOrElse { mapOf("error" to it.message) }
        val patch = JSONObject()
            .put("mounted", diag["mounted"])
            .put("match", diag["match"])
            .put("targetMd5", diag["targetMd5"])
            .put("expectedMd5", diag["expectedMd5"] ?: Patcher.PATCHED_MD5)
            .put("stockVersion", diag["stockVersion"])
            .put("stockPath", diag["stockPath"])
        diag["error"]?.let { patch.put("adbError", it) }

        return JSONObject()
            .put("reportId", id)
            .put("app", JSONObject()
                .put("version", pkg?.versionName)
                .put("build", pkg?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }))
            .put("device", device)
            .put("data", data)
            .put("patch", patch)
            .put("perms", perms)
            .put("globals", globals)
            .put("broadcastSeenInLog", diag["broadcastSeenInLog"])
            .put("logTail", diag["logTail"])
            .apply { if (!comment.isNullOrBlank()) put("comment", comment.take(500)) }
    }

    private fun post(json: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-SMDash-Report-Key", REPORT_KEY)
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(json) }
            val code = conn.responseCode
            Log.i(TAG, "POST → $code")
            code in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "POST failed", e); false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
