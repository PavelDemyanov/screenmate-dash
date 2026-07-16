package app.smdash

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.smdash.model.DashStore
import app.smdash.model.DashStyle
import app.smdash.model.DashboardState
import app.smdash.model.mockDashboardFlow
import app.smdash.model.parseStockState
import app.smdash.ui.DashboardTile
import app.smdash.ui.MiniTile
import app.smdash.ui.StackTile
import app.smdash.ui.StripTile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Foreground service: draws the dashboard as a draggable system overlay over the
 *  Tesla video. Five quick taps hide it (revealing the stock dashboard underneath);
 *  a SHOWOURS broadcast brings it back.
 *
 *  The window is always the full tile size of the SELECTED style ([DashStyle]) and is never
 *  resized during gestures (→ no flash); the one sanctioned resize is a discrete style switch
 *  ([onStyleChanged]). The tile is drawn with `graphicsLayer` alpha + scale (pivot top-center,
 *  so it stays anchored at the top and shrinks downward). All collapse geometry uses the
 *  *visible* (scaled) height `effH = curH*scale`. Drag freely repositions the open dashboard;
 *  dragging it up so
 *  the visible bottom edge enters the top band collapses it into a faint handle pill at
 *  that edge. Pinch (two fingers) scales 50%..100%. The active touch area is limited to
 *  the visible (scaled) dashboard, so there's no invisible grab zone below it. */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private var view: ComposeView? = null // visuals (full-size, NOT_TOUCHABLE)
    private var touchView: ComposeView? = null // gestures (small, sized to the visible dashboard)
    private val owner = OverlayLifecycleOwner()
    private lateinit var lp: WindowManager.LayoutParams
    private val taps = ArrayDeque<Long>()

    /** dashboard opacity (0..1). 0 = collapsed (only the handle pill), 1 = open. */
    private val dashAlpha = MutableStateFlow(1f)

    /** dashboard scale (MIN_SCALE..1) set by a two-finger pinch. */
    private val dashScale = MutableStateFlow(1f)

    /** committed collapsed state (for tap handling + restore); content follows dashAlpha. */
    private var collapsed = false

    private var animator: ValueAnimator? = null

    private val scope = CoroutineScope(Dispatchers.Main)
    private var mockJob: Job? = null
    /** post-forced-start re-assert (boot race guard); cancelled on a new start / switch-to-stock */
    private var reassertJob: Job? = null
    // realSeen / lastRealAtMs / mockActive live in the companion — ReportCollector reads them.

    // turn-signal hold (data layer). isIndicator{Left,Right} arrives as a brief ~20ms pulse once per
    // ~720ms blink cycle — shorter than a Compose frame, so StateFlow + collectAsState would coalesce
    // it away and the UI would never see it. We latch EACH SIDE independently (onReceive sees EVERY
    // broadcast) for TURN_HOLD_MS after its last pulse and feed a STABLE state to DashStore. Sides are
    // held separately so hazard (both indicators) survives even if the two sides pulse out of phase.
    // Blind-spot flags are levels, not pulses → they pass straight through (no hold).
    private var heldLeft = false
    private var heldRight = false
    private var clearLeftJob: Job? = null
    private var clearRightJob: Job? = null
    private var lastState = DashboardState()

    private fun pushState(parsed: DashboardState) {
        lastState = parsed
        if (parsed.turnLeft) {
            heldLeft = true
            clearLeftJob?.cancel()
            clearLeftJob = scope.launch { delay(TURN_HOLD_MS); heldLeft = false; emitHeld() }
        }
        if (parsed.turnRight) {
            heldRight = true
            clearRightJob?.cancel()
            clearRightJob = scope.launch { delay(TURN_HOLD_MS); heldRight = false; emitHeld() }
        }
        emitHeld()
        publishTransp()
    }

    /** publish the latest state with the turn sides latched on (blind flags pass straight through). */
    private fun emitHeld() {
        DashStore.flow.value = lastState.copy(
            turnLeft = lastState.turnLeft || heldLeft,
            turnRight = lastState.turnRight || heldRight,
        )
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                ACTION_STATE -> {
                    val s = i.getStringExtra("s") ?: return
                    realSeen = true
                    lastRealAtMs = SystemClock.elapsedRealtime()
                    mockActive = false
                    mockJob?.cancel() // real data arrived → stop the emulator demo loop
                    runCatching { pushState(parseStockState(s)) }
                }
                // 5 taps on the STOCK dashboard land here with NO style extra → enter the cycle at
                // the FIRST style (Arc); the settings panel sends a "style" extra to jump straight to
                // the chosen style. Either way this re-arms the master gate (a pick means "show it").
                ACTION_SHOW_OURS -> {
                    // the panel sends a "style" extra → jump to it; a bare 5-tap on the stock sends
                    // none → fromKey(null) = ARC, the first stop of the ring stock → Arc → Stack →
                    // Strip → Mini → stock (the Arc→…→stock steps are OUR overlay's own 5-tap cycle).
                    DashStore.style.value = DashStyle.fromKey(i.getStringExtra("style"))
                    prefs.edit().putBoolean("master", true).apply()
                    showOverlay()
                }
                ACTION_SHOW_STOCK -> {
                    prefs.edit().putBoolean("master", true).apply()
                    hideOverlay()
                }
                // master toggle OFF in the settings panel → hide BOTH dashboards
                ACTION_HIDE_ALL -> hideBoth()
                ACTION_SET_TRANSP -> setTranspOverride(i.getFloatExtra("v", -1f))
                ACTION_SEND_REPORT -> handleSendReport(i.getStringExtra("comment"))
            }
        }
    }

    /** Panel tapped "Send report": collect diagnostics off-thread, POST them, and mirror the
     *  result into Settings.Global so the panel can show the report id (+ a Toast on the box). */
    private fun handleSendReport(comment: String?) {
        writeReportGlobal(ReportCollector.GLOBAL_REPORT_STATUS, "sending")
        runCatching { Toast.makeText(this, "Sending report…", Toast.LENGTH_SHORT).show() }
        scope.launch(Dispatchers.IO) {
            val id = runCatching { ReportCollector.send(this@OverlayService, comment) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (id != null) {
                    writeReportGlobal(ReportCollector.GLOBAL_REPORT_ID, id)
                    writeReportGlobal(ReportCollector.GLOBAL_REPORT_STATUS, "ok")
                    runCatching { Toast.makeText(this@OverlayService, "Report sent: #$id", Toast.LENGTH_LONG).show() }
                } else {
                    writeReportGlobal(ReportCollector.GLOBAL_REPORT_STATUS, "err")
                    runCatching { Toast.makeText(this@OverlayService, "Couldn't send report", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    private fun writeReportGlobal(key: String, value: String) {
        runCatching { Settings.Global.putString(contentResolver, key, value) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- tile size of the SELECTED style (px). The window is created at exactly this size and is
    // never resized during a gesture; it only changes when the user picks another style — or when
    // the car's clock format flips 12h↔24h (STRIP's card is wider with the AM/PM block; the flip
    // comes from the car settings, so it's just as discrete as a style switch). ---
    /** STRIP grows for 12h clocks. Prefer live state; fall back to the last-seen format (and the
     *  system 12/24h setting on first boot) so a cold start doesn't create the narrow 24h window
     *  and clip "AM"/"PM" until a style cycle forces a resize. */
    private fun useAmpmWidth(): Boolean =
        DashStore.flow.value.ampm.isNotEmpty() ||
            prefs.getBoolean(PREF_AMPM_WIDTH, !DateFormat.is24HourFormat(this))

    private fun curW(): Int = DashStore.style.value.pxWFor(useAmpmWidth())
    private fun curH(): Int = DashStore.style.value.pxH

    /** visible (scaled) dashboard height in px — all collapse geometry uses this. */
    private fun effH(): Int = (curH() * dashScale.value).roundToInt()

    /** bottom-edge fade ramp, capped for low tiles (a 300px ramp would keep STRIP half-faded). */
    private fun fadeSpan(): Float = minOf(FADE_SPAN, effH() * 0.55f)

    /** on release, a visible bottom edge above this collapses (≈34% of the visible height — the
     *  same threshold the fixed 230px constant encoded for the full-size ARC tile). */
    private fun expandCommit(): Int = STRIP_BOTTOM + (0.34f * effH()).roundToInt()

    // --- drag bounds for the open dashboard (visible content must stay on screen,
    // and the top can't be lowered past BOTTOM_FRAC of the screen) ---
    private fun minX(): Int = -((curW() * (1f - dashScale.value)) / 2f).roundToInt()
    private fun maxX(): Int =
        (resources.displayMetrics.widthPixels - curW() * (1f + dashScale.value) / 2f).roundToInt()
    /** lowest the top may rest: keep the bottom BOTTOM_FRAC of the screen clear (by bottom edge). */
    private fun maxY(): Int =
        (resources.displayMetrics.heightPixels * (1f - BOTTOM_FRAC) - effH()).roundToInt().coerceAtLeast(0)

    /** clamp the collapsed strip's X so the (centered) handle pill stays fully on screen. */
    private fun clampStripX(x: Int): Int {
        val half = STRIP_W_PX / 2
        val center = curW() / 2 // pill is centered in the full-width window
        return x.coerceIn(-(center - half), resources.displayMetrics.widthPixels - (center + half))
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotif()
        prefs = getSharedPreferences("overlay", MODE_PRIVATE)
        app.smdash.model.CompactTuning.load(this) // apply Pavel's per-element text nudges to the live tiles
        owner.create()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(ACTION_STATE); addAction(ACTION_SHOW_OURS); addAction(ACTION_SHOW_STOCK)
                addAction(ACTION_HIDE_ALL); addAction(ACTION_SET_TRANSP); addAction(ACTION_SEND_REPORT)
            },
            Context.RECEIVER_EXPORTED,
        )

        dashScale.value = prefs.getFloat("scale", 1f).coerceIn(MIN_SCALE, 1f)
        collapsed = prefs.getBoolean("collapsed", false)
        // Follow the car's stock "Dashboard Transparency" slider by default: unset pref → -1f → null
        // = AUTO, so the transparency that rides in the stock broadcast drives our panel. A user who
        // sets OUR own in-app slider persists a 0..0.8 value here, which then overrides the stock's.
        DashStore.transpOverride.value = prefs.getFloat("transp", -1f).takeIf { it >= 0f }
        DashStore.style.value = DashStyle.fromKey(prefs.getString("style", null)) // before lp — it sizes the window
        // Publish our version for the injected stock settings panel (it runs in the stock/settings
        // process and Android's package-visibility filter blocks getPackageInfo("app.smdash") there,
        // but Settings.Global is readable without a query). Refreshed on every service (re)start.
        runCatching {
            Settings.Global.putString(
                contentResolver, "smdash_ver",
                packageManager.getPackageInfo(packageName, 0).versionName,
            )
        }
        publishTransp()

        lp = WindowManager.LayoutParams(
            curW(), curH(), // fixed per style — only the content scales; changes ONLY on a style switch
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or // all touches pass through; gestures live in touchView
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("ox", 40)
            y = if (collapsed) STRIP_BOTTOM - effH() else prefs.getInt("oy", RESTING_TOP)
        }
        if (collapsed) {
            lp.x = prefs.getInt("sx", lp.x)
            dashAlpha.value = 0f
        }

        // demo data until the stock hook sends real state (so the emulator shows a live tile);
        // the mock mirrors the system 12/24h setting so the AM/PM layout is testable off-car
        mockActive = true
        mockJob = scope.launch {
            mockDashboardFlow(is24h = DateFormat.is24HourFormat(this@OverlayService))
                .collect { if (!realSeen) DashStore.flow.value = it }
        }

        // live style switching from the settings panel (same process → the flow is the channel)
        scope.launch {
            var last = DashStore.style.value
            DashStore.style.collect { s -> if (s != last) { last = s; onStyleChanged() } }
        }

        // clock format flip (12h↔24h from the car settings) changes STRIP's card width → the same
        // discrete window resize as a style switch (detected on the data, so mock and real both work)
        scope.launch {
            var last = DashStore.flow.value.ampm.isNotEmpty()
            DashStore.flow.collect { st ->
                val ampm = st.ampm.isNotEmpty()
                if (ampm != last) {
                    last = ampm
                    prefs.edit().putBoolean(PREF_AMPM_WIDTH, ampm).apply()
                    applyWindowGeometry()
                }
            }
        }

        // Visibility (and the stock-hide flag) is decided in onStartCommand — the single
        // reconciliation point — so the flag, the persisted choice and the actual window can
        // never drift apart. onStartCommand always runs right after onCreate for a started service.
    }

    /** The VISIBLE dashboard. Lives in a full-size, NOT_TOUCHABLE window (passes all touches
     *  through). Scaled/faded with graphicsLayer — never resized, so no flashing. */
    private fun buildDrawView(): ComposeView {
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val rawState by DashStore.flow.collectAsState()
                val transp by DashStore.transpOverride.collectAsState()
                // manual override (settings panel / SETTRANSP) wins over the car's stock value
                val state = transp?.let { rawState.copy(bgTransparency = it) } ?: rawState
                val alpha by dashAlpha.collectAsState()
                val scale by dashScale.collectAsState()
                val style by DashStore.style.collectAsState()
                Box(Modifier.fillMaxSize()) {
                    // dashboard fills the window; fades with alpha, scales with the pinch,
                    // anchored at the top (top-center pivot) so it shrinks downward
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            this.alpha = alpha
                            scaleX = scale; scaleY = scale
                            transformOrigin = TransformOrigin(0.5f, 0f)
                        },
                    ) {
                        when (style) {
                            DashStyle.ARC -> DashboardTile(state)
                            DashStyle.STACK -> StackTile(state)
                            DashStyle.STRIP -> StripTile(state)
                            DashStyle.MINI -> MiniTile(state)
                        }
                    }
                    // faint handle pill at the visible (scaled) bottom edge — only while collapsed
                    val pillY = with(LocalDensity.current) { (style.pxH * scale).toDp() } - 26.dp
                    Box(
                        Modifier.align(Alignment.TopCenter).offset(y = pillY)
                            .alpha((1f - alpha).coerceIn(0f, 1f)),
                    ) { CollapsedHandle() }
                }
            }
        }
    }

    /** Transparent, sized to exactly the visible dashboard ([touchLp]). This is the ONLY touchable
     *  window — touches outside it pass straight through to apps behind (small window + NOT_TOUCH_
     *  MODAL). Hosts all gestures; no in-bounds check needed since the window IS the grab area. */
    private fun buildTouchView(): ComposeView {
        val svc = this
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var pinch = false
                            var moved = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val cnt = event.changes.count { it.pressed }
                                if (cnt >= 2) {
                                    pinch = true
                                    val z = event.calculateZoom()
                                    if (z != 1f) svc.onPinch(z)
                                } else if (cnt == 1 && !pinch) {
                                    val pan = event.calculatePan()
                                    if (pan != Offset.Zero) { moved = true; svc.onDrag(pan.x, pan.y) }
                                }
                                event.changes.forEach { it.consume() }
                                if (event.changes.none { it.pressed }) break
                            }
                            when {
                                pinch -> svc.onPinchEnd()
                                moved -> svc.onDragEnd()
                                else -> svc.onTap()
                            }
                        }
                    },
                )
            }
        }
    }

    /** Touch-window params: positioned/sized to the *visible* (scaled) dashboard plus a little side
     *  slop. Collapsed = the draw window slides off the top, so this same rect lands as the strip
     *  at the screen top — one formula covers both. */
    private fun touchLp(): WindowManager.LayoutParams {
        val s = dashScale.value
        val vw = (curW() * s).roundToInt()
        val pad = GRAB_PAD.toInt()
        return WindowManager.LayoutParams(
            vw + 2 * pad, effH(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or // touches outside this small window pass through
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lp.x + ((curW() - vw) / 2f).roundToInt() - pad
            y = lp.y
        }
    }

    /** The user picked another dashboard style: persist it, un-collapse (a switch should present
     *  the new dashboard), resize the window to the new tile and re-clamp the position. This is
     *  the ONE sanctioned window resize — discrete, outside any gesture, so no per-frame jank. */
    private fun onStyleChanged() {
        prefs.edit().putString("style", DashStore.style.value.key).apply()
        applyWindowGeometry()
        publishSelection() // keep smdash_style current so the settings panel reflects 5-tap cycling too
    }

    /** Re-size/re-clamp the window to the current effective tile size (style and clock format).
     *  This is the ONE sanctioned discrete resize path — never called from inside a gesture.
     *  [expand] (default true) un-collapses: style / clock flips should present the full tile.
     *  Pass false from [showOverlay] so a sticky restart can keep a user-collapsed handle. */
    private fun applyWindowGeometry(expand: Boolean = true) {
        animator?.cancel()
        if (expand) {
            if (collapsed) {
                collapsed = false
                prefs.edit().putBoolean("collapsed", false).apply()
            }
            dashAlpha.value = 1f
            lp.x = clampX(lp.x)
            lp.y = lp.y.coerceIn(0, maxY())
        }
        lp.width = curW()
        lp.height = curH()
        view?.let { runCatching { wm.updateViewLayout(it, lp) } }
        syncTouch()
    }

    /** Re-align the touch window to the dashboard (call once the dashboard settles). */
    private fun syncTouch() {
        val tv = touchView ?: return
        runCatching { wm.updateViewLayout(tv, touchLp()) }
    }

    /** Show our dashboard. Idempotent: adds the windows only if missing, but ALWAYS re-asserts the
     *  persisted choice + the stock-hide flag, so it doubles as the "ensure ours is up" reconcile.
     *  If the windows can't attach (e.g. the SYSTEM_ALERT_WINDOW grant hasn't propagated yet at a
     *  cold boot) it rolls the partial add back, reveals the stock so the screen is never blank, and
     *  leaves view==null so the next reconcile (re-assert / sticky restart) retries the attach.
     *
     *  Always finishes with [applyWindowGeometry] (size-only). A cold-boot [addView] can leave the
     *  ComposeView measuring wrong / at the pre-AMPM STRIP width until the first [updateViewLayout]
     *  — which is exactly what cycling styles already did via [onStyleChanged]. */
    private fun showOverlay() {
        if (view == null) {
            // Size from current style + clock hint *before* attach (lp may still be the onCreate
            // snapshot taken before mock/real ampm arrived).
            lp.width = curW()
            lp.height = curH()
            val v = buildDrawView()
            val tv = buildTouchView()
            val added = runCatching {
                wm.addView(v, lp)         // visuals — full-size, NOT_TOUCHABLE (passes through)
                wm.addView(tv, touchLp()) // gestures — small, only over the visible dashboard
            }
            if (added.isFailure) {
                runCatching { wm.removeView(v) }
                runCatching { wm.removeView(tv) }
                Log.e(TAG, "addView failed; revealing stock, will retry on next reconcile", added.exceptionOrNull())
                setStockHidden(false) // never leave the stock hidden with no window of ours = blank
                return
            }
            view = v
            touchView = tv
        }
        // Force the same layout pass a style switch gets — even when width/height are unchanged.
        applyWindowGeometry(expand = false)
        prefs.edit().putBoolean("ours", true).apply()
        setStockHidden(true) // ours is up → hide the stock dashboard (re-asserted on every reconcile)
        publishSelection()
    }

    private fun hideOverlay() {
        reassertJob?.cancel(); reassertJob = null // a deliberate switch-to-stock beats an in-flight boot re-assert
        view?.let { runCatching { wm.removeView(it) } }
        touchView?.let { runCatching { wm.removeView(it) } }
        view = null
        touchView = null
        prefs.edit().putBoolean("ours", false).apply()
        setStockHidden(false) // ours hidden → reveal the stock dashboard
        publishSelection()
    }

    /** Master gate OFF (settings-panel "Show Dashboard" switch): hide BOTH dashboards. Remove our
     *  windows AND raise the stock-hide flag so the stock dashboard is hidden too (its observer
     *  reacts). The "ours"/"style" prefs are left intact, so flipping the master back on restores the
     *  last chosen dashboard. */
    private fun hideBoth() {
        reassertJob?.cancel(); reassertJob = null
        view?.let { runCatching { wm.removeView(it) } }
        touchView?.let { runCatching { wm.removeView(it) } }
        view = null
        touchView = null
        prefs.edit().putBoolean("master", false).apply()
        setStockHidden(true) // hide the stock too → the screen shows neither dashboard
        publishSelection()
    }

    /** Mirror the current selection into Settings.Global so the STOCK settings panel (which can only
     *  READ globals — it lacks WRITE_SECURE_SETTINGS) can show the right thumbnail highlight + master
     *  state on open. Our app owns every write to these keys. */
    private fun publishSelection() {
        runCatching {
            val master = prefs.getBoolean("master", true)
            val ours = prefs.getBoolean("ours", true)
            val sel = if (ours) DashStore.style.value.key else "stock"
            Settings.Global.putString(contentResolver, "smdash_style", sel)
            Settings.Global.putInt(contentResolver, "smdash_master", if (master) 1 else 0)
        }.onFailure { Log.e(TAG, "publishSelection failed", it) }
    }

    /** Set (or clear, v<0) the manual transparency override and persist it. */
    private fun setTranspOverride(v: Float) {
        val t = if (v < 0f) null else v.coerceIn(0f, 0.8f)
        DashStore.transpOverride.value = t
        prefs.edit().putFloat("transp", t ?: -1f).apply()
        publishTransp()
    }

    private var lastTranspPub = -999

    /** Mirror the effective transparency (percent 0..80) into Settings.Global so the injected stock
     *  settings panel can open OUR own transparency slider at the right position (Global is readable
     *  without a query, unlike getPackageInfo). Throttled: only writes when the percent changes. */
    private fun publishTransp() {
        runCatching {
            val t = (DashStore.transpOverride.value ?: lastState.bgTransparency).coerceIn(0f, 0.8f)
            val pct = (t * 100).roundToInt()
            if (pct != lastTranspPub) {
                Settings.Global.putInt(contentResolver, "smdash_transp", pct)
                lastTranspPub = pct
            }
        }
    }

    /** Flag the stock app reads (Settings.Global) to hide/show itself. We guarantee an onChange even
     *  when the value already equals the target: a freshly cold-booted stock may register its
     *  ContentObserver AFTER our first write, and the settings provider fires no onChange for an
     *  unchanged value — so toggle once to force the late observer to re-read. Failures (e.g. a
     *  mis-granted WRITE_SECURE_SETTINGS) are logged instead of silently breaking the invariant. */
    private fun setStockHidden(hidden: Boolean) {
        val target = if (hidden) 1 else 0
        runCatching {
            val cur = Settings.Global.getInt(contentResolver, "smdash_hide", -1)
            if (cur == target) Settings.Global.putInt(contentResolver, "smdash_hide", if (hidden) 0 else 1)
            Settings.Global.putInt(contentResolver, "smdash_hide", target)
        }.onFailure { Log.e(TAG, "setStockHidden($hidden) failed", it) }
    }

    /** Opacity from the visible bottom-edge Y: 0 at the collapsed line, → 1 over [fadeSpan]. */
    private fun alphaFor(bottomY: Int): Float =
        ((bottomY - STRIP_BOTTOM) / fadeSpan()).coerceIn(0f, 1f)

    fun onTap() {
        if (collapsed) { // a tap on the handle opens the dashboard
            expand()
            return
        }
        val now = SystemClock.uptimeMillis()
        taps.addLast(now)
        while (taps.isNotEmpty() && now - taps.first() > 1500) taps.removeFirst()
        if (taps.size >= 5) {
            taps.clear()
            cycleStyle() // five taps → advance to the next dashboard in the cycle
        }
    }

    /** Five-tap cycle: stock → Arc → Stack → Strip → Mini → stock, round and round. Called only
     *  while OUR dashboard is showing (our overlay handles the tap); the stock→Arc step is the
     *  stock's own 5-tap hook ([ACTION_SHOW_OURS]). Advancing a style resizes the window live via
     *  the [DashStore.style] collector; a tap on the last style (Mini) drops back to the stock. */
    private fun cycleStyle() {
        val order = DashStyle.entries // ARC, STACK, STRIP, MINI (enum declaration order)
        val idx = order.indexOf(DashStore.style.value)
        if (idx in 0 until order.lastIndex) {
            DashStore.style.value = order[idx + 1] // next style, stay on ours
        } else {
            hideOverlay() // was the last style → back to the stock dashboard
        }
    }

    fun onDrag(dx: Float, dy: Float) {
        val v = view ?: return
        animator?.cancel()
        val h = effH()
        // free drag — the dashboard can even be pulled off the edges; it springs back on release
        lp.x += dx.roundToInt()
        var ny = lp.y + dy.roundToInt()
        // never let the visible bottom edge rise above the collapsed line (top stays free for collapse)
        val minY = STRIP_BOTTOM - h
        if (ny < minY) ny = minY
        lp.y = ny
        dashAlpha.value = alphaFor(lp.y + h)
        runCatching { wm.updateViewLayout(v, lp) }
    }

    fun onDragEnd() {
        val bottomY = lp.y + effH()
        if (bottomY < expandCommit()) {
            collapse()
        } else {
            // dropped while open → spring back inside the bounds (and ensure full opacity)
            collapsed = false
            val tx = lp.x.coerceIn(minX(), maxX())
            val ty = lp.y.coerceIn(0, maxY())
            prefs.edit().putBoolean("collapsed", false)
                .putInt("ox", tx).putInt("oy", ty).apply()
            animateTo(toAlpha = 1f, toY = ty, toX = tx)
        }
    }

    /** Two-finger pinch: zoom is the per-event ratio; accumulate into 50%..100%. */
    fun onPinch(zoom: Float) {
        if (collapsed) return // scaling only makes sense while open
        animator?.cancel()
        dashScale.value = (dashScale.value * zoom).coerceIn(MIN_SCALE, 1f)
        // touchView keeps the gesture during the pinch (captured); it's resized at onPinchEnd
    }

    fun onPinchEnd() {
        prefs.edit().putFloat("scale", dashScale.value).putInt("oy", lp.y).apply()
        if (!collapsed) dashAlpha.value = 1f
        syncTouch() // resize the touch window to the new scale
    }

    private fun collapse() {
        collapsed = true
        val tx = clampStripX(lp.x) // spring the strip back on screen if it was dragged off
        prefs.edit().putBoolean("collapsed", true).putInt("sx", tx).apply()
        animateTo(toAlpha = 0f, toY = STRIP_BOTTOM - effH(), toX = tx)
    }

    private fun expand() {
        collapsed = false
        lp.x = clampX(lp.x)
        val ty = prefs.getInt("oy", RESTING_TOP) // open back to the last position
        prefs.edit().putBoolean("collapsed", false).putInt("ox", lp.x).apply()
        animateTo(toAlpha = 1f, toY = ty)
    }

    /** Clamp the open tile's X to the on-screen bounds. */
    private fun clampX(x: Int): Int = x.coerceIn(minX(), maxX())

    /** Animate dashboard alpha and window X/Y to targets (decelerating = springs into place). */
    private fun animateTo(toAlpha: Float, toY: Int, toX: Int = lp.x) {
        val v = view ?: return
        animator?.cancel()
        val fromA = dashAlpha.value
        val fromY = lp.y
        val fromX = lp.x
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                dashAlpha.value = fromA + (toAlpha - fromA) * t
                lp.x = (fromX + (toX - fromX) * t).roundToInt()
                lp.y = (fromY + (toY - fromY) * t).roundToInt()
                runCatching { wm.updateViewLayout(v, lp) }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    dashAlpha.value = toAlpha
                    lp.x = toX
                    lp.y = toY
                    runCatching { wm.updateViewLayout(v, lp) }
                    syncTouch() // re-align the touch window to the settled dashboard
                }
            })
            start()
        }
    }

    /** The single reconciliation point. Runs on every start (boot, "Start overlay", sticky
     *  restart). A *forced* start (boot / install / "Start overlay") always brings OUR dashboard
     *  up; a bare restart restores the last persisted choice. Either way [showOverlay]/[hideOverlay]
     *  set the stock-hide flag to match, so the three state pieces can't drift into a blank screen. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val forceOurs = intent?.getBooleanExtra(EXTRA_SHOW_OURS, false) ?: false
        val forceStock = intent?.getBooleanExtra(EXTRA_SHOW_STOCK, false) ?: false
        reassertJob?.cancel(); reassertJob = null
        // Master gate. Any explicit force (boot / install / "Start" / "Stop") re-arms it; a bare
        // sticky restart honours the last persisted state. Master off ⇒ neither dashboard shows.
        if (forceOurs || forceStock) prefs.edit().putBoolean("master", true).apply()
        if (!prefs.getBoolean("master", true)) {
            hideBoth()
            return START_STICKY
        }
        val wantOurs = when {
            forceStock -> false              // "Stop overlay" → reveal the stock
            forceOurs -> true                // boot / install / "Start overlay" → our dashboard
            else -> prefs.getBoolean("ours", true) // bare sticky restart → restore last choice
        }
        if (wantOurs) {
            // "force ours up" must surface the dashboard itself, not a leftover collapsed handle
            if (forceOurs && collapsed) {
                collapsed = false
                dashAlpha.value = 1f
                lp.y = prefs.getInt("oy", RESTING_TOP)
                prefs.edit().putBoolean("collapsed", false).apply()
            }
            showOverlay()
            // re-assert across a backoff so a stock that registers its hide-observer late on a slow
            // cold OTA boot still gets the flag (showOverlay also re-attaches if a prior addView failed)
            if (forceOurs) reassertJob = scope.launch {
                for (d in REASSERT_BACKOFF_MS) {
                    delay(d)
                    if (!prefs.getBoolean("ours", true)) return@launch
                    showOverlay()
                }
            }
        } else {
            hideOverlay()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        runCatching { scope.cancel() }
        view?.let { runCatching { wm.removeView(it) } }
        touchView?.let { runCatching { wm.removeView(it) } }
        view = null
        touchView = null
        owner.destroy()
        super.onDestroy()
    }

    private fun startForegroundNotif() {
        val ch = "smdash"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ch, "SM Dash", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, ch)
            .setContentTitle("SM Dash")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    companion object {
        const val ACTION_STATE = "app.smdash.STATE"
        const val ACTION_SEND_REPORT = "app.smdash.SENDREPORT"

        /** Data-flow state read by [ReportCollector] for the diagnostic report. Written on the main
         *  thread, read from an IO thread → @Volatile. */
        @Volatile var realSeen = false
        @Volatile var lastRealAtMs = 0L
        @Volatile var mockActive = false
        const val ACTION_SHOW_OURS = "app.smdash.SHOWOURS"
        const val ACTION_SHOW_STOCK = "app.smdash.SHOWSTOCK"
        /** settings-panel master toggle OFF → hide BOTH dashboards (ours removed + stock flag raised) */
        const val ACTION_HIDE_ALL = "app.smdash.HIDEALL"
        const val ACTION_STOCK_VIS = "app.smdash.STOCKVIS"
        /** set the manual transparency override; float extra "v" in 0..0.8, or <0 to clear (follow stock) */
        const val ACTION_SET_TRANSP = "app.smdash.SETTRANSP"
        const val STOCK_PKG = "co.teslogic.screenmate"
        const val TAG = "smdash"

        /** intent extra (boolean): force OUR dashboard up on start (boot / install / "Start
         *  overlay"), ignoring the persisted choice — the service then reconciles the flag to match */
        const val EXTRA_SHOW_OURS = "show_ours"
        /** intent extra (boolean): force the STOCK dashboard ("Stop overlay") — works even if the
         *  service had been killed, because it starts the service which then reconciles the flag */
        const val EXTRA_SHOW_STOCK = "show_stock"
        /** backoff delays (ms) for the post-forced-start re-assert — covers a slow cold OTA boot
         *  where the stock registers its hide-observer seconds after our first flag write */
        val REASSERT_BACKOFF_MS = longArrayOf(2000L, 5000L)

        /** how long to keep a turn signal latched after the last pulse (~2 blink cycles) */
        const val TURN_HOLD_MS = 1500L

        // tile size now lives per style in [DashStyle] (ARC = the historical 570×569)
        /** visible bottom-edge Y (parent coords) when collapsed — the handle pill peeks here */
        const val STRIP_BOTTOM = 36
        /** tile top Y when open (default) */
        const val RESTING_TOP = 24
        /** visible-bottom opacity ramp length (px), capped by [fadeSpan] for low tiles */
        const val FADE_SPAN = 300f
        /** extra px of slop around the visible dashboard that still counts as a grab */
        const val GRAB_PAD = 40f
        /** collapse / expand animation duration */
        const val ANIM_MS = 160L
        /** smallest pinch scale (50% of the original size) */
        const val MIN_SCALE = 0.5f
        /** keep this bottom fraction of the screen clear — the dashboard's bottom edge can't rest below it */
        const val BOTTOM_FRAC = 0.2f
        /** collapsed handle pill width in px (≈168dp at density 240) — for the strip's X bounds */
        const val STRIP_W_PX = 252

        /** Last-seen 12h clock (STRIP needs a wider window). Survives reboot so cold start doesn't
         *  attach the narrow 24h size and clip the AM/PM suffix until a style cycle resizes. */
        const val PREF_AMPM_WIDTH = "ampm_width"
    }
}

/** The thin handle the dashboard collapses into — a solid opaque grey pill (visible on any
 *  background, incl. white video) drawn at the dashboard's bottom edge. */
@Composable
private fun CollapsedHandle() {
    Box(
        Modifier.size(width = 168.dp, height = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 120.dp, height = 5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF808080)),
        )
    }
}
