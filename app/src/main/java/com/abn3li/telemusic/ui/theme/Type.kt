package com.abn3li.telemusic.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import com.abn3li.telemusic.R

/** The app's font: Inter for Latin text, Noto Sans Arabic for Arabic. Built once and kept. */
object AppFonts {
    @Volatile private var family: FontFamily? = null
    @Volatile private var typeface: Typeface? = null

    fun family(context: Context): FontFamily = family ?: synchronized(this) {
        family ?: build(context.applicationContext).also { family = it }
    }

    /** For text drawn outside Compose (shared lyrics pictures). Falls back to the system font
     * until [family] has run, which the app theme does on its first frame. */
    fun typeface(bold: Boolean): Typeface {
        val base = typeface ?: return if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        return if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, if (bold) 700 else 400, false)
        else Typeface.create(base, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun build(context: Context): FontFamily {
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { combined(context) }.getOrNull()?.let { typeface = it; return FontFamily(it) }
        }
        // Before Android 10 a custom Arabic fallback can't be chained: Arabic uses the phone's font.
        return FontFamily(
            Font(R.font.inter_light, FontWeight.Light),
            Font(R.font.inter_regular, FontWeight.Normal),
            Font(R.font.inter_medium, FontWeight.Medium),
            Font(R.font.inter_semibold, FontWeight.SemiBold),
            Font(R.font.inter_bold, FontWeight.Bold),
            Font(R.font.inter_extrabold, FontWeight.ExtraBold),
            Font(R.font.inter_black, FontWeight.Black)
        )
    }

    @androidx.annotation.RequiresApi(29)
    private fun combined(context: Context): Typeface {
        val res = context.resources
        fun fam(vararg fonts: Pair<Int, Int>): android.graphics.fonts.FontFamily {
            val list = fonts.map { (id, w) -> android.graphics.fonts.Font.Builder(res, id).setWeight(w).build() }
            return android.graphics.fonts.FontFamily.Builder(list.first()).apply { list.drop(1).forEach(::addFont) }.build()
        }
        val latin = fam(R.font.inter_light to 300, R.font.inter_regular to 400,
            R.font.inter_medium to 500, R.font.inter_semibold to 600, R.font.inter_bold to 700,
            R.font.inter_extrabold to 800, R.font.inter_black to 900)
        val arabic = fam(R.font.noto_sans_arabic_regular to 400, R.font.noto_sans_arabic_medium to 500,
            R.font.noto_sans_arabic_semibold to 600, R.font.noto_sans_arabic_bold to 700)
        return Typeface.CustomFallbackBuilder(latin)
            .addCustomFallback(arabic)
            .setSystemFallback("sans-serif")
            .build()
    }
}

/** Material's type scale with every style set in the app font. Inter is wide, so its letters
 * sit a little closer than Material's open spacing: more so the bigger the text. */
internal fun appTypography(font: FontFamily): Typography {
    val t = Typography()
    return Typography(
        displayLarge = t.displayLarge.copy(fontFamily = font, letterSpacing = (-0.022).em),
        displayMedium = t.displayMedium.copy(fontFamily = font, letterSpacing = (-0.022).em),
        displaySmall = t.displaySmall.copy(fontFamily = font, letterSpacing = (-0.022).em),
        headlineLarge = t.headlineLarge.copy(fontFamily = font, letterSpacing = (-0.02).em),
        headlineMedium = t.headlineMedium.copy(fontFamily = font, letterSpacing = (-0.02).em),
        headlineSmall = t.headlineSmall.copy(fontFamily = font, letterSpacing = (-0.02).em),
        titleLarge = t.titleLarge.copy(fontFamily = font, letterSpacing = (-0.015).em),
        titleMedium = t.titleMedium.copy(fontFamily = font, letterSpacing = (-0.015).em),
        titleSmall = t.titleSmall.copy(fontFamily = font, letterSpacing = (-0.015).em),
        bodyLarge = t.bodyLarge.copy(fontFamily = font, letterSpacing = (-0.01).em),
        bodyMedium = t.bodyMedium.copy(fontFamily = font, letterSpacing = (-0.01).em),
        bodySmall = t.bodySmall.copy(fontFamily = font, letterSpacing = (-0.004).em),
        labelLarge = t.labelLarge.copy(fontFamily = font, letterSpacing = (-0.005).em),
        labelMedium = t.labelMedium.copy(fontFamily = font, letterSpacing = (-0.005).em),
        labelSmall = t.labelSmall.copy(fontFamily = font, letterSpacing = (-0.005).em)
    )
}

/** The app font, for text styles built by hand (text fields) instead of taken from the theme. */
val appFontFamily: FontFamily
    @Composable get() = MaterialTheme.typography.bodyLarge.fontFamily ?: FontFamily.Default
