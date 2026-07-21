package app.smdash.model

import kotlinx.coroutines.flow.MutableStateFlow

/** Latest dashboard state fed from the patched stock app's broadcast. */
object DashStore {
    val flow = MutableStateFlow(DashboardState())

    /** Manual transparency override (0..0.8), set by the in-app settings panel / SETTRANSP
     *  broadcast. `null` = follow the car's stock "Dashboard Transparency" (the value that
     *  rides in [flow]). On the emulator there's no stock value, so this is how you drive it. */
    val transpOverride = MutableStateFlow<Float?>(null)

    /** Selected dashboard style; set by the settings panel, persisted by OverlayService. */
    val style = MutableStateFlow(DashStyle.ARC)

    /** ANALOG dial: radial nudge (dp) for the speed numbers — user-adjustable in the tuner so the
     *  numbers can slide in/out from the centre. Baseline = measured prototype radius (126);
     *  [ANALOG_NUM_R_DEFAULT] (−6, Pavel's on-device pick) is the shipped default. */
    val analogNumR = MutableStateFlow(ANALOG_NUM_R_DEFAULT)

    const val ANALOG_NUM_R_DEFAULT = -6f

    /** ANALOG dial: number alignment. true = align each number's OUTER edge (the digit nearest the
     *  ticks) onto one radius, so the gap to the ticks is even whether the number is 1 or 3 digits
     *  (how real gauges do it); false = the classic centre-on-one-radius layout. Toggle in the tuner. */
    val analogEdgeAlign = MutableStateFlow(true)
}

private fun field(s: String, key: String): String? =
    Regex("[(, ]" + Regex.escape(key) + "=([^,)]*)").find(s)?.groupValues?.get(1)?.trim()

private fun bool(s: String, key: String): Boolean = field(s, key)?.equals("true", true) == true

private fun intOf(str: String?): Int = str?.let { Regex("-?\\d+").find(it)?.value?.toIntOrNull() } ?: 0

/** "19° C" / "25° C" / "50 ºF" -> "19°" (drop trailing unit letter). */
private fun cleanTemp(v: String?): String = v?.replace(Regex("\\s*[CFcf]\\s*$"), "")?.trim() ?: ""

/** The stock formats the clock by the car's 12/24h setting: 24h = "19:01", 12h = "7:01 pm"
 *  (suffix casing/spacing varies). Split the suffix off so the tiles can render it small
 *  (v4 AM-PM handoff) instead of letting the long string overflow the card. */
private val AMPM_RE = Regex("(?i)^\\s*(\\d{1,2}:\\d{2})\\s*([AP])\\.?\\s*M\\.?\\s*$")

private fun splitClock(raw: String): Pair<String, String> {
    val m = AMPM_RE.find(raw) ?: return raw to ""
    return m.groupValues[1] to (m.groupValues[2].uppercase() + "M")
}

/** Parse the stock DashboardState.toString() into our model.
 *  Stock fields: power, speed, speedMax, cruiseAvailable, speedLimit, isSpeedLimitVisible,
 *  speedUnits, gears, selectedGear, time, temperature, batteryPercent, ..., batteryDisplayText,
 *  outsideTemp, batteryTemp, isOutsideTempVisible, isBatteryTempVisible, isBatteryHeatingActive,
 *  autopilotState, csaState, isTirePressure, isSafetyBelt, isHighBeam, isIndicatorLeft,
 *  isIndicatorRight, isBlindSpotRearLeft, isBlindSpotRearRight, handsOnLevel, messages. */
