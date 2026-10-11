package dk.yepzdk.hapanel

import android.app.Activity
import android.app.ActivityOptions
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.TextView

/**
 * Persistent HOME trampoline: every landing on "home" (boot, HA app update,
 * crash) relaunches the HA app pinned in lock task. Waits while the setup
 * wizard is unfinished; if the app is missing, kicks the installer. Retries
 * until both conditions clear.
 */
class HomeActivity : Activity() {

    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val retry = object : Runnable {
        override fun run() {
            if (!launchIfReady()) handler.postDelayed(this, RETRY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            textSize = 20f
        }
        setContentView(status)
    }

    override fun onResume() {
        super.onResume()
        // Self-heal for the adb `dpm set-device-owner` path, where no
        // provisioning callback ever fires: apply policies on first landing.
        val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java)
        if (dpm.isDeviceOwnerApp(packageName) &&
            !dpm.isLockTaskPermitted(KioskSetup.HA_PACKAGE)
        ) {
            KioskSetup.run(this, null)
        }
        if (launchIfReady()) return
        if (packageManager.getLaunchIntentForPackage(KioskSetup.HA_PACKAGE) == null) {
            InstallService.start(this)
        }
        handler.postDelayed(retry, RETRY_MS)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(retry)
    }

    /** @return true if setup is complete and the HA app was found and launched. */
    private fun launchIfReady(): Boolean {
        if (!KioskSetup.isSetupComplete(this)) {
            status.text = "Waiting for device setup to finish…"
            return false
        }
        val launch = packageManager.getLaunchIntentForPackage(KioskSetup.HA_PACKAGE)
        if (launch == null) {
            status.text = "Installing Home Assistant…"
            return false
        }
        val options = ActivityOptions.makeBasic().apply { setLockTaskEnabled(true) }
        try {
            startActivity(launch, options.toBundle())
        } catch (e: SecurityException) {
            // Not lock-task-permitted (e.g. policies not applied yet) — run unpinned
            // rather than showing a black screen.
            Log.w(KioskSetup.TAG, "Lock task launch failed, starting unpinned", e)
            startActivity(launch)
        }
        return true
    }

    companion object {
        private const val RETRY_MS = 5_000L
    }
}
