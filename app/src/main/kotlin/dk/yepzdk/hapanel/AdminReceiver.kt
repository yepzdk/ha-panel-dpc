package dk.yepzdk.hapanel

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle

class AdminReceiver : DeviceAdminReceiver() {

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        // Android 9-11 path. On 12+ this still fires, but PolicyComplianceActivity
        // owns setup there — running it here too would race the setup wizard.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            val extras = intent.getParcelableExtra<PersistableBundle>(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
            )
            KioskSetup.run(context, extras)
        }
    }

    companion object {
        fun component(context: Context): ComponentName =
            ComponentName(context, AdminReceiver::class.java)
    }
}
