package dk.yepzdk.hapanel

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.annotation.TargetApi
import android.os.PersistableBundle

/**
 * Android 12+ provisioning step 1: tell the setup wizard we want a fully
 * managed device. No UI — decide and finish immediately.
 */
@TargetApi(31)
class GetProvisioningModeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent().apply {
            putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
            )
            // The admin extras must be copied into the result, or they never
            // reach PolicyComplianceActivity.
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<PersistableBundle>(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
            )?.let { putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, it) }
        }
        setResult(RESULT_OK, result)
        finish()
    }
}
