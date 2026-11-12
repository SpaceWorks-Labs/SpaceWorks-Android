package org.spaceworks.app.telemetry

/** Removes personal and secret data from diagnostics before anything leaves the device. */
object TelemetryScrubber {
    /** Header names never sent (compared case-insensitively). */
    val SENSITIVE_HEADERS=setOf("authorization","cookie","set-cookie","x-makerspace-id","proxy-authorization")
    private val UUID=Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val NUMBER=Regex("\\d+")
    private val LONG_TOKEN=Regex("[A-Za-z0-9_-]{24,}")

    /** Drop query/fragment and userinfo; replace numeric, UUID and token-like path segments with `{id}`. */
    fun url(raw:String):String {
        val withoutQuery=raw.substringBefore('#').substringBefore('?')
        val scheme=withoutQuery.substringBefore("://","")
        val rest=if(scheme.isEmpty())withoutQuery else withoutQuery.substringAfter("://")
        val authority=if(scheme.isEmpty())"" else rest.substringBefore('/').substringAfter('@')
        val path=if(scheme.isEmpty())rest else rest.substring(rest.indexOf('/').let {if(it<0)rest.length else it})
        val cleaned=path.split('/').joinToString("/") {segment->
            if(segment.matches(NUMBER) || segment.matches(UUID) || segment.matches(LONG_TOKEN))"{id}" else segment
        }
        return if(scheme.isEmpty())cleaned else "$scheme://$authority$cleaned"
    }

    fun headers(headers:Map<String,String>?):Map<String,String>? =
        headers?.filterKeys {it.lowercase() !in SENSITIVE_HEADERS}
}
