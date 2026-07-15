package dk.yepzdk.hapanel

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.os.Bundle
import android.os.PersistableBundle

/**
 * Runs once we are device owner: Android 12+ ADMIN_POLICY_COMPLIANCE, or the
 * legacy PROVISIONING_SUCCESSFUL launch on 9-11. Applies policies and returns
 * RESULT_OK fast — hanging here strands the device in the setup wizard.
 */
class PolicyComplianceActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val extras = intent.getParcelableExtra<PersistableBundle>(
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
        )
        // Idempotent — may run after AdminReceiver already did on 9-11.
        KioskSetup.run(this, extras)
        setResult(RESULT_OK)
        finish()
    }
}
