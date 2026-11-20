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
import com.abn3li.telemusic.R

/** The app's font: Ubuntu for Latin text, Noto Sans Arabic for Arabic. Built once and kept. */
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
            Font(R.font.ubuntu_light, FontWeight.Light),
            Font(R.font.ubuntu_regular, FontWeight.Normal),
            Font(R.font.ubuntu_medium, FontWeight.Medium),
            Font(R.font.ubuntu_bold, FontWeight.Bold)
        )
    }

    @androidx.annotation.RequiresApi(29)
    private fun combined(context: Context): Typeface {
        val res = context.resources
        fun fam(vararg fonts: Pair<Int, Int>): android.graphics.fonts.FontFamily {
            val list = fonts.map { (id, w) -> android.graphics.fonts.Font.Builder(res, id).setWeight(w).build() }
            return android.graphics.fonts.FontFamily.Builder(list.first()).apply { list.drop(1).forEach(::addFont) }.build()
        }
        val latin = fam(R.font.ubuntu_light to 300, R.font.ubuntu_regular to 400,
            R.font.ubuntu_medium to 500, R.font.ubuntu_bold to 700)
        val arabic = fam(R.font.noto_sans_arabic_regular to 400, R.font.noto_sans_arabic_medium to 500,
            R.font.noto_sans_arabic_semibold to 600, R.font.noto_sans_arabic_bold to 700)
        return Typeface.CustomFallbackBuilder(latin)
            .addCustomFallback(arabic)
            .setSystemFallback("sans-serif")
            .build()
    }
}

/** Material's type scale with every style set in the app font. */
internal fun appTypography(font: FontFamily): Typography {
    val t = Typography()
    return Typography(
        displayLarge = t.displayLarge.copy(fontFamily = font),
        displayMedium = t.displayMedium.copy(fontFamily = font),
        displaySmall = t.displaySmall.copy(fontFamily = font),
        headlineLarge = t.headlineLarge.copy(fontFamily = font),
        headlineMedium = t.headlineMedium.copy(fontFamily = font),
        headlineSmall = t.headlineSmall.copy(fontFamily = font),
        titleLarge = t.titleLarge.copy(fontFamily = font),
        titleMedium = t.titleMedium.copy(fontFamily = font),
        titleSmall = t.titleSmall.copy(fontFamily = font),
        bodyLarge = t.bodyLarge.copy(fontFamily = font),
        bodyMedium = t.bodyMedium.copy(fontFamily = font),
        bodySmall = t.bodySmall.copy(fontFamily = font),
        labelLarge = t.labelLarge.copy(fontFamily = font),
        labelMedium = t.labelMedium.copy(fontFamily = font),
        labelSmall = t.labelSmall.copy(fontFamily = font)
    )
}

/** The app font, for text styles built by hand (text fields) instead of taken from the theme. */
val appFontFamily: FontFamily
    @Composable get() = MaterialTheme.typography.bodyLarge.fontFamily ?: FontFamily.Default
