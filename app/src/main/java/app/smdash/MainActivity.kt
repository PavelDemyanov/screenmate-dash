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
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

                    UpdateCard(ctx, s)

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

/**
 * Update status, in OUR app. This exists because the injected settings panel — where the update
 * button used to live — vanishes together with the patch the moment Screenmate updates itself.
 * That is exactly when the user most needs to be told a new SM Dash is required, and it is the one
 * moment the panel cannot tell them. So the facts live here: what we are, what the box runs, and
 * what this build was made for.
 */
@Composable
private fun UpdateCard(ctx: android.content.Context, s: Strings) {
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var stock by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(tick) {
        stock = withContext(Dispatchers.IO) { UpdateChecker.stockVersion(ctx) }
        status = runCatching {
            Settings.Global.getString(ctx.contentResolver, UpdateChecker.GLOBAL_STATUS)
        }.getOrNull().orEmpty()
        target = runCatching {
            Settings.Global.getString(ctx.contentResolver, UpdateChecker.GLOBAL_LATEST)
        }.getOrNull().orEmpty()
    }

    val mine = UpdateChecker.currentVersion(ctx)
    val need = Patcher.REQUIRED_STOCK_PREFIX
    val stockOk = stock.isEmpty() || stock.startsWith(need)

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF17181C)).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(s.updTitle, style = TextStyle(color = Color.White, fontSize = 16.sp))
        BasicText(
            "SM Dash $mine" + if (stock.isNotEmpty()) "   ·   Screenmate $stock" else "",
            style = TextStyle(color = Color(0xFF9AA0A6), fontSize = 13.sp, textAlign = TextAlign.Center),
        )
        if (!stockOk) {
            // The box moved on without us: say so plainly, with both versions.
            BasicText(
                s.updStockGonePrefix + need,
                style = TextStyle(color = Color(0xFFF2564B), fontSize = 13.sp, textAlign = TextAlign.Center),
            )
        }
        when {
            checking -> BasicText(s.updChecking, style = TextStyle(color = Color(0xFF9AA0A6), fontSize = 13.sp))
            status == "available" && target.isNotEmpty() ->
                Btn(s.updAvailPrefix + target, Color(0xFF2E7D5B)) {
                    ctx.sendBroadcast(
                        Intent(OverlayService.ACTION_DO_UPDATE).setPackage(ctx.packageName),
                    )
                    checking = true
                }
            status == "blocked_stock" -> {
                val needs = runCatching {
                    Settings.Global.getString(ctx.contentResolver, UpdateChecker.GLOBAL_NEEDS_STOCK)
                }.getOrNull().orEmpty()
                BasicText(
                    s.updNeedStockPrefix + needs,
                    style = TextStyle(color = Color(0xFFF2564B), fontSize = 13.sp, textAlign = TextAlign.Center),
                )
            }
            status == "current" ->
                BasicText(s.updCurrent, style = TextStyle(color = Color(0xFF7FE0B4), fontSize = 13.sp))
        }
        Btn(s.updCheck, Color(0xFF33343A)) {
            checking = true
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { UpdateChecker.check(ctx, force = true) }
                withContext(Dispatchers.Main) { checking = false; tick++ }
            }
        }
    }
}
