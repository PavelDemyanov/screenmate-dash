package app.smdash.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.smdash.model.ApMode
import app.smdash.model.BeamMode
import app.smdash.model.CompactTuning
import app.smdash.model.DashboardState
import app.smdash.model.DashStyle

/* Compact dashboard tiles from the "Speedometer v3 (compact)" handoff: 01·STACK, 02·STRIP,
 * 03·MINI. Same data + same telltale icons as the ARC tile (Pavel's redrawn set in
 * Speedometer.kt), only the layout differs: no arc — the speed reads as a big digit plus
 * (STACK/STRIP) a thin linear bar. All sizes map the prototype's CSS px 1:1 to dp. */

/* ----- palette (matches the prototype card chrome) ----- */
private val CardTop = Color(0xFF0A0A0B) // near-black — the stock panel is very dark, so are we now
private val CardBot = Color(0xFF000000) // pure black at the bottom (max darkness at 0 % transparency)
private val CardBorder = Color(0xFF2B2C30)
private val DigitCol = Color(0xFFF6F6F8)
private val LabelCol = Color(0xFF75777C)
private val StatCol = Color(0xFFC8C9CD)
private val BarTrack = Color(0xFF27282C)
private val BarFill = Color(0xFFF0F0F2)
private val Hairline = Color(0xFF2A2B2F)
private val PillBorder = Color(0xFF34353A)
private val PillBg = Color(0x0AFFFFFF) // rgba(255,255,255,0.04)
private val GearOffCompact = Color(0x3DFFFFFF)
private val SigGreen = Color(0xFF34E07A)
private val DangerRed = Color(0xFFE53935)  // turn + blind-spot on the same side (stock's danger colour)
private val Gold = Color(0xFFF5C420)

/** per-style edge-glow geometry:
 *  [blindEnd] — the blind-spot wash fades to 0 at this fraction of the width;
 *  [sigEnd]   — the turn-signal wash fades EVENLY (linear) from the edge to 0 at this fraction. */
private data class GlowSpec(val blindEnd: Float, val sigEnd: Float)

private fun glowSpec(style: DashStyle) = when (style) {
    DashStyle.STACK, DashStyle.STACK_TEMP -> GlowSpec(0.38f, 0.52f)
    DashStyle.STRIP -> GlowSpec(0.34f, 0.46f)
    else -> GlowSpec(0.44f, 0.56f) // MINI (ARC never uses these)
}

/** the linear speed bar tops out here (prototype value; the arc's 230 is never reached anyway) */
private const val BAR_MAX = 160f
private const val BAR_MAX_MPH = 100f    // ≈160 km/h — same physical full-scale when the car is in mph

/** digit type: 80px bold. The LAYOUT box is pinned to 64dp by [SpeedDigits] (the prototype's
 *  line-height:.8); the text itself is centered in it with font padding stripped, so the digit
 *  glyphs (~0.7em cap height) land inside the box without bleeding onto neighbours. */
/** strip Android's extra top/bottom line padding so text sits like the browser prototype — CSS has
 *  no includeFontPadding equivalent, and leaving it on pushes every glyph down + inflates rows. */
private val NOPAD = PlatformTextStyle(includeFontPadding = false)

private fun digitStyle() = TextStyle(
    fontFamily = MartianMono, fontWeight = FontWeight.Bold, fontSize = 80.sp,
    color = DigitCol, letterSpacing = (-2.4).sp,
    platformStyle = NOPAD,
)

private fun statStyle(color: Color) = TextStyle(
    fontFamily = MartianMono, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = color,
    platformStyle = NOPAD,
)

/* ---- clock with the optional AM/PM suffix (v4 AM-PM handoff) ----
 * 24h ("19:01") renders exactly as before at the original left-anchored coordinate.
 * 12h renders the prototype's fixed right-packed block: digits right-anchored in a 70dp box
 * (5 mono chars = "12:59", Martian advance 0.7em), then a 4dp gap, then the small dim suffix.
 * All numbers below are measured from the v4 .dc.html (getBoundingClientRect). */
