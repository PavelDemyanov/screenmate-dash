package app.smdash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-applies the system patch on every boot AND whenever OUR app is updated (install-over). On boot
 * the bind-mount is ephemeral (lost on reboot) so we just re-mount; on an app UPDATE the bundled
 * `patched_stock.apk` may be newer than what's mounted, so `Patcher.apply()` re-pushes it (it md5-
 * checks and only pushes when different) and force-stops BOTH stock + settings so the new panel shows
 * — no manual "Install patch" tap or reboot needed. Either way we reconnect to the local root adbd.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = ctx.applicationContext
        val pending = goAsync()
        Thread {
            try {
                // adbd may not be listening the instant we boot — retry briefly
                val s = Strings.of(Strings.isRu(app))
                var ok = false
                var attempt = 0
                while (!ok && attempt < 10) {
                    ok = Patcher.apply(app, s) { Log.d("SMPATCH", it) }
                    if (!ok) Thread.sleep(3000)
                    attempt++
                }
                Log.d("SMPATCH", "boot apply ok=$ok")
            } catch (e: Exception) {
                Log.e("SMPATCH", "boot", e)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
