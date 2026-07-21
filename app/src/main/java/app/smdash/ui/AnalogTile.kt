package app.smdash.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.smdash.model.ApMode
import app.smdash.model.BeamMode
import app.smdash.model.DashboardState
import kotlin.math.cos
import kotlin.math.sin

/* ================= ANALOG · round speedometer with a needle ("Speedometer v7") =================
 * The prototype's black rounded frame is intentionally dropped — the tile is just the 370px dial.
 * ALL coordinates below are MEASURED from the v7 prototype via getBoundingClientRect (centres, in
 * the 370-circle frame, centre = 185,185): numbers on radius 126, KM/H at (185,106), gear (185,136),
 * turn arrows (120,184)/(250,184), telltale row y=262, clock/sign y=309, hub 38 at centre.
 *   dialA(v) = -120° + v/max*240°  (0° = up / 12 o'clock, CW positive); ticks every 10 on radius 175.
 * z-order (matches the prototype): ticks/numbers/KM/H → gear/arrows/icons/clock/sign (z2) → NEEDLE
 * (z3) → hub (z4, metallic conic) → blind glow (z5). So the needle draws OVER the gear, the hub OVER
 * the needle.  Signals (Pavel's spec): plain turn = green blink; turn + blind spot same side = RED;
 * hazard (both) = both blink in sync; blind spot alone = amber side glow. */

private const val DIAL = 370f
private const val CTR = 185f
private const val TICK_OUTER = 175f
private const val NUM_R = 126f      // number CENTRES (measured), not the tick edge

private val TickWhite = Color(0xFFD8D8DA)
private val TickRed = Color(0xFFFF5B5B)
private val NumWhite = Color(0xFFE8E8EA)
private val NeedleRed = Color(0xFFFF3A3A)
private val SlotBg = Color(0x8C000000)
private val SlotBorder = Color(0xFF2E2F34)
private val ArrowGreen = Color(0xFF34E07A)
private val ArrowRed = Color(0xFFE53935)

private fun dialA(v: Float, max: Float) = -120f + v / max * 240f

/** point at radius [r], angle [deg] (0 = up, CW+), in the 370-unit dial frame */
private fun dialPt(r: Float, deg: Float): Pair<Float, Float> {
    val rad = Math.toRadians(deg.toDouble())
    return (CTR + r * sin(rad)).toFloat() to (CTR - r * cos(rad)).toFloat()
}

/** a [w]×[h] box centred at ([cx],[cy]) in dial space */
@Composable
private fun BoxScope.CenterAt(cx: Float, cy: Float, w: Float, h: Float, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.offset((cx - w / 2f).dp, (cy - h / 2f).dp).size(w.dp, h.dp), contentAlignment = Alignment.Center, content = content)
}

private fun analogArrow(color: Color, left: Boolean) = listOf(
    if (left) VPath("M2,10 L13,2 L13,6.5 L22,6.5 L22,13.5 L13,13.5 L13,18 Z", fill = color)
    else VPath("M22,10 L11,2 L11,6.5 L2,6.5 L2,13.5 L11,13.5 L11,18 Z", fill = color),
)

