# Private Vault 2.0.3 — Candidate repair build
Version code 4. Application ID and permanent release signing configuration are unchanged.

## Changes under review
- Restore: portable asset validation, bounded backup accounting, creation-only initial setup, atomic recovery journal replacement, and pre-recorded restore destinations.
- Privacy: synchronized key lifetime and session checks, one-time picker return allowance, reduced cross-session UI state, clipboard cleanup retries, stronger separation of real-slot cloud management.
- Autofill: automatic native matching requires an explicit package association. Website credentials require deliberate manual confirmation when the web origin cannot be verified. Exact verified HTTPS matching is supported by the matcher, but no browser is presumed verified.
- Encrypted media: validate PVM1 header and chunk bounds and wipe playback key copies on close. Existing format and authenticated data are retained.
- Update checking: bound manifest response size and require one signer; existing certificate and application ID checks remain in place.
- Platform: exclude private vault files, preferences and database from operating-system backup; user-created encrypted backups remain supported.
- New password creation rejects whitespace-only input. CSV import distinguishes username and title headers.

## Verification and rollout
This is a candidate, not a public release or a claim to have resolved every audit finding. Existing V1/V2 backup layouts and update contracts remain unchanged. CI must run unit tests, lint, debug build, and Android instrumentation-test compilation. Signed release build and on-device testing are separate steps. Use a test vault with non-sensitive records to verify current data survives an update, backup/restore, locks, biometrics, Autofill, and cloud backup. No release tag or OTA manifest should be published from this review branch.

Remaining work: full device tests, verified browser-origin integration, comprehensive cloud/decoy isolation and cancellation testing, memory/performance improvements for large media and backup inputs, and broader UI reviews. Do not infer production readiness from unit-test results alone.