private val AmPmCol = Color(0xFF85878C)

private fun ampmStyle() = TextStyle(
    fontFamily = MartianMono, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
    letterSpacing = 0.06.em, color = AmPmCol, platformStyle = NOPAD,
)

/** [x24] = 24h left anchor; [digitsRightX] = 12h digits right edge; [ampmX] = suffix left edge.
 *  The suffix's measured top is +8dp below the digits' top (the browser's baseline alignment of
 *  12px against 20px Martian) — same offset on every tile. */
@Composable
private fun BoxScope.StatClock(state: DashboardState, x24: Float, digitsRightX: Float, ampmX: Float, y: Float) {
    if (state.ampm.isEmpty()) {
        Abs(x24, y) { BasicText(state.time, style = statStyle(StatCol)) }
    } else {
        Abs(digitsRightX - 70f, y) {
            Box(Modifier.width(70.dp), contentAlignment = Alignment.TopEnd) {
                BasicText(state.time, style = statStyle(StatCol))
            }
        }
        Abs(ampmX, y + 8f) { BasicText(state.ampm, style = ampmStyle()) }
    }
}

/** speed digits, each in a fixed 0.66em cell so the block width only depends on digit count.
 *  The row's LAYOUT height is pinned to 64dp (the prototype's line-height:.8 box); the actual
 *  glyphs are taller and legitimately overdraw above/below — Compose doesn't clip text. */
@Composable
private fun SpeedDigits(speed: Int, liftDp: Float = -11f) {
    Row(Modifier.height(64.dp)) {
        speed.toString().forEach { ch ->
            Box(Modifier.width(52.8.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                BasicText(
                    ch.toString(),
                    // Martian Mono's tall metrics sink the ink below the box centre even with
                    // includeFontPadding off; lift it so the visible glyph is centred in its 64dp box.
                    // [liftDp] is the live-tunable nudge (per style, TuningActivity).
                    Modifier.offset(y = liftDp.dp),
                    style = digitStyle(), softWrap = false, overflow = TextOverflow.Visible,
                )
            }
        }
    }
}

@Composable
private fun KmhLabel(mph: Boolean, tracking: Float = 3.7f) {
    BasicText(
        if (mph) "MPH" else "KM/H",
        // never wrap — in STRIP the col1 is sized by IntrinsicSize.Min, which would otherwise break
        // "KM/H" onto two lines ("KM/" + "H") and collapse the column width
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
        style = TextStyle(fontFamily = MartianMono, fontSize = 11.sp, letterSpacing = tracking.sp, color = LabelCol, platformStyle = NOPAD),
    )
}

/** thin linear speed bar (replaces the arc in the compact styles) */
@Composable
private fun SpeedBar(speed: Int, mph: Boolean, modifier: Modifier = Modifier) {
    val pct = (speed / (if (mph) BAR_MAX_MPH else BAR_MAX)).coerceIn(0f, 1f)
    Box(modifier.height(4.dp).clip(RoundedCornerShape(4.dp)).background(BarTrack)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(pct).clip(RoundedCornerShape(4.dp)).background(BarFill))
    }
}

/** the active gear as a single rounded pill (compact styles drop the PRND row) */
@Composable
private fun GearPill(gear: Char, sizeDp: Float, radiusDp: Float) {
    Box(
        Modifier.size(sizeDp.dp).clip(RoundedCornerShape(radiusDp.dp))
            .background(PillBg).border(1.dp, PillBorder, RoundedCornerShape(radiusDp.dp)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            gear.toString(),
            style = TextStyle(fontFamily = MartianMono, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = DigitCol, platformStyle = NOPAD),
        )
    }
}

