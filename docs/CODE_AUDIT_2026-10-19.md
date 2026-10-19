# TongCut interim pre-build code audit — 19 October 2026

Scope: `development/capcut-parity`, 40 versioned repository files, all Kotlin source + CI dependencies + project model + exporter.
The owner's request supersedes the previous no-APK gate **for one internal debug build** so they can validate current progress. This is NOT a final finished CapCut-equivalent application.

## Static review
- P0/P1/P2 are still not accepted: real Android devices have not been used in this environment.
- **High priority:** LayerTimeline persists and validates overlays, but preview/export compositing is not implemented. A hard guard blocks a layered project from silent flattened export.
- **High priority:** MediaStore-only permission can be revoked, leaving projects with unreadable video URIs. An explicit missing-media relink flow exists; actual post-restart/revocation needs device testing.
- **Medium:** Autosave cancels/restarts for every EditorDraft change. Verify that rapid project switches don't restore a stale draft or apply late saves; nested ProjectLibrary operations are synchronized and all writes use AtomicFile.
- **Medium:** User's saved clip trim and seek timebases must agree between Media3 clipped playback, sequential timeline model and Transformer. Existing source-time/timeline-time JVM tests pass; on-device media test required.
- **Medium:** VideoFrameThumbnail uses local MediaMetadataRetriever per sample and a bounded frame-count cache. Preview decode load, image recycling, failures and thumbnail accuracy require device checks.
- **Medium:** Mixed audio layouts, silent clips, HDR, variable FPS, long videos, 4K codec limits and background cancellation are unverified.
- **Low:** UI is a CapCut-inspired structural layout rather than feature parity; many buttons intentionally display planned-feature messages.
- **Release:** Android Keystore sign, Play distribution, privacy and licence audits are pending. Debug APK for owner testing only.

## Review method and required CI
1. Inspect source tree and feature gate docs.
2. Fresh Gradle clean, compile, unit tests, Android Lint, debug assemble.
3. Unzip/integrity-check the output including AndroidManifest.xml and DEX entries.
4. Inspect warnings/errors and fix regressions until CI green.
5. Owner device acceptance: import 3 clips, preview/seek, split and reorder, trim, autosave, force stop/relaunch, rename/duplicate/delete/recover project, relink missing source, filter/crop and export; compare duration and audio sync. Do not treat clean CI as device proof.
6. No new version bump to stable main, no Google Play release; keep debug build clearly marked in file name and response.

## Strict audit CI result — verified
- Initial CI run: [39195044809](https://github.com/manbir6-ux/TongCut/actions/runs/39195044809) **failed** Lint with 6 errors, 11 warnings, despite 35/35 unit tests passing.
- Fix commit: [eb9c0869](https://github.com/manbir6-ux/TongCut/commit/eb9c08696fe3534910efbc5e617649956b7e6b2a) applied:
  - Replaced API31-only BigInteger.longValueExact with checked overflow-safe conversion for Android min SDK29.
  - Added Media3 UnstableApi opt-in for PlayerView operations.
  - Removed unnecessary explicit screen-orientation pinning; added data-extraction rules and proper app name resource.
  - Isolated test package `au.parmtech.tongcut.test` / `TongCut TEST`, to avoid replacing the owner's earlier APK with a different debug-signing key.
- Corrected CI run: [39195407945](https://github.com/manbir6-ux/TongCut/actions/runs/39195407945) **SUCCESS**.
- Tested commands: `gradle --no-daemon --stacktrace clean testDebugUnitTest lintDebug assembleDebug`.
- JVM tests: **35 passed, 0 failed, 0 errors**, six test suites.
- Android Lint: **0 errors, 9 warnings**. Warnings: older target SDK, available newer Compose BOM/activity/core/json dependencies, Android 12 backup-rule interoperability advice, and three Kotlin KTX style suggestions. Existing dependency versions were kept to avoid untested major upgrades.
- APK integrity: ZIP contents valid, AndroidManifest.xml exists, five classes DEX files present, 67,459,386-byte debug APK.
- Extracted owner test APK SHA-256: `621880f9d5a4758743a9e291bb6cfb0c8888e35df877c1fdaecd2683c74ec200`.
- GitHub Actions artifact: `TongCut-development-audited-debug-apk`, expires after 7 days.
- **Device and feature integration test NOT performed here.** Success of unit tests, lint, and packaging does not prove runtime media codec compatibility, exported overlays, permission persistence, full UI behavior, or CapCut feature parity. Keep P1/P2 and release acceptance gates open.
