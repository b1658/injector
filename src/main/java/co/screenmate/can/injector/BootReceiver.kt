package co.screenmate.can.injector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/** Re-applies the ephemeral bind-mount after each reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val pending = goAsync()
        thread(name = "smcan-inject-boot") {
            try {
                Patcher(app).apply()
                LivenessJobService.schedule(app)
            } finally { pending.finish() }
        }
    }
}
