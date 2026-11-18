# Phase 1–2 execution report

**Branch:** `feat/phase-1-2-engineering-reliability`  
**Base revision:** `4e2185d9bad9a225035ead122f3c9bd111c6f090`  
**Scope:** engineering quality, evidence/attachment correctness, and campaign persistence boundaries.

## Implemented

### Phase 1 — engineering quality and tests

- Pinned the Gradle wrapper back to **8.13**, matching AGP **8.13.2**; corrected the root toolchain comments to reflect Kotlin Gradle Plugin **2.2.21**.
- CI now runs `testDebugUnitTest`, `lintDebug`, and `jacocoTestReport` on pull requests, and uploads test/coverage reports.
- Expanded the synthetic AI grounding golden set to **100 deterministic draft cases** (20 synthetic domains × 5 subject variants). The test checks the grounding gate, one CTA, opt-out language, and a subject-length bound. An invented-claim negative control remains.
- Added regression coverage for strict attachment selection and the campaign snapshot decoder.
- Changed the evidence fingerprint from a truncated 64-bit prefix to the full **SHA-256** hex digest.

### Phase 1 — attachment correctness / fail-closed behavior

- The attachment selector no longer falls back to the two newest arbitrary PDFs.
- A sendable attachment set must contain exactly one Persian and one English trial PDF, both explicitly marked as trial, both exactly two pages, and both from the same filename family.
- Full/final/premium reports, unknown page counts, unreadable PDFs, unrelated language variants, and single-file folders produce no sendable pair.
- `AttachmentGuard` repeats the name/language/pair check and requires exactly two files and exactly two pages at approval/send validation.
- The send-time guard continues to compare the actual PDF magic header, byte count, actual page count, and SHA-256 against the approval snapshot.

### Phase 2 — campaign persistence boundary

- Added `CampaignRepository` as the Room persistence boundary for active campaign snapshots and studio drafts.
- `App` now delegates campaign restore/save and draft writes through the repository rather than directly invoking the DAOs in those paths.
- Snapshot encoding is deterministic (deduplicated/sorted contacts, selected keys, contexts, and source lists).
- Snapshot restore rejects malformed contacts/domains and invalid company contexts, and removes selected keys that no longer map to a restored contact.
- Existing Room schema version and migrations are unchanged; no destructive migration or schema rewrite was introduced.

## Important behavior changes

- A folder containing only one trial PDF is now **not eligible for email approval**. The app requires the complete Persian/English pair.
- A trial PDF with unknown page count is not eligible. It must be parsed and verified as exactly two pages.
- Full reports are never selected merely because they are newer than trial files.
- The reader parses at most six PDFs per domain, but identifies and prioritizes the best filename-matched Persian/English trial pair before applying that cap. Older eligible trial files therefore remain eligible for verification even when newer unrelated reports exist.
- When no readable, matching, exactly two-page bilingual trial pair exists, the app emits a warning and sends no PDF attachment.

## Validation status

The previous feature-branch CI run `44278801794` completed successfully on commit `e03390a7dcb52aee914c7de6be6692d3ab6ae02c`. Unit tests, Android lint, debug APK assembly, release APK/AAB assembly, and artifact upload all succeeded. The run uploaded `SaliBussiness-APK` and `unit-test-coverage` artifacts. This confirms pipeline health for that revision; it does not establish a measured domain-logic coverage threshold or production signing unless signing secrets were configured.

Each subsequent change must pass the pull request CI gate. Do not mark the work production-ready until all of these pass:

1. Gradle wrapper distribution and configuration — passed on run `44278801794`.
2. JVM unit tests, including the 100-case golden set and attachment/snapshot regression tests — passed on run `44278801794`.
3. Android lint — passed on run `44278801794`.
4. Debug APK assembly — passed on run `44278801794`.
5. Release APK/AAB assembly — passed on run `44278801794`; production signing remains conditional on configured signing secrets.
6. The new trial-priority regression test and updated reader/docs require a fresh CI run before this revision is considered validated.

## Remaining work — intentionally not claimed complete

- Extracting all domain logic (AI parsers, quality gate, send policy, PDF fact parsing) into a platform-independent module.
- MockWebServer contract tests for hcnsec, You.com, Brevo, throttling, and ambiguous provider responses.
- Compose UI and instrumentation/Espresso tests.
- ViewModel/UiState migration for all Compose screens.
- Process-death testing on a real Android device/emulator and a campaign restore UX state.
- Room MigrationTestHelper coverage and an explicit migration policy for future schema changes.
- Enforcing a numeric JaCoCo coverage threshold for the domain layer.

**Release decision:** this PR materially hardens attachment correctness and campaign persistence, but it is not by itself a production release approval.
