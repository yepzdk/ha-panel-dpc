package dk.yepzdk.hapanel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a restart: re-arms the heartbeat and reports the boot to the Smart
 * Panel integration once the network is up. Nothing while unlinked.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!StatusReporter.isActive(context)) return
        StatusJobService.scheduleHeartbeat(context)
        StatusJobService.scheduleBootReport(context)
    }
}
