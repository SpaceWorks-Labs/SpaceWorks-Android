package org.spaceworks.app.features

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.spaceworks.app.copy.CoreStrings
import org.spaceworks.app.copy.apiFailure
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.app.platform.MobileCopyException
import org.spaceworks.app.platform.MobileText
import org.spaceworks.app.platform.failure

/** Presentation text for a failure: local copy, the server's (or eligibility) copy, or the connection fallback. */
fun MobileText.featureFailure(error:Exception):String = when(error) {
    is MobileCopyException->failure(error)
    is ApiFailure->apiFailure(error)
    else->invoke(CoreStrings.CORE_COULDN_T_CONNECT_TRY_AGAIN)
}

/**
 * Per-session, per-makerspace state for one feature. A scope change or sign-out discards everything,
 * and a result that arrives for an older scope is dropped.
 */
abstract class FeatureHolder<S>(private val empty:(MemberScope?)->S,protected val text:MobileText,
    private val onUnauthorized:suspend()->Unit) {
    protected val mutable=MutableStateFlow(empty(null))
    val state:StateFlow<S> = mutable.asStateFlow()
    private var generation=0L
    var scope:MemberScope?=null
        private set
    fun activate(next:MemberScope){if(scope!=next){generation++;scope=next;mutable.value=empty(next)}}
    fun clear(){generation++;scope=null;mutable.value=empty(null)}

    /** Runs [block] for the current scope. Returns the value, or null when it failed or the scope moved on. */
    protected suspend fun <T> run(start:(S)->S,block:suspend(MemberScope)->T,done:(S,T)->S,failed:(S,String,ApiFailure?)->S):T? {
        val target=scope?:return null
        val owner=generation
        mutable.update(start)
        try {
            val value=block(target)
            if(owner!=generation)return null
            mutable.update {done(it,value)}
            return value
        }catch(cancelled:CancellationException){throw cancelled}
        catch(error:Exception){
            if(owner!=generation)return null
            val api=error as? ApiFailure
            mutable.update {failed(it,text.featureFailure(error),api)}
            if(api?.status==401)onUnauthorized()
            return null
        }
    }
}
