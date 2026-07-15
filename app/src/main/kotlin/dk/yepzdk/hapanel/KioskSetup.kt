package dk.yepzdk.hapanel

import android.content.Context
import android.os.PersistableBundle
import android.util.Log

/**
 * All device-owner policy setup, shared by every provisioning path.
 * Idempotent — safe to run more than once.
 */
object KioskSetup {

    const val TAG = "HaPanelDpc"
    const val HA_PACKAGE = "io.homeassistant.companion.android.minimal"

    fun run(context: Context, adminExtras: PersistableBundle?) {
        persistExtras(context, adminExtras)
        Log.i(TAG, "Provisioning complete, extras=$adminExtras")
        // Policies applied in the next step of the build (task 3).
    }

    private fun persistExtras(context: Context, extras: PersistableBundle?) {
        if (extras == null) return
        val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
        prefs.edit().apply {
            extras.keySet().forEach { key -> putString(key, extras.getString(key)) }
            apply()
        }
    }
}
