package dk.yepzdk.hapanel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads the HA Companion (minimal flavor) APK and installs it silently
 * via PackageInstaller — no confirmation UI, since we are device owner.
 */
class InstallService : Service() {

    private val busy = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        if (busy.compareAndSet(false, true)) {
            Thread {
                try {
                    downloadAndInstall()
                } catch (e: Exception) {
                    Log.e(KioskSetup.TAG, "HA app install failed", e)
                } finally {
                    busy.set(false)
                    stopSelf()
                }
            }.start()
        }
        return START_NOT_STICKY
    }

    private fun downloadAndInstall() {
        val url = KioskSetup.haApkUrl(this) ?: DEFAULT_APK_URL
        Log.i(KioskSetup.TAG, "Downloading HA APK from $url")

        val apk = File(cacheDir, "ha.apk")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        try {
            conn.inputStream.use { input -> apk.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        Log.i(KioskSetup.TAG, "Downloaded ${apk.length()} bytes, installing")

        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply { setAppPackageName(KioskSetup.HA_PACKAGE) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("ha.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val resultIntent = Intent(ACTION_INSTALL_RESULT).setPackage(packageName)
            val pending = PendingIntent.getBroadcast(
                this,
                0,
                resultIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
        apk.delete()
    }

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Installer", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Installing Home Assistant")
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

    /** Handles the PackageInstaller result. */
    class ResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE,
            )
            if (status == PackageInstaller.STATUS_SUCCESS) {
                Log.i(KioskSetup.TAG, "HA app installed")
                KioskSetup.grantHaPermissions(context)
                // Land back in the trampoline, which pins the freshly installed app.
                context.startActivity(
                    Intent(context, HomeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } else {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.e(KioskSetup.TAG, "HA app install failed: status=$status $msg")
            }
        }
    }

    companion object {
        // ponytail: GitHub's stable latest-release URL; pin a version via the
        // QR admin extras (ha_apk_url) if an HA release ever breaks the panel.
        const val DEFAULT_APK_URL =
            "https://github.com/home-assistant/android/releases/latest/download/app-minimal-release.apk"
        const val ACTION_INSTALL_RESULT = "dk.yepzdk.hapanel.INSTALL_RESULT"
        private const val CHANNEL = "installer"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, InstallService::class.java))
        }
    }
}
