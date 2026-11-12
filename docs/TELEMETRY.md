# Telemetry

Owner extension (2026-11-12): Firebase Analytics, PostHog, Microsoft Clarity and Sentry are authorized,
replacing the earlier "no analytics SDKs" rule for these four providers only. The backend is unchanged.
The policy matches the backend's web telemetry rules: **a provider runs only when it is configured and
the person agreed to its purpose; withdrawing stops it at once.** iOS implements the same behaviour,
event names and copy.

## Providers

| Provider | SDK (see [DEPENDENCIES.md](DEPENDENCIES.md)) | Role | Consent purpose |
|---|---|---|---|
| Firebase Analytics | `firebase-analytics` (Firebase BoM) | Usage events and screen views | Usage analytics (`analytics`) |
| PostHog | `posthog-android` | Product analytics, same events; no autocapture, no session replay, no feature flags; app lifecycle events on | Usage analytics (`analytics`) |
| Microsoft Clarity | `clarity-compose` | Session replay and heatmaps with strict masking | Session replay (`replay`) |
| Sentry | `sentry-android` | Crashes, ANRs, performance traces | Crash and performance reports (`diagnostics`) |

- No Firebase Crashlytics: Sentry is the only crash handler. FCM push is unchanged.
- No advertising identifier: the manifest removes `com.google.android.gms.permission.AD_ID`, sets
  `google_analytics_adid_collection_enabled=false`, `google_analytics_default_allow_ad_personalization_signals=false`,
  `google_analytics_automatic_screen_reporting_enabled=false` and `firebase_analytics_collection_enabled=false`.
  Collection is enabled at runtime only after consent, with Consent Mode `ad_storage`, `ad_user_data` and
  `ad_personalization` denied.
- Sentry auto-init is off (`io.sentry.auto-init=false`); Sentry starts in code only after consent and
  can start or stop without restarting the app.

## Configuration (public values only)

`branding/brand.json` has an optional `telemetry` block; a missing or `null` value turns that provider off:

```json
"telemetry": {
  "sentry_dsn": null,
  "sentry_environment": null,
  "sentry_traces_sample_rate": 0.1,
  "posthog_api_key": null,
  "posthog_host": "https://eu.i.posthog.com",
  "clarity_project_id": null
}
```

Every value can be overridden for CI without editing the file. Precedence: Gradle property, then
environment variable, then `brand.json`.

| brand.json key | Gradle property | Environment variable |
|---|---|---|
| `sentry_dsn` | `-Pspaceworks.sentryDsn=` | `SPACEWORKS_SENTRY_DSN` |
| `sentry_environment` | `-Pspaceworks.sentryEnvironment=` | `SPACEWORKS_SENTRY_ENVIRONMENT` |
| `sentry_traces_sample_rate` | `-Pspaceworks.sentryTracesSampleRate=` | `SPACEWORKS_SENTRY_TRACES_SAMPLE_RATE` |
| `posthog_api_key` | `-Pspaceworks.posthogApiKey=` | `SPACEWORKS_POSTHOG_API_KEY` |
| `posthog_host` | `-Pspaceworks.posthogHost=` | `SPACEWORKS_POSTHOG_HOST` |
| `clarity_project_id` | `-Pspaceworks.clarityProjectId=` | `SPACEWORKS_CLARITY_PROJECT_ID` |
| (debug override) | `-Pspaceworks.telemetryDebug=true` | `SPACEWORKS_TELEMETRY_DEBUG=1` |

Gradle validates the values (HTTPS DSN and host, sample rate 0–1, no unexpected characters) and writes
them into generated `BuildConfig.TELEMETRY_*` fields, which `TelemetrySetup` reads. Firebase is configured
only by the private `app/google-services.json` (git-ignored; the Google Services plugin applies only when
it exists, and `BuildConfig.TELEMETRY_FIREBASE_CONFIGURED` follows it). Never commit DSNs, keys or that file.

**Debug builds and the DEBUG demo never send anything** unless the debug override is set. Demo sessions
are suppressed even with the override (`AppTelemetry.demoActive`): demo data never reaches a provider.

