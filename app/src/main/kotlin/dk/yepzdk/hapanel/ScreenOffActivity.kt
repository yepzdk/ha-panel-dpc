package dk.yepzdk.hapanel

import android.app.Activity
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
 * Dismissed by SCREEN_ON, a tap, or back. The system brightness setting is
 * untouched, so SET_BRIGHTNESS while blanked applies once the panel wakes.
 */
class ScreenOffActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        current = this
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
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
        // Set only while the overlay is showing; receivers run on the main thread.
        private var current: ScreenOffActivity? = null

        fun show(context: Context) {
            context.startActivity(
                Intent(context, ScreenOffActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        fun dismiss() {
            current?.finish()
        }
    }
}
