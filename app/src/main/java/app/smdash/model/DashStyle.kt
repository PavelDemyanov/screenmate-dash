package app.smdash.model

import kotlin.math.roundToInt

/** Dashboard presentation styles. ARC is the original variant-03 Bold Dual-Arc tile; the compact
 *  styles come from the "Speedometer v3 (compact)" handoff (01·STACK / 02·STRIP / 03·MINI).
 *
 *  Each style declares its own DESIGN size in dp; the overlay window is created at exactly
 *  [pxW]×[pxH] (device density is fixed at 240 → 1.5 px/dp) and, per the hard-won invariant,
 *  is never resized during a gesture — only when the user picks another style. */
enum class DashStyle(val key: String, val wDp: Float, val hDp: Float) {
    ARC("arc", 380f, 379.33f),   // 570×569 px — the historical tile size, unchanged
    STACK("stack", 300f, 230f),   // exact prototype card size (measured via getBoundingClientRect)
    // STACK + outside/battery temperatures flanking the speed ("Speedometer v5" handoff). Same card
    // as STACK (300×230) — the two temps sit inside it at the left/right edges.
    STACK_TEMP("stacktemp", 300f, 230f),
    STRIP("strip", 552.7f, 114f), // exact prototype card size
    MINI("mini", 232f, 249f);     // exact prototype card size

    val pxW: Int get() = (wDp * DENSITY).roundToInt()
    val pxH: Int get() = (hDp * DENSITY).roundToInt()

    /** Card width for the active clock format. STRIP's prototype card is `width:fit-content`, and
     *  the v4 (AM-PM) handoff grows its stats column to the fixed 88px time block → the measured
     *  card is 575 (vs 552.7 in 24h). The other cards are fixed-width — the time block fits inside.
     *  The format flips only from the car's settings, so the window resize this implies is as
     *  discrete (and sanctioned) as a style switch. */
    fun wDpFor(ampm: Boolean): Float = if (this == STRIP && ampm) 575f else wDp
    fun pxWFor(ampm: Boolean): Int = (wDpFor(ampm) * DENSITY).roundToInt()

    companion object {
        /** device density scale (240 dpi) — same on the car box and the emulator AVD */
        const val DENSITY = 1.5f

        fun fromKey(k: String?): DashStyle = entries.firstOrNull { it.key == k } ?: ARC
    }
}
