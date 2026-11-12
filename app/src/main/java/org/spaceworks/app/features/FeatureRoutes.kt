package org.spaceworks.app.features

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import org.spaceworks.app.platform.rememberMobileText
import org.spaceworks.app.ui.BrandButton

/** Member overlays opened from Home, Activity or Profile. */
enum class FeatureRoute { HISTORY, WAIVER }

/** [onMembershipChanged] refreshes the member overview after an action that changes eligibility or activity. */
@Composable fun FeatureRouteScreen(features:MemberFeatures,route:FeatureRoute,onMembershipChanged:()->Unit,onBack:()->Unit) {
    when(route) {
        FeatureRoute.HISTORY->BorrowingHistoryScreen(features.history,onBack)
        FeatureRoute.WAIVER->WaiverScreen(features.waiver,onBack,onMembershipChanged)
    }
}

/** Activity entries. They sit outside the membership-gated overview, so account-only borrowers reach them. */
@Composable fun ActivityFeatureEntries(open:(FeatureRoute)->Unit) {
    val copyText=rememberMobileText()
    BrandButton(copyText(FeatureStrings.HISTORY_TITLE),{open(FeatureRoute.HISTORY)},modifier=Modifier.semantics {testTag="borrowing-history-entry"})
}