@Composable
private fun IconCircle(content: @Composable () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(SlotBg).border(1.dp, SlotBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun AnalogTile(state: DashboardState) {
    val mph = state.mph
    val maxV = if (mph) 160f else 220f
    val redlineV = if (mph) 130f else 180f
    val face = 1f - state.bgTransparency.coerceIn(0f, 0.8f)

    Box(Modifier.size(DIAL.dp).clip(CircleShape)) {
        // ---------- base: bezel + face + ticks (behind everything) ----------
        Canvas(Modifier.matchParentSize()) {
            val s = size.width / DIAL
            val c = Offset(CTR * s, CTR * s)
            drawCircle(
                brush = Brush.sweepGradient(
                    0f to Color(0xFFBABCC0), 0.22f to Color(0xFF55585C), 0.48f to Color(0xFFE8EAEC),
                    0.72f to Color(0xFF4E5155), 1f to Color(0xFFBABCC0), center = c,
                ),
                radius = 185f * s, center = c,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color(0xFF26272B), 0.7f to Color(0xFF121316),
                    center = Offset(c.x, size.height * 0.36f), radius = size.width * 0.62f,
                ),
                radius = 174f * s, center = c, alpha = face,
            )
            var v = 0
            while (v <= maxV.toInt()) {
                val major = v % 20 == 0
                val h = if (major) 17f else 9f
                val w = if (major) 3f else 2f
                val col = if (v >= redlineV) TickRed else TickWhite
                val a = dialA(v.toFloat(), maxV)
                val (ox, oy) = dialPt(TICK_OUTER, a)
                val (ix, iy) = dialPt(TICK_OUTER - h, a)
                drawLine(col, Offset(ox * s, oy * s), Offset(ix * s, iy * s), strokeWidth = w * s, cap = StrokeCap.Round)
                v += 10
            }
        }

        // ---------- numbers (centres on radius 126) + KM/H ----------
        var nv = 0
        while (nv <= maxV.toInt()) {
            val (x, y) = dialPt(NUM_R, dialA(nv.toFloat(), maxV))
            val col = if (nv >= redlineV) TickRed else NumWhite
            CenterAt(x, y, 46f, 32f) {
                BasicText("$nv", style = TextStyle(color = col, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center))
            }
            nv += 20
        }
        CenterAt(CTR, 106f, 80f, 16f) {
            BasicText(if (mph) "MPH" else "KM/H", style = TextStyle(fontFamily = MartianMono, fontSize = 11.sp, letterSpacing = 0.34.em, color = Color(0xFF75777C)))
        }

        // ---------- z2: gear, telltales, clock+sign ----------
        CenterAt(CTR, 136f, 30f, 30f) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x8C000000)).border(1.dp, SlotBorder, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                BasicText(state.gear.toString(), style = TextStyle(color = Color(0xFFF6F6F8), fontSize = 18.sp, fontWeight = FontWeight.Bold))
            }
        }
        CenterAt(CTR, 262f, 200f, 34f) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                when (state.autopilot) {
                    ApMode.ON -> IconCircle { VIcon(24f, 24f, AP_PATHS, Modifier.size(22.dp)) }
                    ApMode.AVAILABLE -> IconCircle { VIcon(24f, 24f, AP_OFF_PATHS, Modifier.size(22.dp)) }
                    else -> {}
                }
                if (state.belt) IconCircle { VIcon(32f, 32f, BELT_PATHS, Modifier.size(19.dp)) }
                if (state.heat) IconCircle { VIcon(24f, 24f, HEAT_PATHS, Modifier.size(21.dp)) }
                when (state.beam) {
                    BeamMode.ON -> IconCircle { VIcon(32f, 32f, BEAM_ON_PATHS, Modifier.size(22.dp)) }
                    BeamMode.AVAILABLE -> IconCircle { VIcon(32f, 32f, BEAM_OFF_PATHS, Modifier.size(22.dp)) }
                    else -> {}
                }
            }
        }
        CenterAt(CTR, 309f, 240f, 44f) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.Bottom) {
                    BasicText(state.time, style = TextStyle(fontFamily = MartianMono, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFC8C9CD)))
                    if (state.ampm.isNotEmpty()) {
                        BasicText(state.ampm, Modifier.offset(y = (-1).dp), style = TextStyle(fontFamily = MartianMono, fontSize = 12.sp, letterSpacing = 0.06.em, fontWeight = FontWeight.SemiBold, color = Color(0xFF85878C)))
                    }
                }
                if (state.limit != null) SpeedLimitSign(state.limit, 40f)
            }
        }

        // ---------- z2: turn arrows flanking the hub (centres 120,184 / 250,184) ----------
        val blink = turnBlinkAlpha()
        CenterAt(120f, 184f, 34f, 28f) {
            Box(Modifier.size(34.dp, 28.dp).alpha(if (state.turnLeft) blink else 0.1f)) {
                VIcon(24f, 20f, analogArrow(if (state.blindLeft) ArrowRed else ArrowGreen, left = true), Modifier.size(34.dp, 28.dp))
            }
        }
        CenterAt(250f, 184f, 34f, 28f) {
            Box(Modifier.size(34.dp, 28.dp).alpha(if (state.turnRight) blink else 0.1f)) {
                VIcon(24f, 20f, analogArrow(if (state.blindRight) ArrowRed else ArrowGreen, left = false), Modifier.size(34.dp, 28.dp))
            }
        }

        // ---------- z3+z4: needle (OVER the gear) then the metallic hub (OVER the needle) ----------
        Canvas(Modifier.matchParentSize()) {
            val s = size.width / DIAL
            val c = Offset(CTR * s, CTR * s)
            val spd = state.speed.coerceIn(0, maxV.toInt())
            rotate(dialA(spd.toFloat(), maxV), pivot = c) {
                drawRoundRect(NeedleRed, topLeft = Offset(c.x - 2.5f * s, c.y - 132f * s), size = Size(5f * s, 132f * s), cornerRadius = CornerRadius(3f * s))
                drawRoundRect(NeedleRed, topLeft = Offset(c.x - 3.5f * s, c.y), size = Size(7f * s, 24f * s), cornerRadius = CornerRadius(3f * s))
            }
            // metallic hub — conic (sweep) gradient, 38px
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(Color(0xFFD9DADC), Color(0xFF5C5F63), Color(0xFFECEEF0), Color(0xFF55585C), Color(0xFFD9DADC)),
                    center = c,
                ),
                radius = 19f * s, center = c,
            )
        }

        // ---------- z5: blind-spot side glow (only a blind spot WITHOUT a turn on that side) ----------
        if (state.blindLeft && !state.turnLeft) CompactBlindGlow(fromRight = false, end = 0.4f)
        if (state.blindRight && !state.turnRight) CompactBlindGlow(fromRight = true, end = 0.4f)

        if (state.hold) AnalogTakeover()
    }
}

@Composable
private fun BoxScope.AnalogTakeover() {
    Box(
        Modifier.matchParentSize().clip(CircleShape).background(Color(0xB80A0A0C)),
        contentAlignment = Alignment.Center,
    ) {
        VIcon(46f, 40f, WHEEL_PATHS, Modifier.size(56.dp, 49.dp))
    }
}
