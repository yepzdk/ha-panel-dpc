package dk.yepzdk.hapanel

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * "Screen off" without sleeping: a black full-screen activity over the pinned
 * dashboard, with the backlight at its minimum for this window only. Real
 * sleep (lockNow) also stops the Companion app's connection to HA, so HA
 * could not wake the panel again.
 *
 * Dismissed by SCREEN_ON, a tap, or back — always by the activity finishing
 * itself, which is the path a tap proved to work. SCREEN_ON reaches it as a
 * new intent: finishing it through a static reference to the instance left
 * the overlay up on a panel.
 *
 * The system brightness setting is untouched, so SET_BRIGHTNESS while blanked
 * applies once the panel wakes.
 *
 * Reports every overlay change (SCREEN_OFF, SCREEN_ON, tap) to the Smart
 * Panel integration from here, once it has taken effect.
 */
class ScreenOffActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // SCREEN_ON raced the overlay's own teardown: nothing to show.
        if (intent?.action == ACTION_DISMISS) {
            finish()
            return
        }
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
        }
        setContentView(
            View(this).apply {
                setBackgroundColor(Color.BLACK)
                setOnClickListener { finish() }
            },
        )
        hideSystemBars()
        // Not on recreation (rotation): the overlay was already up.
        if (savedInstanceState == null) {
            StatusReporter.send(this, StatusReporter.EVENT_CHANGE, screenOff = true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_DISMISS) {
            finish()
        } else {
            // SCREEN_OFF while already blanked: nothing changed, but confirm the state.
            StatusReporter.send(this, StatusReporter.EVENT_CHANGE, screenOff = true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The task list may still hold this task while it tears down, so the
        // state is passed explicitly.
        if (isFinishing) StatusReporter.send(this, StatusReporter.EVENT_CHANGE, screenOff = false)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    companion object {
        private const val ACTION_DISMISS = "dk.yepzdk.hapanel.action.DISMISS_SCREEN_OFF"

        fun show(context: Context) {
            context.startActivity(intent(context))
        }

        /**
         * Finish the overlay if it is up; no-op otherwise.
         * @return true if the overlay was up and is now being dismissed.
         */
        fun dismiss(context: Context): Boolean {
            if (!isShowing(context)) return false
            context.startActivity(intent(context).setAction(ACTION_DISMISS))
            return true
        }

        private fun intent(context: Context) =
            Intent(context, ScreenOffActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** True while the overlay's task exists, i.e. the screen is blanked. */
        fun isShowing(context: Context): Boolean =
            context.getSystemService(ActivityManager::class.java).appTasks.any { task ->
                // A task can vanish between listing and reading it.
                runCatching { task.taskInfo.baseActivity?.className }.getOrNull() ==
                    ScreenOffActivity::class.java.name
            }
    }
}
