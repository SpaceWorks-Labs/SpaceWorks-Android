package org.spaceworks.app.features

import org.spaceworks.app.member.MemberScope

/** The member P1 feature holders of one signed-in session; they share its scope and lifetime. */
class MemberFeatures(val history:BorrowingHistoryStateHolder,val waiver:WaiverStateHolder) {
    private val holders get()=listOf<FeatureHolder<*>>(history,waiver)
    fun activate(scope:MemberScope)=holders.forEach {it.activate(scope)}
    fun clear()=holders.forEach {it.clear()}
}

/** Real wiring: generated operations over the member grant's session. */
fun createMemberFeatures(session:org.spaceworks.app.network.ApiSession,text:org.spaceworks.app.platform.MobileText,onUnauthorized:suspend()->Unit)=
    MemberFeatures(BorrowingHistoryStateHolder(GeneratedBorrowingHistoryGateway(session),text,onUnauthorized),
        WaiverStateHolder(GeneratedWaiverGateway(session),text,onUnauthorized))
