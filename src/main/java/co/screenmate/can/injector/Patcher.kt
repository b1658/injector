package co.screenmate.can.injector

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * Applies/removes the agent by bind-mounting an agent-patched copy of the privileged host app's
 * APK over the live one, via the box's own root adbd (127.0.0.1:5555). Ephemeral (RAM) — reverted
 * on reboot, re-applied by [BootReceiver]. NO system partition is written: dm-verity stays
 * enforcing and vendor OTA integrity is preserved. Same technique as PavelDemyanov/screenmate-dash
 * (MIT); validated on-device — see docs/INJECTION.md.
 *
 * Host = co.screenmate.miscutils (a small, non-critical *privileged* app, domain platform_app),
 * NOT the main display app. The patched APK (assets/patched-host.apk, produced by
 * tools/build-host-apk.sh from the device's own APK — never redistributed) has a one-line smali
 * hook in TimeSyncService.onCreate that starts the pure-Java agent, plus the agent as classes2.dex.
 * The agent reads all ~1155 vendor signals via CAR_VENDOR_EXTENSION and broadcasts them.
 */
class Patcher(private val ctx: Context) {

    private val log = StringBuilder()
    private fun log(s: String) { log.appendLine(s) }

    /** True if we can open an authenticated adbd session on loopback (key must be authorized once). */
    fun canConnect(): Boolean = runCatching { connect().use { true } }.getOrDefault(false)

    /** True once the live host APK content equals our staged patched copy (bind-mount active). */
    fun isApplied(): Boolean = runCatching {
        connect().use { d -> mountMatchesPatched(d) }
    }.getOrDefault(false)

    /**
     * True only if the live mount is this build's CURRENT bundled asset. [isApplied] asks the weaker
     * "is the last-staged patch mounted", which stays true when an OLDER agent is still mounted from
     * a previous install — so an on-launch/liveness re-apply is skipped and the stale agent lives on
     * (e.g. a v1 producer that newer, auth-requiring consumers reject). This re-stages the bundled
     * asset first, so a version bump reliably re-applies. Costs one asset copy; call it off the
     * hot path (launch / liveness), not the per-second status refresh.
     */
    fun isCurrentApplied(): Boolean = runCatching {
        connect().use { d ->
            stageAsset(d) ?: return@use false
            mountMatchesPatched(d)
        }
    }.getOrDefault(false)

    fun apply(): String {
        log.clear()
        try {
            connect().use { d ->
                val staged = stageAsset(d) ?: return fail("assets/$PATCHED_ASSET missing — run tools/build-host-apk.sh")
                log("staged: $staged")

                selinuxLabel(d, TARGET)?.let { d.sh("chcon '$it' '$staged'"); log("chcon $it") }

                d.sh("umount -l '$TARGET' 2>/dev/null; mount -o bind '$staged' '$TARGET'")
                if (mountMatchesPatched(d)) log("bind-mount active") else log("  ! bind-mount not visible")

                restartHost(d)
                log(proof(d))
            }
            log("APPLY OK")
        } catch (t: Throwable) {
            log("ERROR: ${t.javaClass.simpleName}: ${t.message}")
        }
        return log.toString()
    }

    fun remove(): String {
        log.clear()
        try {
            connect().use { d ->
                d.sh("umount -l '$TARGET' 2>/dev/null")
                d.sh("am force-stop --user $USER $HOST_PKG; am force-stop $HOST_PKG")
                log("REMOVE OK — host app restored on next launch/reboot")
            }
        } catch (t: Throwable) {
            log("ERROR: ${t.message}")
        }
        return log.toString()
    }

    /**
     * Fast recovery path. An LMK trim (the usual cause of the feed going silent while driving) kills
     * only the host *process* — the bind-mount is a kernel mount and survives, so the agent just
     * needs the host relaunched for TimeSyncService.onCreate to re-fire it. This skips the asset
     * re-stage + re-mount + proof polling that [apply] does, so a [ReinjectReceiver] nudge recovers
     * in ~1s instead of seconds. Caller should have confirmed [isApplied] first; if the mount is
     * actually gone (force-stop/OTA), use [apply] instead.
     */
    fun restartHostOnly(): String {
        log.clear()
        try {
            connect().use { d ->
                restartHost(d)
                log("HOST RESTART OK")
            }
        } catch (t: Throwable) {
            log("ERROR: ${t.javaClass.simpleName}: ${t.message}")
        }
        return log.toString()
    }

    /**
     * Master runtime TX arm. Touches (arm) or removes (disarm) the marker file the `SmCanTx` executor
     * checks before every transmit — so OFF blocks ALL CAN transmit box-wide (including the
     * jbv1-bridge consumer), ON allows it. This loader itself never transmits; it only flips the
     * marker over the same root adbd channel the bind-mount uses. Returns a short status line.
     */
    fun setTxArmed(armed: Boolean): String = runCatching {
        connect().use { d ->
            if (armed) d.sh("touch '$TX_ARM_MARKER' && chmod 644 '$TX_ARM_MARKER'")
            else d.sh("rm -f '$TX_ARM_MARKER'")
            val state = d.shell("[ -f '$TX_ARM_MARKER' ] && echo ARMED || echo DISARMED").output.trim()
            "CAN TX $state"
        }
    }.getOrElse { "ERROR: ${it.message}" }

    // --- helpers ---
    /**
     * Open an authenticated adbd session on loopback. dadb signs the handshake with a persisted
     * keypair; adbd (ro.adb.secure=1) prompts the user to authorize it on the FIRST connect
     * ("Allow USB debugging? / Always allow"). After that the key is trusted and connects are silent.
     */
    private fun connect(): Dadb = Dadb.create(HOST, PORT, keyPair())

    private fun keyPair(): AdbKeyPair {
        val priv = File(ctx.filesDir, "adbkey")
        val pub = File(ctx.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    private fun Dadb.sh(cmd: String): String {
        val r = shell(cmd)
        if (r.exitCode != 0) log("  ! ($cmd) exit=${r.exitCode} ${r.output.trim()}")
        return r.output
    }

    /** Restart the host so the framework loads the bind-mounted dex and fires TimeSyncService. */
    private fun restartHost(d: Dadb) {
        d.sh("am force-stop --user $USER $HOST_PKG; am force-stop $HOST_PKG")
        // BOOT_COMPLETED drives the host's BootReceiver to (re)start its services like a real boot;
        // start-service guarantees TimeSyncService.onCreate (our hook) fires even if that changes.
        d.sh("am broadcast --user $USER -a android.intent.action.BOOT_COMPLETED -p $HOST_PKG")
        d.sh("am start-service --user $USER -n $HOST_PKG/$HOOK_SERVICE")
        log("restarted $HOST_PKG — agent should now be live")
    }

    /** Surface the agent's proof-of-privilege line from logcat, if present (best-effort). */
    private fun proof(d: Dadb): String {
        repeat(6) {
            Thread.sleep(700) // host restart -> onCreate -> car-service bind can take a moment
            val line = d.shell("logcat -d -s SmCanAgent -t 60").output
                .lineSequence().lastOrNull { it.contains("PRIVILEGED READ OK") }
            if (line != null) return "proof: ${line.trim().substringAfter("SmCanAgent:").trim()}"
        }
        return "proof: (agent not seen yet — check 'logcat -s SmCanAgent')"
    }

    /** Copy the bundled patched APK to internal storage (root adbd can read it) and cp into tmp. */
    private fun stageAsset(d: Dadb): String? {
        val local = File(ctx.filesDir, PATCHED_ASSET)
        val extracted = runCatching {
            ctx.assets.open(PATCHED_ASSET).use { input ->
                local.outputStream().use { input.copyTo(it) }
            }
        }
        extracted.onFailure { log("  ! extract asset: ${it.message}"); return null }
        if (!local.exists() || local.length() == 0L) { log("  ! asset empty after extract"); return null }
        // Make the file world-readable so the root adbd `cp` sees it regardless of SELinux label.
        runCatching { local.setReadable(true, false) }
        val r = d.shell("cp '${local.absolutePath}' '$STAGED' && chmod 644 '$STAGED' && echo OK")
        return if (r.output.contains("OK")) STAGED else { log("  ! cp to tmp: ${r.output.trim()}"); null }
    }

    private fun selinuxLabel(d: Dadb, target: String): String? =
        d.shell("ls -Z '$target'").output.trim()
            .split(Regex("\\s+")).firstOrNull { it.count { c -> c == ':' } >= 3 }

    /** The bind-mount is active iff the live target's hash equals our staged patched APK's. */
    private fun mountMatchesPatched(d: Dadb): Boolean {
        val out = d.shell("md5sum '$TARGET' '$STAGED' 2>/dev/null").output
        val hashes = out.lineSequence().mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull() }
            .filter { it.length == 32 }.toList()
        return hashes.size == 2 && hashes[0] == hashes[1]
    }

    private fun fail(msg: String): String { log("ERROR: $msg"); return log.toString() }

    private companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 5555
        const val HOST_PKG = "co.screenmate.miscutils"
        const val HOOK_SERVICE = ".TimeSyncService"   // hooked startup service (onCreate -> agent)
        const val TARGET = "/system_ext/priv-app/ScreenmateMiscUtils/ScreenmateMiscUtils.apk"
        const val STAGED = "/data/local/tmp/smcan_host.apk"
        const val TX_ARM_MARKER = "/data/local/tmp/smcan-tx.armed"
        const val USER = 10
        const val PATCHED_ASSET = "patched-host.apk"
    }
}
