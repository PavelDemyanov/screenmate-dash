package app.smdash.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import app.smdash.model.DashStore
import kotlinx.coroutines.delay

/** Same green as the tile's turn bar; red when the blind spot on that side is also set (turning into an
 *  occupied lane) — the tile does the same, so the two never disagree. */
private val EdgeGreen = Color(0xFF34E07A)
private val EdgeRed = Color(0xFFE53935)

/**
 * Content of ONE full-screen-edge turn-signal window (left or right). The window itself is exactly the
 * band (its width = the user's width setting) and is only VISIBLE while that side is signalling or a
 * settings preview runs — [app.smdash.OverlayService] owns that. Here we only paint: solid at the screen
 * edge, fading to transparent inwards, multiplied by the user's transparency and by the SAME blink clock
 * the tile uses ([turnBlinkAlpha]), so the band and the tile's arrow flash together.
 *
 * Note: Android caps a pass-through (NOT_TOUCHABLE) overlay window at 80 % opacity, so "fully opaque"
 * here is 80 % on the car — the same cap our dashboard lives with.
 */
@Composable
fun EdgeGlow(left: Boolean) {
    val st by DashStore.flow.collectAsState()
    val transp by DashStore.edgeTranspPct.collectAsState()
    val demoUntil by DashStore.edgeDemoUntil.collectAsState()
    val demoStart by DashStore.edgeDemoStart.collectAsState()
    // Demo: steps of one blink period counted from demoStart — even steps light the RIGHT band, odd the
    // LEFT ("right, then left"). Both windows compute the same step from the shared start, so they never
    // overlap; each lit step re-mounts the blink below, i.e. exactly one flash per step.
    var demoLit by remember { mutableStateOf(false) }
    LaunchedEffect(demoUntil, demoStart) {
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (now >= demoUntil) break
            val step = (now - demoStart).coerceAtLeast(0) / DashStore.EDGE_DEMO_STEP_MS
            demoLit = (step % 2 == 0L) != left
            delay(demoStart + (step + 1) * DashStore.EDGE_DEMO_STEP_MS - now)
        }
        demoLit = false
    }
    val signalling = if (left) st.turnLeft else st.turnRight
    if (!demoLit && !signalling) return
    val blink = turnBlinkAlpha()
    val danger = !demoLit && (if (left) st.blindLeft else st.blindRight)
    val c = if (danger) EdgeRed else EdgeGreen
    val a = (1f - transp.coerceIn(0, 100) / 100f) * blink
    // eased fade: most of the colour sits near the edge, the inner half thins out to nothing
    val brush = if (left) {
        Brush.horizontalGradient(0f to c, 0.35f to c.copy(alpha = 0.55f), 0.7f to c.copy(alpha = 0.15f), 1f to c.copy(alpha = 0f))
    } else {
        Brush.horizontalGradient(0f to c.copy(alpha = 0f), 0.3f to c.copy(alpha = 0.15f), 0.65f to c.copy(alpha = 0.55f), 1f to c)
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = a }.background(brush))
}
