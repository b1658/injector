package co.screenmate.can.injector

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import kotlin.concurrent.thread

/**
 * Periodic self-heal net. If the injected agent is no longer applied — the host app was
 * force-stopped, updated, or LMK-killed and restarted clean — the bind-mount is gone and the signal
 * feed is dead until someone re-injects. This standing job re-applies it without the user opening
 * the app, complementing [BootReceiver] (reboot) and [MainActivity] (on-launch).
 *
 * JobScheduler enforces a ~15-minute floor on periodic intervals, so this is a safety net for a
 * dead feed, not a fast failover; the producer's own watchdog covers sub-second recovery while the
 * host process is alive.
 */
class LivenessJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val app = applicationContext
        thread(name = "smcan-liveness") {
            try {
                val p = Patcher(app)
                if (p.canConnect() && !p.isCurrentApplied()) {
                    Log.i(TAG, "liveness: current agent not applied — re-applying")
                    p.apply()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "liveness check failed", t)
            } finally {
                jobFinished(params, false)
            }
        }
        return true // work continues on the background thread
    }

    override fun onStopJob(params: JobParameters?): Boolean = true // reschedule if cut short

    companion object {
        private const val TAG = "SmCanInjector"
        private const val JOB_ID = 0x5C10
        private const val INTERVAL_MS = 15L * 60 * 1000 // JobScheduler periodic floor

        /** Idempotent: schedules the periodic health check (persisted across reboot). */
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, LivenessJobService::class.java))
                .setPersisted(true)
                .setPeriodic(INTERVAL_MS)
                .build()
            runCatching { js.schedule(job) }
        }
    }
}
