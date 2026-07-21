package app.smdash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smdash.model.DashStore
import app.smdash.model.DashStyle
import app.smdash.model.DashboardState
import app.smdash.ui.AnalogTile
import app.smdash.ui.DashboardTile
import app.smdash.ui.MiniTile
import app.smdash.ui.StackTile
import app.smdash.ui.StackTempTile
import app.smdash.ui.StripTile
import app.smdash.ui.TuneSlider
import kotlin.math.min

/**
 * Mock of the stock "Dashboard settings" panel for the emulator (and a manual override on the car):
 * a dashboard STYLE picker (live scaled-down previews of every [DashStyle], fed by the same
 * [DashStore.flow] the overlay renders — on the emulator the mock loop animates them) plus a
 * transparency slider that drives OUR dashboard live. On the car, transparency normally rides in
 * the stock broadcast; here we set [DashStore.transpOverride] directly (same process as the overlay)
 * and persist it in the `overlay` prefs so [OverlayService] reloads it. "Auto" clears the override.
 * Picking a style sets [DashStore.style]; the running overlay reacts instantly (and persists it) —
 * we persist here too in case the overlay isn't running.
 *
 * The dashboard overlay draws on top of this activity (TYPE_APPLICATION_OVERLAY) at the top-left,
 * so the panel lives in the lower-centre of the screen — both are visible and usable at once.
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        val prefs = getSharedPreferences("overlay", MODE_PRIVATE)
        setContent {
            val s = Strings.of(Strings.isRu(ctx))
            // percent 0..80; -1 (Auto) means "no override → follow the stock value"
            var pct by remember { mutableStateOf((DashStore.transpOverride.value ?: 0f) * 100f) }
            var auto by remember { mutableStateOf(DashStore.transpOverride.value == null) }
            val style by DashStore.style.collectAsState()
            val live by DashStore.flow.collectAsState()

            fun push() {
                val v = if (auto) null else (pct / 100f).coerceIn(0f, 0.8f)
                DashStore.transpOverride.value = v          // instant (same process as the overlay)
                prefs.edit().putFloat("transp", v ?: -1f).apply() // persisted for OverlayService reload
            }

            fun pickStyle(st: DashStyle) {
                DashStore.style.value = st                       // overlay reacts + persists
                prefs.edit().putString("style", st.key).apply()  // …and persist even if it's not running
            }

            // Light backdrop simulating the bright car screen behind the dashboard, so fading the
            // dashboard's background here is actually visible while you drag (over a dark backdrop
            // a transparent dark panel would look unchanged).
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0xFFEDF0F3), Color(0xFFB7BEC7))),
                ),
            ) {
                BasicText(
                    s.previewNote,
                    Modifier.align(Alignment.TopCenter).padding(top = 40.dp),
                    style = TextStyle(color = Color(0xFF55606B), fontSize = 13.sp, textAlign = TextAlign.Center),
                )
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(28.dp).width(560.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF17181C)).padding(22.dp),
                ) {
                    // ---- dashboard style (live previews) ----
                    BasicText(s.styleTitle, style = TextStyle(color = Color.White, fontSize = 18.sp))
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StyleCard(s.styleArc, DashStyle.ARC, style == DashStyle.ARC, live, Modifier.weight(1f)) { pickStyle(DashStyle.ARC) }
                        StyleCard(s.styleStack, DashStyle.STACK, style == DashStyle.STACK, live, Modifier.weight(1f)) { pickStyle(DashStyle.STACK) }
                        StyleCard(s.styleStackTemp, DashStyle.STACK_TEMP, style == DashStyle.STACK_TEMP, live, Modifier.weight(1f)) { pickStyle(DashStyle.STACK_TEMP) }
                        StyleCard(s.styleStrip, DashStyle.STRIP, style == DashStyle.STRIP, live, Modifier.weight(1f)) { pickStyle(DashStyle.STRIP) }
                        StyleCard(s.styleMini, DashStyle.MINI, style == DashStyle.MINI, live, Modifier.weight(1f)) { pickStyle(DashStyle.MINI) }
                        StyleCard(s.styleAnalog, DashStyle.ANALOG, style == DashStyle.ANALOG, live, Modifier.weight(1f)) { pickStyle(DashStyle.ANALOG) }
                    }

                    // ---- transparency ----
                    BasicText(
                        s.transpTitle,
                        Modifier.padding(top = 20.dp),
                        style = TextStyle(color = Color.White, fontSize = 18.sp),
                    )
                    BasicText(
                        s.transpHint,
                        Modifier.padding(top = 4.dp, bottom = 14.dp),
                        style = TextStyle(color = Color(0xFF8A9099), fontSize = 12.sp),
                    )
                    val shownPct = if (auto) 0f else pct
                    TuneSlider("${s.transpLabel}   ${shownPct.toInt()}%", shownPct, 0f, 80f) {
                        pct = it; auto = false; push()
                    }
                    Row80(
                        left = s.transpAuto, leftActive = auto, onLeft = { auto = true; push() },
                        right = s.close, onRight = { finish() },
                    )
                }
            }
        }
    }
}

/** One style option: a LIVE scaled-down tile preview (fed by the real state flow) + name below. */
@Composable
private fun StyleCard(
    label: String,
    style: DashStyle,
    active: Boolean,
    live: DashboardState,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val border = if (active) Color(0xFF2E5D7D) else Color(0xFF24262B)
    Column(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1D1F24))
            .border(if (active) 2.dp else 1.dp, border, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(72.dp), contentAlignment = Alignment.Center) { TilePreview(style, live) }
        BasicText(
            label,
            Modifier.padding(top = 8.dp),
            style = TextStyle(color = if (active) Color.White else Color(0xFF8A9099), fontSize = 13.sp),
        )
    }
}

/** Renders the real tile composable at full design size, scaled into a small preview box.
 *  (requiredSize keeps the tile's own measurement; graphicsLayer shrinks it — no double-scaling.) */
@Composable
private fun TilePreview(style: DashStyle, live: DashboardState) {
    // previews stay opaque and never show the full-card takeover, but keep the live blinkers
    val state = live.copy(bgTransparency = 0f, hold = false)
    val k = min(96f / style.wDp, 68f / style.hDp)
    Box(Modifier.size((style.wDp * k).dp, (style.hDp * k).dp)) {
        Box(
            Modifier.requiredSize(style.wDp.dp, style.hDp.dp).graphicsLayer {
                scaleX = k; scaleY = k
                transformOrigin = TransformOrigin(0.5f, 0.5f)
            },
        ) {
            when (style) {
                DashStyle.ARC -> DashboardTile(state)
                DashStyle.STACK -> StackTile(state)
                DashStyle.STACK_TEMP -> StackTempTile(state)
                DashStyle.STRIP -> StripTile(state)
                DashStyle.MINI -> MiniTile(state)
                DashStyle.ANALOG -> AnalogTile(state)
            }
        }
    }
}

/** Two side-by-side pill buttons (Auto / Close). */
@Composable
private fun Row80(left: String, leftActive: Boolean, onLeft: () -> Unit, right: String, onRight: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Pill(left, if (leftActive) Color(0xFF2E5D7D) else Color(0xFF24262B), Modifier.weight(1f), onLeft)
        Pill(right, Color(0xFF33343A), Modifier.weight(1f), onRight)
    }
}

@Composable
private fun Pill(text: String, bg: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(bg).clickable { onClick() }.padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = TextStyle(color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center))
    }
}
