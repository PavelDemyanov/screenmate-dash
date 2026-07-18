package app.smdash

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Applies (and re-applies) the system patch by talking to the device's OWN root adbd over
 * localhost — the only root path on this locked device (bootloader locked, dm-verity enforcing,
 * no Magisk, but adbd runs as root and `persist.adb.tcp.port=5555` survives reboot).
 *
 * It bind-mounts the patched stock dashboard APK over the ACTIVE stock APK (ephemeral, so it must
 * be re-applied every boot), grants our overlay its perms, and starts everything. Idempotent: safe
 * to call repeatedly (boot + manual + MY_PACKAGE_REPLACED).
 *
 * v0.16 — re-based onto stock **v1.8**. The stock auto-updated and became an UPDATED_SYSTEM_APP:
 * the live code now lives at `/data/app/<random>/base.apk` (the frozen `/system_ext` copy is a
 * hidden package the system no longer loads). So we resolve that path LIVE via `pm path` (its dir
 * changes on every stock update) and bind-mount over it. The AOT odex sitting next to it is bypassed
 * automatically: our rebuilt dex checksums don't match the stale odex, so ART discards it and loads
 * dex straight from the bind-mounted apk (verified on-device: the process maps
 * `[anon:dalvik-classes.dex extracted in memory from …/base.apk]`, no base.odex mapped).
 */
object Patcher {
    private const val TAG = "SMPATCH"
    private const val STOCK_PKG = "co.teslogic.screenmate"
    private const val WORK = "/data/local/tmp/smdash"
    private const val PATCHED = "$WORK/patched_stock.apk"
    private const val BACKUP = "$WORK/orig_stock.apk"
    // md5 of assets/patched_stock.apk — the v1.8-based build:
    // КМ/Ч (both const-string sites) + smEmit (DashboardState broadcast + backstop hide) +
    // SmHideObserver (instant hide) + SmdashPanel settings-block (classes4.dex, since stock v1.8
    // already ships its own classes3.dex) — now with the "Send report" (SENDREPORT) button.
    // Rebuild → update this hash. Prior: d2a5c01f… (report button, КМ/Ч), 3690611e… (KPH+update, pre-review).
    // 359fd3ad… = v0.26: stock speed label English "KPH" + panel "Update to vX" button (inert-busy fix).
    // 0a608da0… = v0.30 (1.8-based, panel + Temp style).
    // 26a21ad0… = v0.32: re-based onto stock **1.9** (hooks re-ported; stock moved back to /system_ext).
    const val PATCHED_MD5 = "26a21ad0749eab51ca0529e3804bf238"

    // The patch is built for stock Screenmate v1.8 (its smali hooks are ported onto v1.8's code).
    // On an older stock (e.g. 1.7) it would mount but silently fail — the v1.8 data hook never fires,
    // so the overlay sits in the demo animation forever (seen in a real 1.7 user's diagnostic report).
    // We refuse to mount unless the stock version matches, and tell the user to update Screenmate.
    // Bump this string when the patch is re-based onto a newer stock.
    private const val REQUIRED_STOCK_PREFIX = "1.9"

    /** guards apply()/revert() against overlapping runs (rapid taps, boot firing mid-tap, …) */
    private val running = AtomicBoolean(false)

