# Notifications and update links

A4 uses only the published generated push registration and deletion operations. The DEBUG demo uses in-memory registration IDs and fixtures; it does not request OS permission, acquire an FCM token, call a server, or send notifications. Its separate scenarios cover permission denial, unavailable service, throttling, unknown payload versions and unauthorized makerspaces.

## Deployment setup still required

Supply the owner's Firebase Android app configuration as ignored `app/google-services.json`; the official Google Services4.5.0 plugin applies only when this file exists. Match the branded application ID and environment. Without configuration, Inbox remains usable and setup says notifications are unavailable for this build. No Analytics SDK is included. OS permission is requested only from the visible Enable notifications action; a blocked app/channel links to native notification settings.

Set a fixed HTTPS `server_url` in `branding/brand.json` to register that exact host's App Link intent filter. The owner must publish the corresponding Digital Asset Links association for the actual package and signing certificate. Dynamic self-hosted server input does not register a wildcard App Link host. The `spaceworks:` custom scheme uses the server already selected by the user. No received URL can configure or replace that server.

Live delivery is deferred with the owner backend/provider setup. Backend request [issue3](https://github.com/SpaceWorks-HQ/SpaceWorks-Android/issues/3) also tracks native inbox/device eligibility and an origin-bound hint contract. Current hints have a maker ID but no deployment identity: a token left on an old deployment after offline sign-out cannot distinguish a new deployment with overlapping maker IDs. No uncontracted payload field was added. Production validation must include data-only FCM delivery; OS-rendered notification payloads can bypass an app service's presentation policy.

## Consent, identity and cleanup

Explicit consent is stored by a SHA-256 key of deployment and user ID. Private preferences contain only consent, returned registration IDs, maker IDs and token fingerprints. Raw FCM/access/refresh tokens and notification text/links are never persisted there. Active direct memberships determine registration scope. SDK token rotation reconciles each membership with immutable maker headers. Zero memberships disables delivery and stops token acquisition.

Logout removes returned IDs before revoking the native grant, then stops FCM and clears local session/private state. Failed association removals retain only their IDs/fingerprints for that same account's future authenticated retry. A switch to another account never deletes the old account's records with the new account's credentials.429 stops the remaining batch and preserves the server deadline. An offline failure cannot be reported as confirmed server removal.

Entering demo pauses the live delivery gate synchronously. Late token callbacks and an already-running registration cannot reopen it. Local consent or a cached membership hint alone does not authorize account content: an actual tap always validates current identity from the chosen deployment. The app service's OS notification text is generic and contains no event body. Background delivery needs deployment validation before release.

## Routing (ROUTES-SPEC)

Every link source — in-app navigation, push taps (`v >= 2`, so the server's v3 payloads route), inbox-row
"Open update", App Links and `spaceworks://` — goes through one pure resolver,
`org.spaceworks.app.routing.WebRouter`, over one registry (`Destination`) whose names are the web's routes
and the analytics screen names. It accepts the web's paths in both forms: `/m/{slug}/…` (central) and
unprefixed custom-domain paths (the push or inbox row's makerspace, or the host's makerspace for a
verified custom domain listed in `branding/brand.json` `app_link_hosts`), plus the legacy
`/makerspaces/{id}/…` aliases. Per-destination query parameters are kept (`section`, `period`,
`next`, `return`, staff `event`/`space`/`booking`); everything else is dropped. Refused: other hosts,
percent-escapes, dot segments, userinfo, control characters. A push link carrying a query falls back to
the push makerspace's Inbox.

Outcomes: a native screen on its registry tab; **not a member** ("Choose another space"), **paused**
space, **feature unavailable**, **not in the app yet** (known web page without a native screen — never a
WebView), staff **sign-in required** (the link replays after the staff step-up), **no access**; public and
token pages open in the system browser; unknown paths fall back to the Inbox (Home when the space has no
notifications module). Slugs are resolved after a fresh `/auth/me`; each membership's public bootstrap is
read once per session for modules, features, custom host and paused state. `WebRouter.webPath` builds
share links with the web's `tenantPath` rule. A link that arrives signed out is held in memory (newest
wins) and replayed after sign-in; sign-out clears it. Links never perform an action.

App Links: the manifest claims the server host's `/m/*/member*`, `/m/*/events/*`, `/m/*/bookings/*`,
`/m/*/items/*`; each `app_link_hosts` entry additionally claims `/member`, `/member/*`, `/events/*`,
`/bookings/*`, `/items/*` through a generated manifest overlay (publish `assetlinks.json` on those hosts).
Staff `/admin` paths never arrive by App Link (the server excludes them); they come from inbox rows.

## Validation

The native journeys cover consent and a second-maker product update, denied scope and unknown-version fallback, denied permission/service recovery/off/logout, and an incoming intent that cannot change deployment. They capture full OS and Compose screenshots on CI phone/tablet devices. Unit cases cover every generated operation, strict URI/version parsing, per-account lifecycle, failed cleanup,429, stale routes, read-only destination lookup and cross-session credential isolation. Live FCM, association files, owner signing and backend delivery remain separate release gates.
