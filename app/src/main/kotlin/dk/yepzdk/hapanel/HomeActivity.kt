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
 * crash) relaunches the HA app pinned in lock task. If the app is missing,
 * kicks the installer and retries until it appears.
 */
class HomeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val retry = object : Runnable {
        override fun run() {
            if (!launchHa()) handler.postDelayed(this, RETRY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "Installing Home Assistant…"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.BLACK)
                textSize = 20f
            },
        )
    }

    override fun onResume() {
        super.onResume()
        if (!launchHa()) {
            InstallService.start(this)
            handler.postDelayed(retry, RETRY_MS)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(retry)
    }

    /** @return true if the HA app was found and launched. */
    private fun launchHa(): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(KioskSetup.HA_PACKAGE)
            ?: return false
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
