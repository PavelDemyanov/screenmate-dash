package app.smdash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Touch-draggable slider (no material dependency). */
@Composable
fun TuneSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText(label, style = TextStyle(color = Color(0xFF9A9CA1), fontSize = 12.sp))
            BasicText(value.toInt().toString(), style = TextStyle(color = Color.White, fontSize = 12.sp))
        }
        Spacer(Modifier.height(4.dp))
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .pointerInput(min, max) {
                    detectTapGestures { o -> onChange(min + (o.x / size.width).coerceIn(0f, 1f) * (max - min)) }
                }
                .pointerInput(min, max) {
                    detectHorizontalDragGestures { ch, _ -> onChange(min + (ch.position.x / size.width).coerceIn(0f, 1f) * (max - min)) }
                },
        ) {
            val w = maxWidth
            val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(3.dp).align(Alignment.CenterStart).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A3B42)))
            Box(Modifier.fillMaxWidth(frac).height(3.dp).align(Alignment.CenterStart).clip(RoundedCornerShape(2.dp)).background(Color(0xFF5FE0B0)))
            Box(Modifier.align(Alignment.CenterStart).offset(x = w * frac - 9.dp).size(18.dp).clip(CircleShape).background(Color.White))
        }
    }
}
