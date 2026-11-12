# API contract provenance

`openapi.json` is a byte-identical copy of the backend's `frontend/openapi-schema.json` from `SpaceWorks-Labs/SpaceWorks`, branch `dev`, release **0.12.0**, commit `5c7f573363e97aa3844d7eb9f372cba31f918c3b` (re-pinned 2026-11-12). SHA-256: `8045528d3ea3def75e4abef01a5cac8317fb95ceb721ed714698d54c0a02ab7e` (17,638,300 bytes; 2,211 paths; 4,357 schemas; OpenAPI 3.0.3). It supersedes the 0.9.2 mobile-backend snapshot `962e6a4b…` (760 paths). The backend's companion prose for rules the schema cannot express is its `docs/mobile/MOBILE-APP-PRD.md` (Version 4, 2026-11-09) and `docs/mobile-integration.md`; `docs/PRD.md` here remains the byte-identical v1 owner copy.

`./gradlew :app:openApiGenerate` runs OpenAPI Generator 7.26.0 (`kotlin`, `jvm-retrofit2`, `kotlinx_serialization`, coroutines, `enumUnknownDefaultCase=true` so an enum value newer than this pin decodes to `unknown_default_open_api` instead of failing the payload). `scripts/contract.py` mechanically selects implemented operation paths and their unchanged transitive schema references, avoiding unused API surface. The full authoritative snapshot remains committed. Expand the operation allowlist as each validated phase needs it. All client code lives in ignored build outputs; no handwritten request/response models.

Schema server examples are overridden with the validated deployment root. Paths already contain `/api/v1/`. Never append a second API prefix. Updates come from the owner; request missing endpoints using a `backend-request` issue.

Native generation compatibility overrides, type mappings, scoped headers and streamed-body cancellation are documented in [GENERATED-CLIENT.md](../docs/GENERATED-CLIENT.md). The raw snapshot above remains unchanged.
