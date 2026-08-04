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

    /** Machine-readable "which Screenmate does each release need" manifest, kept in the repo. Served
     *  by raw.githubusercontent (no auth, no API rate limit — the same reason we resolve the latest
     *  tag from a redirect rather than the API). */
    private const val COMPAT_URL = "https://raw.githubusercontent.com/$REPO/main/compat.json"

    private const val STOCK_PKG = "co.teslogic.screenmate"

    /** Global keys the injected settings panel reads (it can't query our package directly). */
    const val GLOBAL_LATEST = "smdash_update_latest"   // version we'd install, e.g. "0.26" ("" if unknown)
    const val GLOBAL_STATUS = "smdash_update_status"   // "available"|"current"|"blocked_stock"|"downloading"|"installing"|"error"
    const val GLOBAL_NEEDS_STOCK = "smdash_update_needs_stock" // when blocked: Screenmate version required

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

    /** Component-wise numeric compare: <0 = [a] older, 0 = same, >0 = [a] newer. Handles 1.10 > 1.9. */
    private fun cmp(a: String, b: String): Int {
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    /** The Screenmate build installed on this box, e.g. "1.14" — read straight from PackageManager
     *  (no root needed; the manifest declares a <queries> entry so it stays visible on API 30+). */
    fun stockVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(STOCK_PKG, 0).versionName }.getOrNull().orEmpty()

    /** app version → required Screenmate version, from [COMPAT_URL]. null = couldn't fetch/parse. */
    private fun fetchCompat(): Map<String, String>? {
        val body = httpGet(COMPAT_URL) ?: return null
        return try {
            val arr = org.json.JSONObject(body).getJSONArray("releases")
            buildMap {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val app = o.optString("app").trim()
                    val stock = o.optString("stock").trim()
                    if (app.isNotEmpty() && stock.isNotEmpty()) put(app, stock)
                }
            }.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "compat.json parse failed", e); null
        }
    }

    /** What the updater intends to do. [offer] is the version to install (or the blocked one). */
    data class Plan(val status: String, val offer: String, val needsStock: String)

    /**
     * Decide what (if anything) to offer. The point of the compatibility gate: a release whose patch
     * targets a NEWER Screenmate than this box runs cannot mount — it would drop the dashboard into
     * demo mode on the next reboot, and Android refuses the downgrade back, so the user would have to
     * uninstall (losing their settings) to recover. So we never offer such a release; we offer the
     * newest release this box's Screenmate can actually run, and if something newer exists but is
     * gated, we say WHICH Screenmate it needs instead of showing an Update button.
     *
     * Fail-open by design: if the manifest or the stock version can't be read (offline, blocked host,
     * stock missing) we fall back to the old "offer the latest" behaviour rather than freezing updates
     * — [Patcher] still refuses to mount a mismatched patch, so that path stays safe, just less helpful.
     */
    private fun plan(ctx: Context): Plan? {
        val latest = fetchLatestTag()?.removePrefix("v") ?: return null
        val cur = currentVersion(ctx)
        val compat = fetchCompat()
        val stock = stockVersion(ctx)
        if (compat == null || stock.isEmpty()) {
            Log.i(TAG, "compat unavailable (compat=${compat != null} stock='$stock') — ungated fallback")
            return Plan(if (isNewer(latest, cur)) "available" else "current", latest, "")
        }
        // Newest release that actually exists (<= latest) AND that this box's Screenmate satisfies.
        // The "<= latest" clamp means an entry added to compat.json before its release is published
        // is harmless.
        val best = compat.keys
            .filter { cmp(it, latest) <= 0 && cmp(stock, compat.getValue(it)) >= 0 }
            .maxWithOrNull { a, b -> cmp(a, b) }
        if (best != null && isNewer(best, cur)) return Plan("available", best, "")
        val needed = compat[latest]
        if (isNewer(latest, cur) && needed != null && cmp(stock, needed) < 0) {
            return Plan("blocked_stock", latest, needed)
        }
        return Plan("current", latest, "")
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

        val pl = plan(ctx) ?: return // network hiccup: leave the last verdict untouched
        if (updating.get()) return // an update started while we were fetching — don't clobber its status
        putGlobal(ctx, GLOBAL_LATEST, pl.offer)
        putGlobal(ctx, GLOBAL_STATUS, pl.status)
        putGlobal(ctx, GLOBAL_NEEDS_STOCK, pl.needsStock)
        Log.i(TAG, "check: offer=${pl.offer} status=${pl.status} needsStock='${pl.needsStock}' " +
            "current=${currentVersion(ctx)} stock=${stockVersion(ctx)}")
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
            // Re-plan rather than trusting whatever status is sitting in Settings.Global: this action
            // is exported (the stock-process panel must reach it), so anyone can fire it, and the
            // globals are world-writable. If the compatibility gate says no, refuse here too —
            // otherwise a stray broadcast could still pull an un-mountable build onto an older box.
            val pl = plan(ctx)
            if (pl == null) {
                putGlobal(ctx, GLOBAL_STATUS, "error"); Log.w(TAG, "update: cannot resolve a target"); return
            }
            if (pl.status == "blocked_stock") {
                putGlobal(ctx, GLOBAL_STATUS, "blocked_stock")
                putGlobal(ctx, GLOBAL_NEEDS_STOCK, pl.needsStock)
                Log.w(TAG, "update refused: v${pl.offer} needs Screenmate ${pl.needsStock}, box has ${stockVersion(ctx)}")
                return
            }
            if (pl.status != "available") { putGlobal(ctx, GLOBAL_STATUS, "current"); return }
            putGlobal(ctx, GLOBAL_STATUS, "downloading")
            // Download THAT version's asset, not "latest" — on an older box the newest compatible
            // release may be several tags behind the newest release.
            if (!download(apkUrlFor(pl.offer), apk)) {
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

    /** Asset URL for a SPECIFIC release. Every tag serves its own APK (verified), which is what lets
     *  an older box be offered the newest build IT can run — so do NOT clobber old tags with the
     *  newest APK, or this silently hands everyone the newest (un-mountable) build. */
    private fun apkUrlFor(version: String): String =
        "https://github.com/$REPO/releases/download/v$version/SMDashPatcher.apk"

    /** Small HTTPS GET returning the body as text (used for the compatibility manifest). */
    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 12000
                readTimeout = 12000
                setRequestProperty("User-Agent", "SMDash-Updater")
            }
            if (conn.responseCode !in 200..299) { Log.w(TAG, "compat http ${conn.responseCode}"); return null }
            conn.inputStream.bufferedReader().use { it.readText() }.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "httpGet failed: $url", e); null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

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
