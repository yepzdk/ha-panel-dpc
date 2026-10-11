package dk.yepzdk.hapanel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * Runs in the new version right after this app was updated (UPDATE_PANEL).
 * Re-applies the policies the new version may have changed, re-pins the
 * dashboard, and shows the version — HA has no sensor for it.
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!KioskSetup.applyPolicies(context)) return

        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        Log.i(KioskSetup.TAG, "Updated to $version")
        Toast.makeText(context, "Panel updated to $version", Toast.LENGTH_LONG).show()

        if (KioskSetup.isSetupComplete(context)) {
            context.startActivity(
                Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
