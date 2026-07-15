package dk.yepzdk.hapanel

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import android.util.Log

/**
 * All device-owner policy setup, shared by every provisioning path.
 * Idempotent — safe to run more than once.
 */
object KioskSetup {

    const val TAG = "HaPanelDpc"
    const val HA_PACKAGE = "io.homeassistant.companion.android.minimal"

    /** Admin-extras key for the HA APK download URL (optional QR override). */
    const val EXTRA_HA_APK_URL = "ha_apk_url"

    fun run(context: Context, adminExtras: PersistableBundle?) {
        persistExtras(context, adminExtras)

        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "Not device owner — skipping policy setup")
            return
        }
        val admin = AdminReceiver.component(context)

        dpm.setLockTaskPackages(admin, arrayOf(HA_PACKAGE, context.packageName))
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)

        // Every path to HOME (boot, crash, app update) lands in our trampoline.
        val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addPersistentPreferredActivity(
            admin,
            homeFilter,
            ComponentName(context, HomeActivity::class.java),
        )

        dpm.setKeyguardDisabled(admin, true)
        dpm.setStatusBarDisabled(admin, true)

        // Wall panel on a charger: never sleep. Must not be combined with
        // setMaximumTimeToLock — the two conflict.
        val plugged = BatteryManager.BATTERY_PLUGGED_AC or
            BatteryManager.BATTERY_PLUGGED_USB or
            BatteryManager.BATTERY_PLUGGED_WIRELESS
        dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, plugged.toString())

        Log.i(TAG, "Kiosk policies applied")
    }

    /** Grant the HA app its runtime permissions. Call after the app is installed. */
    fun grantHaPermissions(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val admin = AdminReceiver.component(context)
        if (Build.VERSION.SDK_INT >= 33) {
            dpm.setPermissionGrantState(
                admin,
                HA_PACKAGE,
                "android.permission.POST_NOTIFICATIONS",
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        }
    }

    fun haApkUrl(context: Context): String? =
        context.getSharedPreferences("config", Context.MODE_PRIVATE)
            .getString(EXTRA_HA_APK_URL, null)

    private fun persistExtras(context: Context, extras: PersistableBundle?) {
        if (extras == null) return
        val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
        prefs.edit().apply {
            extras.keySet().forEach { key -> putString(key, extras.getString(key)) }
            apply()
        }
    }
}
