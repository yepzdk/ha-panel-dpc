package dk.yepzdk.hapanel

import android.app.Activity
import android.os.Bundle

/**
 * Persistent HOME trampoline: every landing on "home" relaunches the HA app
 * pinned in lock task. Completed in the installer/trampoline build step.
 */
class HomeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
