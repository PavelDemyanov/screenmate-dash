package app.smdash

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smdash.ui.TuningScreen

/** Control panel: install/remove the system patch, start/stop the overlay, or tune positions. */
class MainActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        setContent {
            var mode by remember { mutableStateOf("control") }
            var ru by remember { mutableStateOf(Strings.isRu(ctx)) }
            val s = Strings.of(ru)
            var log by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            val append: (String) -> Unit = { line -> main.post { log = (log + line + "\n").takeLast(4000) } }

            if (mode == "tune") {
                TuningScreen()
            } else {
                Column(
                    Modifier.fillMaxSize().background(Color(0xFF0E0F11)).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LangChip("EN", active = !ru) { ru = false; Strings.setRu(ctx, false) }
                        LangChip("RU", active = ru) { ru = true; Strings.setRu(ctx, true) }
                    }
                    BasicText("ScreenMate Dash", style = TextStyle(color = Color.White, fontSize = 22.sp))

                    Btn(s.installPatch, Color(0xFF2E5D7D), enabled = !busy) {
                        log = ""; busy = true
                        Thread { Patcher.apply(ctx, s, append); main.post { busy = false } }.start()
                    }
                    Btn(s.removePatch, Color(0xFF5A4A2A), enabled = !busy) {
                        log = ""; busy = true
                        Thread { Patcher.revert(ctx, s, append); main.post { busy = false } }.start()
                    }
                    Btn(s.hardReset, Color(0xFF6B3A2A), enabled = !busy) {
                        log = ""; busy = true
                        Thread { Patcher.hardReset(ctx, s, append); main.post { busy = false } }.start()
                    }
                    Btn(s.startOverlay, Color(0xFF2E7D5B)) { startOverlay(ctx) }
                    Btn(s.stopOverlay, Color(0xFF5A3A3A)) {
                        // switch to the stock dashboard; start the service with the force-stock extra so it
                        // reconciles the flag even if the process had been killed (a broadcast would no-op),
                        // and stays alive so 5-tap / "Start" can bring ours back
                        ctx.startForegroundService(
                            Intent(ctx, OverlayService::class.java).putExtra(OverlayService.EXTRA_SHOW_STOCK, true),
                        )
                    }
                    Btn(s.dashSettings, Color(0xFF33343A)) { startActivity(Intent(ctx, SettingsActivity::class.java)) }
                    Btn("Выравнивание текста (тюнер)", Color(0xFF3A3550)) { startActivity(Intent(ctx, TuningActivity::class.java)) }

                    if (log.isNotEmpty()) {
                        Box(
                            Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF17181C)).padding(12.dp).verticalScroll(rememberScrollState()),
                        ) {
                            BasicText(log, style = TextStyle(color = Color(0xFFB9C0C7), fontSize = 13.sp))
                        }
                    }
                }
            }
        }
    }

    private fun startOverlay(ctx: Context) {
        if (Settings.canDrawOverlays(ctx)) {
            // force OUR dashboard up (reliable regardless of the last persisted toggle state)
            ctx.startForegroundService(
                Intent(ctx, OverlayService::class.java).putExtra(OverlayService.EXTRA_SHOW_OURS, true),
            )
            moveTaskToBack(true) // step aside so the overlay shows over the Tesla screen
        } else {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
        }
    }
}

@Composable
private fun LangChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (active) Color(0xFF2E5D7D) else Color(0xFF24262B))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = TextStyle(color = if (active) Color.White else Color(0xFF8A9099), fontSize = 14.sp))
    }
}

@Composable
private fun Btn(text: String, bg: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.width(380.dp).clip(RoundedCornerShape(12.dp))
            .background(if (enabled) bg else Color(0xFF2A2B30))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = TextStyle(color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center))
    }
}
