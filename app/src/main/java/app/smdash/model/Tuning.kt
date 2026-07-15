package app.smdash.model

import android.content.Context

/** Live-tunable layout params (set on-device via sliders, then baked in as defaults). */
data class Tuning(
    // defaults = the values Pavel dialed in on-device (2026-06-14)
    val digitSize: Float = 73.24f,  // speed number font size (sp)
    val digitDy: Float = -23.83f,   // speed+KM/H vertical offset (dp, from arc bottom)
    val unitGap: Float = 6f,        // gap between number and KM/H (dp)
    val signDx: Float = 0f,         // speed-limit sign X (dp)
    val signDy: Float = 45.85f,     // speed-limit sign Y (dp)
    val signSize: Float = 44.01f,   // speed-limit sign diameter (dp)
    val g1dx: Float = -0.75f,       // group 1 (autopilot+belt) X
    val g1dy: Float = -1.20f,       // group 1 Y
    val g2dx: Float = 7.35f,        // group 2 (heat+beam) X
    val g2dy: Float = -0.87f,       // group 2 Y
    val iconSize: Float = 26.08f,   // telltale icon size (dp)
    val arcStroke: Float = 14f,     // arc thickness (arc viewBox units)
)

private const val PREF = "tuning"

fun loadTuning(ctx: Context): Tuning {
    val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    val d = Tuning()
    return Tuning(
        digitSize = p.getFloat("digitSize", d.digitSize),
        digitDy = p.getFloat("digitDy", d.digitDy),
        unitGap = p.getFloat("unitGap", d.unitGap),
        signDx = p.getFloat("signDx", d.signDx),
        signDy = p.getFloat("signDy", d.signDy),
        signSize = p.getFloat("signSize", d.signSize),
        g1dx = p.getFloat("g1dx", d.g1dx),
        g1dy = p.getFloat("g1dy", d.g1dy),
        g2dx = p.getFloat("g2dx", d.g2dx),
        g2dy = p.getFloat("g2dy", d.g2dy),
        iconSize = p.getFloat("iconSize", d.iconSize),
        arcStroke = p.getFloat("arcStroke", d.arcStroke),
    )
}

fun saveTuning(ctx: Context, t: Tuning) {
    ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().apply {
        putFloat("digitSize", t.digitSize); putFloat("digitDy", t.digitDy); putFloat("unitGap", t.unitGap)
        putFloat("signDx", t.signDx); putFloat("signDy", t.signDy); putFloat("signSize", t.signSize)
        putFloat("g1dx", t.g1dx); putFloat("g1dy", t.g1dy); putFloat("g2dx", t.g2dx); putFloat("g2dy", t.g2dy)
        putFloat("iconSize", t.iconSize); putFloat("arcStroke", t.arcStroke)
        apply()
    }
}

fun Tuning.logLine(): String =
    ("SMTUNE digitSize=%.0f digitDy=%.0f unitGap=%.0f sign=(%.0f,%.0f) signSize=%.0f " +
        "g1=(%.0f,%.0f) g2=(%.0f,%.0f) icon=%.0f arc=%.0f")
        .format(digitSize, digitDy, unitGap, signDx, signDy, signSize, g1dx, g1dy, g2dx, g2dy, iconSize, arcStroke)
