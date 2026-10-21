# TongCut Current Development QA — 21 October 2026

**Status:** internal TEST APK built and checked; editor feature/device acceptance is STILL OPEN.
**Repository:** `manbir6-ux/TongCut`.
**Base source:** `development/capcut-parity` at `ca57147ece25724b07982734e349d3dca551aeb1`.
**Clean integration branch:** `fix/compile-regressions-20261021` (not merged; GitHub connector PR write returned 403).
**Temporary QA branch:** `fix/development-qa-20261021`.
**No change to `main` release or to final P0–P9 acceptance checklist.**

## Accepted evidence only

Green full local-JVM/Lint/debug-APK run:
https://github.com/manbir6-ux/TongCut/actions/runs/39532377401

- **363 JVM tests passed, 0 failed, 0 skipped**, across 45 XML suites.
- Android Lint **0 errors, 37 non-blocking warnings**.
- `assembleDebug` and APK integrity check succeeded. GitHub artifact ID `12315793911`.
- TEST APK SHA-256: `b7e61175e3f38dfebc633f110e3316f29a8239ec4753cf8367a709d5dea83c28`.
- APK size: 69,131,785 bytes. Package is `au.parmtech.tongcut.test` (separately installable Android debug signature).
- The complete QA report artifact is archived on GitHub run, and the exact APK bytes were checked for ZIP integrity, AndroidManifest.xml and DEX entries.

**Evidence limits:** no physical handset signoff, no screenshot-level UI parity, no full codec/thermal/performance certification and no production signing. A09 experimental face-reshape branches are not integrated into this app.

## Corrections from baseline source

- `SequentialTimeline.kt`: remove accidental tool warning lines before Kotlin `package`.
- `SequentialTimelineTest.kt`: remove same source corruption.
- `ExactReverseFrameDecoder.kt`: fix ByteBuffer `position()` method invocation; add missing source-PTS frame-duration and output-frame index delegates used by reverse preview/export.
- `PhotoMatte.kt`: define the missing eight-vertex `MAX_POLYGON_POINTS` constant, preserving GPU shader limitations.
- `EditorWorkspace.kt`: color-grading tool selectors now use integer indices matching state, rather than incompatible string values.
- `CaptionEntrance.kt`: normalize fully completed RISE animation Y offset to positive zero; maintain authored timing.
- `PhotoMatteTest.kt`: test valid capped 0.08 blur strength, not an out-of-range 1/3; original blur bounds remain unchanged.
- `VisualOverlaySlotsTest.kt`: require schema at least 11 (feature introduction), rather than incorrectly pinning current schema to historical version 11. Legacy schema 6 roundtrip and slot compatibility remain checked.
- `ExportService.kt` and `AndroidManifest.xml`: declare Android 13+ notification permission, check runtime status before drawer updates and tolerate revocation; keep foreground export start independent of notification permission (system Task Manager still shows FGS when denied).
- `AudioOverlayEngine.kt`: explicit Media3 UnstableApi opt-in for known API use.
- `tools/ci/check_kotlin_sources.py`: pre-Gradle invalid header/conflict marker guard wired into existing source CI.
- `DevelopmentBuildContractsTest.kt`: regression coverage for timeline boundaries, reverse PTS indexing/durations and polygon shader bound.

## Further QA in progress, not accepted

- API 29–32 crop-metadata compatibility in `ExactReverseFrameDecoder.kt`: uses the existing stable `crop-left/top/right/bottom` metadata names instead of API33-declared constant references, with no change to actual frame crop logic. Isolated full QA run: https://github.com/manbir6-ux/TongCut/actions/runs/39533487478 . **Pending; do not extend green certification to this new commit yet.**
- Android API35 emulator smoke covering project recovery, PiP export, transitions, audio ducking and video masks: https://github.com/manbir6-ux/TongCut/actions/runs/39533401250 . **Pending; no device pass claimed.**
- User Android phone still must test media pick, project save/restart, trim/split, layered preview/audio, export, video mask/transition, background job/cancel, unsupported codec behavior, crash-free lifecycle.

## Integrator A20 instructions

Do not merge a temporary debug APK workflow. Use the narrow clean patch branch `fix/compile-regressions-20261021` and review 14 source/test/CI files plus this document; preserve latest development changes if the base has moved. Open an ordinary pull request through GitHub/Codex/Work using an account with PR-write permission; current connector cannot open PRs. Run source/unit/lint first, then Android emulator and owner handset acceptance. No force pushes, `main` merges, production releases or premature CapCut-parity claims.
