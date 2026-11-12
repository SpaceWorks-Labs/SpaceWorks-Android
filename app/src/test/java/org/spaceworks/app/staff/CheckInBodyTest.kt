package org.spaceworks.app.staff

import org.junit.Assert.*
import org.junit.Test

/** 0.12.0 check-in: a ticket QR carries a UUID `checkin_token`; any other scan is a card/badge `credential` (≤ 80). */
class CheckInBodyTest {
    @Test fun ticketUuidIsSentAsCheckinToken() {
        val body=checkInBody(" 9B2F6C1E-4D3A-4F5B-8C7D-0E1F2A3B4C5D ")!!
        assertEquals("9b2f6c1e-4d3a-4f5b-8c7d-0e1f2a3b4c5d",body.checkinToken.toString());assertNull(body.credential)
    }
    @Test fun otherScansAreCredentialsWithinTheBackendLimit() {
        val body=checkInBody("BADGE-0042")!!
        assertNull(body.checkinToken);assertEquals("BADGE-0042",body.credential)
        assertNull(checkInBody(""));assertNull(checkInBody("x".repeat(81)))
        assertEquals("x".repeat(80),checkInBody("x".repeat(80))!!.credential)
    }
}
