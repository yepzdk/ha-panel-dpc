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

        /** Contract: every 15 minutes. Both values are the JobScheduler minimums. */
        private const val HEARTBEAT_MS = 15 * 60 * 1000L
        private const val HEARTBEAT_FLEX_MS = 5 * 60 * 1000L

        /**
         * Start the heartbeat unless it already runs. Called from LINK, boot
         * and package replaced; each of those sends its own report, so the
         * first heartbeat must not follow right away.
         *
         * The first run of a periodic job falls in the flex window at the end
         * of the first period (AOSP JobStatus.createFromJobInfo: earliest =
         * now + period - flex). With the default flex, which equals the
         * period, it would run at once; a 5-minute flex puts it 10-15 minutes
         * after scheduling, and every later run 15 minutes apart.
         */
        fun scheduleHeartbeat(context: Context) {
            val pending = context.getSystemService(JobScheduler::class.java).getPendingJob(JOB_HEARTBEAT)
            // Rescheduling resets the period, so keep a job that matches; one
            // from an older build with other timing is replaced.
            if (pending != null && pending.intervalMillis == HEARTBEAT_MS &&
                pending.flexMillis == HEARTBEAT_FLEX_MS && pending.isPersisted
            ) {
                return
            }
            schedule(
                context,
                job(context, JOB_HEARTBEAT, StatusReporter.EVENT_HEARTBEAT)
                    .setPeriodic(HEARTBEAT_MS, HEARTBEAT_FLEX_MS)
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
