package org.spaceworks.app.testsupport

import kotlinx.serialization.json.*

/** Backend 0.12.0 wire payloads for tests. Fixtures in `contract012/` follow the pinned schema. */
object Payloads {
    fun fixture(name:String)=requireNotNull(javaClass.classLoader?.getResourceAsStream("contract012/$name.json")) {name}
        .bufferedReader().use {it.readText()}
    /** A complete `AuthUserPayload`; [overrides] is a JSON object body (without braces) merged on top. */
    fun user(overrides:String=""):String {
        val base=Json.parseToJsonElement(BASE_USER).jsonObject
        val extra=if(overrides.isBlank())JsonObject(emptyMap()) else Json.parseToJsonElement("{$overrides}").jsonObject
        return JsonObject(base+extra).toString()
    }
    private val lenient=Json {ignoreUnknownKeys=true}
    /** The generated client's wire format (UUID / date-time contextual adapters). */
    val wire=Json {ignoreUnknownKeys=true;serializersModule=kotlinx.serialization.modules.SerializersModule {
        contextual(java.util.UUID::class,org.openapitools.client.infrastructure.UUIDAdapter)
        contextual(java.time.OffsetDateTime::class,org.openapitools.client.infrastructure.OffsetDateTimeAdapter)
    }}
    /** Positional builder matching the pre-0.12.0 constructor order; new required fields use defaults. */
    fun testUser(sessionAuthority:String,id:Int,username:String,email:String,displayName:String,phone:String,emailVerified:Boolean,
        role:String,isSuperuser:Boolean,mustChangePassword:Boolean,makerspaces:List<org.spaceworks.generated.model.AuthMembership>)=
        lenient.decodeFromString<org.spaceworks.generated.model.AuthUserPayload>(user()).copy(
            sessionAuthority=org.spaceworks.generated.model.SessionAuthorityEnum.entries.first {it.value==sessionAuthority},
            id=id,username=username,email=email,displayName=displayName,phone=phone,emailVerified=emailVerified,role=role,
            isSuperuser=isSuperuser,mustChangePassword=mustChangePassword,makerspaces=makerspaces)
    private const val BASE_USER="""{"session_authority":"member","id":1,"username":"member","email":"member@example.invalid","display_name":"Member","phone":"","email_verified":true,"role":"member","is_superuser":false,"must_change_password":false,"kind":"person","is_platform":false,"has_staff_role":false,"has_elevated_authority":false,"has_organizations":false,"staff_roles":[],"superadmin_actions":[],"mfa_grace_ends_at":null,"mfa_enrolment_due":false,"merge_suggested":false,"handle":"member","handle_required":false,"handle_suggestion":null,"preferred_locale":null,"effective_locale":"en","supported_locales":[],"makerspaces":[{"id":7,"slug":"fab-lab","role":"member","role_id":1,"role_ids":[1],"role_slugs":["member"],"role_names":["Member"],"role_name":"Member","role_slug":"member","source":"membership","tracks":["community"],"actions":[],"can_configure_machine_types":false,"is_machine_only":false,"can_refer":false,"can_verify":false,"verified_at":null,"referrals_enabled":false}]}"""
}
