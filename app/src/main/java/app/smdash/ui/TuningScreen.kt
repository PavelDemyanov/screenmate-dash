package app.smdash.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smdash.model.ApMode
import app.smdash.model.BeamMode
import app.smdash.model.DashboardState
import app.smdash.model.Tuning
import app.smdash.model.loadTuning
import app.smdash.model.logLine
import app.smdash.model.saveTuning

/** Live position/size tuning with sliders. Saves to prefs + logcat (tag SMTUNE). */
@Composable
fun TuningScreen() {
    val ctx = LocalContext.current
    var t by remember { mutableStateOf(loadTuning(ctx)) }
    val set: (Tuning) -> Unit = { nt -> t = nt; saveTuning(ctx, nt); Log.d("SMTUNE", nt.logLine()) }

    val state = DashboardState(
        speed = 188, gear = 'D', battery = 72, limit = 110,
        autopilot = ApMode.ON, belt = true, heat = true, beam = BeamMode.ON,
    )

    Row(Modifier.fillMaxSize().background(Color(0xFF0E0F11))) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            DashboardTile(state, t)
        }
        Column(
            Modifier.width(440.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(18.dp),
        ) {
            BasicText("НАСТРОЙКА  ·  значения сохраняются", style = TextStyle(color = Color.White, fontSize = 14.sp))
            Spacer(Modifier.height(10.dp))
            TuneSlider("ЦИФРА размер", t.digitSize, 50f, 120f) { set(t.copy(digitSize = it)) }
            TuneSlider("ЦИФРА ↕", t.digitDy, -70f, 60f) { set(t.copy(digitDy = it)) }
            TuneSlider("KM/H зазор", t.unitGap, -10f, 40f) { set(t.copy(unitGap = it)) }
            TuneSlider("ЗНАК ↕", t.signDy, 0f, 150f) { set(t.copy(signDy = it)) }
            TuneSlider("ЗНАК ↔", t.signDx, -120f, 120f) { set(t.copy(signDx = it)) }
            TuneSlider("ЗНАК размер", t.signSize, 22f, 66f) { set(t.copy(signSize = it)) }
            TuneSlider("Группа1 ↔", t.g1dx, -40f, 80f) { set(t.copy(g1dx = it)) }
            TuneSlider("Группа1 ↕", t.g1dy, -10f, 90f) { set(t.copy(g1dy = it)) }
            TuneSlider("Группа2 ↔", t.g2dx, -80f, 40f) { set(t.copy(g2dx = it)) }
            TuneSlider("Группа2 ↕", t.g2dy, -10f, 90f) { set(t.copy(g2dy = it)) }
            TuneSlider("Иконки размер", t.iconSize, 16f, 44f) { set(t.copy(iconSize = it)) }
            TuneSlider("Дуга толщина", t.arcStroke, 6f, 24f) { set(t.copy(arcStroke = it)) }
            Spacer(Modifier.height(12.dp))
            BasicText(t.logLine(), style = TextStyle(color = Color(0xFF6BCB77), fontSize = 10.sp))
            Spacer(Modifier.height(30.dp))
        }
    }
}