    /** Compare a stock versionName against [REQUIRED_STOCK_PREFIX], component-wise numeric:
     *  <0 = older (e.g. 1.7 vs 1.8), >0 = newer (e.g. 1.9 vs 1.8), 0 = same line. */
    private fun stockVsRequired(ver: String): Int {
        fun parts(v: String) = v.trim().split('.', '-').map { seg -> seg.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(ver); val b = parts(REQUIRED_STOCK_PREFIX)
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    private fun Dadb.md5(path: String): String =
        sh("md5sum '$path' 2>/dev/null").trim().substringBefore(' ')

    /**
     * The ACTIVE stock apk path. On v1.8 this is `/data/app/~~<rand>/…-<rand>==/base.apk`, and the
     * random dir changes on EVERY stock update — so it must be resolved live, never hardcoded.
     */
    private fun Dadb.stockApkPath(): String =
        sh("pm path $STOCK_PKG 2>/dev/null").lineSequence()
            .firstOrNull { it.startsWith("package:") }?.removePrefix("package:")?.trim() ?: ""

    /** The stock Screenmate versionName, e.g. "1.8". PM reports the ORIGINAL scanned manifest even
     *  when our patch is bind-mounted over it, so this is the device's real stock version either way. */
    private fun Dadb.stockVersion(): String =
        sh("dumpsys package $STOCK_PKG 2>/dev/null | grep -m1 versionName")
            .substringAfter("versionName=", "").trim().substringBefore(' ')

    /**
     * Ensure the stock's own "Show Dashboard" setting is ON. Our vehicle-data hook (`smEmit`) lives
     * inside the stock `DashboardView`, which the stock only creates when its `dashboard-visible`
     * pref is true. A user who turned the stock dashboard OFF *before* installing (e.g. because they
     * didn't like its UI) would otherwise get NO real data — the overlay sits in the demo animation
     * forever (a real user hit exactly this). Forcing it ON only enables the DATA source; our own
     * `SmHideObserver` still hides the stock's rendered view, so the user sees OUR overlay, not the
     * stock UI they disliked. We flip only an explicit `false` → `true` (the stock default is already
     * visible) and edit the file IN PLACE via `cat >` so the stock's owner + SELinux context are
     * preserved (recreating the inode would change them and the stock could no longer read it).
     * Verified on-device: the flip preserves `u10_a177` + `...:c522,c768` and the stock honours it.
     */
    private fun Dadb.enableStockDashboard() {
        val f = "/data/user/10/$STOCK_PKG/shared_prefs/SETTINGS_REPO.xml"
        val needs = sh("[ -f '$f' ] && grep -q 'dashboard-visible\" value=\"false\"' '$f' && echo hit")
        if (!needs.contains("hit")) return // no file, or already visible → nothing to do
        val tmp = "$WORK/sr.tmp"
        sh("mkdir -p $WORK; sed 's/\\(dashboard-visible\" value=\"\\)false\"/\\1true\"/' '$f' > '$tmp' && cat '$tmp' > '$f' && rm -f '$tmp'")
    }

    /** The fixed adb keypair shipped in assets; copied to filesDir so dadb can read it as Files. */
    private fun keyPair(ctx: Context): AdbKeyPair {
        val priv = File(ctx.filesDir, "adbkey")
        val pub = File(ctx.filesDir, "adbkey.pub")
        if (!priv.exists()) ctx.assets.open("adbkey").use { i -> priv.outputStream().use { i.copyTo(it) } }
        if (!pub.exists()) ctx.assets.open("adbkey.pub").use { i -> pub.outputStream().use { i.copyTo(it) } }
        return AdbKeyPair.read(priv, pub)
    }

    private fun Dadb.sh(cmd: String): String = shell(cmd).output

    /**
     * Connect to local root adbd and bring the patch up. [log] receives progress lines.
     * Returns true on success. Runs blocking — call off the main thread.
     */
    fun apply(ctx: Context, s: Strings, log: (String) -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) { log(s.busy); return false } // no overlapping runs
        var dadb: Dadb? = null
        try {
            log(s.connecting)
            dadb = Dadb.create("127.0.0.1", 5555, keyPair(ctx))
            val id = dadb.sh("id").trim()
            if (!id.contains("uid=0")) { log(s.noRootPrefix + id); return false }
            log(s.rootOk)

            // Make sure the stock's own dashboard is enabled so its DashboardView (our data source)
            // runs — otherwise the overlay is stuck in the demo animation. Runs on EVERY apply (before
            // the stock is force-stopped + restarted below), so it fixes both a fresh install with the
            // toggle off AND an already-stuck box on its next boot (boot always takes the full path).
            dadb.enableStockDashboard()

            // Resolve the ACTIVE stock apk (its /data/app dir changes on every stock update).
            val target = dadb.stockApkPath()
            // GUARD: only ever touch a real Screenmate — the stock dashboard APK must be there
            if (target.isEmpty() || !dadb.sh("[ -e '$target' ] && echo ok").contains("ok")) {
                log(s.notFound); return false
            }

            dadb.sh("mkdir -p $WORK")

            // already patched? re-ensure grants/overlay (idempotent) and stop — NO re-mount (no stacking)
            if (dadb.md5(target) == PATCHED_MD5) {
                ensureGrantsAndStart(dadb)
                log(s.alreadyActive)
                return true
            }

            // GUARD: only mount on the stock version this patch is built for. On an older stock the
            // apk mounts but the data hook never fires → the overlay is stuck in the demo animation
            // (a real 1.7 user hit exactly this). Refuse and tell them to update Screenmate. This runs
            // only on a fresh (not-yet-mounted) apply, so it never disturbs a working install.
            // Block only a CONFIRMED wrong version; if the read comes back empty (a dumpsys hiccup),
            // proceed rather than falsely block a legit 1.8 install — an empty read isn't evidence.
            val ver = dadb.stockVersion()
            if (ver.isNotEmpty() && !ver.startsWith(REQUIRED_STOCK_PREFIX)) {
                // Direction matters: a user BELOW 1.8 (e.g. 1.7) should update Screenmate to 1.8; a user
                // ABOVE it (e.g. 1.9 — Screenmate shipped a newer stock) can't "downgrade to 1.8", so
                // tell them SM Dash itself needs an update to support their newer stock.
                if (stockVsRequired(ver) < 0) log(s.wrongStockOldPrefix + ver + s.wrongStockOldSuffix)
                else log(s.wrongStockNewPrefix + ver + s.wrongStockNewSuffix)
                return false
            }

            // 1. stage the patched APK in /data (once; re-push if missing/wrong). It must carry the
            //    SAME SELinux context as the TARGET so the platform_app process can mmap it once bound.
            //    That context depends on where the stock lives, and it MOVES between stock versions:
            //    v1.8 was at /data/app (apk_data_file); v1.9 moved back to /system_ext (system_file).
            //    So we read the live target's context and copy it, instead of hardcoding either one.
            if (dadb.md5(PATCHED) != PATCHED_MD5) {
                log(s.unpacking)
                val tmp = File(ctx.cacheDir, "patched_stock.apk")
                ctx.assets.open("patched_stock.apk").use { i -> tmp.outputStream().use { i.copyTo(it) } }
                log(s.uploading)
                dadb.push(tmp, PATCHED, "644".toInt(8), System.currentTimeMillis())
                tmp.delete()
                val tctx = dadb.sh("ls -Zd '$target' 2>/dev/null").trim()
                    .split(Regex("\\s+")).firstOrNull { it.count { c -> c == ':' } >= 3 }
                    ?: "u:object_r:system_file:s0"
                dadb.sh("chmod 644 $PATCHED; chcon '$tctx' $PATCHED")
            }
            // integrity gate: never mount a corrupt file — that would break the stock dashboard
            if (dadb.md5(PATCHED) != PATCHED_MD5) { log(s.corrupt); return false }

            // 2. back up the pristine original once (target is verified not-patched here)
            dadb.sh("[ -f $BACKUP ] || cp '$target' $BACKUP")

            // 3. apply the bind-mount; verify, and ROLL BACK on failure so the stock stays intact.
            // Peel ANY existing patch layer FIRST. A plain umount is EBUSY here — system_server keeps
            // the live apk mmap'd — so replacing an already-mounted (older) patch by a straight
            // `umount; mount` silently fails (the old layer stays; the new bind stacks wrong), which
            // then spun the boot re-apply into 10 failing retries → an ANR that killed us. Use LAZY
            // umount in a loop until the md5 stops changing (pristine reached), then bind the new one.
            log(s.applying)
            dadb.sh(
                "p=; n=0; while [ \$n -lt 20 ]; do m=\$(md5sum '$target' | cut -d' ' -f1); " +
                    "[ \"\$m\" = \"\$p\" ] && break; p=\$m; " +
                    "umount '$target' 2>/dev/null; umount -l '$target' 2>/dev/null; n=\$((n+1)); done",
            )
            dadb.sh("mount -o bind $PATCHED '$target'")
            if (dadb.md5(target) != PATCHED_MD5) {
                dadb.sh("umount -l '$target' 2>/dev/null")
                log(s.mountFailed); return false
            }
            // Reload the patched dex WITHOUT popping the settings UI on every boot (users complained
            // the settings page slides open on restart — it broke their MacroDroid automations).
            // Force-stop the stock (its dashboard + settings) and com.android.settings so both drop the
            // stale dex; the injected SM DASH panel then re-maps whenever the user next opens settings.
            // We can't bring the dashboard back with `am start …MainActivity` — that IS the stock's
            // launcher = the settings screen, so it foregrounds the panel every boot (the exact bug) —
            // and `am start-foreground-service` is blocked by Android 14's FGS-from-background rule.
            // Instead re-deliver BOOT_COMPLETED to the stock's own boot receiver, which starts
            // `.services.AppService` as a foreground service (allowed from a boot context): the dashboard
            // process comes back RESIDENT and broadcasting with NO visible Activity, and the settings
            // process stays available — the on-screen gear opens it on demand.
            // --include-stopped-packages is required because force-stop left the package stopped.
            dadb.sh("am force-stop $STOCK_PKG; am force-stop com.android.settings")
            dadb.sh("am broadcast --user 10 -a android.intent.action.BOOT_COMPLETED -p $STOCK_PKG --include-stopped-packages")
            // Safety net: if the boot broadcast didn't spawn the stock (protected-broadcast refusal /
            // receiver missing / FGS refused), fall back to launching the activity so the dashboard still
            // comes up. That fallback DOES show settings — but a working dashboard beats a blank one, and
            // it bounds the worst case to the OLD behaviour rather than a data-less boot.
            Thread.sleep(1500)
            if (dadb.sh("pidof $STOCK_PKG").trim().isEmpty()) {
                dadb.sh("am start --user 10 -n $STOCK_PKG/.settings.ui.activity.MainActivity")
            }

            ensureGrantsAndStart(dadb)
            log(s.done)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "apply failed", e)
            log(s.errorPrefix + e.message)
            return false
        } finally {
            runCatching { dadb?.close() }
            running.set(false)
        }
    }

