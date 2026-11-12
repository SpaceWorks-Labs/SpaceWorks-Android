package org.spaceworks.app.features

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.spaceworks.app.platform.MobileCopyKey

/**
 * Member eligibility refusals (PRD "Eligibility refusal codes and copy", web `eligibilityCopy.ts`).
 * The same copy is used for requests, contextual requests, bookings, registrations and renewals;
 * unknown codes keep the server's `detail`.
 */
object Eligibility {
    /** The refusal code: a string, or DRF's one-element list shape `{"code":["membership_required"]}`. */
    fun code(value:JsonElement?):String? = when(value) {
        is JsonPrimitive->value.contentOrNull
        is JsonArray->(value.firstOrNull() as? JsonPrimitive)?.takeIf {it.isString}?.contentOrNull
        else->null
    }
    fun copy(code:String?):MobileCopyKey? = when(code) {
        "membership_required"->FeatureStrings.ELIGIBILITY_MEMBERSHIP_REQUIRED
        "membership_inactive"->FeatureStrings.ELIGIBILITY_MEMBERSHIP_INACTIVE
        "membership_lapsed"->FeatureStrings.ELIGIBILITY_MEMBERSHIP_LAPSED
        "waiver_acceptance_required"->FeatureStrings.ELIGIBILITY_WAIVER_REQUIRED
        "presence_required"->FeatureStrings.ELIGIBILITY_PRESENCE_REQUIRED
        "borrower_restricted","member_restricted"->FeatureStrings.ELIGIBILITY_RESTRICTED
        "no_machine_access"->FeatureStrings.ELIGIBILITY_NO_MACHINE_ACCESS
        "machine_access_expired"->FeatureStrings.ELIGIBILITY_MACHINE_ACCESS_EXPIRED
        "event_only_product"->FeatureStrings.ELIGIBILITY_EVENT_ONLY_PRODUCT
        "self_renewal_off"->FeatureStrings.ELIGIBILITY_SELF_RENEWAL_OFF
        "renewal_limit"->FeatureStrings.ELIGIBILITY_RENEWAL_LIMIT
        "renewals_off"->FeatureStrings.ELIGIBILITY_RENEWALS_OFF
        "machine_intake_unavailable"->FeatureStrings.ELIGIBILITY_MACHINE_INTAKE_UNAVAILABLE
        "organization_audience_required"->FeatureStrings.ELIGIBILITY_ORGANIZATION_AUDIENCE_REQUIRED
        "organization_audience_not_ready"->FeatureStrings.ELIGIBILITY_ORGANIZATION_AUDIENCE_NOT_READY
        "organization_audience_disabled"->FeatureStrings.ELIGIBILITY_ORGANIZATION_AUDIENCE_DISABLED
        "organization_audience_changed"->FeatureStrings.ELIGIBILITY_ORGANIZATION_AUDIENCE_CHANGED
        "handle_required"->FeatureStrings.ELIGIBILITY_HANDLE_REQUIRED
        else->null
    }
}
