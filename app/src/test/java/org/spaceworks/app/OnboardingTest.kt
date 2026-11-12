package org.spaceworks.app

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.theme.BrandMotion

class OnboardingTest {
    @Test fun skipCompletionReplayAndThemePersistWithoutTouchingAccountSettings() {
        val settings=mutableMapOf("server" to "https://example.invalid")
        val intro=OnboardingStateHolder(false){settings["intro"]=it.toString()}
        assertFalse(intro.completed.value);intro.finish();assertEquals("true",settings["intro"])
        val returning=OnboardingStateHolder(settings["intro"].toBoolean()){settings["intro"]=it.toString()}
        assertTrue(returning.completed.value);returning.replay();assertEquals("https://example.invalid",settings["server"])
        val appearance=AppearanceStateHolder(false){settings["dark"]=it.toString()};appearance.setDark(true)
        assertTrue(AppearanceStateHolder(settings["dark"].toBoolean()){}.dark.value)
    }
    @Test fun motionComesFromPinnedBrandAndReducedMotionHasNoCustomDelay() {
        val motion=Json.parseToJsonElement(File("../tokens/mobile-motion.json").readText()).jsonObject
        // App durations name Design-Kit motion tokens: fast 120, base 180, slow 240 (0.12.0).
        assertEquals("fast",motion["duration_ms"]!!.jsonObject["color"]!!.jsonPrimitive.content)
        assertEquals(120,BrandMotion.COLOR);assertEquals(180,BrandMotion.OVERLAY_ENTER);assertEquals(120,BrandMotion.OVERLAY_EXIT)
        assertEquals(240,BrandMotion.SLOW);assertArrayEquals(floatArrayOf(.16f,1f,.3f,1f),BrandMotion.overlayEnterEasing,0f)
        assertEquals(0,BrandMotion.BLOCKING_DELAY);assertEquals(0,BrandMotion.duration(200,true));assertFalse(BrandMotion.REDUCED_SPATIAL)
        assertEquals("none",motion["onboarding"]!!.jsonObject["logo_motion"]!!.jsonPrimitive.content)
    }
}
