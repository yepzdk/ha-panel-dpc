package dk.yepzdk.hapanel

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reports the panel's state to the Smart Panel integration: one JSON POST to
 * the status webhook persisted by LINK. No retry — the next trigger carries
 * fresh state. Silent while unlinked or not device owner.
 */
object StatusReporter {

    const val EVENT_LINK = "link"
    const val EVENT_REPORT = "report"
    const val EVENT_BOOT = "boot"
    const val EVENT_CHANGE = "change"
    const val EVENT_HEARTBEAT = "heartbeat"
    const val EVENT_UPDATED = "updated"

    private const val TIMEOUT_MS = 10_000

    /** True when a report would be sent: linked and device owner. */
    fun isActive(context: Context): Boolean {
        if (KioskSetup.linkRef(context) == null || KioskSetup.statusUrl(context) == null) return false
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    /**
     * Report from inside [BroadcastReceiver.onReceive]. Keeps the receiver
     * alive with goAsync() until the request ends, so the process is not
     * killed mid-request.
     */
    fun sendFromReceiver(receiver: BroadcastReceiver, context: Context, event: String) {
        if (!isActive(context)) return
        val pending = receiver.goAsync()
        send(context, event) { pending.finish() }
    }

    /**
     * POST the status off the main thread. [screenOff] overrides the overlay
     * check for callers that know the overlay state better than the task list
     * does (the overlay itself, while it starts or finishes). [onDone] runs
     * on the worker thread, or right away when nothing is sent.
     */
    fun send(context: Context, event: String, screenOff: Boolean? = null, onDone: () -> Unit = {}) {
        if (!isActive(context)) {
            onDone()
            return
        }
        val app = context.applicationContext
        Thread {
            try {
                post(app, event, screenOff)
            } catch (e: Exception) {
                Log.w(KioskSetup.TAG, "Status report ($event) failed", e)
            } finally {
                onDone()
            }
        }.start()
    }

    private fun post(context: Context, event: String, screenOff: Boolean?) {
        // Read again on the worker thread: an UNLINK may have landed meanwhile.
        val ref = KioskSetup.linkRef(context) ?: return
        val url = KioskSetup.statusUrl(context) ?: return
        val body = status(context, ref, event, screenOff).toString().toByteArray(Charsets.UTF_8)

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setFixedLengthStreamingMode(body.size)
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code in 200..299) {
                Log.i(KioskSetup.TAG, "Status report ($event) sent: HTTP $code")
            } else {
                Log.w(KioskSetup.TAG, "Status report ($event) rejected: HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun status(context: Context, ref: String, event: String, screenOff: Boolean?): JSONObject {
        val resolver = context.contentResolver
        val self = packageInfo(context, context.packageName)
            ?: error("Own package info unavailable")
        val brightness = try {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Settings.SettingNotFoundException) {
            null
        }
        val brightnessMode = Settings.System.getInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        return JSONObject()
            .put("ref", ref)
            .put("event", event)
            .put("app_version", self.versionName ?: JSONObject.NULL)
            .put("app_version_code", self.longVersionCode)
            .put("ha_app_version", packageInfo(context, KioskSetup.HA_PACKAGE)?.versionName ?: JSONObject.NULL)
            .put("maintenance", KioskSetup.isMaintenance(context))
            .put("screen_off", screenOff ?: ScreenOffActivity.isShowing(context))
            .put("brightness", brightness ?: JSONObject.NULL)
            .put("brightness_auto", brightnessMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("android_version", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
    }

    /** @return null when [pkg] is not installed. */
    private fun packageInfo(context: Context, pkg: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(pkg, 0)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
