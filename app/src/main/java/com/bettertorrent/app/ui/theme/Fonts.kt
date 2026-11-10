package com.bettertorrent.app.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.bettertorrent.app.R
import com.bettertorrent.app.data.AppFont

/**
 * Bundled open-source fonts (SIL OFL 1.1, licenses in assets/licenses/fonts).
 * Most are trimmed to Latin + the four weights the UI uses. Lora and Merriweather
 * have Reserved Font Names, so they ship unmodified. Characters a font lacks
 * (e.g. Tamil, Hindi, CJK names) fall back to the system font automatically.
 */
object AppFonts {

    private fun fourWeights(regular: Int, medium: Int, semiBold: Int, bold: Int) = FontFamily(
        Font(regular, FontWeight.Normal),
        Font(medium, FontWeight.Medium),
        Font(semiBold, FontWeight.SemiBold),
        Font(bold, FontWeight.Bold),
    )

    private val inter by lazy {
        fourWeights(R.font.inter_regular, R.font.inter_medium, R.font.inter_semibold, R.font.inter_bold)
    }
    private val poppins by lazy {
        fourWeights(R.font.poppins_regular, R.font.poppins_medium, R.font.poppins_semibold, R.font.poppins_bold)
    }
    private val nunito by lazy {
        fourWeights(R.font.nunito_regular, R.font.nunito_medium, R.font.nunito_semibold, R.font.nunito_bold)
    }
    private val manrope by lazy {
        fourWeights(R.font.manrope_regular, R.font.manrope_medium, R.font.manrope_semibold, R.font.manrope_bold)
    }
    private val sourceSerif by lazy {
        fourWeights(R.font.sourceserif4_regular, R.font.sourceserif4_medium, R.font.sourceserif4_semibold, R.font.sourceserif4_bold)
    }
    private val literata by lazy {
        fourWeights(R.font.literata_regular, R.font.literata_medium, R.font.literata_semibold, R.font.literata_bold)
    }

    /** One variable file; each weight is a point on its wght axis. */
    @OptIn(ExperimentalTextApi::class)
    private val lora by lazy {
        FontFamily(
            listOf(400, 500, 600, 700).map { w ->
                Font(R.font.lora_variable, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
            },
        )
    }

    /** Upstream static Regular and Bold; Medium maps to Regular and SemiBold to Bold. */
    private val merriweather by lazy {
        FontFamily(
            Font(R.font.merriweather_regular, FontWeight.Normal),
            Font(R.font.merriweather_bold, FontWeight.Bold),
        )
    }

    fun family(font: AppFont): FontFamily = when (font) {
        AppFont.SYSTEM -> FontFamily.Default
        AppFont.INTER -> inter
        AppFont.POPPINS -> poppins
        AppFont.NUNITO -> nunito
        AppFont.MANROPE -> manrope
        AppFont.LORA -> lora
        AppFont.MERRIWEATHER -> merriweather
        AppFont.SOURCE_SERIF -> sourceSerif
        AppFont.LITERATA -> literata
    }

    /** License files in assets/licenses/fonts, keyed by display name. */
    val licenseFiles = listOf(
        "Inter" to "inter.txt",
        "Poppins" to "poppins.txt",
        "Nunito" to "nunito.txt",
        "Manrope" to "manrope.txt",
        "Lora" to "lora.txt",
        "Merriweather" to "merriweather.txt",
        "Source Serif 4" to "sourceserif4.txt",
        "Literata" to "literata.txt",
    )
}
