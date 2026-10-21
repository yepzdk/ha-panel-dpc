package dk.yepzdk.hapanel

import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * Commands the HA server sends via the Companion app's command_broadcast_intent
 * notification. Exported by necessity so the HA app can reach it; the only
 * other apps on a provisioned panel are ours.
 *
 * SCREEN_OFF        — blank the screen with a black overlay (ScreenOffActivity);
 *                     the device stays awake so HA can still reach it
 * SCREEN_ON         — remove the overlay (a tap on the panel does the same)
 * SET_BRIGHTNESS    — extra "brightness": int 0-255, or "auto" for the light sensor
 * NAVIGATE          — open HA at extra "path" (default: the default dashboard);
 *                     loads the page fresh, so it doubles as a reload
 * REBOOT            — restart the device
 * MAINTENANCE_ON    — unpin HA and enable the status bar until MAINTENANCE_OFF
 *                     or the next restart
 * MAINTENANCE_OFF   — re-apply the kiosk policies and pin HA again
 * UPDATE_HA         — re-download and reinstall the HA app
 * UPDATE_PANEL      — update this app; optional extra "url" (default: latest release)
 * FACTORY_RESET     — wipe the device for re-provisioning; requires extra
 *                     confirm="wipe" so a stray broadcast can't nuke a panel
 */
class PanelCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val admin = AdminReceiver.component(context)

        when (intent.action) {
            ACTION_SCREEN_OFF -> ScreenOffActivity.show(context)

            ACTION_SCREEN_ON -> ScreenOffActivity.dismiss(context)

            ACTION_SET_BRIGHTNESS -> {
                // HA's intent_extras may arrive typed as string or int — accept both.
                @Suppress("DEPRECATION")
                val raw = intent.extras?.get("brightness")?.toString()
                if (raw == "auto") {
                    dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS_MODE, "1")
                    return
                }
                val value = raw?.toIntOrNull()?.takeIf { it in 0..255 }
                if (value == null) {
                    Log.w(KioskSetup.TAG, "SET_BRIGHTNESS without 0-255 or auto extra")
                    return
                }
                // Manual mode first, or automatic brightness overrides the value.
                dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS_MODE, "0")
                dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS, value.toString())
            }

            ACTION_NAVIGATE -> navigate(context, intent.getStringExtra("path").orEmpty())

            ACTION_REBOOT -> {
                Log.i(KioskSetup.TAG, "Reboot requested")
                try {
                    dpm.reboot(admin)
                } catch (e: IllegalStateException) {
                    // Refused during a phone call.
                    Log.w(KioskSetup.TAG, "Reboot refused", e)
                    Toast.makeText(context, "Restart refused: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            ACTION_MAINTENANCE_ON -> {
                if (KioskSetup.startMaintenance(context)) {
                    Toast.makeText(context, "Maintenance mode on", Toast.LENGTH_LONG).show()
                }
            }

            ACTION_MAINTENANCE_OFF -> {
                if (KioskSetup.stopMaintenance(context)) {
                    Toast.makeText(context, "Maintenance mode off", Toast.LENGTH_LONG).show()
                    // The trampoline pins the HA app again.
                    context.startActivity(
                        Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }

            ACTION_UPDATE_HA -> InstallService.startHa(context)

            ACTION_UPDATE_PANEL -> InstallService.startSelf(context, intent.getStringExtra("url"))

            ACTION_FACTORY_RESET -> {
                if (intent.extras?.get("confirm")?.toString() == "wipe") {
                    Log.i(KioskSetup.TAG, "Factory reset requested — wiping")
                    @Suppress("DEPRECATION")
                    dpm.wipeData(0)
                } else {
                    Log.w(KioskSetup.TAG, "FACTORY_RESET without confirm=wipe ignored")
                }
            }
        }
    }

    /**
     * Open HA's `homeassistant://navigate` deep link. [path] is a frontend path
     * in URL form and may carry a query, e.g. `lovelace/0?disable_km`.
     */
    private fun navigate(context: Context, path: String) {
        val uri = Uri.parse("homeassistant://navigate/" + path.removePrefix("/"))
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(KioskSetup.HA_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(KioskSetup.TAG, "HA app cannot open $uri", e)
        }
    }

    companion object {
        const val ACTION_SCREEN_OFF = "dk.yepzdk.hapanel.SCREEN_OFF"
        const val ACTION_SCREEN_ON = "dk.yepzdk.hapanel.SCREEN_ON"
        const val ACTION_SET_BRIGHTNESS = "dk.yepzdk.hapanel.SET_BRIGHTNESS"
        const val ACTION_NAVIGATE = "dk.yepzdk.hapanel.NAVIGATE"
        const val ACTION_REBOOT = "dk.yepzdk.hapanel.REBOOT"
        const val ACTION_MAINTENANCE_ON = "dk.yepzdk.hapanel.MAINTENANCE_ON"
        const val ACTION_MAINTENANCE_OFF = "dk.yepzdk.hapanel.MAINTENANCE_OFF"
        const val ACTION_UPDATE_HA = "dk.yepzdk.hapanel.UPDATE_HA"
        const val ACTION_UPDATE_PANEL = "dk.yepzdk.hapanel.UPDATE_PANEL"
        const val ACTION_FACTORY_RESET = "dk.yepzdk.hapanel.FACTORY_RESET"
    }
}
