
package com.example.chord.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.chord.R

val ChordFont = FontFamily(
    Font(R.font.sf_pro_display_regular, FontWeight.Normal),
    Font(R.font.sf_pro_display_medium, FontWeight.Medium),
    Font(R.font.sf_pro_display_semibold, FontWeight.SemiBold),
    Font(R.font.sf_pro_display_bold, FontWeight.Bold)
)

private fun TextStyle.sfPro() = copy(fontFamily = ChordFont)

val ChordTypography: Typography = Typography().let {
    it.copy(
        displayLarge = it.displayLarge.sfPro(),
        displayMedium = it.displayMedium.sfPro(),
        displaySmall = it.displaySmall.sfPro(),

        headlineLarge = it.headlineLarge.sfPro(),
        headlineMedium = it.headlineMedium.sfPro(),
        headlineSmall = it.headlineSmall.sfPro(),

        titleLarge = it.titleLarge.sfPro(),
        titleMedium = it.titleMedium.sfPro(),
        titleSmall = it.titleSmall.sfPro(),

        bodyLarge = it.bodyLarge.sfPro(),
        bodyMedium = it.bodyMedium.sfPro(),
        bodySmall = it.bodySmall.sfPro(),

        labelLarge = it.labelLarge.sfPro(),
        labelMedium = it.labelMedium.sfPro(),
        labelSmall = it.labelSmall.sfPro()
    )
}
