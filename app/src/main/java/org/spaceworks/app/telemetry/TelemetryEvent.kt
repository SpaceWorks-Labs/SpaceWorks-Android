package org.spaceworks.app.telemetry

/**
 * The complete event taxonomy (docs/TELEMETRY.md), identical on Android and iOS: snake_case, at most
 * 40 characters, string/number/boolean properties only. Never add personal data, free text, names,
 * QR payloads, tokens or server-issued ids as properties.
 */
enum class TelemetryEventName(val wire:String) {
    APP_OPEN("app_open"), SCREEN_VIEW("screen_view"), ONBOARDING_COMPLETED("onboarding_completed"),
    SERVER_CONNECTED("server_connected"), SIGN_IN_STARTED("sign_in_started"), SIGN_IN_SUCCEEDED("sign_in_succeeded"),
    SIGN_IN_FAILED("sign_in_failed"), MFA_CHALLENGE_SHOWN("mfa_challenge_shown"), SIGN_UP_STARTED("sign_up_started"),
    SIGN_UP_COMPLETED("sign_up_completed"), PASSWORD_RESET_REQUESTED("password_reset_requested"), SIGN_OUT("sign_out"),
    MAKERSPACE_SWITCHED("makerspace_switched"), INVENTORY_SEARCHED("inventory_searched"),
    HARDWARE_REQUEST_SUBMITTED("hardware_request_submitted"), LOAN_RENEWED("loan_renewed"), BOOKING_CREATED("booking_created"),
    BOOKING_CANCELLED("booking_cancelled"), EVENT_REGISTERED("event_registered"),
    EVENT_REGISTRATION_CANCELLED("event_registration_cancelled"), TICKET_OPENED("ticket_opened"),
    PAYMENT_STARTED("payment_started"), PAYMENT_FINISHED("payment_finished"), RECEIPT_DOWNLOADED("receipt_downloaded"),
    PUSH_PERMISSION_RESULT("push_permission_result"), PUSH_OPENED("push_opened"), DEEP_LINK_OPENED("deep_link_opened"),
    STAFF_REQUEST_DECIDED("staff_request_decided"), STAFF_ITEM_ISSUED("staff_item_issued"), STAFF_ITEM_RETURNED("staff_item_returned"),
    STAFF_DIRECT_HANDOUT("staff_direct_handout"), QR_SCANNED("qr_scanned"), EVENT_CHECK_IN("event_check_in"),
    ACCOUNT_DELETION_STARTED("account_deletion_started"), ACCOUNT_DELETION_COMPLETED("account_deletion_completed"),
    CONSENT_UPDATED("consent_updated")
}

enum class SignInMethod(val wire:String) { PASSWORD("password"), GOOGLE("google"), APPLE("apple"), OIDC("oidc"), PHONE("phone"), PASSKEY("passkey"),
    /** Staff step-up: a second, makerspace-bound staff grant (authenticator required). */
    STAFF("staff") }

/** One typed event. Construct only through the factory functions below so names and properties stay closed. */
class TelemetryEvent private constructor(val name:TelemetryEventName,val properties:Map<String,Any> = emptyMap()) {
    override fun equals(other:Any?)=other is TelemetryEvent && other.name==name && other.properties==properties
    override fun hashCode()=31*name.hashCode()+properties.hashCode()
    override fun toString()="${name.wire}$properties"