Clarity's masking mode is a Clarity project setting, not an SDK option: set the project to **Strict**
(mask all text, inputs and images) in the Clarity dashboard before enabling `clarity_project_id`.
The app additionally pauses recording on every sensitive screen (below).

## Consent

- Purposes: **Usage analytics**, **Session replay**, **Crash and performance reports**. All off by default.
- First run: after the introduction, a "Help improve <app name>" sheet appears over the server or
  sign-in screen (it never blocks them). It has one switch per configured, not-yet-asked purpose and
  **Allow all**, **Save choices** and **Not now** (everything off). Dismissing the sheet equals Not now.
  It is shown once; a newly configured purpose is asked on its own. Copy is identical to iOS.
- Profile → **Privacy and analytics** shows the same switches at any time, with the line "Withdrawing
  stops collection now. It cannot delete what a provider already holds." Changes apply immediately.
- A purpose whose providers are not configured has no switch. With no configured provider, neither the
  sheet nor the Profile entry is shown.
- Consent is per install (not per account), in the `telemetry-consent` shared preferences with a
  schema version. When a new purpose becomes configured, only that purpose is asked again; a different
  schema version resets to all off.
- Withdrawal: Firebase `setAnalyticsCollectionEnabled(false)` + `resetAnalyticsData()`, PostHog
  `optOut()` + `reset()`, Sentry `close()`, Clarity `pause()`. Granting starts the provider immediately.

## What is collected

- Events (below) with string/number/boolean properties, screen views as route templates
  (for example `inventory`, `membership_card`, `staff_evidence`), never raw ids. The names are identical
  to iOS: `intro`, `consent_prompt`, `server_connect`, `sign_in`, `mfa`, `phone_sign_in`, `sign_up`,
  `password_reset`, `password_change`, `recovery_codes`, `email_verification`, `home`, `inventory`,
  `inventory_detail`, `activity`, `inbox`, `profile`, `bookings`, `spaces`, `space_detail`, `booking_detail`,
  `event_detail`, `event_ticket`, `membership_card`, `profile_edit`, `communication_preferences`, `payments`,
  `payment_detail`, `account_security`, `account_deletion`, `notification_settings`, `app_guide`,
  `privacy_analytics`, `staff_tools`, `staff_scan`, `staff_evidence` (Android does not emit every one yet).
- Super properties: `platform` (`android`), `app_version`, `build_type`, `deployment` (first 12 hex of
  SHA-256 of the server origin) and `role_kind` (`member`, or `staff` with native staff authority and staff actions in the selected makerspace).
- Pseudonymous user id: lowercase hex SHA-256 of `"<origin>|<user id>"` (origin `scheme://host[:port]`,
  lowercase, no trailing slash), set on sign-in for
  Firebase (`setUserId`), PostHog (`identify`, person properties `role_kind` and `platform` only) and
  Sentry (`user.id` only). Cleared on sign-out.
- **Never sent:** email, name, phone, makerspace or item names, free text, QR payloads, tokens,
  server-issued ids, request/response bodies or headers.

### Sentry scrubbing

`sendDefaultPii=false`; no screenshots or view hierarchy; user-interaction breadcrumbs off. `beforeSend`
and `beforeBreadcrumb` (`SentryScrubbing`, `TelemetryScrubber`) drop `Authorization`, `Cookie`,
`Set-Cookie`, `X-Makerspace-Id`, query strings, fragments, request bodies and cookies, keep only
`user.id`, and replace numeric, UUID and token-like path segments with `{id}`. `tracesSampleRate` comes
from configuration (default 0.1); environment is `sentry_environment` or the build type; release is
`<application id>@<version>+<build>`. Release builds are R8-minified; upload the mapping file to Sentry
privately if readable stack traces are needed (no Sentry Gradle plugin or auth token is in the repo).

### Sensitive screens (session replay paused)

Sign-in (and its DEBUG previews), MFA, phone sign-in, sign-up, password reset/change, recovery codes,
email verification, account security and reauth, account deletion, payments and payment detail, the staff
QR scanner and evidence camera, the membership card and the event ticket. Recording resumes when the last of them closes. There are no WebViews.