/** telltale rail — our redrawn icons, each in a fixed slot so the rail never shifts */
@Composable
private fun IconSlot(sizeDp: Float, content: @Composable () -> Unit) {
    Box(Modifier.size(sizeDp.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun ApIcon(mode: ApMode, iconDp: Float) {
    when (mode) {
        ApMode.ON -> VIcon(24f, 24f, AP_PATHS, Modifier.size(iconDp.dp))
        ApMode.AVAILABLE -> VIcon(24f, 24f, AP_OFF_PATHS, Modifier.size(iconDp.dp))
        ApMode.NONE -> Unit
    }
}

@Composable
private fun BeamIcon(mode: BeamMode, iconDp: Float) {
    when (mode) {
        BeamMode.ON -> VIcon(32f, 32f, BEAM_ON_PATHS, Modifier.size(iconDp.dp))
        BeamMode.AVAILABLE -> VIcon(32f, 32f, BEAM_OFF_PATHS, Modifier.size(iconDp.dp))
        BeamMode.NONE -> Unit
    }
}

/** Temperature readout for STACK_TEMP: number (20sp) + a small "°" (13sp), coloured by the value.
 *  Colour maps exactly like the v5 prototype's `_tempColor`: white at 0°, → cold blue toward −30°,
 *  → hot orange toward +30° (clamped). The value is parsed out of the stock temp string ("18°"). */
private val ColdRgb = Triple(125, 184, 255)
private val HotRgb = Triple(255, 138, 0)

private fun lerpI(a: Int, b: Int, k: Float): Int = (a + (b - a) * k).roundToInt()

private fun tempColor(t: Int): Color {
    val c = t.coerceIn(-30, 30)
    return if (c < 0) {
        val k = -c / 30f
        Color(lerpI(255, ColdRgb.first, k), lerpI(255, ColdRgb.second, k), lerpI(255, ColdRgb.third, k))
    } else {
        val k = c / 30f
        Color(lerpI(255, HotRgb.first, k), lerpI(255, HotRgb.second, k), lerpI(255, HotRgb.third, k))
    }
}

/** first signed integer in the stock temp string ("18° C" / "-5°" / "") → null when absent. */
private fun tempVal(s: String): Int? = Regex("-?\\d+").find(s)?.value?.toIntOrNull()

@Composable
private fun TempReadout(raw: String) {
    val v = tempVal(raw)
    val col = v?.let { tempColor(it) } ?: DigitCol
    Row(verticalAlignment = Alignment.Bottom) {
        BasicText(v?.toString() ?: raw.trimEnd('°', ' '), style = statStyle(col))
        BasicText("°", Modifier.padding(bottom = 1.dp), style = statStyle(col).copy(fontSize = 13.sp))
    }
}

/** battery text (colored by charge) + optional trailing horizontal glyph (STRIP) */
@Composable
private fun BattText(b: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        BasicText("$b", style = statStyle(battColor(b)))
        BasicText(
            "%",
            Modifier.padding(bottom = 1.dp),
            style = TextStyle(fontFamily = MartianMono, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = battColor(b).copy(alpha = 0.6f), platformStyle = NOPAD),
        )
    }
}

/** small horizontal battery glyph (26×13, nub on the right) — STRIP's stats column */
@Composable
private fun HBattGlyph(b: Int) {
    val col = battColor(b)
    Canvas(Modifier.size(width = 29.dp, height = 13.dp)) {
        val bodyW = 26.dp.toPx()
        val border = 1.5.dp.toPx()
        drawRoundRect(
            Color(0xFF4B4D52), topLeft = Offset(0f, 0f), size = Size(bodyW, size.height),
            cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(border),
        )
        drawRoundRect(
            Color(0xFF4B4D52),
            topLeft = Offset(bodyW + 1.5.dp.toPx(), size.height / 2 - 2.5.dp.toPx()),
            size = Size(2.5.dp.toPx(), 5.dp.toPx()), cornerRadius = CornerRadius(1.dp.toPx()),
        )
        val inset = border + 1.5.dp.toPx()
        val fillW = (bodyW - inset * 2) * (b.coerceIn(3, 100) / 100f)
        drawRoundRect(col, topLeft = Offset(inset, inset), size = Size(fillW, size.height - inset * 2), cornerRadius = CornerRadius(1.dp.toPx()))
    }
}

/** takeover overlay for the compact tiles (row layout + short label fit the low cards) */
@Composable
private fun BoxScope.CompactTakeover(row: Boolean, label: String, wheelDp: Float) {
    Box(Modifier.matchParentSize().background(Color(0x99090909)), contentAlignment = Alignment.Center) {
        val text = @Composable {
            BasicText(
                label,
                style = TextStyle(fontFamily = MartianMono, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 1.3.sp, color = Color.White),
            )
        }
        val wheel = @Composable { VIcon(46f, 40f, WHEEL_PATHS, Modifier.size(width = wheelDp.dp, height = (wheelDp * 40f / 46f).dp)) }
        if (row) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { wheel(); text() }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) { wheel(); text() }
        }
    }
}

