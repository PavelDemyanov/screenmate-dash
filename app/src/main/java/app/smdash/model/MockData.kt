package app.smdash.model

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Calendar

/** Reproduces the design's live demo loop so we can see the dashboard breathe
 *  without the car: speed ramp, turn/blind cycle, battery drain, belt/beam toggles.
 *  [is24h] mirrors the system clock format (the car's stock does the same formatting),
 *  so flipping the emulator's "Use 24-hour format" exercises the AM/PM layout. */
fun mockDashboardFlow(is24h: Boolean = true): Flow<DashboardState> = flow {
    val t0 = System.currentTimeMillis()
    while (true) {
        val el = (System.currentTimeMillis() - t0) / 1000.0

        // speed: triangle 0 -> 210 -> 0 over 16s
        val period = 16.0
        val phase = (el % period) / period
        val tri = if (phase < 0.5) phase * 2 else (1 - phase) * 2
        val speed = (tri * 210).toInt()

        // turn / blind / hazard cycle over 22s — exercises every combination the stock can emit
        val cyc = el % 22
        var turnL = false; var turnR = false; var blindL = false; var blindR = false
        when {
            cyc < 3.0 -> turnR = true                          // right turn
            cyc in 4.0..7.0 -> turnL = true                    // left turn
            cyc in 8.0..11.0 -> { turnL = true; turnR = true } // HAZARD — both at once
            cyc in 12.0..15.0 -> blindR = true                 // blind spot right (amber)
            cyc in 16.0..19.0 -> { turnL = true; blindL = true } // turn into occupied blind spot (red)
        }

        // battery 100 -> 5 over 26s (so red/yellow thresholds show)
        val battery = (100 - (el % 26) / 26 * 95).toInt()

        // group-2 telltales toggle over 28s
        val wc = el % 28
        val belt = !(wc in 9.0..14.0)
        // high beam cycles all three states over 21s: none -> available (grey) -> on (blue)
        val bc = el % 21
        val beam = when {
            bc < 7 -> BeamMode.NONE
            bc < 14 -> BeamMode.AVAILABLE
            else -> BeamMode.ON
        }

        // autopilot cycles all three states over 18s: none -> available (grey) -> engaged (blue)
        val ac = el % 18
        val ap = when {
            ac < 6 -> ApMode.NONE
            ac < 12 -> ApMode.AVAILABLE
            else -> ApMode.ON
        }

        val cal = Calendar.getInstance()
        val time: String
        val ampm: String
        if (is24h) {
            time = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            ampm = ""
        } else {
            val h12 = cal.get(Calendar.HOUR).let { if (it == 0) 12 else it } // Calendar.HOUR is 0..11
            time = "%d:%02d".format(h12, cal.get(Calendar.MINUTE))
            ampm = if (cal.get(Calendar.AM_PM) == Calendar.PM) "PM" else "AM"
        }

        emit(
            DashboardState(
                speed = speed,
                time = time,
                ampm = ampm,
                battery = battery,
                gear = if (speed > 0) 'D' else 'P',
                turnLeft = turnL,
                turnRight = turnR,
                blindLeft = blindL,
                blindRight = blindR,
                autopilot = ap,
                belt = belt,
                heat = true,
                beam = beam,
                hold = false,
            )
        )
        delay(70)
    }
}
