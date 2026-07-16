package app.smdash.ui

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.smdash.model.ApMode
import app.smdash.model.BeamMode
import app.smdash.model.DashboardState
import app.smdash.model.Tuning

/* ----- palette (variant 03) ----- */
private val PanelTop = Color(0xFF0A0A0B) // near-black (match the stock's very dark panel)
private val PanelBot = Color(0xFF000000) // pure black at the bottom (max darkness at 0 % transparency)
private val PanelBorder = Color(0xFF2B2C30)
private val ArcTrack = Color(0xFF27282C)
private val DigitCol = Color(0xFFF6F6F8)
private val LabelCol = Color(0xFF75777C)
private val GearOff = Color(0x3DFFFFFF)
private val StatCol = Color(0xFFC8C9CD)
private val DimCol = Color(0xFF65676C)
private val SignalGreen = Color(0xFF34E07A)
private val DangerRed = Color(0xFFE53935)  // stock's colour for turn + blind-spot on the same side
private val BlindGold = Color(0xF59E5414) // base; alpha handled in brush

/** speed -> arc color (green→yellow→red), matching the design thresholds. */
fun arcColor(speed: Int): Color {
    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
    val hue = when {
        speed < 72 -> 138f
        speed < 88 -> lerp(138f, 52f, (speed - 72) / 16f)
        speed < 112 -> 52f
        speed < 128 -> lerp(52f, 4f, (speed - 112) / 16f)
        else -> 4f
    }
    return Color.hsl(hue, 0.82f, 0.56f)
}

internal fun battColor(b: Int) = when {
    b < 20 -> Color(0xFFFF5B5B)
    b < 30 -> Color(0xFFF5C420)
    else -> Color(0xFF5FE0B0)
}

/* ----- tiny vector-icon renderer (ports SVG <path> data); shared with the compact tiles ----- */
internal data class VPath(
    val d: String,
    val fill: Color? = null,
    val stroke: Color? = null,
    val sw: Float = 0f,
    val evenOdd: Boolean = false,
)

