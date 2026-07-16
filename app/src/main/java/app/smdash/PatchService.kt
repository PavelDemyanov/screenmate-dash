package app.smdash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * Foreground service that (re)applies the system patch. [BootReceiver] starts it on boot /
 * MY_PACKAGE_REPLACED because [Patcher.apply]'s ~28 MB push + bind-mount can take well over a minute
 * — past a BroadcastReceiver's ~60 s budget even with `goAsync()`. Running it in the receiver ANR-
 * killed it mid-upload and left the new patch un-mounted (the app updated, but the mount stayed on
 * the previous build). A foreground service has no such deadline, so the long push+mount finishes.
 * Boot/replaced broadcasts are exempt from the FGS-from-background rule, so the start is allowed; we
 * call startForeground immediately, then do the work off-thread and stop.
 */
class PatchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ch = "smdash"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ch, "SM Dash", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, ch)
            .setContentTitle("SM Dash")
            .setContentText("Applying patch…")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        val app = applicationContext
        Thread {
            try {
                val s = Strings.of(Strings.isRu(app))
                var ok = false
                var attempt = 0
                // adbd may not be listening the instant we boot — retry briefly.
                while (!ok && attempt < 10) {
                    ok = Patcher.apply(app, s) { Log.d("SMPATCH", it) }
                    if (!ok) Thread.sleep(3000)
                    attempt++
                }
                Log.d("SMPATCH", "PatchService apply ok=$ok")
            } catch (e: Exception) {
                Log.e("SMPATCH", "PatchService", e)
            } finally {
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }
}
