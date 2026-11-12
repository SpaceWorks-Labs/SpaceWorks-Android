package org.spaceworks.app.features

import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.spaceworks.app.auth.requireBody
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiSession
import org.spaceworks.app.platform.MobileText
import org.spaceworks.generated.api.PersonalNotificationsApi
import org.spaceworks.generated.model.InboxSnooze
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The app picks the time (the server allows up to 30 days ahead); there is no free-form picker. */
enum class SnoozeChoice { ONE_HOUR, TOMORROW_MORNING, NEXT_WEEK;
    fun until(now:ZonedDateTime):OffsetDateTime = when(this) {
        ONE_HOUR->now.plusHours(1)
        TOMORROW_MORNING->now.toLocalDate().plusDays(1).atTime(9,0).atZone(now.zone)
        NEXT_WEEK->now.plusDays(7)
    }.toOffsetDateTime()
}

interface InboxSnoozeGateway {
    /** Returns the server's `snoozed_until`. */
    suspend fun snooze(scope:MemberScope,id:Int,until:OffsetDateTime):String?
}

class GeneratedInboxSnoozeGateway(private val session:ApiSession):InboxSnoozeGateway {
    override suspend fun snooze(scope:MemberScope,id:Int,until:OffsetDateTime)=session.scopedApi(PersonalNotificationsApi::class.java,scope.id)
        .apiV1NotificationsInboxSnoozeCreate(id=id,makerspaceId=scope.id,inboxSnooze=InboxSnooze(until)).requireBody().snoozedUntil?.toString()
}

data class InboxSnoozeState(val scope:MemberScope?=null,val busy:Int?=null,val snoozed:Map<Int,String> = emptyMap(),val error:String?=null)

class InboxSnoozeStateHolder(private val api:InboxSnoozeGateway,text:MobileText=MobileText.ENGLISH,onUnauthorized:suspend()->Unit={},
    private val now:()->ZonedDateTime={ZonedDateTime.now(ZoneId.systemDefault())})
    :FeatureHolder<InboxSnoozeState>({InboxSnoozeState(scope=it)},text,onUnauthorized) {
    /** One explicit tap, one request; a failure is shown and never retried automatically. */
    suspend fun snooze(id:Int,choice:SnoozeChoice):Boolean {
        if(state.value.busy!=null)return false
        return run({it.copy(busy=id,error=null)},{api.snooze(it,id,choice.until(now()))},
            {s,until->s.copy(busy=null,snoozed=s.snoozed+(id to until.orEmpty()))},{s,message,_->s.copy(busy=null,error=message)})!=null
    }
    fun dismissError()=mutable.update {it.copy(error=null)}
}

/** Inbox row action: hide this message until a chosen time (the server brings it back then). */
@androidx.compose.runtime.Composable fun InboxSnoozeActions(holder:InboxSnoozeStateHolder,message:org.spaceworks.app.member.InboxItem,onSnoozed:()->Unit) {
    val id=message.id.toIntOrNull()?:return
    val copyText=org.spaceworks.app.platform.rememberMobileText()
    val s by holder.state.collectAsStateWithLifecycle()
    val scope=androidx.compose.runtime.rememberCoroutineScope()
    org.spaceworks.app.ui.MemberSection(copyText(FeatureStrings.SNOOZE_TITLE)) {
        listOf(SnoozeChoice.ONE_HOUR to FeatureStrings.SNOOZE_ONE_HOUR,SnoozeChoice.TOMORROW_MORNING to FeatureStrings.SNOOZE_TOMORROW,SnoozeChoice.NEXT_WEEK to FeatureStrings.SNOOZE_NEXT_WEEK).forEach {(choice,label)->
            org.spaceworks.app.ui.BrandTextButton(copyText(label),{scope.launch {if(holder.snooze(id,choice))onSnoozed()}},s.busy==null,
                androidx.compose.ui.Modifier.semantics {testTag="snooze-${choice.name.lowercase()}"})
        }
        org.spaceworks.app.ui.BrandError(s.error)
    }
}
