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
 * Downloads an APK and installs it silently via PackageInstaller — no
 * confirmation UI, since we are device owner. Two targets: the HA Companion
 * app (minimal flavor), and this app itself (over-the-air update).
 *
 * The session is locked to the target's package name, and Android only
 * accepts an update signed with the installed app's key, so a wrong URL
 * cannot install anything else.
 */
class InstallService : Service() {

    private val busy = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val self = intent?.getStringExtra(EXTRA_TARGET) == TARGET_SELF
        startForegroundWithNotification(self)
        if (busy.compareAndSet(false, true)) {
            val url = if (self) intent?.getStringExtra(EXTRA_URL) ?: PANEL_APK_URL
            else KioskSetup.haApkUrl(this) ?: HA_APK_URL
            val pkg = if (self) packageName else KioskSetup.HA_PACKAGE
            Thread {
                try {
                    downloadAndInstall(url, pkg, self)
                } catch (e: Exception) {
                    Log.e(KioskSetup.TAG, "Install of $pkg failed", e)
                } finally {
                    busy.set(false)
                    stopSelf()
                }
            }.start()
        } else {
            Log.w(KioskSetup.TAG, "Install already running — ignoring request")
        }
        return START_NOT_STICKY
    }

    private fun downloadAndInstall(url: String, pkg: String, self: Boolean) {
        Log.i(KioskSetup.TAG, "Downloading $pkg from $url")

        val apk = File(cacheDir, "install.apk")
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
        ).apply { setAppPackageName(pkg) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("app.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val resultIntent = Intent(ACTION_INSTALL_RESULT)
                .setPackage(packageName)
                .putExtra(EXTRA_TARGET, if (self) TARGET_SELF else TARGET_HA)
            val pending = PendingIntent.getBroadcast(
                this,
                if (self) 1 else 0,
                resultIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            // A self-update kills this process on commit; the new version
            // carries on in PackageReplacedReceiver.
            session.commit(pending.intentSender)
        }
        apk.delete()
    }

    private fun startForegroundWithNotification(self: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Installer", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (self) "Updating panel" else "Installing Home Assistant")
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
            val target = intent.getStringExtra(EXTRA_TARGET)
            val status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE,
            )
            if (status != PackageInstaller.STATUS_SUCCESS) {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.e(KioskSetup.TAG, "Install of $target failed: status=$status $msg")
                return
            }
            // Self-update success is handled by PackageReplacedReceiver in the new version.
            if (target != TARGET_HA) return

            Log.i(KioskSetup.TAG, "HA app installed")
            KioskSetup.grantHaPermissions(context)
            // Land back in the trampoline, which pins the freshly installed app.
            // Not while provisioning: the setup wizard is still running, and
            // covering it means setup never completes. The wizard lands on
            // HOME (the trampoline) itself when it finishes.
            if (KioskSetup.isSetupComplete(context)) {
                context.startActivity(
                    Intent(context, HomeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    companion object {
        // ponytail: GitHub's stable latest-release URL; pin a version via the
        // QR admin extras (ha_apk_url) if an HA release ever breaks the panel.
        const val HA_APK_URL =
            "https://github.com/home-assistant/android/releases/latest/download/app-minimal-release.apk"

        /** This app's latest release — the same URL the provisioning QR points at. */
        const val PANEL_APK_URL =
            "https://github.com/yepzdk/ha-panel-dpc/releases/latest/download/ha-panel-dpc.apk"

        const val ACTION_INSTALL_RESULT = "dk.yepzdk.hapanel.INSTALL_RESULT"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_URL = "url"
        private const val TARGET_HA = "ha"
        private const val TARGET_SELF = "self"
        private const val CHANNEL = "installer"

        fun startHa(context: Context) {
            context.startForegroundService(Intent(context, InstallService::class.java))
        }

        /** Update this app from [url], or from the latest release when null. */
        fun startSelf(context: Context, url: String?) {
            context.startForegroundService(
                Intent(context, InstallService::class.java)
                    .putExtra(EXTRA_TARGET, TARGET_SELF)
                    .putExtra(EXTRA_URL, url),
            )
        }
    }
}
