package app.smdash

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import app.smdash.model.ApMode
import app.smdash.model.CompactTuning
import app.smdash.model.DashStyle
import app.smdash.model.DashboardState
import app.smdash.model.TileTune
import app.smdash.ui.MiniTile
import app.smdash.ui.StackTile
import app.smdash.ui.StripTile

/**
 * Live text-alignment tuner for the compact tiles. Pavel nudges each text element up/down on the
 * emulator; every change is saved to the `compact_tuning` prefs. Read the chosen numbers back with
 *   adb shell run-as app.smdash cat shared_prefs/compact_tuning.xml
 * (or tap "Показать значения" → logcat tag `smdash-tune`) and bake them into [TileTune] defaults.
 */
class TuningActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CompactTuning.load(this)
        val ctx = this
        setContent {
            var style by remember { mutableStateOf(DashStyle.STACK) }
            var dump by remember { mutableStateOf("") }
            val demo = DashboardState(
                speed = 88, gear = 'D', battery = 81, time = "22:37", limit = 110,
                autopilot = ApMode.ON, heat = true,
            )
            Column(
                Modifier.fillMaxWidth().background(Color(0xFF0B0C0E))
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BasicText(
                    "Выравнивание текста — двигай по высоте",
                    style = TextStyle(color = Color.White, fontSize = 18.sp),
                )
                // style tabs
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in listOf(DashStyle.STACK, DashStyle.STRIP, DashStyle.MINI)) {
                        Chip(s.name, active = style == s) { style = s }
                    }
                }
                // live tile (its size is fixed per style)
                Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    when (style) {
                        DashStyle.STACK -> StackTile(demo)
                        DashStyle.STRIP -> StripTile(demo)
                        else -> MiniTile(demo)
                    }
                }
                // controls for the current style
                val flow = CompactTuning.flow(style)
                val t by flow.collectAsState()
                val bump: (TileTune) -> Unit = { flow.value = it; CompactTuning.save(ctx) }
                TuneRow("Цифры", t.digit) { bump(t.copy(digit = t.digit + it)) }
                TuneRow("KM/H", t.kmh) { bump(t.copy(kmh = t.kmh + it)) }
                TuneRow(if (style == DashStyle.STACK) "PRND" else "Передача", t.gear) { bump(t.copy(gear = t.gear + it)) }
                TuneRow("Батарея", t.batt) { bump(t.copy(batt = t.batt + it)) }
                TuneRow("Время", t.time) { bump(t.copy(time = t.time + it)) }
                TuneRow("Знак лимита", t.limit) { bump(t.copy(limit = t.limit + it)) }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Показать значения", active = false) {
                        dump = CompactTuning.dump(); Log.i("smdash-tune", "\n$dump")
                    }
                    Chip("Сброс стиля", active = false) { bump(CompactTuning.def(style)) }
                }
                if (dump.isNotEmpty()) {
                    BasicText(
                        dump,
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF17181C)).padding(10.dp),
                        style = TextStyle(color = Color(0xFFB9C0C7), fontSize = 12.sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TuneRow(label: String, value: Float, onDelta: (Float) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, Modifier.width(96.dp), style = TextStyle(color = Color.White, fontSize = 14.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Step("−5") { onDelta(-5f) }
            Step("−1") { onDelta(-1f) }
            Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) {
                BasicText(
                    "${value.toInt()}",
                    style = TextStyle(color = Color(0xFF7FE0B4), fontSize = 16.sp, textAlign = TextAlign.Center),
                )
            }
            Step("+1") { onDelta(1f) }
            Step("+5") { onDelta(5f) }
        }
    }
}

@Composable
private fun Step(text: String, onClick: () -> Unit) {
    Box(
        Modifier.width(46.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2C31))
            .clickable { onClick() }.padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = TextStyle(color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center))
    }
}

@Composable
private fun Chip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (active) Color(0xFF2E5D7D) else Color(0xFF24262B))
            .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = TextStyle(color = if (active) Color.White else Color(0xFF9AA0A6), fontSize = 14.sp))
    }
}