@Composable
internal fun VIcon(vbW: Float, vbH: Float, paths: List<VPath>, modifier: Modifier) {
    Canvas(modifier) {
        val s = size.width / vbW
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            paths.forEach { vp ->
                val p = PathParser().parsePathString(vp.d).toPath()
                vp.fill?.let {
                    if (vp.evenOdd) p.fillType = PathFillType.EvenOdd
                    drawPath(p, it)
                }
                vp.stroke?.let {
                    drawPath(p, it, style = Stroke(width = vp.sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}

/* ----- icon path data (from the design) ----- */
// autopilot ENGAGED — stock ic_autopilot_on.xml (24×24): blue disc + white wheel
internal val AP_PATHS = listOf(
    VPath("M12,12m-12,0a12,12 0,1 1,24 0a12,12 0,1 1,-24 0", fill = Color(0xFF1461FF)),
    VPath("M12,20C16.418,20 20,16.418 20,12C20,7.582 16.418,4 12,4C7.582,4 4,7.582 4,12C4,16.418 7.582,20 12,20ZM12.002,18.546C15.617,18.546 18.548,15.615 18.548,12C18.548,8.385 15.617,5.455 12.002,5.455C8.388,5.455 5.457,8.385 5.457,12C5.457,15.615 8.388,18.546 12.002,18.546Z", fill = Color.White, evenOdd = true),
    VPath("M6.109,11.322C5.71,11.363 5.327,11.4 5.2,11.4V12.8H5.667C6.67,12.8 7.654,13.073 8.514,13.588C10.18,14.588 11.2,16.39 11.2,18.333V18.8H12.8V18.333C12.8,16.39 13.82,14.588 15.486,13.588C16.346,13.073 17.33,12.8 18.333,12.8H18.8V11.4L17.946,11.315C17.189,11.239 16.456,11.013 15.743,10.746C14.836,10.406 13.448,10 12,10C10.526,10 9.114,10.421 8.207,10.764C7.528,11.022 6.831,11.246 6.109,11.322Z", fill = Color.White),
)
// autopilot AVAILABLE (not engaged) — Pavel's recolor of the on-icon (24×24): dark-grey disc + grey wheel
internal val AP_OFF_PATHS = listOf(
    VPath("M0,12C0,5.373,5.373,0,12,0s12,5.373,12,12-5.373,12-12,12S0,18.627,0,12", fill = Color(0xFF212121)),
    VPath("M12,4c-4.418,0-8,3.582-8,8s3.582,8,8,8,8-3.582,8-8-3.582-8-8-8ZM12.002,18.546c-3.614,0-6.545-2.931-6.545-6.546s2.931-6.545,6.545-6.545,6.546,2.93,6.546,6.545-2.931,6.546-6.546,6.546Z", fill = Color(0xFF494949), evenOdd = true),
    VPath("M6.109,11.322c-.399.041-.782.078-.909.078v1.4h.467c1.003,0,1.987.273,2.847.788,1.666,1,2.686,2.802,2.686,4.745v.467h1.6v-.467c0-1.943,1.02-3.745,2.686-4.745.86-.515,1.844-.788,2.847-.788h.467v-1.4l-.854-.085c-.757-.076-1.49-.302-2.203-.569-.907-.34-2.295-.746-3.743-.746-1.474,0-2.886.421-3.793.764-.679.258-1.376.482-2.098.558Z", fill = Color(0xFF494949)),
)
// stock belt telltale, re-fitted to fill the 32×32 viewport (Pavel's Illustrator export); #ff3a3a
internal val BELT_PATHS = listOf(
    VPath("M20.23,3.904c0,2.156-1.748,3.904-3.904,3.904s-3.904-1.748-3.904-3.904S14.169,0,16.325,0s3.904,1.748,3.904,3.904Z", fill = Color(0xFFFF3A3A)),
    VPath("M9.897,20.24l11.084-10.579c2.48,2.156,4.128,5.985,4.128,10.348,0,.414-.015.824-.044,1.228-3.246-1.528-5.973-2.332-8.74-2.332-2.085,0-4.149.457-6.428,1.335ZM24.792,23.27c1.146.559,2.383,1.218,3.743,1.981l.956-1.701c-1.596-.896-3.057-1.669-4.426-2.313-.05.697-.142,1.376-.274,2.033Z", fill = Color(0xFFFF3A3A), evenOdd = true),
    VPath("M19.328,8.539c-.937-.473-1.949-.731-3.003-.731-4.799,0-8.698,5.344-8.783,11.98l11.785-11.249Z", fill = Color(0xFFFF3A3A)),
    VPath("M14.596,31.972c-3.254-.902-5.86-4.303-6.737-8.702,3.369-1.641,5.939-2.414,8.466-2.414s5.098.773,8.466,2.414c-.886,4.445-3.537,7.87-6.838,8.73.878-1.779,1.574-3.351,2.385-5.286.521-1.244-.357-2.628-1.707-2.659-1.643-.04-3.083-.029-4.718.015-1.318.035-2.212,1.353-1.732,2.581.779,1.992,1.514,3.59,2.416,5.322Z", fill = Color(0xFFFF3A3A)),
    VPath("M25.109,20.008c0,.414-.015.824-.044,1.228-3.246-1.528-5.973-2.332-8.74-2.332-2.085,0-4.149.457-6.428,1.335l11.084-10.579c2.48,2.156,4.128,5.985,4.128,10.348Z", fill = Color(0xFFFF3A3A)),
    VPath("M7.859,23.27c-.208-1.038-.318-2.132-.318-3.262,0-.073,0-.146.001-.22l-4.383,3.761.956,1.701c.18-.101.359-.201.536-.299l.728-.4c.875-.474,1.698-.902,2.48-1.282Z", fill = Color(0xFFFF3A3A)),
    VPath("M19.328,8.539c.581.294,1.135.672,1.653,1.121l5.291-5.051-1.348-1.411-5.595,5.341Z", fill = Color(0xFFFF3A3A)),
)
internal val HEAT_PATHS = listOf(
    VPath("M6,4 C5,7 5,10 6,13 C7,16 7,18 6,20", stroke = Color(0xFFFF9B3A), sw = 2f),
    VPath("M12,4 C11,7 11,10 12,13 C13,16 13,18 12,20", stroke = Color(0xFFFF9B3A), sw = 2f),
    VPath("M18,4 C17,7 17,10 18,13 C19,16 19,18 18,20", stroke = Color(0xFFFF9B3A), sw = 2f),
)
// auto-high-beam telltale (Pavel's 32×32 export). Same geometry, two colorways:
//   headlamp path with evenOdd → the "A" is a background-coloured cutout; the small triangle
//   (the A's counter) is drawn back on top. AVAILABLE = grey #494949, ON = blue #1461ff.
internal fun beamPaths(c: Color) = listOf(
    VPath("M1.078,10.364 L9.566,10.364", stroke = c, sw = 2.1f),
    VPath("M1.078,16.427 L9.566,16.427", stroke = c, sw = 2.1f),
    VPath("M1.078,22.49 L9.566,22.49", stroke = c, sw = 2.1f),
    VPath("M15.629,4.907v23.038c9.029,0,16.349-3.805,16.349-11.519,0-8.72-7.32-11.519-16.349-11.519ZM28.222,21.26h-2.289l-.909-2.366h-4.163l-.859,2.366h-2.231l4.058-10.415h2.223l4.171,10.415h-.001Z", fill = c, evenOdd = true),
    VPath("M21.506,17.14L24.348,17.14L24.349,17.14L22.913,13.275L21.506,17.14Z", fill = c),
)
internal val BEAM_OFF_PATHS = beamPaths(Color(0xFF494949)) // available (not engaged)
internal val BEAM_ON_PATHS = beamPaths(Color(0xFF1461FF))  // high beam engaged
internal val WHEEL_PATHS = listOf(
    VPath("M40.175,17.81C40.175,27.256 32.494,35.041 22.945,35.041C13.499,35.041 5.714,27.36 5.714,17.81C5.714,8.364 13.395,0.579 22.945,0.579C32.494,0.579 40.175,8.364 40.175,17.81ZM9.139,20.198C16.717,20.302 20.246,26.426 20.661,31.719C14.745,30.681 10.177,26.01 9.139,20.198ZM9.035,16.253C9.762,9.195 15.783,3.797 23.049,3.797C30.315,3.797 36.231,9.299 37.062,16.253H33.636C32.287,16.253 31.145,15.838 29.899,15.319C28.239,14.696 26.266,13.866 23.152,13.866C20.038,13.866 18.066,14.592 16.405,15.319C15.16,15.838 14.018,16.253 12.668,16.253H9.035ZM25.436,31.719C31.353,30.681 35.92,26.114 36.958,20.198C29.277,20.302 25.747,26.426 25.436,31.719Z", fill = Color(0xFFD8D8DA), evenOdd = true),
    VPath("M0.523,35.352C0.316,35.975 0.004,38.674 3.222,39.297C6.647,40.023 7.685,37.324 7.893,36.182C8.101,35.248 8.516,26.425 8.931,25.699C9.346,24.972 9.865,23.623 10.177,23.104C10.592,22.273 11.007,21.65 10.903,20.301C10.799,19.263 11.318,15.215 10.28,14.8C9.554,14.488 9.242,14.903 9.035,15.215C8.827,15.526 8.723,16.564 7.997,16.668C7.582,16.668 6.544,14.8 6.544,14.177C6.544,13.346 6.336,13.139 6.336,13.139C6.336,13.139 5.298,12.724 4.779,13.035C4.364,13.346 4.364,14.177 3.637,14.8C3.014,15.422 3.222,16.149 3.118,16.564C3.014,16.979 2.703,17.083 2.495,17.706C2.392,18.329 1.976,19.055 1.873,19.471C1.665,19.99 1.769,20.716 2.392,21.65C3.014,22.585 3.43,23.519 3.845,23.934C4.26,24.349 4.364,24.764 4.26,25.18C4.26,25.802 1.042,33.484 0.523,35.352Z", fill = Color(0xFFFF3A3A)),
    VPath("M45.469,35.352C45.677,35.975 45.988,38.674 42.771,39.297C39.345,40.023 38.307,37.324 38.1,36.182C37.892,35.248 37.477,26.425 37.062,25.699C36.646,24.972 36.127,23.623 35.816,23.104C35.401,22.273 34.986,21.65 35.089,20.301C35.193,19.263 34.674,15.215 35.712,14.8C36.439,14.488 36.75,14.903 36.958,15.215C37.165,15.526 37.269,16.564 37.996,16.668C38.411,16.668 39.449,14.8 39.449,14.177C39.449,13.346 39.657,13.139 39.657,13.139C39.657,13.139 40.695,12.724 41.214,13.035C41.733,13.346 41.629,14.177 42.355,14.8C42.978,15.422 42.771,16.149 42.874,16.564C42.978,16.979 43.29,17.083 43.497,17.706C43.601,18.329 44.016,19.055 44.12,19.471C44.328,19.99 44.224,20.716 43.601,21.65C42.978,22.585 42.563,23.519 42.148,23.934C41.733,24.349 41.629,24.764 41.733,25.18C41.836,25.802 45.054,33.484 45.469,35.352Z", fill = Color(0xFFFF3A3A)),
)

/* ----- the variant-03 dashboard tile ----- */
@Composable
fun DashboardTile(state: DashboardState, tuning: Tuning = Tuning(), modifier: Modifier = Modifier) {
    val accent = arcColor(state.speed)
    // stock "Dashboard Transparency" → fade only the panel background (content stays opaque); 0 = solid
    val bg = 1f - state.bgTransparency.coerceIn(0f, 0.8f)
    Box(modifier.width(380.dp).height(IntrinsicSize.Min)) {
      Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(PanelTop.copy(alpha = PanelTop.alpha * bg), PanelBot.copy(alpha = PanelBot.alpha * bg))))
            .border(1.dp, PanelBorder.copy(alpha = PanelBorder.alpha * bg), RoundedCornerShape(28.dp))
      ) {
        Column(Modifier.padding(start = 30.dp, end = 30.dp, top = 30.dp, bottom = 24.dp)) {

            // ---- arc + digit + telltales + sign ----
            Box(Modifier.fillMaxWidth().height(224.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val s = size.width / 220f
                    val topLeft = Offset(22f * s, 9.98f * s)
                    val diam = 176f * s
                    val sw = tuning.arcStroke * s
                    drawArc(ArcTrack, 150f, 240f, false, topLeft, Size(diam, diam), style = Stroke(sw, cap = StrokeCap.Round))
                    val pct = (state.speed.toFloat() / state.speedMax).coerceIn(0f, 1f)
                    drawArc(accent, 150f, 240f * pct, false, topLeft, Size(diam, diam), style = Stroke(sw, cap = StrokeCap.Round))
                }

                // digit + KM/H — sit low in the arc (matches prototype: bottom of the ring)
                Column(
                    Modifier.align(Alignment.BottomCenter).offset(y = tuning.digitDy.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BasicText(
                        state.speed.toString(),
                        style = TextStyle(fontFamily = MartianMono, fontWeight = FontWeight.Bold, fontSize = tuning.digitSize.sp, color = DigitCol, textAlign = TextAlign.Center),
                    )
                    Spacer(Modifier.height(tuning.unitGap.dp))
                    BasicText(if (state.mph) "MPH" else "KM/H", style = TextStyle(fontFamily = MartianMono, fontSize = 11.sp, letterSpacing = 4.sp, color = LabelCol))
                }

                // speed-limit sign (over the digit, upper area)
                if (state.limit != null) {
                    Box(Modifier.align(Alignment.TopCenter).offset(x = tuning.signDx.dp, y = tuning.signDy.dp), contentAlignment = Alignment.Center) {
                        SpeedLimitSign(state.limit, tuning.signSize)
                    }
                }

                // group 1 — autopilot + belt (top-left)
                Row(Modifier.align(Alignment.TopStart).offset(x = tuning.g1dx.dp, y = tuning.g1dy.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (state.autopilot) {
                        ApMode.ON -> VIcon(24f, 24f, AP_PATHS, Modifier.size(tuning.iconSize.dp))
                        ApMode.AVAILABLE -> VIcon(24f, 24f, AP_OFF_PATHS, Modifier.size(tuning.iconSize.dp))
                        ApMode.NONE -> Unit
                    }
                    if (state.belt) VIcon(32f, 32f, BELT_PATHS, Modifier.size(tuning.iconSize.dp))
                }
                // group 2 — heat + beam (top-right)
                Row(Modifier.align(Alignment.TopEnd).offset(x = tuning.g2dx.dp, y = tuning.g2dy.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.heat) VIcon(24f, 24f, HEAT_PATHS, Modifier.size(tuning.iconSize.dp))
                    when (state.beam) {
                        BeamMode.ON -> VIcon(32f, 32f, BEAM_ON_PATHS, Modifier.size(tuning.iconSize.dp))
                        BeamMode.AVAILABLE -> VIcon(32f, 32f, BEAM_OFF_PATHS, Modifier.size(tuning.iconSize.dp))
                        BeamMode.NONE -> Unit
                    }
                }
            }

            // ---- gears ----
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally)) {
                for (g in listOf('P', 'R', 'N', 'D')) {
                    val on = g == state.gear
                    BasicText(
                        g.toString(),
                        style = TextStyle(fontFamily = MartianMono, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, fontSize = 20.sp, color = if (on) accent else GearOff),
                    )
                }
            }

            // ---- divider ----
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2A2B2F)))
            Spacer(Modifier.height(14.dp))

            // ---- footer: battery, time, temps ----
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    BatteryGlyph(state.battery)
                    BasicText(buildPct(state.battery), style = TextStyle(fontFamily = MartianMono, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = battColor(state.battery)))
                }
                if (state.ampm.isEmpty()) {
                    BasicText(state.time, style = TextStyle(fontFamily = MartianMono, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = StatCol))
                } else {
                    // 12h clock: small dim AM/PM suffix, baseline-aligned (v4 AM-PM handoff). ARC's
                    // footer is SpaceBetween, so the wider group re-centres itself — no overflow.
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        BasicText(state.time, Modifier.alignByBaseline(), style = TextStyle(fontFamily = MartianMono, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = StatCol))
                        BasicText(state.ampm, Modifier.alignByBaseline(), style = TextStyle(fontFamily = MartianMono, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.06.em, color = Color(0xFF85878C)))
                    }
                }
                BasicText("${state.outTemp} / ${state.cabinTemp}", style = TextStyle(fontFamily = MartianMono, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = StatCol))
            }
        }

        // ---- blind-spot glow inside the card — only when that side ISN'T also indicating
        // (a turn + blind-spot on the same side renders as the red danger bar instead) ----
        if (state.blindRight && !state.turnRight) BlindGlow(fromRight = true)
        if (state.blindLeft && !state.turnLeft) BlindGlow(fromRight = false)
        if (state.hold) Takeover()
      } // end card

        // turn signals OUTSIDE the card so their glow is not clipped by it. Each side is independent
        // (both on at once = hazard), already latched/held in OverlayService (the indicator pulse is
        // too brief for Compose to observe). Green normally; RED if the blind spot on that same side
        // is also set (turning into an occupied lane — the stock's danger colour). Both edges share
        // ONE blink clock so hazard flashes in sync (not two drifting timers).
        if (state.turnLeft || state.turnRight) {
            val blink = turnBlinkAlpha()
            if (state.turnLeft) EdgeBar(Alignment.CenterStart, if (state.blindLeft) DangerRed else SignalGreen, blink)
            if (state.turnRight) EdgeBar(Alignment.CenterEnd, if (state.blindRight) DangerRed else SignalGreen, blink)
        }
    }
}

