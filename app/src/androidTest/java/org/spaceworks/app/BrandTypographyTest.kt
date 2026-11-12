package org.spaceworks.app

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.theme.BrandFonts

/** Real Android resource + Compose registration proof, using each kit face's distinct M advance (hmtx / unitsPerEm). */
class BrandTypographyTest {
    @Test fun kitFacesResolveToTheirNativeGlyphMetricsAtMultipleScales() {
        assertTrue("Brand fonts must be bundled for this check",BrandFonts.BUNDLED)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val resolver=createFontFamilyResolver(context)
        fun advance(family:FontFamily,weight:FontWeight,size:Float):Float {
            val typeface=resolver.resolve(family,weight,fontSynthesis=FontSynthesis.None).value as Typeface
            return Paint().apply {textSize=size;this.typeface=typeface;isSubpixelText=true;isLinearText=true}.getRunAdvance("M",0,1,0,1,false,1)
        }
        listOf(16f,32f,51.2f).forEach {size ->
            assertEquals("Outfit 400",.839f*size,advance(BrandFonts.sans,FontWeight.W400,size),.03f*size/16)
            assertEquals("Outfit 700 variable instance",.858f*size,advance(BrandFonts.sans,FontWeight.W700,size),.03f*size/16)
            assertTrue("Outfit 800 display is wider than 400",advance(BrandFonts.display,FontWeight.ExtraBold,size)>advance(BrandFonts.sans,FontWeight.W400,size))
            assertTrue("Outfit 300 light display is narrower than 400",advance(BrandFonts.displayLight,FontWeight.Light,size)<advance(BrandFonts.sans,FontWeight.W400,size))
        }
    }
}
