package dk.yepzdk.hapanel

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Commands the HA server sends via the Companion app's command_broadcast_intent
 * notification. Exported by necessity so the HA app can reach it; the only
 * other apps on a provisioned panel are ours.
 *
 * SCREEN_OFF        — blank the screen (lockNow; keyguard is disabled, so any
 *                     wake lands straight back on the dashboard)
 * SET_BRIGHTNESS    — extra "brightness" int 0-255
 * UPDATE_HA         — re-download and reinstall the HA app
 */
class PanelCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val admin = AdminReceiver.component(context)

        when (intent.action) {
            ACTION_SCREEN_OFF -> dpm.lockNow()

            ACTION_SET_BRIGHTNESS -> {
                // HA's intent_extras may arrive typed as string or int — accept both.
                @Suppress("DEPRECATION")
                val value = intent.extras?.get("brightness")?.toString()?.toIntOrNull()
                    ?.takeIf { it in 0..255 }
                if (value == null) {
                    Log.w(KioskSetup.TAG, "SET_BRIGHTNESS without valid 0-255 extra")
                    return
                }
                // Manual mode first, or automatic brightness overrides the value.
                dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS_MODE, "0")
                dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS, value.toString())
            }

            ACTION_UPDATE_HA -> InstallService.start(context)
        }
    }

    companion object {
        const val ACTION_SCREEN_OFF = "dk.yepzdk.hapanel.SCREEN_OFF"
        const val ACTION_SET_BRIGHTNESS = "dk.yepzdk.hapanel.SET_BRIGHTNESS"
        const val ACTION_UPDATE_HA = "dk.yepzdk.hapanel.UPDATE_HA"
    }
}
