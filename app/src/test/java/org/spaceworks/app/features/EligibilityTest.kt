package org.spaceworks.app.features

import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.copy.apiFailure
import org.spaceworks.app.network.Errors
import org.spaceworks.app.platform.MobileText

class EligibilityTest {
    @Test fun stringAndListCodesUseMemberCopy() {
        val plain=Errors.map(403,"""{"detail":"Accept the current makerspace waiver first.","code":"waiver_acceptance_required"}""")
        assertEquals("waiver_acceptance_required",plain.code)
        assertEquals(FeatureStrings.ELIGIBILITY_WAIVER_REQUIRED,plain.localCopy)
        assertEquals("Accept the space's current waiver in your member area, then try again.",MobileText.ENGLISH.apiFailure(plain))
        // DRF ValidationError shape: the first string of a one-element list.
        val listed=Errors.map(403,"""{"code":["membership_required"],"detail":["An active membership is required."]}""")
        assertEquals("membership_required",listed.code)
        assertTrue(listed.detail.startsWith("This needs an active membership of this space."))
        assertEquals(FeatureStrings.ELIGIBILITY_NO_MACHINE_ACCESS,Errors.map(403,"""{"detail":"x","code":["no_machine_access"]}""").localCopy)
    }
    @Test fun everyEmittedCodeHasCopyAndUnknownKeepsDetail() {
        listOf("membership_required","membership_inactive","membership_lapsed","waiver_acceptance_required","presence_required",
            "borrower_restricted","member_restricted","no_machine_access","machine_access_expired","machine_intake_unavailable",
            "organization_audience_required","organization_audience_not_ready","organization_audience_disabled",
            "organization_audience_changed","handle_required")
            .forEach {assertNotNull(it,Eligibility.copy(it))}
        // Renewal refusals carry their own server copy (`renewal_refusal` / detail).
        assertNull(Eligibility.copy("renewal_limit"))
        val unknown=Errors.map(403,"""{"detail":"Bookings need a phone number.","code":"phone_required"}""")
        assertNull(unknown.localCopy);assertEquals("Bookings need a phone number.",unknown.detail)
        assertNull(Eligibility.code(kotlinx.serialization.json.JsonArray(emptyList())))
        assertNull(Errors.map(403,"""{"code":[7]}""").code)
    }
}
