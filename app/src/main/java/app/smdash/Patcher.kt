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
    // already ships its own classes3.dex) — now with the "Отправить отчёт" (SENDREPORT) button.
    // Rebuild → update this hash. Prior: e549778… (pre-report-button).
    const val PATCHED_MD5 = "f05e05a46dfcbaf1f7287105c6f47646"

    /** guards apply()/revert() against overlapping runs (rapid taps, boot firing mid-tap, …) */
    private val running = AtomicBoolean(false)

    private fun Dadb.md5(path: String): String =
        sh("md5sum '$path' 2>/dev/null").trim().substringBefore(' ')

    /**
     * The ACTIVE stock apk path. On v1.8 this is `/data/app/~~<rand>/…-<rand>==/base.apk`, and the
     * random dir changes on EVERY stock update — so it must be resolved live, never hardcoded.
     */
    private fun Dadb.stockApkPath(): String =
        sh("pm path $STOCK_PKG 2>/dev/null").lineSequence()
            .firstOrNull { it.startsWith("package:") }?.removePrefix("package:")?.trim() ?: ""

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

            // 1. stage the patched APK in /data (once; re-push if missing/wrong). It must carry the
            //    apk_data_file SELinux context (that's what the platform_app process can mmap from
            //    /data/app) — NOT system_file, which was right only for the old /system_ext target.
            if (dadb.md5(PATCHED) != PATCHED_MD5) {
                log(s.unpacking)
                val tmp = File(ctx.cacheDir, "patched_stock.apk")
                ctx.assets.open("patched_stock.apk").use { i -> tmp.outputStream().use { i.copyTo(it) } }
                log(s.uploading)
                dadb.push(tmp, PATCHED, "644".toInt(8), System.currentTimeMillis())
                tmp.delete()
                dadb.sh("chmod 644 $PATCHED; chcon u:object_r:apk_data_file:s0 $PATCHED")
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
