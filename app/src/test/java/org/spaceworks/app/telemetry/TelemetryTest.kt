package org.spaceworks.app.telemetry

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.Request
import io.sentry.protocol.User
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.ConnectionStateHolder
import org.spaceworks.app.OnboardingStateHolder

class TelemetryTest {
    private val allConfigured=TelemetryConfig(sentryDsn="https://key@o1.ingest.sentry.io/1",posthogApiKey="phc_test",
        clarityProjectId="abc123",sendingAllowed=true,appVersion="1.0",buildType="release")
    private fun controller(config:TelemetryConfig=allConfigured,storage:MemoryConsentStorage=MemoryConsentStorage(),
        vararg providers:FakeProvider)=TelemetryController(config,storage,providers.toList())

    @After fun tearDown()=RecordingTelemetry.uninstall()

    @Test fun noProviderStartsBeforeConsentAndGrantingStartsOnlyThatPurpose() {
        val analytics=FakeProvider(TelemetryPurpose.ANALYTICS);val replay=FakeProvider(TelemetryPurpose.REPLAY);val crash=FakeProvider(TelemetryPurpose.DIAGNOSTICS)
        val telemetry=controller(providers=arrayOf(analytics,replay,crash))
        telemetry.track(TelemetryEvent.appOpen);telemetry.screen(TelemetryScreen.HOME)
        assertEquals(emptyList<String>(),analytics.calls+replay.calls+crash.calls)
        telemetry.setConsent(TelemetryConsent(diagnostics=true))
        assertEquals(listOf("start","track:consent_updated"),crash.calls)
        assertTrue(analytics.calls.isEmpty() && replay.calls.isEmpty())
    }

    @Test fun withdrawalCallsStopAndNothingIsSentAfterwards() {
        val analytics=FakeProvider(TelemetryPurpose.ANALYTICS)
        val telemetry=controller(providers=arrayOf(analytics))
        telemetry.setConsent(TelemetryConsent(analytics=true))
        telemetry.setConsent(TelemetryConsent())
        telemetry.track(TelemetryEvent.loanRenewed)
        assertEquals(listOf("start","track:consent_updated","stop"),analytics.calls)
    }

    @Test fun debugBuildsWithoutOverrideNeverStartAProvider() {
        val analytics=FakeProvider(TelemetryPurpose.ANALYTICS)
        val telemetry=controller(allConfigured.copy(sendingAllowed=false),providers=arrayOf(analytics))
        telemetry.setConsent(TelemetryConsent(true,true,true))
        telemetry.track(TelemetryEvent.appOpen)
        assertTrue(analytics.calls.isEmpty())
        assertEquals(TelemetryConsent(true,true,true),telemetry.consent.value)
    }

    @Test fun absentConfigurationIsANoOpAndHidesTheConsentUi() {
        val telemetry=controller(TelemetryConfig(sendingAllowed=true))
        assertTrue(telemetry.configuredPurposes.isEmpty())
        assertTrue(telemetry.pendingPrompt.value.isEmpty())
        telemetry.setConsent(TelemetryConsent(true,true,true))
        assertEquals(TelemetryConsent(),telemetry.consent.value)
    }

    @Test fun consentIsStoredWithSchemaAndOnlyNewlyConfiguredPurposesAreAskedAgain() {
        val storage=MemoryConsentStorage()
        val first=controller(allConfigured.copy(clarityProjectId=null),storage)
        assertEquals(setOf(TelemetryPurpose.ANALYTICS,TelemetryPurpose.DIAGNOSTICS),first.pendingPrompt.value)
        first.setConsent(TelemetryConsent(analytics=true))
        assertEquals(StoredConsent.SCHEMA,storage.value?.schema)
        val second=controller(allConfigured,storage)
        assertEquals(setOf(TelemetryPurpose.REPLAY),second.pendingPrompt.value)
        assertTrue(second.consent.value.analytics)
        val outdated=controller(allConfigured,MemoryConsentStorage(StoredConsent(TelemetryConsent(true,true,true),TelemetryPurpose.entries.toSet(),schema=0)))
        assertEquals(TelemetryConsent(),outdated.consent.value)
    }