/** Turn signal, prototype-style: an in-card green wash from the edge (.92 at the rim → .32 at
 *  [mid] → 0 at [end]) plus a bright rim band (the CSS `inset … box-shadow`), blinking with the
 *  CSS `edgeblink` keyframes (0.68s period, ~44% duty, fast 40ms fade-out). Clipped by the card. */
@Composable
private fun BoxScope.CompactSignalGlow(fromRight: Boolean, end: Float, color: Color, a: Float) {
    // EVEN wash: high alpha at the edge fading UNIFORMLY (linear) to transparent by `end`. The
    // midpoint stop sits at half alpha + half distance so the falloff reads smooth end-to-end
    // instead of the old front-loaded spike (bright rim + steep drop). blink alpha `a` from the
    // tile's shared [turnBlinkAlpha] clock, so hazard flashes both sides together.
    val edge = color.copy(alpha = 0.85f)
    val half = color.copy(alpha = 0.42f)
    val wash = if (fromRight) {
        Brush.horizontalGradient(
            0f to Color.Transparent,
            (1f - end) to Color.Transparent,
            (1f - end * 0.5f) to half,
            1f to edge,
        )
    } else {
        Brush.horizontalGradient(
            0f to edge,
            (end * 0.5f) to half,
            end to Color.Transparent,
            1f to Color.Transparent,
        )
    }
    Box(Modifier.matchParentSize().alpha(a).background(wash))
}

/** Blind-spot warning, prototype-style: a wide gold wash from the edge (.6 → 0 at [end]),
 *  softly pulsing 50%↔100% (the CSS `blindpulse` 1s ease-in-out). Sits UNDER the content. */
@Composable
private fun BoxScope.CompactBlindGlow(fromRight: Boolean, end: Float) {
    val t = rememberInfiniteTransition(label = "blind")
    val a by t.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "blindAlpha",
    )
    val wash = if (fromRight) {
        Brush.horizontalGradient(
            0f to Color.Transparent, (1f - end) to Color.Transparent, 1f to Gold.copy(alpha = 0.6f),
        )
    } else {
        Brush.horizontalGradient(
            0f to Gold.copy(alpha = 0.6f), end to Color.Transparent, 1f to Color.Transparent,
        )
    }
    Box(Modifier.matchParentSize().alpha(a).background(wash))
}

/** shared card chrome: gradient bg + border, transparency-faded like the ARC panel.
 *  Overlay stacking mirrors the prototype's z-order: blind glow (z5) UNDER the [content] (z6),
 *  turn signal (z7) OVER it, [topOverlay] (takeover, z9) on top. All clipped by the card. */
