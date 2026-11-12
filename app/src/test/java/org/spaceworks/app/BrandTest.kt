package org.spaceworks.app
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import org.spaceworks.app.theme.BrandTokens
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.spaceworks.app.theme.LightScheme
import org.spaceworks.app.theme.DarkScheme
import org.spaceworks.app.theme.BrandTypography
import org.spaceworks.app.theme.BrandFontFamily
import org.spaceworks.app.theme.BrandFonts
import org.spaceworks.app.theme.BrandCards
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Typography
class BrandTest {
    @Test fun materialRolesUseKitFamiliesWithoutChangingNativeTypeScale() {
        fun roles(t:Typography)=listOf(t.displayLarge,t.displayMedium,t.displaySmall,
            t.headlineLarge,t.headlineMedium,t.headlineSmall,t.titleLarge,t.titleMedium,t.titleSmall,
            t.bodyLarge,t.bodyMedium,t.bodySmall,t.labelLarge,t.labelMedium,t.labelSmall)
        roles(BrandTypography).zip(roles(Typography())).forEachIndexed {index,(brand,native) ->
            // Display headings: Outfit 800; every other role: Outfit at its role weight (open-fonts rule).
            assertEquals(if(index<4)BrandFonts.display else BrandFontFamily,brand.fontFamily)
            assertEquals(if(index<4)FontWeight.ExtraBold else brand.fontWeight,brand.fontWeight)
            assertEquals(native.fontSize,brand.fontSize)
            assertEquals(native.lineHeight,brand.lineHeight)
            assertEquals(native.letterSpacing,brand.letterSpacing)
        }
        assertEquals(FontWeight.Normal,BrandTypography.bodyLarge.fontWeight)
        assertEquals(FontWeight.SemiBold,BrandTypography.labelLarge.fontWeight)
        assertTrue("Kit fonts are converted at prepare time on this build",BrandFonts.BUNDLED)
    }
    @Test fun themeMatchesPinnedSource() {
        assertEquals(0xFFFFF4E8.toInt(),BrandTokens.Light.bg.toArgb())
        assertEquals(0xFF006E63.toInt(),BrandTokens.Light.accent.toArgb())
        // Design-Kit 0.12.0 solid-black dark family and scrim token.
        assertEquals(0xFF000000.toInt(),BrandTokens.Dark.bg.toArgb())
        assertEquals(0xFF0B0B0B.toInt(),BrandTokens.Dark.surface.toArgb())
        assertEquals(0xFF141414.toInt(),BrandTokens.Dark.panel.toArgb())
        assertEquals(0xFF000000.toInt(),DarkScheme.scrim.toArgb())
        assertEquals(0xFF142C2A.toInt(),LightScheme.scrim.toArgb())
        assertEquals(0xFF000000.toInt(),DarkScheme.background.toArgb())
        assertEquals(0xFF142C2A.toInt(),BrandCards.Dark.background.toArgb())
        assertEquals(0xFFDFFF8F.toInt(),BrandCards.Light.ribbon.toArgb())
    }
    @Test fun interactiveTextAndLabelsHaveAccessibleContrast() {
        fun contrast(a:Color,b:Color):Double { val x=a.luminance().toDouble();val y=b.luminance().toDouble();return (maxOf(x,y)+0.05)/(minOf(x,y)+0.05) }
        listOf(LightScheme,DarkScheme).forEach {scheme ->
            assertTrue(contrast(scheme.onPrimary,scheme.primary)>=4.5)
            listOf(scheme.background,scheme.surface).forEach {surface ->
                assertTrue(contrast(scheme.onPrimaryContainer,surface)>=4.5)
                assertTrue(contrast(scheme.onSurface,surface)>=4.5)
            }
        }
    }
    @Test fun applicationIdReadsBrandConfiguration() { val brand=Json.parseToJsonElement(File("../branding/brand.json").readText()).jsonObject; assertEquals(brand.getValue("android_application_id").jsonPrimitive.content,BuildConfig.APPLICATION_ID) }
}
