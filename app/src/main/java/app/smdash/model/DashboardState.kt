package app.smdash.model

/** Snapshot of everything the dashboard renders. Fed by mock data now, by the
 *  patched stock app later (B1: broadcast of the real DashboardState). */
data class DashboardState(
    val speed: Int = 0,
    // true when the car shows mph (stock speedUnits=MPH) — flips the unit label and the gauge
    // full-scale; speed/limit values arrive already converted by the car, we never convert.
    val mph: Boolean = false,
    val speedMax: Int = 230,        // arc full-scale in the CAR's units (230 km/h; 145 when mph)
    val time: String = "14:12",
    // 12-hour marker ("AM"/"PM") when the car clock is in 12h mode; "" in 24h mode. Split off the
    // stock's time string ("7:01 pm") in parseStockState — rendered as a small dim suffix per the
    // "Speedometer v4 (AM-PM)" handoff so it never overflows the card.
    val ampm: String = "",
    val battery: Int = 78,
    val gear: Char = 'P',           // one of P R N D
    val outTemp: String = "25°",
    val cabinTemp: String = "28°",
    val limit: Int? = 110,          // speed-limit sign number; null = hidden
    // Turn signals + blind-spot, mirrored 1:1 from the stock's four independent booleans (there is
    // NO dedicated hazard flag in the stock — hazard = turnLeft && turnRight both on at once; and a
    // turn + blind-spot on the SAME side is the stock's red "danger" case). Keeping them separate
    // lets us render both edges simultaneously; the single-value enum couldn't.
    val turnLeft: Boolean = false,
    val turnRight: Boolean = false,
    val blindLeft: Boolean = false,
    val blindRight: Boolean = false,
    // telltales (group 1: autopilot + belt, group 2: heat + beam)
    val autopilot: ApMode = ApMode.AVAILABLE,
    val belt: Boolean = false,
    val heat: Boolean = true,
    val beam: BeamMode = BeamMode.NONE,
    // hands-on takeover (full-tile warning)
    val hold: Boolean = false,
    // panel-background transparency from the stock "Dashboard Transparency" setting (0 = opaque … 0.8 = max)
    val bgTransparency: Float = 0f,
)

enum class ApMode { NONE, AVAILABLE, ON }

// auto high beam — mirrors the stock's nullable isHighBeam: null=NONE, false=AVAILABLE (grey), true=ON (blue)
enum class BeamMode { NONE, AVAILABLE, ON }