@Composable
private fun TileCard(
    style: DashStyle,
    state: DashboardState,
    radiusDp: Float,
    content: @Composable BoxScope.() -> Unit,
    topOverlay: @Composable BoxScope.() -> Unit = {},
) {
    val bg = 1f - state.bgTransparency.coerceIn(0f, 0.8f)
    val glow = glowSpec(style)
    Box(Modifier.width(style.wDpFor(state.ampm.isNotEmpty()).dp).height(style.hDp.dp)) {
        Box(
            Modifier.fillMaxSize()
                .clip(RoundedCornerShape(radiusDp.dp))
                .background(Brush.linearGradient(listOf(CardTop.copy(alpha = bg), CardBot.copy(alpha = bg))))
                .border(1.dp, CardBorder.copy(alpha = CardBorder.alpha * bg), RoundedCornerShape(radiusDp.dp)),
        ) {
            // blind-spot wash (amber, under content) only when that side isn't also indicating
            if (state.blindRight && !state.turnRight) CompactBlindGlow(fromRight = true, end = glow.blindEnd)
            if (state.blindLeft && !state.turnLeft) CompactBlindGlow(fromRight = false, end = glow.blindEnd)
            content()
            // turn signals (over content), each side independent → both on = hazard; RED if the blind
            // spot on the same side is also set (turning into an occupied lane — the stock's danger
            // colour). Both edges share ONE blink clock so hazard flashes in sync.
            if (state.turnLeft || state.turnRight) {
                val blink = turnBlinkAlpha()
                if (state.turnRight) {
                    CompactSignalGlow(true, glow.sigEnd, if (state.blindRight) DangerRed else SigGreen, blink)
                }
                if (state.turnLeft) {
                    CompactSignalGlow(false, glow.sigEnd, if (state.blindLeft) DangerRed else SigGreen, blink)
                }
            }
            topOverlay()
        }
    }
}

/* Absolute placement: every element sits at its exact prototype coordinate (CSS px → dp 1:1),
 * measured from the REAL .dc.html via getBoundingClientRect. No layout engine re-derives positions,
 * so font-width differences (e.g. our wider Martian "KM/H") never push neighbours around. The
 * per-element [TileTune] value is an extra Y nudge (default 0). */
@Composable
private fun BoxScope.Abs(x: Float, y: Float, content: @Composable () -> Unit) {
    Box(Modifier.offset(x = x.dp, y = y.dp)) { content() }
}

/** PRND row (STACK), active gear bold-white — placed absolutely at the prototype's coordinate. */
@Composable
private fun PrndRow(gear: Char) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (g in listOf('P', 'R', 'N', 'D')) {
            val on = g == gear
            BasicText(
                g.toString(),
                style = TextStyle(
                    fontFamily = MartianMono, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 18.sp, color = if (on) Color.White else GearOffCompact, platformStyle = NOPAD,
                ),
            )
        }
    }
}

/* ============================ 01 · STACK (card 300×230) ============================ */
@Composable
fun StackTile(state: DashboardState) {
    val t by CompactTuning.stack.collectAsState()
    TileCard(DashStyle.STACK, state, radiusDp = 22f, content = {
        Abs(46.5f, 24f) { IconSlot(26f) { ApIcon(state.autopilot, 22f) } }
        Abs(84.5f, 24f) { IconSlot(26f) { if (state.belt) VIcon(32f, 32f, BELT_PATHS, Modifier.size(20.dp)) } }
        Abs(122.5f, 24f) { IconSlot(26f) { if (state.heat) VIcon(24f, 24f, HEAT_PATHS, Modifier.size(21.dp)) } }
        Abs(160.5f, 24f) { IconSlot(26f) { BeamIcon(state.beam, 23f) } }
        Abs(200.5f, 28f) { Box(Modifier.size(width = 1.dp, height = 18.dp).background(Color(0xFF2C2D31))) }
        Abs(213.5f, 17f + t.limit) { if (state.limit != null) SpeedLimitSign(state.limit, 40f) }
        Abs(70.5f, 71f + t.digit) { Box(Modifier.width(159.dp), contentAlignment = Alignment.Center) { SpeedDigits(state.speed) } }
        Abs(19f, 143f + t.kmh) { Box(Modifier.width(262.dp), contentAlignment = Alignment.Center) { KmhLabel(state.mph) } }
        Abs(19f, 169f) { SpeedBar(state.speed, state.mph, Modifier.width(262.dp)) }
        Abs(19f, 187f + t.gear) { PrndRow(state.gear) }
        // space-between row: the wider 12h time block shifts the middle (battery) item left (measured)
        Abs(if (state.ampm.isEmpty()) 137f else 125.9f, 187f + t.batt) { BattText(state.battery) }
        StatClock(state, x24 = 211f, digitsRightX = 258.8f, ampmX = 262.8f, y = 187f + t.time)
    }, topOverlay = {
        if (state.hold) CompactTakeover(row = false, label = "HOLD STEERING WHEEL", wheelDp = 46f)
    })
}