    @Test fun identityIsAStableHashAndNeverTheRawId() {
        val user=TelemetryUser.of("https://makers.example.org","42",staff=true)
        assertEquals(user,TelemetryUser.of("https://makers.example.org","42",staff=true))
        assertTrue(user.id.matches(Regex("[0-9a-f]{64}")))
        assertFalse(user.id.contains("42") && user.id=="42")
        assertEquals(TelemetryUser.sha256("https://makers.example.org|42"),user.id)
        // Origin normalized like iOS: lowercase, no trailing slash.
        assertEquals(user,TelemetryUser.of("HTTPS://Makers.Example.org/","42",staff=true))
        assertEquals(12,user.deployment.length)
        assertEquals("staff",user.roleKind)
        assertNotEquals(user.id,TelemetryUser.of("https://other.example.org","42",false).id)
        // Known vector: SHA-256("abc").
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",TelemetryUser.sha256("abc"))
    }

    @Test fun identityReachesProvidersStartedLaterAndResetClearsIt() {
        val analytics=FakeProvider(TelemetryPurpose.ANALYTICS)
        val telemetry=controller(providers=arrayOf(analytics))
        val user=TelemetryUser.of("https://m.example","7",false)
        telemetry.identify(user)
        telemetry.setConsent(TelemetryConsent(analytics=true))
        telemetry.reset()
        assertEquals(listOf("start","identify:${user.id}","track:consent_updated","reset"),analytics.calls)
    }

    @Test fun sensitiveScreensPauseReplayUntilTheLastOneCloses() {
        val replay=FakeProvider(TelemetryPurpose.REPLAY)
        val telemetry=controller(providers=arrayOf(replay))
        telemetry.setConsent(TelemetryConsent(replay=true));replay.calls.clear()
        telemetry.screen(TelemetryScreen.PAYMENTS);telemetry.screen(TelemetryScreen.STAFF_SCAN)
        telemetry.screenLeft(TelemetryScreen.STAFF_SCAN);assertTrue(telemetry.sensitiveVisible)
        telemetry.screenLeft(TelemetryScreen.PAYMENTS)
        assertEquals(listOf("sensitive:true","screen:payments","screen:staff_scan","sensitive:false"),replay.calls)
        assertTrue(TelemetryScreen.SIGN_IN.sensitive && TelemetryScreen.MEMBERSHIP_CARD.sensitive && TelemetryScreen.ACCOUNT_DELETION.sensitive)
    }

    @Test fun demoModeSendsNothing() {
        val recording=RecordingTelemetry.install()
        AppTelemetry.demoActive=true
        OnboardingStateHolder(false){}.finish()
        AppTelemetry.track(TelemetryEvent.bookingCreated);AppTelemetry.screen(TelemetryScreen.HOME)
        AppTelemetry.identify(TelemetryUser.of("https://demo","1",false))
        assertTrue(recording.events.isEmpty() && recording.screens.isEmpty() && recording.users.isEmpty())
    }

    @Test fun eventNamesAreUniqueValidAndPropertiesArePrimitive() {
        val names=TelemetryEventName.entries.map {it.wire}
        assertEquals(names.size,names.toSet().size)
        names.forEach {assertTrue(it,it.matches(Regex("[a-z][a-z0-9_]{0,39}")))}
        assertEquals(36,names.size)
        // Screen names are identical to iOS TelemetryScreen.
        assertEquals(listOf("intro","consent_prompt","server_connect","sign_in","mfa","phone_sign_in","sign_up","password_reset",
            "password_change","recovery_codes","email_verification","home","inventory","inventory_detail","activity","inbox","profile",
            "bookings","spaces","space_detail","booking_detail","event_detail","event_ticket","membership_card","profile_edit",
            "communication_preferences","payments","payment_detail","account_security","account_deletion","notification_settings",
            "app_guide","privacy_analytics","staff_tools","staff_scan","staff_evidence"),TelemetryScreen.entries.map {it.route})
        TelemetryScreen.entries.forEach {assertTrue(it.route,it.route.matches(Regex("[a-z][a-z_]{0,39}")))}
        val samples=listOf(TelemetryEvent.signInFailed(SignInMethod.PASSWORD,"invalid_credentials"),TelemetryEvent.hardwareRequestSubmitted(3),
            TelemetryEvent.paymentFinished("stripe","paid"),TelemetryEvent.consentUpdated(TelemetryConsent(true)),TelemetryEvent.serverConnected(false))
        samples.flatMap {it.properties.values}.forEach {assertTrue(it is String || it is Number || it is Boolean)}
        assertEquals(mapOf("scope" to "everywhere"),TelemetryEvent.signOut(everywhere=true).properties)
    }

