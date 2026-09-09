package co.screenmate.can.injector

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * SMO-style one-tap loader — nothing more. Installed once (after enabling Developer Options +
 * wireless/USB debugging), it self-configures on launch by talking to the box's OWN localhost root
 * adbd — no PC tether — to bind-mount the agent-patched host APK, then shows live status.
 * [BootReceiver] re-applies after each reboot. Signals flow to any consumer holding [PERM_RECEIVE].
 *
 * This app never transmits. Its one TX control is a **master arm** switch that enables/disables CAN
 * transmit box-wide via [Patcher.setTxArmed] (the `SmCanTx` executor refuses to transmit unless the
 * arm marker is set). The actual TX (the exact set-speed scroll) lives in the `:tx-client` SDK and
 * its consumers (e.g. `screenmate-jbv1-bridge`); `tools/can-tx.sh` drives the low-level `SmCanTx`
 * for bring-up. See `docs/TX.md` and `docs/EXPLORATIONS.md`.
 *
 * The UI is a hand-built dark "automotive" layout (no androidx): glanceable status card, a clear
 * primary/secondary/destructive action hierarchy, a colour-coded TX-arm card, and a timestamped
 * activity log — all sized for a car centre screen.
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var headerPill: TextView
    private lateinit var dotAdbd: StatusRow
    private lateinit var dotAgent: StatusRow
    private lateinit var dotSignals: StatusRow
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    // --- CAN transmit master arm (persisted, default OFF) ---
    private lateinit var txSwitch: Switch
    private lateinit var txCard: LinearLayout
    private lateinit var txStatus: TextView
    private val prefs by lazy { getPreferences(MODE_PRIVATE) }
    private var txEnabled: Boolean
        get() = prefs.getBoolean("tx_enabled", false)
        set(v) { prefs.edit().putBoolean("tx_enabled", v).apply() }

    @Volatile private var lastBatchAt = 0L
    @Volatile private var lastBatchCount = 0
    @Volatile private var lastFlags = 0
    @Volatile private var busy = false

    // Last-known state per row, for the aggregate header pill.
    private var sAdbd = Status.IDLE
    private var sAgent = Status.IDLE
    private var sSignals = Status.IDLE

    private val logBuf = StringBuilder()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val signalReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val batch = i?.getByteArrayExtra(EXTRA_BATCH) ?: return
            lastBatchCount = parseSignals(batch)
            lastBatchAt = SystemClock.elapsedRealtime()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(BG)

        dotAdbd = StatusRow("Localhost adbd", "127.0.0.1:5555")
        dotAgent = StatusRow("Agent injected", "bind-mount over host APK")
        dotSignals = StatusRow("Vendor signals", "CAN broadcast feed")
        logView = TextView(this).apply {
            textSize = 12f; setTextColor(LOGTXT); typeface = Typeface.MONOSPACE
        }

        val apply = primaryButton("Re-apply now") { runAction { Patcher(this).apply() } }
        val recheck = secondaryButton("Re-check status") { thread { refresh() } }
        val remove = dangerButton("Remove (restore host)") { runAction { Patcher(this).remove() } }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(20), dp(28), dp(20), dp(28))

            addView(header())
            addView(sectionLabel("Status"))
            addView(card().apply {
                addView(dotAdbd.view); addView(divider()); addView(dotAgent.view)
                addView(divider()); addView(dotSignals.view)
            })
            addView(sectionLabel("Actions"))
            addView(card().apply { addView(apply); addView(recheck); addView(remove) })
            addView(sectionLabel("CAN transmit"))
            addView(txCard())
            addView(sectionLabel("Activity"))
            addView(logPanel())
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })

        registerReceiver(signalReceiver, IntentFilter(ACTION_SIGNALS), RECEIVER_EXPORTED)
        log("loader started")

        // Self-configure on launch (SMO behaviour): inject if not already applied, and reconcile the
        // box-wide TX arm marker to the saved toggle state (default OFF = disarmed).
        thread {
            val applied = Patcher(this).isCurrentApplied()
            if (!applied && !busy) runActionBlocking { Patcher(this).apply() }
            runCatching { Patcher(this).setTxArmed(txEnabled) }
            LivenessJobService.schedule(applicationContext)
            refresh()
        }
        startStatusLoop()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(signalReceiver) }
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // --- actions ---
    private fun runAction(block: () -> String) { thread { runActionBlocking(block) } }

    private fun runActionBlocking(block: () -> String) {
        if (busy) return
        busy = true
        log("working…")
        val result = try { block() } catch (t: Throwable) { "ERROR: ${t.message}" } finally { busy = false }
        log(result)
        refresh()
    }

    // --- status ---
    private fun startStatusLoop() {
        ui.post(object : Runnable {
            override fun run() {
                paintSignals()
                ui.postDelayed(this, 1000)
            }
        })
    }

    /** Connects to adbd and checks the bind-mount (blocking; call off the main thread). */
    private fun refresh() {
        val p = Patcher(this)
        val adbdOk = p.canConnect()
        val applied = adbdOk && p.isApplied()
        ui.post {
            sAdbd = if (adbdOk) Status.OK else Status.BAD
            dotAdbd.set(sAdbd, if (adbdOk) "Connected" else "Unreachable — enable debugging + authorize this app")
            sAgent = if (applied) Status.OK else Status.BAD
            dotAgent.set(sAgent, if (applied) "Active" else "Not applied")
            paintSignals()
        }
    }

    private fun paintSignals() {
        val age = SystemClock.elapsedRealtime() - lastBatchAt
        val alive = lastBatchAt > 0 && age < 3000
        // A batch heartbeats every ~500ms even with no readable signal, so distinguish the states:
        // green = signals flowing; amber = producer alive but the car has no data / its service is
        // down; red = no heartbeat at all (producer stopped).
        val state: Status
        val detail: String
        when {
            !alive && lastBatchAt == 0L -> { state = Status.BAD; detail = "No broadcasts yet" }
            !alive -> { state = Status.BAD; detail = "Producer silent ${age / 1000}s (agent stopped?)" }
            lastFlags and FLAG_CAR_DOWN != 0 ->
                { state = Status.WARN; detail = "Agent alive — vehicle service down (reconnecting)" }
            lastFlags and FLAG_NO_DATA != 0 ->
                { state = Status.WARN; detail = "Agent alive — car asleep/parked (0 signals)" }
            else -> { state = Status.OK; detail = "Flowing ($lastBatchCount signals @~10Hz)" }
        }
        sSignals = state
        dotSignals.set(state, detail)
    }

    private fun updateOverall() {
        val all = listOf(sAdbd, sAgent, sSignals)
        val (text, color) = when {
            all.any { it == Status.BAD } -> "Needs attention" to RED
            all.any { it == Status.WARN } -> "Live — limited data" to AMBER
            all.all { it == Status.OK } -> "Ready" to GREEN
            else -> "Checking…" to MUTED
        }
        headerPill.text = "●  $text"
        headerPill.setTextColor(color)
        headerPill.background = rounded(pillFill(color), dp(20))
    }

    // --- timestamped activity log ---
    private fun log(s: String) = ui.post {
        logBuf.append(clock.format(Date())).append("   ").append(s).append('\n')
        if (logBuf.length > 6000) logBuf.delete(0, logBuf.length - 6000)
        logView.text = logBuf.trimEnd()
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // --- layout builders (no androidx) ---
    private fun header(): View {
        val title = TextView(this).apply {
            text = "Screenmate CAN"; textSize = 24f; setTextColor(TXT)
            typeface = Typeface.DEFAULT_BOLD
        }
        val subtitle = TextView(this).apply {
            text = "privileged loader"; textSize = 14f; setTextColor(MUTED)
            setPadding(0, dp(2), 0, dp(12))
        }
        headerPill = TextView(this).apply {
            text = "●  Checking…"; textSize = 13f; setTextColor(MUTED)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(pillFill(MUTED), dp(20))
            setPadding(dp(14), dp(7), dp(14), dp(7))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, 0, dp(8))
            addView(title); addView(subtitle)
            addView(headerPill, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
    }

    private fun sectionLabel(t: String) = TextView(this).apply {
        text = t.uppercase(Locale.US); textSize = 12f; setTextColor(MUTED)
        letterSpacing = 0.10f
        setPadding(dp(4), dp(18), 0, dp(8))
    }

    /** A rounded dark card that stretches full-width with a bottom margin. */
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, dp(18))
        setPadding(dp(18), dp(6), dp(18), dp(6))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(STROKE)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1))
    }

    private fun logPanel(): View {
        logScroll = ScrollView(this).apply {
            background = rounded(INSET, dp(14))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(190))
            addView(logView)
            isVerticalScrollBarEnabled = true
        }
        return logScroll
    }

    // --- styled buttons (larger touch targets for a car screen) ---
    private fun primaryButton(label: String, onClick: () -> Unit) =
        styledButton(label, fill = BLUE, textColor = Color.WHITE, bold = true, onClick = onClick)

    private fun secondaryButton(label: String, onClick: () -> Unit) =
        styledButton(label, fill = Color.TRANSPARENT, textColor = TXT, stroke = STROKE_HI, strokeW = dp(1), onClick = onClick)

    private fun dangerButton(label: String, onClick: () -> Unit) =
        styledButton(label, fill = Color.TRANSPARENT, textColor = RED, stroke = pillFill(RED, 0x55), strokeW = dp(1), onClick = onClick)

    private fun styledButton(
        label: String, fill: Int, textColor: Int, stroke: Int = 0, strokeW: Int = 0,
        bold: Boolean = false, onClick: () -> Unit,
    ) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(textColor)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        background = rounded(fill, dp(12), stroke, strokeW)
        minHeight = dp(54)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(8); bottomMargin = dp(8)
        }
    }

    // --- CAN transmit master arm ---
    /**
     * The one TX control this loader keeps: a persisted master switch that ARMS/DISARMS CAN transmit
     * box-wide. Flipping it writes the arm marker via [Patcher.setTxArmed] over root adbd, which the
     * `SmCanTx` executor checks before every transmit — so OFF blocks ALL TX (including the
     * jbv1-bridge consumer), ON allows it (the executor's allowlist + speed gate still apply). Default
     * OFF. This app itself never puts a frame on the bus.
     */
    private fun txCard(): View {
        txSwitch = Switch(this).apply {
            text = "Enable CAN transmit"
            textSize = 16f
            setTextColor(TXT)
            typeface = Typeface.DEFAULT_BOLD
            isChecked = txEnabled
            setPadding(0, dp(4), 0, dp(4))
            setOnCheckedChangeListener { _, isChecked ->
                txEnabled = isChecked
                updateTxUi()
                runAction { Patcher(this@MainActivity).setTxArmed(isChecked) }
            }
        }
        txStatus = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(8), 0, dp(2))
        }
        txCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            addView(txSwitch); addView(txStatus)
        }
        updateTxUi()
        return txCard
    }

    private fun updateTxUi() = ui.post {
        if (txEnabled) {
            txCard.background = rounded(CARD, dp(18), RED, dp(2))
            txStatus.setTextColor(RED)
            txStatus.text = "⚠ ARMED — consumers can now write to the live vehicle bus box-wide. " +
                "Deny-by-default allowlist + speed gate still apply in the executor."
            txSwitch.thumbTintList = ColorStateList.valueOf(RED)
            txSwitch.trackTintList = ColorStateList.valueOf(pillFill(RED, 0x66))
        } else {
            txCard.background = rounded(CARD, dp(18))
            txStatus.setTextColor(MUTED)
            txStatus.text = "Disabled — no consumer can transmit. This app never puts a frame on the bus."
            txSwitch.thumbTintList = ColorStateList.valueOf(Color.parseColor("#C7CED6"))
            txSwitch.trackTintList = ColorStateList.valueOf(STROKE_HI)
        }
    }

    /** One status row: colour-coded dot, bold label, muted detail line. */
    private inner class StatusRow(label: String, initialDetail: String) {
        private val dot = TextView(this@MainActivity).apply {
            text = "●"; textSize = 14f; setTextColor(MUTED)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(22), WRAP_CONTENT)
        }
        private val labelView = TextView(this@MainActivity).apply {
            text = label; textSize = 16f; setTextColor(TXT); typeface = Typeface.DEFAULT_BOLD
        }
        private val detailView = TextView(this@MainActivity).apply {
            text = initialDetail; textSize = 13f; setTextColor(MUTED); setPadding(0, dp(2), 0, 0)
        }
        val view = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, dp(14))
            addView(dot)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(labelView); addView(detailView)
            })
        }
        fun set(state: Status, detail: String) {
            dot.setTextColor(statusColor(state))
            detailView.text = detail
            updateOverall()
        }
    }

    /**
     * Decode a broadcast batch just far enough to report the signal count + car-state flags in the
     * status row. Handles wire versions v1/v2/v3 (see docs/PROTOCOL.md): v1 (int version, int count,
     * records); v2 (int version, long seq, long tsElapsed, int flags, int count, records); v3 (v2 plus
     * int reconnectCount + int maxReadMicros before count). Sets [lastFlags] and returns the count, or
     * 0 on an unknown version / malformed batch.
     */
    private fun parseSignals(b: ByteArray): Int = runCatching {
        val din = DataInputStream(ByteArrayInputStream(b))
        when (din.readInt()) {
            1 -> { lastFlags = 0; din.readInt() }
            // v2: long seq, long tsElapsed, int flags, int count (then records + 8-byte HMAC we skip)
            2 -> { din.readLong(); din.readLong(); lastFlags = din.readInt(); din.readInt() }
            // v3: long seq, long tsElapsed, int flags, int reconnectCount, int maxReadMicros, int count
            3 -> {
                din.readLong(); din.readLong(); lastFlags = din.readInt()
                din.readInt(); din.readInt()  // reconnectCount, maxReadMicros
                din.readInt()                 // count (returned)
            }
            else -> { lastFlags = 0; 0 }
        }
    }.getOrDefault(0)

    // --- palette & small drawable helpers ---
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, radius: Int, stroke: Int = 0, strokeW: Int = 0) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            if (strokeW > 0) setStroke(strokeW, stroke)
        }

    /** A translucent tint of [color] for pill/track fills. */
    private fun pillFill(color: Int, alpha: Int = 0x22) = (alpha shl 24) or (color and 0x00FFFFFF)

    private fun statusColor(s: Status) = when (s) {
        Status.OK -> GREEN
        Status.WARN -> AMBER
        Status.BAD -> RED
        Status.IDLE -> MUTED
    }

    private enum class Status { IDLE, OK, WARN, BAD }

    private companion object {
        const val ACTION_SIGNALS = "co.screenmate.can.agent.SIGNALS"
        const val EXTRA_BATCH = "batch"
        const val PERM_RECEIVE = "co.screenmate.can.permission.SIGNALS"
        const val FLAG_NO_DATA = 0x1
        const val FLAG_CAR_DOWN = 0x2

        // Dark "automotive" palette.
        val BG = Color.parseColor("#0E1013")
        val CARD = Color.parseColor("#191C21")
        val INSET = Color.parseColor("#101317")
        val TXT = Color.parseColor("#ECEFF3")
        val LOGTXT = Color.parseColor("#AEB6C0")
        val MUTED = Color.parseColor("#8B95A1")
        val STROKE = Color.parseColor("#23272E")
        val STROKE_HI = Color.parseColor("#333A44")
        val GREEN = Color.parseColor("#33C26B")
        val AMBER = Color.parseColor("#E7A13B")
        val RED = Color.parseColor("#E5544B")
        val BLUE = Color.parseColor("#3B82F6")
    }
}
