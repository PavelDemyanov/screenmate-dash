package app.smdash.model

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/** Per-element vertical nudges (dp) for a compact tile's TEXT, tuned live on-device
 *  (TuningActivity) and persisted. The tiles read these so Pavel can eyeball-align every element
 *  against the card; the chosen numbers get baked back here as the [TileTune] defaults. */
data class TileTune(
    val digit: Float = -25f, // big speed digits (Martian Mono's ink sits low in its box)
    val kmh: Float = 0f,     // "KM/H" label
    val gear: Float = 0f,    // PRND row (STACK) / gear pill (STRIP, MINI)
    val batt: Float = 0f,    // battery %
    val time: Float = 0f,    // clock
    val limit: Float = 0f,   // speed-limit sign
)

/** Live per-style tuning store. One [TileTune] per compact style; the tiles collect their own.
 *  The [*_DEF] baselines are Pavel's on-device alignment (tuned 2026-07-09) — the shipped defaults;
 *  the `compact_tuning` prefs (only written by the tuner) override them when present. */
object CompactTuning {
    // absolute placement + Pavel's on-device digit nudges (tuner, 2026-07-09)
    val STACK_DEF = TileTune(digit = -11f)
    val STRIP_DEF = TileTune(digit = -26f)
    val MINI_DEF = TileTune(digit = -10f)

    val stack = MutableStateFlow(STACK_DEF)
    val strip = MutableStateFlow(STRIP_DEF)
    val mini = MutableStateFlow(MINI_DEF)

    fun flow(s: DashStyle) = when (s) {
        DashStyle.STRIP -> strip
        DashStyle.MINI -> mini
        else -> stack // STACK (ARC never uses this)
    }

    /** per-style default (for the tuner's "reset") */
    fun def(s: DashStyle) = when (s) {
        DashStyle.STRIP -> STRIP_DEF
        DashStyle.MINI -> MINI_DEF
        else -> STACK_DEF
    }

    private const val PREFS = "compact_tuning"

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        stack.value = read(p, "stack", STACK_DEF)
        strip.value = read(p, "strip", STRIP_DEF)
        mini.value = read(p, "mini", MINI_DEF)
    }

    fun save(ctx: Context) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        write(e, "stack", stack.value); write(e, "strip", strip.value); write(e, "mini", mini.value)
        e.apply()
    }

    private fun read(p: SharedPreferences, k: String, d: TileTune) = TileTune(
        digit = p.getFloat("${k}_digit", d.digit),
        kmh = p.getFloat("${k}_kmh", d.kmh),
        gear = p.getFloat("${k}_gear", d.gear),
        batt = p.getFloat("${k}_batt", d.batt),
        time = p.getFloat("${k}_time", d.time),
        limit = p.getFloat("${k}_limit", d.limit),
    )

    private fun write(e: SharedPreferences.Editor, k: String, t: TileTune) {
        e.putFloat("${k}_digit", t.digit).putFloat("${k}_kmh", t.kmh).putFloat("${k}_gear", t.gear)
            .putFloat("${k}_batt", t.batt).putFloat("${k}_time", t.time).putFloat("${k}_limit", t.limit)
    }

    /** Human-readable snapshot — logged + shown in the tuner so the numbers can be baked as defaults. */
    fun dump(): String = buildString {
        appendLine("STACK  $stackLine")
        appendLine("STRIP  $stripLine")
        append("MINI   $miniLine")
    }
    private val stackLine get() = line(stack.value)
    private val stripLine get() = line(strip.value)
    private val miniLine get() = line(mini.value)
    private fun line(t: TileTune) =
        "digit=${t.digit} kmh=${t.kmh} gear=${t.gear} batt=${t.batt} time=${t.time} limit=${t.limit}"
}