/* ==================== 01b · STACK_TEMP (card 300×230, STACK + temps) ====================
 * Identical to STACK, plus two temperature readouts flanking the speed: LEFT = outside air
 * (state.outTemp), RIGHT = the car's second temp (state.cabinTemp, sourced from the stock's
 * batteryTemp — Screenmate exposes no true cabin sensor). Both are value-coloured (see TempReadout).
 * All coordinates measured from the "Speedometer v5" .dc.html via getBoundingClientRect. Reuses
 * STACK's per-element tuning (same layout). */
@Composable
fun StackTempTile(state: DashboardState) {
    val t by CompactTuning.stack.collectAsState()
    TileCard(DashStyle.STACK_TEMP, state, radiusDp = 22f, content = {
        Abs(46.5f, 24f) { IconSlot(26f) { ApIcon(state.autopilot, 22f) } }
        Abs(84.5f, 24f) { IconSlot(26f) { if (state.belt) VIcon(32f, 32f, BELT_PATHS, Modifier.size(20.dp)) } }
        Abs(122.5f, 24f) { IconSlot(26f) { if (state.heat) VIcon(24f, 24f, HEAT_PATHS, Modifier.size(21.dp)) } }
        Abs(160.5f, 24f) { IconSlot(26f) { BeamIcon(state.beam, 23f) } }
        Abs(200.5f, 28f) { Box(Modifier.size(width = 1.dp, height = 18.dp).background(Color(0xFF2C2D31))) }
        Abs(213.5f, 17f + t.limit) { if (state.limit != null) SpeedLimitSign(state.limit, 40f) }
        // temperatures: outside left-anchored at x=19; the second temp right-anchored to x=281
        Abs(19f, 91f) { TempReadout(state.outTemp) }
        Abs(200f, 91f) { Box(Modifier.width(81.dp), contentAlignment = Alignment.TopEnd) { TempReadout(state.cabinTemp) } }
        Abs(70.5f, 71f + t.digit) { Box(Modifier.width(159.dp), contentAlignment = Alignment.Center) { SpeedDigits(state.speed) } }
        Abs(19f, 143f + t.kmh) { Box(Modifier.width(262.dp), contentAlignment = Alignment.Center) { KmhLabel(state.mph) } }
        Abs(19f, 169f) { SpeedBar(state.speed, state.mph, Modifier.width(262.dp)) }
        Abs(19f, 187f + t.gear) { PrndRow(state.gear) }
        Abs(if (state.ampm.isEmpty()) 137f else 125.9f, 187f + t.batt) { BattText(state.battery) }
        StatClock(state, x24 = 211f, digitsRightX = 258.8f, ampmX = 262.8f, y = 187f + t.time)
    }, topOverlay = {
        if (state.hold) CompactTakeover(row = false, label = "HOLD STEERING WHEEL", wheelDp = 46f)
    })
}

