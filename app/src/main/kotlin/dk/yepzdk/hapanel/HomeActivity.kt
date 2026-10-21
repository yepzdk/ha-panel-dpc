package dk.yepzdk.hapanel

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.TextView

/**
 * Persistent HOME trampoline: every landing on "home" (boot, HA app update,
 * crash) relaunches the HA app pinned in lock task, or unpinned in
 * maintenance mode. Waits while the setup wizard is unfinished; if the app is
 * missing, kicks the installer. Retries until both conditions clear. The
 * first launch after provisioning opens the HA app's onboarding at the
 * server from the QR (`ha_url`), when set.
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
        val maintenance = KioskSetup.isMaintenance(this)
        // Self-heal for the adb `dpm set-device-owner` path, where no
        // provisioning callback ever fires: apply policies on first landing.
        // Also re-locks a panel restarted during maintenance mode.
        val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java)
        if (!maintenance && dpm.isDeviceOwnerApp(packageName) &&
            !dpm.isLockTaskPermitted(KioskSetup.HA_PACKAGE)
        ) {
            KioskSetup.applyPolicies(this)
        }
        if (launchIfReady(maintenance)) return
        if (packageManager.getLaunchIntentForPackage(KioskSetup.HA_PACKAGE) == null) {
            InstallService.startHa(this)
        }
        handler.postDelayed(retry, RETRY_MS)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(retry)
    }

    /** @return true if setup is complete and the HA app was found and launched. */
    private fun launchIfReady(maintenance: Boolean = KioskSetup.isMaintenance(this)): Boolean {
        if (!KioskSetup.isSetupComplete(this)) {
            status.text = "Waiting for device setup to finish…"
            return false
        }
        // Every network added during setup is saved by now; runs once.
        WifiCleanup.runOnce(this)
        val launch = packageManager.getLaunchIntentForPackage(KioskSetup.HA_PACKAGE)
        if (launch == null) {
            status.text = "Installing Home Assistant…"
            return false
        }
        val invite = inviteIntent()
        val target = invite ?: launch
        if (maintenance) {
            startActivity(target)
        } else {
            val options = ActivityOptions.makeBasic().apply { setLockTaskEnabled(true) }
            try {
                startActivity(target, options.toBundle())
            } catch (e: SecurityException) {
                // Not lock-task-permitted (e.g. policies not applied yet) — run unpinned
                // rather than showing a black screen.
                Log.w(KioskSetup.TAG, "Lock task launch failed, starting unpinned", e)
                startActivity(target)
            }
        }
        if (invite != null) KioskSetup.markInviteOpened(this)
        return true
    }

    /**
     * The HA app's invite deep link for the server URL from the QR (`ha_url`),
     * on the first launch after provisioning only; null otherwise. It opens
     * onboarding with that server filled in.
     *
     * Format per LinkHandlerImpl.handleInviteLink (home-assistant/android
     * 2026.8.4, launch/link/LinkHandler.kt): `homeassistant://invite/#url=<url>`.
     * The app reads the fragment as a query string ("https://?<fragment>",
     * then getQueryParameter("url")), which percent-decodes the value — so the
     * URL is encoded here, keeping `&`, `#` or `+` in it intact.
     */
    private fun inviteIntent(): Intent? {
        if (KioskSetup.isInviteOpened(this)) return null
        val haUrl = KioskSetup.haUrl(this)?.takeIf { it.isNotBlank() } ?: return null
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("homeassistant://invite/#url=" + Uri.encode(haUrl)))
            .setPackage(KioskSetup.HA_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(packageManager) == null) {
            // An HA app without the invite link: onboard by hand. Marked as done
            // anyway, so a later HA update cannot start a second onboarding.
            Log.w(KioskSetup.TAG, "HA app cannot open the invite link, starting it normally")
            KioskSetup.markInviteOpened(this)
            return null
        }
        return intent
    }

    companion object {
        private const val RETRY_MS = 5_000L
    }
}
