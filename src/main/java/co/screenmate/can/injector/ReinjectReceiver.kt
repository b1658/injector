package co.screenmate.can.injector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import kotlin.concurrent.thread

/**
 * Fast producer recovery, consumer-driven. The injected agent lives in the host process
 * (co.screenmate.miscutils), which Android runs as a *cached* process (oom_score_adj ~905) and
 * trims routinely — even with free RAM — so the feed goes silent mid-drive. [LivenessJobService]
 * heals this, but only on JobScheduler's ~15-minute floor: far too slow for a live dashboard.
 *
 * This receiver closes that gap. A consumer (the jbv1-bridge's PrivilegedBroadcastClient) that sees
 * the feed go silent broadcasts [ACTION_REINJECT] here; because this is a *manifest-declared*
 * receiver, the framework spins the injector process up to deliver even when it too was trimmed —
 * then we relaunch the host so the agent re-fires. Recovery drops from minutes to ~1s.
 *
 * Because an LMK trim leaves the bind-mount intact (it is a kernel mount, not tied to the process),
 * the common case only needs [Patcher.restartHostOnly] — a bare host relaunch, no re-stage/re-mount.
 * Only if the mount is actually gone (force-stop/OTA) do we fall back to a full [Patcher.apply].
 *
 * Abuse control: the manifest gates senders by [Patcher] PERM_RECEIVE, and a process-wide debounce
 * ([MIN_INTERVAL_MS]) means a flood of nudges cannot thrash the host with force-stops — at most one
 * recovery attempt per interval regardless of how many consumers shout.
 */
class ReinjectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REINJECT) return

        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (now - lastRun in 0 until MIN_INTERVAL_MS) {
                Log.d(TAG, "reinject nudge ignored (debounced)")
                return
            }
            lastRun = now
        }

        val app = context.applicationContext
        val pending = goAsync()
        thread(name = "smcan-reinject") {
            try {
                val p = Patcher(app)
                if (!p.canConnect()) {
                    Log.w(TAG, "reinject: adbd unreachable, skipping")
                    return@thread
                }
                // Mount still live (LMK trim) -> just relaunch the host; else re-apply from scratch.
                val result = if (p.isApplied()) p.restartHostOnly() else p.apply()
                Log.i(TAG, "reinject nudge handled: ${result.trim().lines().lastOrNull()}")
            } catch (t: Throwable) {
                Log.w(TAG, "reinject failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "SmCanInjector"

        /** Consumer -> injector: "the feed is silent, please recover the producer now." */
        const val ACTION_REINJECT = "co.screenmate.can.injector.REINJECT"

        // Process-wide floor between recovery attempts. A host restart + agent re-bind takes a couple
        // seconds; anything faster would just pile force-stops on a host that is already coming back.
        private const val MIN_INTERVAL_MS = 8_000L

        private val lock = Any()
        @Volatile private var lastRun = 0L
    }
}