    companion object {
        private fun of(name:TelemetryEventName,vararg properties:Pair<String,Any>)=TelemetryEvent(name,mapOf(*properties))
        val appOpen get()=of(TelemetryEventName.APP_OPEN)
        fun screenView(screen:TelemetryScreen)=of(TelemetryEventName.SCREEN_VIEW,"screen" to screen.route)
        val onboardingCompleted get()=of(TelemetryEventName.ONBOARDING_COMPLETED)
        fun serverConnected(locked:Boolean)=of(TelemetryEventName.SERVER_CONNECTED,"locked" to locked)
        fun signInStarted(method:SignInMethod)=of(TelemetryEventName.SIGN_IN_STARTED,"method" to method.wire)
        fun signInSucceeded(method:SignInMethod)=of(TelemetryEventName.SIGN_IN_SUCCEEDED,"method" to method.wire)
        /** [code] is the server error `code`, else the HTTP status class (`4xx`), else `network`. */
        fun signInFailed(method:SignInMethod,code:String)=of(TelemetryEventName.SIGN_IN_FAILED,"method" to method.wire,"code" to code.take(40))
        /** [kind] is `enrollment`, `verification` or `email_code` (unusual-sign-in code). */
        fun mfaChallengeShown(kind:String)=of(TelemetryEventName.MFA_CHALLENGE_SHOWN,"kind" to kind)
        val signUpStarted get()=of(TelemetryEventName.SIGN_UP_STARTED)
        val signUpCompleted get()=of(TelemetryEventName.SIGN_UP_COMPLETED)
        val passwordResetRequested get()=of(TelemetryEventName.PASSWORD_RESET_REQUESTED)
        fun signOut(everywhere:Boolean)=of(TelemetryEventName.SIGN_OUT,"scope" to if(everywhere)"everywhere" else "device")
        val makerspaceSwitched get()=of(TelemetryEventName.MAKERSPACE_SWITCHED)
        fun inventorySearched(hasQuery:Boolean)=of(TelemetryEventName.INVENTORY_SEARCHED,"has_query" to hasQuery)
        fun hardwareRequestSubmitted(itemCount:Int)=of(TelemetryEventName.HARDWARE_REQUEST_SUBMITTED,"item_count" to itemCount)
        val loanRenewed get()=of(TelemetryEventName.LOAN_RENEWED)
        val bookingCreated get()=of(TelemetryEventName.BOOKING_CREATED)
        val bookingCancelled get()=of(TelemetryEventName.BOOKING_CANCELLED)
        val eventRegistered get()=of(TelemetryEventName.EVENT_REGISTERED)
        val eventRegistrationCancelled get()=of(TelemetryEventName.EVENT_REGISTRATION_CANCELLED)
        val ticketOpened get()=of(TelemetryEventName.TICKET_OPENED)
        fun paymentStarted(vendor:String)=of(TelemetryEventName.PAYMENT_STARTED,"vendor" to vendor)
        /** [outcome] is `paid`, `pending`, `cancelled`, `failed` or `unavailable`. */
        fun paymentFinished(vendor:String,outcome:String)=of(TelemetryEventName.PAYMENT_FINISHED,"vendor" to vendor,"outcome" to outcome)
        val receiptDownloaded get()=of(TelemetryEventName.RECEIPT_DOWNLOADED)
        fun pushPermissionResult(granted:Boolean)=of(TelemetryEventName.PUSH_PERMISSION_RESULT,"granted" to granted)
        fun pushOpened(feature:String)=of(TelemetryEventName.PUSH_OPENED,"feature" to feature.take(40))
        fun deepLinkOpened(kind:String)=of(TelemetryEventName.DEEP_LINK_OPENED,"kind" to kind.take(40))
        /** [decision] is `accepted` or `rejected`. */
        fun staffRequestDecided(decision:String)=of(TelemetryEventName.STAFF_REQUEST_DECIDED,"decision" to decision)
        val staffItemIssued get()=of(TelemetryEventName.STAFF_ITEM_ISSUED)
        val staffItemReturned get()=of(TelemetryEventName.STAFF_ITEM_RETURNED)
        val staffDirectHandout get()=of(TelemetryEventName.STAFF_DIRECT_HANDOUT)
        /** [context] is `handover_container`, `handover_equipment` or `event_check_in`, never the payload. */
        fun qrScanned(context:String)=of(TelemetryEventName.QR_SCANNED,"context" to context)
        val eventCheckIn get()=of(TelemetryEventName.EVENT_CHECK_IN)
        val accountDeletionStarted get()=of(TelemetryEventName.ACCOUNT_DELETION_STARTED)
        val accountDeletionCompleted get()=of(TelemetryEventName.ACCOUNT_DELETION_COMPLETED)
        fun consentUpdated(consent:TelemetryConsent)=of(TelemetryEventName.CONSENT_UPDATED,
            "analytics" to consent.analytics,"replay" to consent.replay,"diagnostics" to consent.diagnostics)
    }
}

/**
 * Route templates only, never raw ids; identical to iOS `TelemetryScreen`. [sensitive] screens pause
 * session replay while visible (sign-in, codes, payments, cards, tickets, camera and QR).
 */
enum class TelemetryScreen(val route:String,val sensitive:Boolean=false) {
    INTRO("intro"), CONSENT_PROMPT("consent_prompt"), SERVER_CONNECT("server_connect"), SIGN_IN("sign_in",true), MFA("mfa",true),
    PHONE_SIGN_IN("phone_sign_in",true), SIGN_UP("sign_up",true), PASSWORD_RESET("password_reset",true),
    PASSWORD_CHANGE("password_change",true), RECOVERY_CODES("recovery_codes",true), EMAIL_VERIFICATION("email_verification",true),
    HOME("home"), INVENTORY("inventory"), INVENTORY_DETAIL("inventory_detail"), ACTIVITY("activity"), INBOX("inbox"),
    PROFILE("profile"), BOOKINGS("bookings"), SPACES("spaces"), SPACE_DETAIL("space_detail"), BOOKING_DETAIL("booking_detail"),
    EVENT_DETAIL("event_detail"), EVENT_TICKET("event_ticket",true), MEMBERSHIP_CARD("membership_card",true),
    PROFILE_EDIT("profile_edit"), COMMUNICATION_PREFERENCES("communication_preferences"), PAYMENTS("payments",true),
    PAYMENT_DETAIL("payment_detail",true), ACCOUNT_SECURITY("account_security",true), ACCOUNT_DELETION("account_deletion",true),
    NOTIFICATION_SETTINGS("notification_settings"), APP_GUIDE("app_guide"), PRIVACY_ANALYTICS("privacy_analytics"),
    STAFF_TOOLS("staff_tools"), STAFF_SCAN("staff_scan",true), STAFF_EVIDENCE("staff_evidence",true)
}
