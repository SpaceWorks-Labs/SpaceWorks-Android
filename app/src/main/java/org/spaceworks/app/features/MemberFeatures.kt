package org.spaceworks.app.features

import org.spaceworks.app.member.MemberScope

/** The member P1 feature holders of one signed-in session; they share its scope and lifetime. */
class MemberFeatures(val history:BorrowingHistoryStateHolder,val waiver:WaiverStateHolder,val contextual:ContextualRequestStateHolder,val feeds:CalendarFeedsStateHolder,val reports:StaffReportsStateHolder,val link:ProviderLinkStateHolder,val snooze:InboxSnoozeStateHolder,val booking:StaffBookingStateHolder) {
    private val holders get()=listOf<FeatureHolder<*>>(history,waiver,contextual,feeds,reports,link,snooze,booking)
    fun activate(scope:MemberScope)=holders.forEach {it.activate(scope)}
    fun clear()=holders.forEach {it.clear()}
}

/** Real wiring: member features use the member grant's session, staff features the staff step-up session. */
fun createMemberFeatures(session:org.spaceworks.app.network.ApiSession,staffSession:org.spaceworks.app.network.ApiSession,
    text:org.spaceworks.app.platform.MobileText,onUnauthorized:suspend()->Unit,onStaffUnauthorized:suspend()->Unit)=
    MemberFeatures(BorrowingHistoryStateHolder(GeneratedBorrowingHistoryGateway(session),text,onUnauthorized),
        WaiverStateHolder(GeneratedWaiverGateway(session),text,onUnauthorized),
        ContextualRequestStateHolder(GeneratedContextualRequestGateway(session),text,onUnauthorized),
        CalendarFeedsStateHolder(GeneratedCalendarFeedGateway(session),text,onUnauthorized),
        StaffReportsStateHolder(GeneratedStaffReportsGateway(staffSession),text,onStaffUnauthorized),
        ProviderLinkStateHolder(GeneratedProviderLinkGateway(session),text,onUnauthorized),
        InboxSnoozeStateHolder(GeneratedInboxSnoozeGateway(session),text,onUnauthorized),
        StaffBookingStateHolder(GeneratedStaffBookingGateway(staffSession),text,onStaffUnauthorized))
