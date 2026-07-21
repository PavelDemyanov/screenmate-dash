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
import androidx.compose.foundation.layout.width
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
 * Everything lives inside one 370px dial (the prototype's black rounded frame is intentionally
 * dropped — the tile is the circle itself). Geometry measured from the v7 prototype:
 *   dial angle for a value: dialA(v) = -120° + v/max*240°  (0° = up / 12 o'clock, CW positive)
 *   ticks every 10 at radius 175 (major every 20: 3×17, minor 2×9); redline (>= redlineV) red
 *   numbers every 20 at radius 153, upright; needle 132 up + 24 tail; hub 38.
 * Signals (Pavel's spec, beyond the prototype): a plain turn = green blinking arrow; a turn WITH a
 * blind spot on the SAME side (can't change lane) = RED arrow; hazard (both turns) = both arrows
 * blink in sync; a blind spot alone = amber side glow. */

private const val DIAL = 370f
private const val CTR = 185f
private const val TICK_OUTER = 175f
private const val NUM_R = 153f

private val TickWhite = Color(0xFFD8D8DA)
private val TickRed = Color(0xFFFF5B5B)
private val NumWhite = Color(0xFFE8E8EA)
private val NeedleRed = Color(0xFFFF3A3A)
private val ArrowGreen = Color(0xFF34E07A)
private val ArrowRed = Color(0xFFE53935)
private val SlotBg = Color(0x8C000000)
private val SlotBorder = Color(0xFF2E2F34)

private fun dialA(v: Float, max: Float) = -120f + v / max * 240f

/** point at radius [r] and angle [deg] (0 = up, CW+), in the 370-unit dial space */
private fun dialPt(r: Float, deg: Float): Pair<Float, Float> {
    val rad = Math.toRadians(deg.toDouble())
    return (CTR + r * sin(rad)).toFloat() to (CTR - r * cos(rad)).toFloat()
}

/** a box of [w]×[h] centred at ([cx],[cy]) in dial space */
@Composable
private fun BoxScope.CenterAt(cx: Float, cy: Float, w: Float, h: Float, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.offset((cx - w / 2f).dp, (cy - h / 2f).dp).size(w.dp, h.dp), contentAlignment = Alignment.Center, content = content)
}

/** a [w]-wide box whose TOP edge sits at [top], horizontally centred at [cx] (top:… ; left:50%) */
@Composable
private fun BoxScope.TopCenter(cx: Float, top: Float, w: Float, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.offset((cx - w / 2f).dp, top.dp).width(w.dp), contentAlignment = Alignment.TopCenter, content = content)
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
        // blind-spot side glow (amber) — only for a blind spot lit WITHOUT a turn on that side
        if (state.blindLeft && !state.turnLeft) CompactBlindGlow(fromRight = false, end = 0.4f)
        if (state.blindRight && !state.turnRight) CompactBlindGlow(fromRight = true, end = 0.4f)

        // gauge: bezel + face + ticks + needle + hub (Canvas, resolution-independent)
        Canvas(Modifier.matchParentSize()) {
            val s = size.width / DIAL
            val c = Offset(CTR * s, CTR * s)
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(Color(0xFFBABCC0), Color(0xFF55585C), Color(0xFFE8EAEC), Color(0xFF4E5155), Color(0xFFBABCC0)),
                    center = c,
                ),
                radius = 185f * s, center = c,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFF26272B), Color(0xFF121316)),
                    center = Offset(c.x, size.height * 0.36f), radius = size.width * 0.7f,
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
            val spd = state.speed.coerceIn(0, maxV.toInt())
            rotate(dialA(spd.toFloat(), maxV), pivot = c) {
                drawRoundRect(NeedleRed, topLeft = Offset(c.x - 2.5f * s, c.y - 132f * s), size = Size(5f * s, 132f * s), cornerRadius = CornerRadius(3f * s))
                drawRoundRect(NeedleRed, topLeft = Offset(c.x - 3.5f * s, c.y), size = Size(7f * s, 24f * s), cornerRadius = CornerRadius(3f * s))
            }
            drawCircle(Color(0xFF3A3C40), radius = 19f * s, center = c)
            drawCircle(
                brush = Brush.radialGradient(listOf(Color(0xFFDDDEE0), Color(0xFF8A8C90)), center = Offset(c.x - 4f * s, c.y - 4f * s), radius = 18f * s),
                radius = 14f * s, center = c,
            )
        }

        // numbers (every 20), upright
        var nv = 0
        while (nv <= maxV.toInt()) {
            val (x, y) = dialPt(NUM_R, dialA(nv.toFloat(), maxV))
            val col = if (nv >= redlineV) TickRed else NumWhite
            CenterAt(x, y, 46f, 30f) {
                BasicText("$nv", style = TextStyle(color = col, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center))
            }
            nv += 20
        }

        // KM/H unit label (top:88)
        TopCenter(CTR, 88f, 80f) {
            BasicText(if (mph) "MPH" else "KM/H", style = TextStyle(fontFamily = MartianMono, fontSize = 11.sp, letterSpacing = 0.34.em, color = Color(0xFF75777C)))
        }

        // gear letter (top:110, 30×30 box)
        CenterAt(CTR, 125f, 30f, 30f) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x8C000000)).border(1.dp, SlotBorder, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                BasicText(state.gear.toString(), style = TextStyle(color = Color(0xFFF6F6F8), fontSize = 18.sp, fontWeight = FontWeight.Bold))
            }
        }

        // turn arrows flanking the hub (left:92 / right:92, top:159)
        val blink = turnBlinkAlpha()
        val lCol = if (state.blindLeft) ArrowRed else ArrowGreen
        val rCol = if (state.blindRight) ArrowRed else ArrowGreen
        Box(Modifier.offset(92.dp, 159.dp).size(34.dp, 28.dp).alpha(if (state.turnLeft) blink else 0.1f)) {
            VIcon(24f, 20f, analogArrow(lCol, left = true), Modifier.size(34.dp, 28.dp))
        }
        Box(Modifier.offset(244.dp, 159.dp).size(34.dp, 28.dp).alpha(if (state.turnRight) blink else 0.1f)) {
            VIcon(24f, 20f, analogArrow(rCol, left = false), Modifier.size(34.dp, 28.dp))
        }

        // telltale row (top:236)
        TopCenter(CTR, 236f, 200f) {
            // only ACTIVE telltales get a circle (no empty dark dots when nothing is lit)
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

        // clock + speed-limit sign (top:278)
        TopCenter(CTR, 278f, 220f) {
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

        // HOLD STEERING WHEEL takeover (red halo + wheel), full-dial overlay
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