## Events

Fired from view models and state holders when the server confirmed success (`*_started` on the attempt).

| Event | Properties | Fired by |
|---|---|---|
| `app_open` | | `SpaceWorksApplication` |
| `screen_view` | `screen` | `TrackScreen` (sent as each SDK's native screen call) |
| `onboarding_completed` | | `OnboardingStateHolder.finish` |
| `server_connected` | `locked` | `ConnectionStateHolder.connect` |
| `sign_in_started` / `sign_in_succeeded` / `sign_in_failed` | `method`, failed adds `code` | `AuthStateHolder` |
| `mfa_challenge_shown` | `kind` (`enrollment`/`verification`) | `AuthStateHolder` |
| `sign_up_started` / `sign_up_completed` / `password_reset_requested` | | `AccountStateHolder` |
| `sign_out` | `scope` (`device`/`everywhere`) | `AuthStateHolder` |
| `makerspace_switched` | | `AuthStateHolder.select` |
| `inventory_searched` | `has_query` | `MemberStateHolder.search` |
| `hardware_request_submitted` | `item_count` | `MemberActionsStateHolder.submitRequest` |
| `loan_renewed`, `booking_created`, `booking_cancelled`, `event_registered`, `event_registration_cancelled`, `ticket_opened` | | `MemberActionsStateHolder` |
| `payment_started` / `payment_finished` | `vendor`, finished adds `outcome` (`paid`/`pending`/`cancelled`/`failed`/`unavailable`) | `PaymentsStateHolder` |
| `receipt_downloaded` | | `PaymentsStateHolder.openDocument` |
| `push_permission_result` | `granted` | notification permission request |
| `push_opened` / `deep_link_opened` | `feature` / `kind` (destination route template) | `LinkStateHolder.resolve` |
| `staff_request_decided` | `decision` (`accepted`/`rejected`) | `StaffStateHolder.accept/reject` |
| `staff_item_issued`, `staff_item_returned`, `staff_direct_handout` | | `StaffStateHolder.confirmHandover` |
| `qr_scanned` | `context` (`handover_container`/`handover_equipment`/`event_check_in`) | `StaffStateHolder` |
| `event_check_in` | | `StaffStateHolder.confirmAttendance` |
| `account_deletion_started` / `account_deletion_completed` | | `AccountDeletionStateHolder` |
| `consent_updated` | `analytics`, `replay`, `diagnostics` | `TelemetryController.setConsent` |

`method` ∈ password, google, apple, oidc, phone, passkey (Android uses password, google, oidc and phone).
`code` is the server error `code`, else the HTTP status class (`4xx`), else `network`. A cancelled
chooser or a local validation message is not reported as a failure.

## Architecture

`telemetry/` holds the `Telemetry` facade (`track`, `screen`, `identify`, `reset`, `setConsent`), the
typed `TelemetryEventName`/`TelemetryEvent`/`TelemetryScreen` lists, `TelemetryController` (consent
gating and routing), `TelemetryProviders` (the four SDK adapters), the scrubber and the consent UI.
Feature code calls only `AppTelemetry`. Tests install `RecordingTelemetry` (test sources).

## Verify with fake keys

1. Build a debug APK with the override and placeholder values, for example
   `./gradlew assembleDebug -Pspaceworks.telemetryDebug=true -Pspaceworks.posthogApiKey=phc_test -Pspaceworks.sentryDsn=https://public@o0.ingest.sentry.io/0 -Pspaceworks.clarityProjectId=test123`.
2. Launch: after the introduction the consent sheet lists three switches; Profile shows Privacy and analytics.
3. With all off, no SDK is initialized (`adb logcat` shows no Sentry/PostHog/Clarity start lines).
   Turn a switch on and the SDK logs its start; turn it off and it stops.
4. Unit tests: `./gradlew testDebugUnitTest --tests 'org.spaceworks.app.telemetry.*'` cover consent
   gating, withdrawal, scrubbing, hashing, demo suppression, absent configuration and event names.
