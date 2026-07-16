package app.smdash

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-update: checks GitHub for a newer release and (on the user's tap) installs it silently
 * through the device's own root adbd — no PC, no system installer UI, one tap.
 *
 * The panel in the stock settings shows the result. This runs in [OverlayService]:
 *   - [check] (cheap, automatic on service start + a throttled panel-open re-check): resolves the
 *     latest release tag WITHOUT the GitHub API — it reads the `Location` redirect of
 *     `…/releases/latest`, so there's no 60/h API rate limit — compares it to our installed
 *     versionName, and writes the verdict into `Settings.Global` (`smdash_update_*`).
 *   - [performUpdate] (only on the panel's "Update" tap): downloads the release APK and hands it to
 *     [Patcher.installApk], which `pm install -r`s it over the local root adbd. The install-over
 *     succeeds because every release is signed with the same pinned key; afterwards
 *     MY_PACKAGE_REPLACED re-applies the patch and restarts the overlay.
 */
object UpdateChecker {
    private const val TAG = "SMUPDATE"
    const val REPO = "PavelDemyanov/screenmate-dash"

    // github.com/<repo>/releases/latest 302-redirects to …/releases/tag/vX.Y — the tag is right
    // there in the Location header, so we learn the latest version without the rate-limited API.
    private const val LATEST_URL = "https://github.com/$REPO/releases/latest"

    // Stable "latest asset" URL — always serves the newest SMDashPatcher.apk (we clobber every tag).
    const val APK_URL = "https://github.com/$REPO/releases/latest/download/SMDashPatcher.apk"

    /** Global keys the injected settings panel reads (it can't query our package directly). */
    const val GLOBAL_LATEST = "smdash_update_latest"   // latest tag seen, e.g. "0.26" ("" if unknown)
    const val GLOBAL_STATUS = "smdash_update_status"   // "available" | "current" | "downloading" | "installing" | "error"

    /** Throttle automatic checks (panel-open can fire this repeatedly). */
    private const val MIN_INTERVAL_MS = 30 * 60 * 1000L
    private const val PREF = "update"
    private const val PREF_LAST_CHECK = "last_check_ms"

    /** True while performUpdate() runs — so a concurrent check() won't clobber the progress status,
     *  and a second tap can't launch a second overlapping download+install. */
    private val updating = AtomicBoolean(false)

    private fun putGlobal(ctx: Context, key: String, value: String) {
        runCatching { Settings.Global.putString(ctx.contentResolver, key, value) }
    }

    private fun getGlobal(ctx: Context, key: String): String =
        runCatching { Settings.Global.getString(ctx.contentResolver, key) }.getOrNull().orEmpty()

    fun currentVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()

    /** "v0.26"/"0.26" → [0,26]; tolerant of junk so a odd tag never crashes the compare. */
    private fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").split('.', '-', '+')
            .mapNotNull { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() }

    /** True iff [latest] is a strictly newer version than [current] (component-wise, numeric). */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parts(latest); val b = parts(current)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Resolve the latest release tag via the redirect Location (no API). Blocking — off-main-thread. */
    fun fetchLatestTag(): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false // we want to READ the redirect target, not follow it
                connectTimeout = 12000
                readTimeout = 12000
                requestMethod = "HEAD"
                setRequestProperty("User-Agent", "SMDash-Updater")
            }
            val code = conn.responseCode
            val loc = conn.getHeaderField("Location") ?: return null
            // …/releases/tag/v0.26  → "v0.26"
            if (code in 300..399) loc.substringAfterLast("/tag/", "").substringAfterLast('/').trim()
                .ifEmpty { null } else null
        } catch (e: Exception) {
            Log.w(TAG, "fetchLatestTag failed", e); null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * Local, network-free reconcile of the update status against the INSTALLED version. Run on every
     * service start (no throttle, no network): if the last-seen latest tag is not actually newer than
     * what's now installed, force the status to "current". This is what clears a "downloading"/
     * "installing" that was left stuck when a self-update replaced our process mid-install (the fresh
     * process is the new version, so there's nothing to install anymore) — even with no network.
     */
    fun reconcile(ctx: Context) {
        if (updating.get()) return // a real install is in progress — don't fight it
        val latest = getGlobal(ctx, GLOBAL_LATEST)
        if (latest.isNotEmpty() && !isNewer(latest, currentVersion(ctx))) {
            putGlobal(ctx, GLOBAL_STATUS, "current")
        }
    }

    /**
     * Check for a newer release and publish the verdict to Settings.Global. [force] bypasses the
     * throttle (used when the user explicitly opens the panel). Blocking — call off the main thread.
     */
    fun check(ctx: Context, force: Boolean = false) {
        if (updating.get()) return // don't overwrite downloading/installing status mid-update
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong(PREF_LAST_CHECK, 0) < MIN_INTERVAL_MS) return
        p.edit().putLong(PREF_LAST_CHECK, now).apply()

        val latest = fetchLatestTag() ?: return // network hiccup: leave the last verdict untouched
        if (updating.get()) return // an update started while we were fetching — don't clobber its status
        val current = currentVersion(ctx)
        putGlobal(ctx, GLOBAL_LATEST, latest.removePrefix("v"))
        val status = if (isNewer(latest, current)) "available" else "current"
        putGlobal(ctx, GLOBAL_STATUS, status)
        Log.i(TAG, "check: latest=$latest current=$current -> $status")
    }

    /**
     * Download the latest APK and install it over the local root adbd (silent, same signing key).
     * Publishes progress into Settings.Global. Blocking — call off the main thread.
     */
    fun performUpdate(ctx: Context) {
        // ACTION_DO_UPDATE is exported (the stock-process panel sends it), so ANY app could broadcast
        // it. The guard below stops a second concurrent install; the verifyApk() gate below is what
        // makes triggering it harmless — we only ever pm-install a file that IS this package, signed
        // with OUR key. A single-flight guard also prevents two downloads racing on the same cache file.
        if (!updating.compareAndSet(false, true)) { Log.i(TAG, "update already running"); return }
        val apk = File(ctx.cacheDir, "smdash_update.apk")
        try {
            putGlobal(ctx, GLOBAL_STATUS, "downloading")
            if (!download(APK_URL, apk)) {
                putGlobal(ctx, GLOBAL_STATUS, "error")
                Log.w(TAG, "download failed"); return
            }
            // SECURITY GATE: never pm-install a file we didn't verify. PackageManager only enforces the
            // same-key rule when REINSTALLING over app.smdash; a foreign package name would install as a
            // brand-new app with NO signature check. So require BOTH: packageName == ours AND the apk is
            // signed with OUR exact certificate. A tampered/wrong asset fails here → status "error".
            if (!verifyApk(ctx, apk)) {
                putGlobal(ctx, GLOBAL_STATUS, "error")
                Log.w(TAG, "downloaded apk failed package/signature verification — refusing to install")
                return
            }
            putGlobal(ctx, GLOBAL_STATUS, "installing")
            // pm install over root adbd. This kills our process mid-install (we're replacing ourselves),
            // so we may never see the result here — that's fine: MY_PACKAGE_REPLACED restarts us, and the
            // fresh version's reconcile() (service start) flips the stuck "installing" back to "current".
            val ok = runCatching { Patcher.installApk(ctx, apk) { Log.i(TAG, it) } }.getOrDefault(false)
            if (!ok) {
                putGlobal(ctx, GLOBAL_STATUS, "error")
                Log.w(TAG, "install failed")
            }
        } finally {
            runCatching { apk.delete() }
            updating.set(false)
        }
    }

    /**
     * Verify a downloaded APK is genuinely THIS package signed with OUR certificate — the gate before
     * a silent root install. Returns false on any mismatch or parse error (fail closed).
     */
    private fun verifyApk(ctx: Context, apk: File): Boolean {
        return try {
            val pm = ctx.packageManager
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return false
            if (archive.packageName != ctx.packageName) return false
            val archiveSigs = archive.signingInfo?.apkContentsSigners ?: return false
            val own = pm.getPackageInfo(ctx.packageName, flags).signingInfo?.apkContentsSigners ?: return false
            val ownHex = own.map { it.toByteArray().toHexString() }.toSet()
            archiveSigs.isNotEmpty() && archiveSigs.all { it.toByteArray().toHexString() in ownHex }
        } catch (e: Exception) {
            Log.w(TAG, "verifyApk failed", e); false
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Straight HTTPS GET to a file, following GitHub's redirect to the asset CDN. */
    private fun download(url: String, dst: File): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 60000
                setRequestProperty("User-Agent", "SMDash-Updater")
            }
            if (conn.responseCode !in 200..299) { Log.w(TAG, "http ${conn.responseCode}"); return false }
            conn.inputStream.use { input -> dst.outputStream().use { out -> input.copyTo(out) } }
            dst.length() > 1_000_000 // a real APK is tens of MB; guard against an error page
        } catch (e: Exception) {
            Log.w(TAG, "download error", e); false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
