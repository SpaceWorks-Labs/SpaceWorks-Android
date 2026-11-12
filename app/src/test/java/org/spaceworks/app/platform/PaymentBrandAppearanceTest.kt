package org.spaceworks.app.platform

import com.stripe.android.paymentsheet.PaymentSheet
import org.junit.Assert.*
import org.junit.Test

class PaymentBrandAppearanceTest {
    @Test fun actualSdkConfigurationKeepsExactBrandSurfacesInBothNativeModes() {
        val appearance=stripeBrandAppearance()
        assertEquals(PaymentSheet.Colors(
            primary=0xFF006E63.toInt(),surface=0xFFFFF4E8.toInt(),component=0xFFFFFFFF.toInt(),
            componentBorder=0xFF828A83.toInt(),componentDivider=0xFFD5D0C6.toInt(),
            onComponent=0xFF142C2A.toInt(),onSurface=0xFF142C2A.toInt(),
            subtitle=0xFF5B6863.toInt(),placeholderText=0xFF5B6863.toInt(),
            appBarIcon=0xFF142C2A.toInt(),error=0xFFB91C1C.toInt()),appearance.getColors(false))
        assertEquals(PaymentSheet.Colors(
            // Design-Kit 0.12.0 solid-black dark family.
            primary=0xFF80B1A6.toInt(),surface=0xFF000000.toInt(),component=0xFF141414.toInt(),
            componentBorder=0xFF787878.toInt(),componentDivider=0xFF2C2C2C.toInt(),
            onComponent=0xFFFFF4E8.toInt(),onSurface=0xFFFFF4E8.toInt(),
            subtitle=0xFFACACAC.toInt(),placeholderText=0xFFACACAC.toInt(),
            appBarIcon=0xFFFFF4E8.toInt(),error=0xFFFCA5A5.toInt()),appearance.getColors(true))
        assertNotEquals(appearance.getColors(false),appearance.getColors(true))
        assertEquals("#006E63",razorpayBrandColor())
    }
}