/** One shared turn-signal blink clock (~1.4 Hz square-ish wave) — call ONCE per tile and feed both
 *  edges so hazard flashes both sides in lock-step. Deterministic (rememberInfiniteTransition), so
 *  every consumer in the same composition gets the identical phase. */
@Composable
internal fun turnBlinkAlpha(): Float {
    val t = rememberInfiniteTransition(label = "turn")
    val a by t.animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes { durationMillis = 720; 1f at 0; 1f at 340; 0f at 380 },
            RepeatMode.Restart,
        ),
        label = "turnBlink",
    )
    return a
}

private fun buildPct(b: Int) = "$b%"

@Composable
internal fun SpeedLimitSign(num: Int, sizeDp: Float = 36f) {
    Box(
        Modifier.size(sizeDp.dp).clip(RoundedCornerShape(50)).background(Color.White).border((sizeDp * 0.14f).dp, Color(0xFFFF3A3A), RoundedCornerShape(50)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(num.toString(), style = TextStyle(fontWeight = FontWeight.Bold, fontSize = (sizeDp * 0.36f).sp, color = Color(0xFF111111)))
    }
}

@Composable
internal fun BatteryGlyph(b: Int) {
    val col = battColor(b)
    Canvas(Modifier.size(width = 14.dp, height = 26.dp)) {
        val w = size.width; val h = size.height
        val border = 1.5.dp.toPx()
        // nub
        drawRect(Color(0xFF4B4D52), topLeft = Offset(w * 0.5f - 3.dp.toPx(), 0f), size = Size(6.dp.toPx(), 2.5.dp.toPx()))
        val top = 2.5.dp.toPx()
        // body outline
        drawRoundRect(Color(0xFF4B4D52), topLeft = Offset(0f, top), size = Size(w, h - top), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = Stroke(border))
        // fill from bottom
        val inset = border + 1.5.dp.toPx()
        val fillH = (h - top - inset * 2) * (b.coerceIn(3, 100) / 100f)
        drawRoundRect(col, topLeft = Offset(inset, h - inset - fillH), size = Size(w - inset * 2, fillH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()))
    }
}

@Composable
internal fun androidx.compose.foundation.layout.BoxScope.EdgeBar(align: Alignment, color: Color, a: Float) {
    // blink alpha `a` comes from the tile's shared [turnBlinkAlpha] clock (so both hazard edges
    // flash together). The held turn side keeps us mounted across the signal's brief refresh dropouts.
    Box(Modifier.align(align).padding(horizontal = 8.dp).fillMaxHeight(0.86f).width(6.dp)) {
        // glow — unbounded blur so it bleeds past the card edge (parent isn't clipped)
        Box(
            Modifier.matchParentSize()
                .blur(10.dp, BlurredEdgeTreatment.Unbounded)
                .background(color.copy(alpha = 0.75f * a), RoundedCornerShape(6.dp))
        )
        // crisp bar
        Box(Modifier.matchParentSize().clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = a)))
    }
}

@Composable
internal fun androidx.compose.foundation.layout.BoxScope.BlindGlow(fromRight: Boolean) {
    val t = rememberInfiniteTransition(label = "blind")
    val a by t.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
        label = "blindAlpha",
    )
    val brush = if (fromRight)
        Brush.horizontalGradient(0f to Color(0x00F5C420), 0.70f to Color(0x00F5C420), 0.88f to Color(0x24F5C420), 1f to Color(0x80F5C420))
    else
        Brush.horizontalGradient(0f to Color(0x80F5C420), 0.12f to Color(0x24F5C420), 0.30f to Color(0x00F5C420), 1f to Color(0x00F5C420))
    Box(Modifier.matchParentSize().alpha(a).background(brush))
}

@Composable
internal fun androidx.compose.foundation.layout.BoxScope.Takeover() {
    Box(Modifier.matchParentSize().background(Color(0x99090909)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            VIcon(46f, 40f, WHEEL_PATHS, Modifier.size(width = 52.dp, height = 45.dp))
            Spacer(Modifier.height(12.dp))
            BasicText("HOLD STEERING WHEEL", style = TextStyle(fontFamily = MartianMono, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 1.4.sp, color = Color.White))
        }
    }
}
