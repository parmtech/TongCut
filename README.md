# TongCut — Android video editor

**Status:** Experimental source development on `development/capcut-parity`, not an accepted release or a complete CapCut replacement. The `main` branch retains the older 0.1.0-alpha editor. All code must be independently implemented and appropriately licensed.

## Implemented in the development branch (source; not feature-accepted)

- Offline source selection/import, main-sequence trim/split/reorder and preview with project persistence, undo/redo and relink support.
- Multiple typed VIDEO, PHOTO, AUDIO and TEXT overlay tracks; timeline gestures, keyframe motion, speed editing, PiP and image/title compositing.
- Experimental Media3 GPU video/photo masks, chroma key and despill, source-colour picker, source-aware audio speed ramps with pitch preservation, basic fades and overlay crossfades.
- Export experiments include H.264 MP4, filters/cropping, progress/cancel, gallery saving and mixed-media composition subject to device codec support.
- Pure JVM model/regression tests and instrumented synthetic-media tests, with past CI evidence recorded in the temporary checklist.

**Important:** Presence of code, a CI pass, a synthetic emulator output or an old diagnostic APK does *not* mean P2/P3 has passed on the owner's phone. In particular, true reverse, full blend modes, general main-sequence/audio transitions, tracking, advanced audio studio, real stickers, licensed visual packs, HDR parity, broad codec coverage and AI provider features remain incomplete or unverified.

**Source of truth:** [Temporary master development and acceptance checklist](docs/TEMP_MASTER_PROJECT_CHECKLIST.md), [P2 acceptance](docs/P2_ACCEPTANCE.md), [P3 acceptance](docs/P3_ACCEPTANCE.md), and [UI parity contract](docs/CAPCUT_UI_PARITY_CONTRACT.md). Keep the temporary checklist until all P0–P9 gates and owner approval are complete.

## Verification (no APK release)

Required host: JDK 17, Android SDK Platform 36/build-tools 36.0.0, Gradle 8.13, Android Gradle Plugin 8.13.2. For normal branch checks:

```bash
gradle --no-daemon testDebugUnitTest lintDebug
```

The ordinary `.github/workflows/android.yml` workflow **does not package or publish an APK**. Other historical diagnostic or instrumentation workflows must not be mistaken for a distributable product. Re-enable any final release packaging only after the applicable acceptance gates and owner signoff.

## Main files

- `MainActivity.kt`, `TongCutApp.kt`, `EditorWorkspace.kt`, `MediaGallery.kt` — app shell and editing screens.
- `EditorDraft.kt`, `ProjectLibrary.kt`, `ProjectStore.kt` — editable projects and persistence.
- `LayerTimeline.kt`, `LayerEdits.kt`, `AdvancedClipEdits.kt` — non-destructive tracks, source clocks and history operations.
- `VisualComposition.kt`, `VisualOverlayPreview.kt`, `VideoExporter.kt` — experimental preview/export pathways.
- `AudioMixPlan.kt`, `AudioSpeedEdits.kt`, `VideoMatteEffect.kt`, `ChromaFrameSampler.kt` — specialist source/render tools.
- `app/src/test` and `app/src/androidTest` — JVM and Android regression coverage.

**Licence:** Original TongCut source is MIT licensed. Third-party libraries retain their upstream licences; never commit copyrighted CapCut source, paid assets, keys or private test media.
