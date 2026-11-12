package org.spaceworks.app.features

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import org.spaceworks.app.platform.rememberMobileText
import org.spaceworks.app.ui.BrandButton

/** Member overlays opened from Home, Activity or Profile. */
enum class FeatureRoute { HISTORY }

@Composable fun FeatureRouteScreen(features:MemberFeatures,route:FeatureRoute,onBack:()->Unit) {
    when(route) {
        FeatureRoute.HISTORY->BorrowingHistoryScreen(features.history,onBack)
    }
}

/** Activity entries. They sit outside the membership-gated overview, so account-only borrowers reach them. */
@Composable fun ActivityFeatureEntries(open:(FeatureRoute)->Unit) {
    val copyText=rememberMobileText()
    BrandButton(copyText(FeatureStrings.HISTORY_TITLE),{open(FeatureRoute.HISTORY)},modifier=Modifier.semantics {testTag="borrowing-history-entry"})
}
