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

    /** Admin-extras key for the HA server URL the HA app's onboarding starts with (optional). */
    const val EXTRA_HA_URL = "ha_url"

    /** Admin-extras key for the QR's Wi-Fi SSID, exact and unquoted (optional). */
    const val EXTRA_WIFI_SSID = "wifi_ssid"

    /**
     * Set once the HA invite link was opened. Never cleared: every QR
     * provisioning starts from a factory reset, which wipes it.
     */
    private const val INVITE_OPENED = "invite_opened"

    /** Set once saved networks other than the QR's were cleaned up; same lifetime as above. */
    private const val WIFI_CLEANED = "wifi_cleaned"

    /** Smart Panel integration link, set by LINK; both absent when unlinked. */
    private const val LINK_REF = "link_ref"
    private const val LINK_STATUS_URL = "link_status_url"

    // Settings.Secure.USER_SETUP_COMPLETE is a hidden SystemApi constant, but
    // the key is annotated @Readable, so apps may read it by name.
    private const val USER_SETUP_COMPLETE = "user_setup_complete"

    /** Boot count at which maintenance mode was turned on; absent when off. */
    private const val MAINTENANCE_BOOT = "maintenance_boot"

    /** Provisioning entry point: store the QR extras, apply policies, install the HA app. */
    fun run(context: Context, adminExtras: PersistableBundle?) {
        persistExtras(context, adminExtras)
        if (applyPolicies(context)) InstallService.startHa(context)
    }

    /**
     * Apply every device-owner policy. Idempotent; also runs after a self-update.
     * @return false if we are not device owner (nothing applied).
     */
    fun applyPolicies(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "Not device owner — skipping policy setup")
            return false
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

        // Stay-on keeps the device awake, but PowerManager still dims the
        // screen once the screen-off timeout passes. Max it out so the panel
        // stays bright; HA blanks it explicitly (SCREEN_OFF).
        dpm.setSystemSetting(admin, Settings.System.SCREEN_OFF_TIMEOUT, Int.MAX_VALUE.toString())

        Log.i(TAG, "Kiosk policies applied")
        return true
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

    /**
     * True once the setup wizard has finished. During QR provisioning our code
     * runs mid-wizard; pinning HA then covers the wizard, setup never completes,
     * and the device boots back to the welcome screen.
     */
    fun isSetupComplete(context: Context): Boolean =
        Settings.Secure.getInt(context.contentResolver, USER_SETUP_COMPLETE, 0) == 1

    fun haApkUrl(context: Context): String? = prefs(context).getString(EXTRA_HA_APK_URL, null)

    fun haUrl(context: Context): String? = prefs(context).getString(EXTRA_HA_URL, null)

    fun isInviteOpened(context: Context): Boolean = prefs(context).getBoolean(INVITE_OPENED, false)

    fun markInviteOpened(context: Context) {
        // Synchronous: the next HOME landing must not open the invite again.
        prefs(context).edit().putBoolean(INVITE_OPENED, true).commit()
    }

    fun wifiSsid(context: Context): String? = prefs(context).getString(EXTRA_WIFI_SSID, null)

    fun isWifiCleaned(context: Context): Boolean = prefs(context).getBoolean(WIFI_CLEANED, false)

    fun markWifiCleaned(context: Context) {
        // Synchronous: the next HOME landing must not clean up again.
        prefs(context).edit().putBoolean(WIFI_CLEANED, true).commit()
    }

    /** Integration ref from LINK, or null when unlinked. */
    fun linkRef(context: Context): String? = prefs(context).getString(LINK_REF, null)

    /** Status webhook URL from LINK, or null when unlinked. */
    fun statusUrl(context: Context): String? = prefs(context).getString(LINK_STATUS_URL, null)

    fun link(context: Context, ref: String, statusUrl: String) {
        // Synchronous: the status report that follows reads these.
        prefs(context).edit().putString(LINK_REF, ref).putString(LINK_STATUS_URL, statusUrl).commit()
    }

    fun unlink(context: Context) {
        prefs(context).edit().remove(LINK_REF).remove(LINK_STATUS_URL).commit()
    }

    /**
     * Maintenance mode: HA runs unpinned and the status bar works, so Android
     * settings are reachable. Tied to the current boot: a restart ends it, so a
     * forgotten MAINTENANCE_OFF cannot leave a panel unlocked.
     */
    fun isMaintenance(context: Context): Boolean =
        prefs(context).getInt(MAINTENANCE_BOOT, -1) == bootCount(context)

    /** @return false if we are not device owner (nothing changed). */
    fun startMaintenance(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return false
        val admin = AdminReceiver.component(context)
        // Synchronous: the HA relaunch that follows reads this flag.
        prefs(context).edit().putInt(MAINTENANCE_BOOT, bootCount(context)).commit()
        // Android closes a pinned task whose package leaves the allowlist; HOME
        // (the trampoline) then reopens HA unpinned.
        dpm.setLockTaskPackages(admin, emptyArray())
        dpm.setStatusBarDisabled(admin, false)
        Log.i(TAG, "Maintenance mode on")
        return true
    }

    /** @return false if we are not device owner (nothing applied). */
    fun stopMaintenance(context: Context): Boolean {
        prefs(context).edit().remove(MAINTENANCE_BOOT).commit()
        return applyPolicies(context)
    }

    private fun bootCount(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)

    private fun prefs(context: Context) =
        context.getSharedPreferences("config", Context.MODE_PRIVATE)

    private fun persistExtras(context: Context, extras: PersistableBundle?) {
        if (extras == null) return
        prefs(context).edit().apply {
            extras.keySet().forEach { key -> putString(key, extras.getString(key)) }
            apply()
        }
    }
}
