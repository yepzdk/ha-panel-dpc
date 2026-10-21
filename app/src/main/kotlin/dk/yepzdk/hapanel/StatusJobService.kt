package dk.yepzdk.hapanel

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.util.Log

/**
 * Status reports that must wait for the network: the 15-minute heartbeat
 * (periodic, survives reboots) and the one-off boot report — BOOT_COMPLETED
 * usually arrives before Wi-Fi is connected, and a report sent then is lost.
 */
class StatusJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val event = params.extras.getString(EXTRA_EVENT) ?: StatusReporter.EVENT_HEARTBEAT
        if (!StatusReporter.isActive(this)) return false
        StatusReporter.send(this, event) { jobFinished(params, false) }
        return true
    }

    // No reschedule: the next heartbeat carries fresh state.
    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val JOB_HEARTBEAT = 1
        private const val JOB_BOOT = 2
        private const val EXTRA_EVENT = "event"

        /** Start the heartbeat, replacing any scheduled one. */
        fun scheduleHeartbeat(context: Context) {
            schedule(
                context,
                job(context, JOB_HEARTBEAT, StatusReporter.EVENT_HEARTBEAT)
                    .setPeriodic(JobInfo.getMinPeriodMillis())
                    // Survives reboots; needs RECEIVE_BOOT_COMPLETED.
                    .setPersisted(true)
                    .build(),
            )
        }

        /** Send the boot report once the network is up. */
        fun scheduleBootReport(context: Context) {
            schedule(context, job(context, JOB_BOOT, StatusReporter.EVENT_BOOT).build())
        }

        /** Stop every pending report (UNLINK). */
        fun cancelAll(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            scheduler.cancel(JOB_HEARTBEAT)
            scheduler.cancel(JOB_BOOT)
        }

        private fun job(context: Context, id: Int, event: String) =
            JobInfo.Builder(id, ComponentName(context, StatusJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExtras(PersistableBundle().apply { putString(EXTRA_EVENT, event) })

        private fun schedule(context: Context, job: JobInfo) {
            val result = context.getSystemService(JobScheduler::class.java).schedule(job)
            if (result != JobScheduler.RESULT_SUCCESS) {
                Log.w(KioskSetup.TAG, "Scheduling status job ${job.id} failed")
            }
        }
    }
}