    @Test fun superPropertiesAreAddedToEveryEvent() {
        val captured=mutableListOf<Map<String,Any>>()
        val provider=object:TelemetryProvider {
            override val purpose=TelemetryPurpose.ANALYTICS
            override fun start()=Unit;override fun stop()=Unit
            override fun track(event:TelemetryEvent,properties:Map<String,Any>){captured+=properties}
        }
        val telemetry=TelemetryController(allConfigured,MemoryConsentStorage(),listOf(provider))
        telemetry.setConsent(TelemetryConsent(analytics=true))
        telemetry.identify(TelemetryUser.of("https://m.example","9",true))
        telemetry.track(TelemetryEvent.inventorySearched(true))
        assertEquals(mapOf("platform" to "android","app_version" to "1.0","build_type" to "release",
            "deployment" to TelemetryUser.sha256("https://m.example").take(12),"role_kind" to "staff","has_query" to true),captured.last())
    }

    @Test fun scrubberRemovesHeadersQueriesAndIds() {
        assertEquals("https://makers.example/api/v1/makerspaces/{id}/requests/{id}/",
            TelemetryScrubber.url("https://user:pw@makers.example/api/v1/makerspaces/12/requests/3f2504e0-4f89-11d3-9a0c-0305e82c3301/?token=secret#frag"))
        assertEquals("/api/v1/auth/{id}",TelemetryScrubber.url("/api/v1/auth/abcdefghijklmnopqrstuvwxyz123456?x=1"))
        assertEquals(mapOf("Accept" to "json"),TelemetryScrubber.headers(mapOf("Authorization" to "Bearer t","Cookie" to "c","X-Makerspace-Id" to "4","Accept" to "json")))
    }

    @Test fun sentryEventsAndBreadcrumbsAreScrubbed() {
        val event=SentryEvent().apply {
            request=Request().apply {url="https://makers.example/api/v1/payments/55/?q=1";queryString="q=1";data="{\"password\":\"x\"}";cookies="session=1"
                headers=mapOf("Authorization" to "Bearer t","X-Makerspace-Id" to "4","Accept" to "json")}
            user=User().apply {id="hash";email="person@example.org";ipAddress="10.0.0.1";username="person"}
        }
        val scrubbed=SentryScrubbing.scrub(event)
        assertEquals("https://makers.example/api/v1/payments/{id}/",scrubbed.request?.url)
        assertNull(scrubbed.request?.queryString);assertNull(scrubbed.request?.data);assertNull(scrubbed.request?.cookies)
        assertEquals(mapOf("Accept" to "json"),scrubbed.request?.headers)
        assertEquals("hash",scrubbed.user?.id);assertNull(scrubbed.user?.email);assertNull(scrubbed.user?.ipAddress);assertNull(scrubbed.user?.username)
        val crumb=Breadcrumb().apply {setData("url","https://m.example/a/9?x=1");setData("http.query","x=1");setData("authorization","t")}
        SentryScrubbing.scrub(crumb)
        assertEquals("https://m.example/a/{id}",crumb.getData("url"));assertNull(crumb.getData("http.query"));assertNull(crumb.getData("authorization"))
        val options=SentryOptions()
        SentryScrubbing.configure(options,allConfigured.copy(release="org.spaceworks.app@1.0+1"),"https://key@o1.ingest.sentry.io/1")
        assertFalse(options.isSendDefaultPii);assertEquals(0.1,options.tracesSampleRate!!,0.0)
        assertEquals("release",options.environment);assertEquals("org.spaceworks.app@1.0+1",options.release)
        assertNotNull(options.beforeSend);assertNotNull(options.beforeBreadcrumb)
    }

    @Test fun onboardingAndServerConnectionReportAtServerConfirmedSuccess() = runBlocking {
        val recording=RecordingTelemetry.install()
        OnboardingStateHolder(false){}.finish()
        val failing=ConnectionStateHolder("https://makers.example",true,false,health={throw IllegalStateException()},save={})
        failing.connect()
        assertEquals(listOf("onboarding_completed"),recording.names)
        ConnectionStateHolder("https://makers.example",true,false,health={},save={}).connect()
        assertEquals(listOf("onboarding_completed","server_connected"),recording.names)
        assertEquals(true,recording.events.last().properties["locked"])
    }
}
