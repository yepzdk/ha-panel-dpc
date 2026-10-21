package dk.yepzdk.hapanel

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * Forget every saved Wi-Fi network except the QR's (`wifi_ssid`), once per
 * provisioning. The setup wizard may ask for a Wi-Fi before the QR scanner
 * opens; a network picked there (e.g. an isolated guest network) stays saved,
 * and after a restart the panel can rejoin it instead of the QR network and
 * lose HA. Runs at the first HOME landing after the wizard, when every
 * network added during setup is known.
 *
 * Platform rules (AOSP, Android 9 to current, WifiServiceImpl /
 * WifiConfigManager / WifiPermissionsUtil):
 * - removeNetwork: CHANGE_WIFI_STATE; from Android 10 only for apps targeting
 *   < Q or "privileged" callers, which includes the device owner
 *   (isTargetSdkLessThanQOrPrivileged). WifiConfigManager.canModifyNetwork
 *   lets the device owner modify any network, not only its own.
 * - getConfiguredNetworks: ACCESS_WIFI_STATE, the same device-owner check,
 *   and enforceCanAccessScanResults, which has no device-owner bypass: it
 *   needs location mode on and the location permission (COARSE on Android 9,
 *   FINE from 10). Without them the list is silently empty. So the DPC grants
 *   itself both for the cleanup. On Android 12+ that works unless
 *   provisioning opted out of sensor permission grants, which our QR does not
 *   (DevicePolicyManagerService.setPermissionGrantState). Where the wizard
 *   left location off, it is switched on only for the cleanup (API 30+,
 *   setLocationEnabled), and below API 30 the cleanup is skipped.
 */
object WifiCleanup {

    private val LOCATION_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    /** Run the cleanup unless it already ran for this provisioning. */
    fun runOnce(context: Context) {
        if (KioskSetup.isWifiCleaned(context)) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        // Not marked done: on the adb path the DPC becomes device owner later.
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        // Marked before the attempt: a crash in it must not repeat on every
        // HOME landing, and networks added later (maintenance) must stay.
        KioskSetup.markWifiCleaned(context)
        try {
            cleanUp(context, dpm)
        } catch (e: RuntimeException) {
            // HOME must keep working whatever the Wi-Fi service does.
            Log.e(KioskSetup.TAG, "Wi-Fi cleanup failed", e)
        }
    }

    private fun cleanUp(context: Context, dpm: DevicePolicyManager) {
        val keep = KioskSetup.wifiSsid(context)?.takeIf { it.isNotEmpty() }
        if (keep == null) {
            Log.i(KioskSetup.TAG, "No wifi_ssid in the QR (older QR): keeping all saved Wi-Fi networks")
            return
        }
        val admin = AdminReceiver.component(context)
        val wifi = context.getSystemService(WifiManager::class.java)
        withLocationAccess(context, dpm, admin) {
            // The permission check also satisfies lint; the grant above makes it pass.
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(KioskSetup.TAG, "Location permission not granted: keeping all saved Wi-Fi networks")
                return@withLocationAccess
            }
            @Suppress("DEPRECATION")
            val saved = wifi.configuredNetworks.orEmpty()
            @Suppress("DEPRECATION")
            if (saved.none { matches(it.SSID, keep) }) {
                // Also the outcome of an empty list, i.e. access denied after all.
                Log.w(
                    KioskSetup.TAG,
                    "QR network not among ${saved.size} saved Wi-Fi networks: keeping all",
                )
                return@withLocationAccess
            }
            for (config in saved) {
                @Suppress("DEPRECATION")
                val ssid = config.SSID
                if (matches(ssid, keep)) continue
                @Suppress("DEPRECATION")
                if (wifi.removeNetwork(config.networkId)) {
                    Log.i(KioskSetup.TAG, "Removed saved Wi-Fi network $ssid")
                } else {
                    Log.w(KioskSetup.TAG, "Could not remove saved Wi-Fi network $ssid")
                }
            }
        }
    }

    /**
     * WifiConfiguration.SSID is quoted for a UTF-8 name ("\"Home\"") and bare
     * hex digits for any other byte sequence.
     */
    private fun matches(configSsid: String?, ssid: String): Boolean {
        if (configSsid == null) return false
        if (configSsid.length >= 2 && configSsid.startsWith('"') && configSsid.endsWith('"')) {
            return configSsid.substring(1, configSsid.length - 1) == ssid
        }
        val hex = ssid.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        return configSsid.equals(hex, ignoreCase = true)
    }

    /**
     * Run [block] with location access, then put back the location mode and
     * the permission policy as they were, whatever happens in between.
     *
     * The permissions themselves are not revoked right away: revoking a
     * runtime permission kills the app's process
     * (PermissionManagerServiceImpl.onPermissionRevoked), which would take
     * HOME down mid-launch and abort a running HA install. Their grant state
     * goes back to unmanaged, and from API 33 they are revoked when the
     * process next ends (revokeSelfPermissionsOnKill). Below 33 they stay
     * granted, unused.
     */
    private fun withLocationAccess(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        block: () -> Unit,
    ) {
        val pkg = context.packageName
        val previous = LOCATION_PERMISSIONS.associateWith { dpm.getPermissionGrantState(admin, pkg, it) }
        val wasGranted = LOCATION_PERMISSIONS.filter {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        val location = context.getSystemService(LocationManager::class.java)
        var locationSwitchedOn = false
        try {
            for (permission in LOCATION_PERMISSIONS) {
                if (!dpm.setPermissionGrantState(
                        admin, pkg, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                    )
                ) {
                    Log.w(KioskSetup.TAG, "Could not grant $permission: keeping all saved Wi-Fi networks")
                    return
                }
            }
            if (!location.isLocationEnabled) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    Log.w(KioskSetup.TAG, "Location is off: keeping all saved Wi-Fi networks")
                    return
                }
                dpm.setLocationEnabled(admin, true)
                locationSwitchedOn = true
                if (!location.isLocationEnabled) {
                    Log.w(KioskSetup.TAG, "Location could not be turned on: keeping all saved Wi-Fi networks")
                    return
                }
            }
            block()
        } finally {
            if (locationSwitchedOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                restore("location mode") { dpm.setLocationEnabled(admin, false) }
            }
            for ((permission, state) in previous) {
                restore(permission) { dpm.setPermissionGrantState(admin, pkg, permission, state) }
            }
            val granted = LOCATION_PERMISSIONS.filterNot { it in wasGranted }
            if (granted.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                restore("location permissions") { context.revokeSelfPermissionsOnKill(granted) }
            }
        }
    }

    /** One restore step; a failure is logged so the remaining steps still run. */
    private fun restore(what: String, step: () -> Unit) {
        try {
            step()
        } catch (e: RuntimeException) {
            Log.e(KioskSetup.TAG, "Could not restore $what after the Wi-Fi cleanup", e)
        }
    }
}
