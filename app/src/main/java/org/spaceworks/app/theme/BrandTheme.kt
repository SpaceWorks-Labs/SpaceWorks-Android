package org.spaceworks.app.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.spaceworks.app.platform.LocalReducedMotion
import org.spaceworks.app.platform.rememberReducedMotion

/** Kit body/interface family (Outfit 400-700), kept for callers that need the UI face directly. */
val BrandFontFamily = BrandFonts.sans

/**
 * Design-Kit type roles (design/docs/ui-brand-spec.md D.3) on Material's native sizes, line heights
 * and scalable sp units: Outfit 800 for display headings, Outfit 600 for titles, 400 for body,
 * 500 for labels and 600 for button labels. Outfit 300 (light display) and Unbounded are opt-in per screen.
 */
val BrandTypography = Typography().run {
    val display=BrandFonts.display; val sans=BrandFonts.sans
    copy(
        displayLarge=displayLarge.copy(fontFamily=display,fontWeight=FontWeight.ExtraBold),
        displayMedium=displayMedium.copy(fontFamily=display,fontWeight=FontWeight.ExtraBold),
        displaySmall=displaySmall.copy(fontFamily=display,fontWeight=FontWeight.ExtraBold),
        headlineLarge=headlineLarge.copy(fontFamily=display,fontWeight=FontWeight.ExtraBold),
        headlineMedium=headlineMedium.copy(fontFamily=sans,fontWeight=FontWeight.SemiBold),
        headlineSmall=headlineSmall.copy(fontFamily=sans,fontWeight=FontWeight.SemiBold),
        titleLarge=titleLarge.copy(fontFamily=sans,fontWeight=FontWeight.SemiBold),
        titleMedium=titleMedium.copy(fontFamily=sans,fontWeight=FontWeight.SemiBold),
        titleSmall=titleSmall.copy(fontFamily=sans,fontWeight=FontWeight.SemiBold),
        bodyLarge=bodyLarge.copy(fontFamily=sans,fontWeight=FontWeight.Normal),
        bodyMedium=bodyMedium.copy(fontFamily=sans,fontWeight=FontWeight.Normal),
        bodySmall=bodySmall.copy(fontFamily=sans,fontWeight=FontWeight.Normal),
        labelLarge=labelLarge.copy(fontFamily=sans,fontWeight=FontWeight.SemiBold),
        labelMedium=labelMedium.copy(fontFamily=sans,fontWeight=FontWeight.Medium),
        labelSmall=labelSmall.copy(fontFamily=sans,fontWeight=FontWeight.Medium)
    )
}

/** True when the app (not the system) selected the dark palette; picks `dark/` mascot art. */
val LocalBrandDark=staticCompositionLocalOf {false}

@Composable fun SpaceWorksTheme(dark:Boolean,content:@Composable ()->Unit) {
    val reduced=rememberReducedMotion()
    CompositionLocalProvider(LocalReducedMotion provides reduced,LocalBrandDark provides dark) {
    MaterialTheme(colorScheme=if(dark)DarkScheme else LightScheme,typography=BrandTypography,
        shapes=Shapes(small=RoundedCornerShape(10.dp),medium=RoundedCornerShape(16.dp)),content=content)
    }
}
