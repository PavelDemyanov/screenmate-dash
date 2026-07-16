package app.smdash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Re-applies the system patch on every boot AND whenever OUR app is updated (install-over). On boot
 * the bind-mount is ephemeral (lost on reboot) so we just re-mount; on an app UPDATE the bundled
 * `patched_stock.apk` may be newer than what's mounted, so [Patcher.apply] re-pushes it (it md5-
 * checks and only pushes when different) and force-stops BOTH stock + settings so the new panel shows
 * — no manual "Install patch" tap or reboot needed.
 *
 * The work runs in [PatchService], NOT here: a fresh patch push+mount can take >60 s (the ~28 MB
 * dadb push), which blows a BroadcastReceiver's time budget even with `goAsync()` and got the
 * receiver ANR-killed mid-upload, leaving the new patch un-mounted. A foreground service has no such
 * deadline. Boot/replaced broadcasts are on the FGS-from-background temporary allowlist, so starting
 * one from here is permitted.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        runCatching {
            ContextCompat.startForegroundService(
                ctx.applicationContext, Intent(ctx.applicationContext, PatchService::class.java),
            )
        }.onFailure { Log.e("SMPATCH", "startForegroundService(PatchService) failed", it) }
    }
}