    /**
     * Read-only patch/stock diagnostics over the local root adbd — for the in-app "Отправить отчёт".
     * Never mutates anything. Returns a map (best-effort; missing keys stay null on error) with:
     * whether the patched apk is actually mounted (md5 vs [PATCHED_MD5]), the stock Screenmate
     * version, whether our STATE broadcast appears in logcat, and a short relevant log tail.
     * Runs blocking — call off the main thread.
     */
    fun collectPatchDiag(ctx: Context): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>(
            "expectedMd5" to PATCHED_MD5, "mounted" to null, "match" to null, "targetMd5" to null,
            "stockVersion" to null, "stockPath" to null, "broadcastSeenInLog" to null, "logTail" to null,
        )
        var dadb: Dadb? = null
        try {
            dadb = Dadb.create("127.0.0.1", 5555, keyPair(ctx))
            val target = dadb.stockApkPath()
            out["stockPath"] = target.ifEmpty { null }
            if (target.isNotEmpty()) {
                val md5 = dadb.md5(target)
                out["targetMd5"] = md5.ifEmpty { null }
                out["mounted"] = md5.isNotEmpty()
                out["match"] = md5 == PATCHED_MD5
            }
            out["stockVersion"] = dadb.sh("dumpsys package $STOCK_PKG 2>/dev/null | grep -m1 versionName")
                .substringAfter("versionName=", "").trim().ifEmpty { null }
            val log = dadb.sh("logcat -d -t 800 2>/dev/null")
            out["broadcastSeenInLog"] = log.contains("app.smdash.STATE")
            out["logTail"] = log.lineSequence()
                .filter { l -> l.contains("SMPATCH") || l.contains("app.smdash") || l.contains("smEmit") || l.contains("AndroidRuntime") }
                .toList().takeLast(40).joinToString("\n").take(4000).ifEmpty { null }
        } catch (e: Exception) {
            out["error"] = e.message
        } finally {
            runCatching { dadb?.close() }
        }
        return out
    }

    /**
     * Install an APK over the local root adbd (silent — no system installer UI). Used by
     * [UpdateChecker] to self-update: the file is our own newer release, signed with the same pinned
     * key, so `pm install -r` reinstalls in place. NOTE: reinstalling app.smdash kills THIS process
     * partway, so the dadb shell may not return "Success" — the install still completes server-side,
     * and MY_PACKAGE_REPLACED brings us back. Blocking — call off the main thread.
     */
    fun installApk(ctx: Context, apk: File, log: (String) -> Unit): Boolean {
        var dadb: Dadb? = null
        try {
            dadb = Dadb.create("127.0.0.1", 5555, keyPair(ctx))
            if (!dadb.sh("id").contains("uid=0")) { log("no root adbd"); return false }
            val remote = "$WORK/update.apk"
            dadb.sh("mkdir -p $WORK")
            log("pushing apk")
            dadb.push(apk, remote, "644".toInt(8), System.currentTimeMillis())
            dadb.sh("chmod 644 $remote")
            log("pm install")
            // -r reinstall keeping data. NO -d (allow-downgrade): a self-update only moves forward, and
            // -d would strip PackageManager's anti-rollback guard. The shell response may be cut off when
            // our process dies mid-install — treat a thrown/killed call as "in progress", not failure;
            // the fresh version reconciles the status on restart.
            val out = runCatching { dadb.sh("pm install -r $remote 2>&1") }.getOrDefault("")
            log("pm result: ${out.trim().take(200)}")
            // Success OR an empty/cut-off response (we were killed) both count as "went through".
            return out.contains("Success", true) || out.isBlank()
        } catch (e: Exception) {
            // A dead socket here usually means the install killed us — the update is really happening.
            Log.i(TAG, "installApk connection dropped (likely mid-install)", e)
            return true
        } finally {
            runCatching { dadb?.close() }
        }
    }

    /** Grant our overlay its perms + show it. Idempotent — safe to call every time. */
    private fun ensureGrantsAndStart(dadb: Dadb) {
        dadb.sh("pm grant --user 10 app.smdash android.permission.WRITE_SECURE_SETTINGS")
        dadb.sh("appops set --user 10 app.smdash SYSTEM_ALERT_WINDOW allow")
        // Start with show_ours=true so a boot / install always brings OUR dashboard up. Do NOT
        // force smdash_hide here — the service owns that flag and reconciles it on start; forcing
        // it here is exactly what let the flag disagree with the service and blank the screen.
        dadb.sh("am start-foreground-service --user 10 -n app.smdash/.OverlayService --ez show_ours true")
    }

    /** Remove the patch: unmount (reverts to the pristine original) and hide our overlay. */
    fun revert(ctx: Context, s: Strings, log: (String) -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) { log(s.busy); return false } // no overlapping runs
        var dadb: Dadb? = null
        try {
            log(s.revertConnecting)
            dadb = Dadb.create("127.0.0.1", 5555, keyPair(ctx))
            if (!dadb.sh("id").contains("uid=0")) { log(s.revertNoRoot); return false }
            val target = dadb.stockApkPath()
            if (target.isEmpty() || !dadb.sh("[ -e '$target' ] && echo ok").contains("ok")) {
                log(s.notFound); return false
            }
            dadb.sh("settings put global smdash_hide 0")
            dadb.sh("am broadcast --user 10 -a app.smdash.SHOWSTOCK") // pull our overlay down too
            log(s.removingMounts)
            // Unmount every stacked layer. A plain `umount` returns EBUSY here because system_server
            // (unkillable) keeps the priv-app APK mmap'd — force-stopping the stock app does NOT release
            // it, so a plain umount never succeeds on a live system and the patch stayed mounted (the
            // SM DASH settings panel lingered after "Remove patch"). Fix: try a clean umount, then fall
            // back to a LAZY `umount -l`, which detaches the mount from the namespace immediately (the
            // path resolves to the pristine underlying file right away) even while a holder lingers.
            // The loop peels any stacked binds until the pristine md5 shows.
            dadb.sh(
                "n=0; while [ \$n -lt 30 ]; do am force-stop $STOCK_PKG; " +
                    "umount '$target' 2>/dev/null; umount -l '$target' 2>/dev/null; " +
                    "m=\$(md5sum '$target' | cut -d' ' -f1); [ \"\$m\" != \"$PATCHED_MD5\" ] && break; n=\$((n+1)); done",
            )
            val left = dadb.sh("md5sum '$target' 2>/dev/null")
            // Reload the settings UI so the injected SM DASH panel disappears: its classes4.dex is loaded
            // by both the stock app AND com.android.settings, so force-stop both, then reopen the stock
            // settings (same two-process reload the apply path needs to make the panel appear).
            dadb.sh("am force-stop com.android.settings")
            dadb.sh("am start --user 10 -n $STOCK_PKG/.settings.ui.activity.MainActivity")
            if (left.contains(PATCHED_MD5)) { log(s.layerRemains); return false }
            log(s.revertedPrefix + BACKUP)
            return true
        } catch (e: Exception) {
            log(s.errorPrefix + e.message); return false
        } finally {
            runCatching { dadb?.close() }
            running.set(false)
        }
    }

    /**
     * Hard reset (recovery for a wedged state — e.g. a stuck double dashboard or a stale settings
     * panel). Does NOT touch the mount: it just force-stops the stock package (its dashboard + its
     * settings UI) and `com.android.settings`, so their code reloads fresh from the still-mounted
     * patched APK, then re-grants our perms, brings OUR overlay back up, and reopens the stock menu.
     */
    fun hardReset(ctx: Context, s: Strings, log: (String) -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) { log(s.busy); return false }
        var dadb: Dadb? = null
        try {
            log(s.hardResetting)
            dadb = Dadb.create("127.0.0.1", 5555, keyPair(ctx))
            val id = dadb.sh("id").trim()
            if (!id.contains("uid=0")) { log(s.noRootPrefix + id); return false }
            // unload the stock dashboard, its settings UI, and the settings host from memory
            dadb.sh("am force-stop $STOCK_PKG")
            dadb.sh("am force-stop com.android.settings")
            // re-grant + bring OUR overlay back up (reconciles the hide flag), then reload the stock menu
            ensureGrantsAndStart(dadb)
            dadb.sh("am start --user 10 -n $STOCK_PKG/.settings.ui.activity.MainActivity")
            log(s.hardResetDone)
            return true
        } catch (e: Exception) {
            log(s.errorPrefix + e.message); return false
        } finally {
            runCatching { dadb?.close() }
            running.set(false)
        }
    }
}