/* ============================ 02 · STRIP (card 552.7×114) ============================ */
@Composable
fun StripTile(state: DashboardState) {
    val t by CompactTuning.strip.collectAsState()
    TileCard(DashStyle.STRIP, state, radiusDp = 20f, content = {
        // digits RIGHT-aligned in the 159dp box; KM/H after it (its width no longer shifts anything).
        // STRIP's prototype row is align-items:baseline, so the measured block-top (17) is the GLYPH
        // top, not the line-box top; our SpeedDigits puts the glyph ~41dp above its row → place at 57
        // so the glyph lands at ~16dp like the prototype (else it clips over the short strip's top).
        Abs(21f, 37f + t.digit) { Box(Modifier.width(159.dp), contentAlignment = Alignment.CenterEnd) { SpeedDigits(state.speed) } }
        Abs(190f, 70f + t.kmh) { KmhLabel(state.mph, tracking = 3.1f) }
        Abs(21f, 93f) { SpeedBar(state.speed, state.mph, Modifier.width(207.7.dp)) }
        Abs(244.7f, 17f) { Box(Modifier.size(width = 1.dp, height = 80.dp).background(Hairline)) }
        Abs(261.7f, 28.5f) { IconSlot(24f) { ApIcon(state.autopilot, 22f) } }
        Abs(296.7f, 28.5f) { IconSlot(24f) { if (state.belt) VIcon(32f, 32f, BELT_PATHS, Modifier.size(19.dp)) } }
        Abs(261.7f, 61.5f) { IconSlot(24f) { if (state.heat) VIcon(24f, 24f, HEAT_PATHS, Modifier.size(20.dp)) } }
        Abs(296.7f, 61.5f) { IconSlot(24f) { BeamIcon(state.beam, 21f) } }
        Abs(334.7f, 39f + t.gear) { GearPill(state.gear, sizeDp = 36f, radiusDp = 10f) }
        Abs(384.7f, 35f + t.limit) { if (state.limit != null) SpeedLimitSign(state.limit, 44f) }
        Abs(444.7f, 17f) { Box(Modifier.size(width = 1.dp, height = 80.dp).background(Hairline)) }
        // 12h: the stats column right-aligns to the wider (575) card's padding edge (measured)
        Abs(if (state.ampm.isEmpty()) 462.3f else 484.6f, 30.5f + t.batt) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                BattText(state.battery); HBattGlyph(state.battery)
            }
        }
        StatClock(state, x24 = 461.7f, digitsRightX = 531.7f, ampmX = 535.7f, y = 59.5f + t.time)
    }, topOverlay = {
        if (state.hold) CompactTakeover(row = true, label = "HOLD STEERING WHEEL", wheelDp = 38f)
    })
}

/* ============================ 03 · MINI (card 232×249) ============================ */
@Composable
fun MiniTile(state: DashboardState) {
    val t by CompactTuning.mini.collectAsState()
    TileCard(DashStyle.MINI, state, radiusDp = 20f, content = {
        Abs(17f, 18f + t.gear) { GearPill(state.gear, sizeDp = 32f, radiusDp = 9f) }
        Abs(177f, 15f + t.limit) { if (state.limit != null) SpeedLimitSign(state.limit, 38f) }
        Abs(36.5f, 63f + t.digit) { Box(Modifier.width(159.dp), contentAlignment = Alignment.Center) { SpeedDigits(state.speed) } }
        Abs(17f, 135f + t.kmh) { Box(Modifier.width(198.dp), contentAlignment = Alignment.Center) { KmhLabel(state.mph) } }
        Abs(56f, 161f) { IconSlot(22f) { ApIcon(state.autopilot, 20f) } }
        Abs(88f, 161f) { IconSlot(22f) { if (state.belt) VIcon(32f, 32f, BELT_PATHS, Modifier.size(17.dp)) } }
        Abs(120f, 161f) { IconSlot(22f) { if (state.heat) VIcon(24f, 24f, HEAT_PATHS, Modifier.size(18.dp)) } }
        Abs(152f, 161f) { IconSlot(22f) { BeamIcon(state.beam, 19f) } }
        Abs(17f, 196f) { Box(Modifier.size(width = 198.dp, height = 1.dp).background(Hairline)) }
        Abs(17f, 208f + t.batt) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(battColor(state.battery)))
                BattText(state.battery)
            }
        }
        StatClock(state, x24 = 145f, digitsRightX = 192.8f, ampmX = 196.8f, y = 208f + t.time)
    }, topOverlay = {
        if (state.hold) CompactTakeover(row = false, label = "HOLD WHEEL", wheelDp = 40f)
    })
}
