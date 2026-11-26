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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.abn3li.telemusic.R

/** The app's font: SF Pro when its files were built in, else Inter;
 * Noto Sans Arabic for Arabic. Built once and kept. */
object AppFonts {
    class Fonts(val text: FontFamily, val display: FontFamily, val sfPro: Boolean)

    @Volatile private var fonts: Fonts? = null
    @Volatile private var typeface: Typeface? = null

    fun fonts(context: Context): Fonts = fonts ?: synchronized(this) {
        fonts ?: build(context.applicationContext).also { fonts = it }
    }

    fun family(context: Context): FontFamily = fonts(context).text

    /** For text drawn outside Compose (shared lyrics pictures). Falls back to the system font
     * until [fonts] has run, which the app theme does on its first frame. */
    fun typeface(bold: Boolean): Typeface {
        val base = typeface ?: return if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        return if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, if (bold) 700 else 400, false)
        else Typeface.create(base, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    /** The weight each SF Pro cut draws. */
    private val sfSteps = mapOf("Thin" to listOf(100), "Ultralight" to listOf(200), "Light" to listOf(300),
        "Regular" to listOf(400), "Medium" to listOf(500), "Semibold" to listOf(600),
        "Bold" to listOf(700), "Heavy" to listOf(800), "Black" to listOf(900))

    /** SF Pro files in the app's assets, as path and weight, for "Text" or "Display". */
    private fun sfPro(context: Context, cut: String): List<Pair<String, Int>> {
        val names = runCatching { context.assets.list("fonts")?.toList() }.getOrNull().orEmpty()
        return names.flatMap { name ->
            val weight = name.removePrefix("SF-Pro-$cut-").removeSuffix(".otf")
            if (name.startsWith("SF-Pro-$cut-") && name.endsWith(".otf"))
                sfSteps[weight].orEmpty().map { "fonts/$name" to it } else emptyList()
        }.sortedBy { it.second }
    }

    private fun build(context: Context): Fonts {
        val sfDisplay = sfPro(context, "Display")
        val sfText = sfPro(context, "Text").ifEmpty { sfDisplay }
        val sfDisplayCut = sfDisplay.ifEmpty { sfText }
        val sf = sfText.isNotEmpty()
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val text = if (sf) combined(context, sfText) else combined(context, null)
                val display = if (sf && sfDisplayCut !== sfText) combined(context, sfDisplayCut) else text
                typeface = text
                return Fonts(FontFamily(text), FontFamily(display), sf)
            }
        }
        // Before Android 10 a custom Arabic fallback can't be chained: Arabic uses the phone's font.
        if (sf) runCatching {
            fun fam(files: List<Pair<String, Int>>) =
                FontFamily(files.map { (path, w) -> Font(path, context.assets, FontWeight(w)) })
            val text = fam(sfText)
            return Fonts(text, if (sfDisplayCut === sfText) text else fam(sfDisplayCut), true)
        }
        val inter = FontFamily(
            Font(R.font.inter_light, FontWeight.Light),
            Font(R.font.inter_regular, FontWeight.Normal),
            Font(R.font.inter_medium, FontWeight.Medium),
            Font(R.font.inter_semibold, FontWeight.SemiBold),
            Font(R.font.inter_bold, FontWeight.Bold),
            Font(R.font.inter_extrabold, FontWeight.ExtraBold),
            Font(R.font.inter_black, FontWeight.Black)
        )
        return Fonts(inter, inter, false)
    }

    /** Latin font (SF Pro [files] from assets, or Inter when null) with Noto Sans Arabic behind it. */
    @androidx.annotation.RequiresApi(29)
    private fun combined(context: Context, files: List<Pair<String, Int>>?): Typeface {
        val res = context.resources
        fun fam(list: List<android.graphics.fonts.Font>) =
            android.graphics.fonts.FontFamily.Builder(list.first()).apply { list.drop(1).forEach(::addFont) }.build()
        fun resFam(vararg fonts: Pair<Int, Int>) =
            fam(fonts.map { (id, w) -> android.graphics.fonts.Font.Builder(res, id).setWeight(w).build() })
        val latin = if (files != null) {
            fam(files.map { (path, w) -> android.graphics.fonts.Font.Builder(context.assets, path).setWeight(w).build() })
        } else {
            resFam(R.font.inter_light to 300, R.font.inter_regular to 400,
                R.font.inter_medium to 500, R.font.inter_semibold to 600, R.font.inter_bold to 700,
                R.font.inter_extrabold to 800, R.font.inter_black to 900)
        }
        val arabic = resFam(R.font.noto_sans_arabic_regular to 400, R.font.noto_sans_arabic_medium to 500,
            R.font.noto_sans_arabic_semibold to 600, R.font.noto_sans_arabic_bold to 700)
        return Typeface.CustomFallbackBuilder(latin)
            .addCustomFallback(arabic)
            .setSystemFallback("sans-serif")
            .build()
    }
}

/** Material's type scale with every style set in the app font. Inter is wide, so its letters
 * sit a little closer than Material's open spacing: more so the bigger the text. SF Pro has its
 * own sizes and weights for the main styles. */
internal fun appTypography(fonts: AppFonts.Fonts): Typography {
    val t = Typography()
    val font = fonts.text
    if (fonts.sfPro) {
        val big = fonts.display
        fun style(base: TextStyle, family: FontFamily, weight: Int, size: Double, spacing: Double) =
            base.copy(fontFamily = family, fontWeight = FontWeight(weight), fontSize = size.sp, letterSpacing = spacing.sp)
        // Small text opens up and big text closes in, to set lines as wide as Apple's own.
        return Typography(
            displayLarge = style(t.displayLarge, big, 800, 34.0, -0.5),
            displayMedium = t.displayMedium.copy(fontFamily = big),
            displaySmall = t.displaySmall.copy(fontFamily = big),
            headlineLarge = style(t.headlineLarge, big, 800, 30.0, -0.4),
            headlineMedium = style(t.headlineMedium, big, 700, 22.0, -0.25),
            headlineSmall = t.headlineSmall.copy(fontFamily = big),
            titleLarge = style(t.titleLarge, big, 700, 20.0, -0.2),
            titleMedium = style(t.titleMedium, font, 600, 16.0, 0.05),
            titleSmall = t.titleSmall.copy(fontFamily = font),
            bodyLarge = style(t.bodyLarge, font, 400, 16.0, 0.2),
            bodyMedium = style(t.bodyMedium, font, 400, 14.0, 0.3),
            bodySmall = t.bodySmall.copy(fontFamily = font),
            labelLarge = t.labelLarge.copy(fontFamily = font),
            labelMedium = style(t.labelMedium, font, 600, 12.0, 0.25),
            labelSmall = style(t.labelSmall, font, 600, 11.0, 0.3)
        )
    }
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
