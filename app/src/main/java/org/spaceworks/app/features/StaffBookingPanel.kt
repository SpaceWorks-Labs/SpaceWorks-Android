package org.spaceworks.app.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.spaceworks.app.platform.MobileText
import org.spaceworks.app.platform.rememberMobileText
import org.spaceworks.app.ui.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable private fun ReadinessList(view:BookingReadinessView,copyText:MobileText) {
    view.checks.forEach {check->
        val state=copyText(when(check.state){"blocked"->FeatureStrings.BOOKING_STATE_BLOCKED;"warning"->FeatureStrings.BOOKING_STATE_WARNING;else->FeatureStrings.BOOKING_STATE_OK})
        Text(copyText(FeatureStrings.BOOKING_CHECK_LINE,state,check.detail),color=if(check.state=="blocked")MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if(check.certifications.isNotEmpty())Text(copyText(FeatureStrings.BOOKING_CHECK_CERTIFICATIONS,check.certifications.joinToString(", ")),color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** New staff booking for a member in [spaceId]: price and readiness first, then an explicit confirm. */
@Composable fun StaffBookingPanel(holder:StaffBookingStateHolder,spaceId:Int) {
    val copyText=rememberMobileText()
    val s by holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var open by remember(spaceId) {mutableStateOf(false)}
    if(!open){BrandButton(copyText(FeatureStrings.BOOKING_NEW),{open=true;scope.launch {holder.open(spaceId)}},modifier=Modifier.testTag("staff-booking-new"));return}
    val start=holder.window().first
    MemberSection(copyText(FeatureStrings.BOOKING_NEW)) {
        BrandError(s.error)
        if(s.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        s.created?.let {Text(copyText(FeatureStrings.BOOKING_CREATED,memberStatus(it.status,copyText)),color=MaterialTheme.colorScheme.onPrimaryContainer,
            modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
        Text(copyText(FeatureStrings.BOOKING_MEMBER),style=MaterialTheme.typography.titleMedium)
        if(!s.busy && s.members.isEmpty())Text(copyText(FeatureStrings.BOOKING_NO_MEMBERS),color=MaterialTheme.colorScheme.onSurfaceVariant)
        s.members.forEach {member->
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).selectable(s.draft.memberId==member.id,!s.busy,Role.RadioButton) {holder.edit {it.copy(memberId=member.id)}},
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {RadioButton(s.draft.memberId==member.id,null);Text(member.name)}
        }
        Text(copyText(FeatureStrings.BOOKING_DAY),style=MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            (0..2).forEach {offset->
                val label=start.toLocalDate().minusDays((s.draft.dayOffset-offset).toLong()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                FilterChip(s.draft.dayOffset==offset,{holder.edit {it.copy(dayOffset=offset)}},label={Text(label)},modifier=Modifier.heightIn(min=48.dp),enabled=!s.busy)
            }
        }
        Text(copyText(FeatureStrings.BOOKING_START,start.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))),modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            BrandTextButton(copyText(FeatureStrings.BOOKING_EARLIER),{holder.edit {it.copy(startMinutes=it.startMinutes-30)}},!s.busy)
            BrandTextButton(copyText(FeatureStrings.BOOKING_LATER),{holder.edit {it.copy(startMinutes=it.startMinutes+30)}},!s.busy)
        }
        Text(copyText(FeatureStrings.BOOKING_LENGTH,s.draft.durationMinutes),modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            BrandTextButton(copyText(FeatureStrings.BOOKING_SHORTER),{holder.edit {it.copy(durationMinutes=it.durationMinutes-30)}},!s.busy)
            BrandTextButton(copyText(FeatureStrings.BOOKING_LONGER),{holder.edit {it.copy(durationMinutes=it.durationMinutes+30)}},!s.busy)
        }
        BrandField(s.draft.note,{value->holder.edit {it.copy(note=value)}},copyText(FeatureStrings.BOOKING_NOTE),enabled=!s.busy)
        BrandButton(copyText(FeatureStrings.BOOKING_CHECK),{scope.launch {holder.preview()}},!s.busy && s.draft.memberId!=null,Modifier.testTag("staff-booking-check"))
        s.quote?.let {quote->MemberPanel {
            if(s.priceChanged)Text(copyText(FeatureStrings.BOOKING_PRICE_CHANGED),color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Assertive})
            Text(if(quote.charged && quote.total.isNotBlank())copyText(FeatureStrings.BOOKING_PRICE,quote.currency.uppercase(),quote.total) else copyText(FeatureStrings.BOOKING_FREE),
                style=MaterialTheme.typography.titleMedium)
            quote.notes.forEach {Text(it,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            s.readiness?.let {readiness->
                Text(copyText(if(readiness.ready)FeatureStrings.BOOKING_READY else FeatureStrings.BOOKING_BLOCKED))
                ReadinessList(readiness,copyText)
            }
            BrandButton(copyText(FeatureStrings.BOOKING_CONFIRM),{scope.launch {holder.confirm()}},s.canConfirm,Modifier.testTag("staff-booking-confirm"))
        }}
    }
}

/** Readiness of an existing booking, on its staff card. */
@Composable fun StaffBookingReadiness(holder:StaffBookingStateHolder,bookingId:Int) {
    val copyText=rememberMobileText()
    val s by holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    val view=s.existing[bookingId]
    if(view==null)BrandTextButton(copyText(FeatureStrings.BOOKING_CHECK_EXISTING),{scope.launch {holder.checkExisting(bookingId)}},!s.busy,Modifier.testTag("staff-booking-readiness-$bookingId"))
    else {Text(copyText(if(view.ready)FeatureStrings.BOOKING_READY else FeatureStrings.BOOKING_BLOCKED));ReadinessList(view,copyText)}
}