fun parseStockState(s: String): DashboardState {
    val speed = intOf(field(s, "speed"))
    // batteryDisplayText is like "77%"; batteryPercent is a 0..1 fraction
    val battery = intOf(field(s, "batteryDisplayText")).takeIf { it > 0 }
        ?: ((field(s, "batteryPercent")?.toFloatOrNull() ?: 0f) * 100f).toInt()

    val gearRaw = field(s, "selectedGear")?.uppercase().orEmpty()
    val gear = when {
        gearRaw.contains("DRIVE") || gearRaw == "D" -> 'D'
        gearRaw.contains("REVERSE") || gearRaw == "R" -> 'R'
        gearRaw.contains("NEUTRAL") || gearRaw == "N" -> 'N'
        else -> 'P'
    }

    val limitVisible = bool(s, "isSpeedLimitVisible")
    val limit = if (limitVisible) intOf(field(s, "speedLimit")).takeIf { it > 0 } else null

    val ap = field(s, "autopilotState")?.uppercase().orEmpty()
    val autopilot = when {
        ap.contains("ENGAGED") || ap.contains("ACTIVE") -> ApMode.ON
        ap.contains("NOT_AVAILABLE") || ap.contains("UNAVAILABLE") || ap == "OFF" || ap.isEmpty() -> ApMode.NONE
        ap.contains("AVAILABLE") || ap.contains("READY") || ap.contains("STANDBY") -> ApMode.AVAILABLE
        else -> ApMode.NONE
    }

    // four independent flags — both indicators on = hazard; indicator + blind same side = danger
    val turnLeft = bool(s, "isIndicatorLeft")
    val turnRight = bool(s, "isIndicatorRight")
    val blindLeft = bool(s, "isBlindSpotRearLeft")
    val blindRight = bool(s, "isBlindSpotRearRight")

    val hands = field(s, "handsOnLevel")?.uppercase().orEmpty()
    val hold = hands.isNotEmpty() && hands != "HIDDEN" && hands != "NONE" &&
        !hands.contains("NO_WARNING") && !hands.endsWith("0")

    val (clock, ampm) = splitClock(field(s, "time") ?: "")

    // Miles vs kilometres. The stock's `speedUnits` field is UNRELIABLE — it sits at its default
    // "MPH" whenever the car is parked (the VHAL speed-units observer only fires once moving), so a
    // metric car parked would wrongly show MPH. `batteryDistanceUnit` ("km"/"mi") is the car's actual
    // distance setting and is populated even when parked — and on Tesla the speed unit and distance
    // unit are ONE setting, so it's the authoritative source. Prefer it; fall back to speedUnits only
    // if it's absent. Values are already in the car's unit — we only pick the label + gauge scale.
    val distUnit = field(s, "batteryDistanceUnit")?.lowercase()?.trim()
    val mph = when (distUnit) {
        "mi", "mile", "miles" -> true
        "km", "km.", "kilometer", "kilometers" -> false
        else -> field(s, "speedUnits")?.uppercase()?.contains("MPH") == true
    }

    return DashboardState(
        speed = speed,
        mph = mph,
        // gauge full-scale follows the units (230 km/h ≈ 145 mph); values arrive pre-converted
        speedMax = if (mph) 145 else 230,
        time = clock,
        ampm = ampm,
        battery = battery.coerceIn(0, 100),
        gear = gear,
        outTemp = cleanTemp(field(s, "outsideTemp")),
        cabinTemp = cleanTemp(field(s, "batteryTemp")),
        limit = limit,
        turnLeft = turnLeft,
        turnRight = turnRight,
        blindLeft = blindLeft,
        blindRight = blindRight,
        autopilot = autopilot,
        belt = bool(s, "isSafetyBelt"),
        heat = bool(s, "isBatteryHeatingActive"),
        beam = run {
            // isHighBeam is nullable in stock: null = no auto-high-beam, false = available (grey), true = on (blue)
            val hb = field(s, "isHighBeam")
            when {
                hb == null || hb.equals("null", true) -> BeamMode.NONE
                hb.equals("true", true) -> BeamMode.ON
                else -> BeamMode.AVAILABLE
            }
        },
        hold = hold,
        // stock "Dashboard Transparency" slider (0..0.8); shipped inside the broadcast state
        bgTransparency = (field(s, "dashboardTransparency")?.toFloatOrNull() ?: 0f).coerceIn(0f, 0.8f),
    )
}
