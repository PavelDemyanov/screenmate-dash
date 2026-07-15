package app.smdash.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.smdash.R

/** Martian Mono (OFL) — the design's number/label font, bundled in res/font. */
val MartianMono = FontFamily(
    Font(R.font.martian_mono_regular, FontWeight.Normal),
    Font(R.font.martian_mono_medium, FontWeight.Medium),
    Font(R.font.martian_mono_semibold, FontWeight.SemiBold),
    Font(R.font.martian_mono_bold, FontWeight.Bold),
)
