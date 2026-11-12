package org.spaceworks.app.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import org.spaceworks.app.copy.CoreStrings
import org.spaceworks.app.copy.apiFailure
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.app.platform.MobileCopyException
import org.spaceworks.app.platform.MobileText
import org.spaceworks.app.platform.failure
import org.spaceworks.generated.model.HandleCheckResponse

/** Live availability of one typed username. `null` availability means not checked yet. */
enum class HandleAvailability { UNCHECKED, CHECKING, AVAILABLE, TAKEN, INVALID }
data class HandleState(val handle:String="",val availability:HandleAvailability=HandleAvailability.UNCHECKED,
    val suggestion:String?=null,val busy:Boolean=false,val error:String?=null)

/**
 * "Choose your username" (decision 308): every account needs a public username before state-changing
 * member routes accept it. The check is advisory; the claim is the server's answer.
 */
class HandleStateHolder(private val api:AuthGateway,private val onClaimed:suspend()->Unit={},
    private val text:MobileText=MobileText.ENGLISH) {
    private val mutable=MutableStateFlow(HandleState())
    val state=mutable.asStateFlow()
    private val claim=Mutex()
    @Volatile private var revision=0L

    /** Pre-fills the step from the payload's `handle_suggestion`, once per account. */
    fun start(suggestion:String?) {
        revision++
        mutable.value=HandleState(handle=suggestion.orEmpty(),suggestion=suggestion,
            availability=if(suggestion.isNullOrBlank())HandleAvailability.UNCHECKED else HandleAvailability.AVAILABLE)
    }
    fun clear(){revision++;mutable.value=HandleState()}
    fun edit(value:String) {
        revision++
        mutable.update {it.copy(handle=value.trim().lowercase().take(30),availability=HandleAvailability.UNCHECKED,error=null)}
    }
    /** Called by the UI after typing pauses; stale answers for an older value are dropped. */
    suspend fun check() {
        val handle=state.value.handle
        val expected=++revision
        if(!valid(handle)){mutable.update {it.copy(availability=HandleAvailability.INVALID)};return}
        mutable.update {it.copy(availability=HandleAvailability.CHECKING)}
        try {
            val result=api.checkHandle(handle)
            if(expected!=revision)return
            mutable.update {it.copy(availability=when {
                result.available==true -> HandleAvailability.AVAILABLE
                result.reason==HandleCheckResponse.Reason.invalid -> HandleAvailability.INVALID
                else -> HandleAvailability.TAKEN
            },suggestion=result.suggestion?.takeIf {s->s.isNotBlank()} ?: it.suggestion)}
        } catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){if(expected==revision)mutable.update {it.copy(availability=HandleAvailability.UNCHECKED)}}
    }
    suspend fun save():Boolean {
        if(!claim.tryLock())return false
        try {
            val handle=state.value.handle
            if(!valid(handle)){mutable.update {it.copy(availability=HandleAvailability.INVALID,error=text(CoreStrings.CORE_USE_3_30_LETTERS_NUMBERS_DOTS_UNDERSCORES_OR_HYPHENS))};return false}
            mutable.update {it.copy(busy=true,error=null)}
            api.claimHandle(handle)
            onClaimed()
            mutable.update {it.copy(busy=false)}
            return true
        } catch(cancelled:CancellationException){throw cancelled}
        catch(f:Exception) {
            mutable.update {it.copy(busy=false,error=when(f){is ApiFailure->text.apiFailure(f);is MobileCopyException->text.failure(f);else->text(CoreStrings.CORE_COULDN_T_CONNECT_TRY_AGAIN)})}
            return false
        } finally {claim.unlock()}
    }
    companion object {
        /** Mirrors the server grammar: 3–30 of a-z, 0-9, `.`, `_`, `-`, starting and ending alphanumeric. */
        private val pattern=Regex("^[a-z0-9](?:[a-z0-9._-]{1,28}[a-z0-9])$")
        fun valid(value:String)=pattern.matches(value)
    }
}
