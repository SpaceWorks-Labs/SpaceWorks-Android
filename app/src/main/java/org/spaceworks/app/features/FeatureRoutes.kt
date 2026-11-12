package org.spaceworks.app.features

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import org.spaceworks.app.member.MemberState
import org.spaceworks.app.member.MemberStateHolder
import org.spaceworks.app.platform.rememberMobileText
import org.spaceworks.app.ui.BrandButton

/** Member overlays opened from Home, Activity or Profile. */
enum class FeatureRoute { HISTORY, WAIVER, CONTEXTUAL, FEEDS, STAFF_REPORTS, LINK_PROVIDER }

/** [onMembershipChanged] refreshes the member overview after an action that changes eligibility or activity. */
@Composable fun FeatureRouteScreen(features:MemberFeatures,route:FeatureRoute,member:MemberStateHolder?,googleClientId:String?,onMembershipChanged:()->Unit,onBack:()->Unit) {
    val copyText=rememberMobileText()
    val memberState by (member?.state?:remember {MutableStateFlow(MemberState())}).collectAsStateWithLifecycle()
    when(route) {
        FeatureRoute.HISTORY->BorrowingHistoryScreen(features.history,onBack)
        FeatureRoute.WAIVER->WaiverScreen(features.waiver,onBack,onMembershipChanged)
        FeatureRoute.CONTEXTUAL->ContextualRequestScreen(features.contextual,requestContexts(memberState.overview.value,copyText),
            memberState.inventory.value?.items.orEmpty(),onBack,onMembershipChanged)
        FeatureRoute.FEEDS->CalendarFeedsScreen(features.feeds,onBack)
        FeatureRoute.STAFF_REPORTS->StaffReportsScreen(features.reports,onBack)
        FeatureRoute.LINK_PROVIDER->if(googleClientId!=null)ProviderLinkScreen(features.link,googleClientId,onBack) else onBack()
    }
}

/** Activity entries. They sit outside the membership-gated overview, so account-only borrowers reach them. */
@Composable fun ActivityFeatureEntries(open:(FeatureRoute)->Unit) {
    val copyText=rememberMobileText()
    BrandButton(copyText(FeatureStrings.HISTORY_TITLE),{open(FeatureRoute.HISTORY)},modifier=Modifier.semantics {testTag="borrowing-history-entry"})
    BrandButton(copyText(FeatureStrings.KITS_TITLE),{open(FeatureRoute.CONTEXTUAL)},modifier=Modifier.semantics {testTag="contextual-request-entry"})
    BrandButton(copyText(FeatureStrings.FEEDS_TITLE),{open(FeatureRoute.FEEDS)},modifier=Modifier.semantics {testTag="calendar-feeds-entry"})
}
