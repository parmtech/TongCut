# A09: offline manual face reshape — isolated still-image prototype

**Status: SOURCE PROTOTYPE / TESTS ADDED; UNVERIFIED. NOT CONNECTED TO TONGCUT VIDEO EDITOR.**

Branch: `agent/A09-face-reshape` (owned by GitHub issue #15). This module is **not** part of the older release APK. It is not evidence of face/body reshaping in the editor, not a face detection model and not a CapCut-parity claim.

## Implemented in this branch

- `app/src/main/java/au/parmtech/tongcut/beauty/FaceReshapeWarp.kt`: independent pure Kotlin CPU geometric face warp with normalized landmarks, bounded user settings (cheek slimming, jaw slimming, eye enlargement, nose slimming and chin-length change). Uses inverse mapping and alpha-aware bilinear sampling to avoid transparent-color fringes.
- `app/src/main/java/au/parmtech/tongcut/beauty/FaceReshapeBitmap.kt`: Android software-Bitmap adapter, validates dimensions and returns a separate newly allocated ARGB8888 bitmap without modifying the original.
- `app/src/main/java/au/parmtech/tongcut/beauty/FaceReshapeFramePlan.kt`: bounded source-timeline observation interpolation with confidence/face-switch/long-gap guards; includes a prepared track for logarithmic per-frame lookup.
- `app/src/main/java/au/parmtech/tongcut/beauty/FaceReshapeControls.kt`: isolated reusable Compose slider panel; not connected to production editor.
- `app/src/main/java/au/parmtech/tongcut/beauty/FaceReshapeLandmarkPicker.kt`: isolated Compose image overlay for manually positioning nine landmarks; not connected to production editor.
- `app/src/test/java/au/parmtech/tongcut/beauty/FaceReshapeFramePlanTest.kt`: source-only tests for interpolation, track switches, occlusion and malformed timing.
- `app/src/test/java/au/parmtech/tongcut/beauty/FaceReshapeWarpTest.kt`: synthetic JVM test **source**, covering neutral identity/copy, geometry changes, eye enlargement, transparent edge color, malformed landmarks and frame-size errors. These tests are **not claimed passed**; per the owner's current feature-first workflow, CI/build/phone QA is deferred.

## Minimal caller contract

```kotlin
// Call off the UI thread; source Bitmap must be software readable.
val anchors = FaceReshapeLandmarks(
    leftEye = FacePoint(0.36f, 0.36f),
    rightEye = FacePoint(0.64f, 0.36f),
    leftCheek = FacePoint(0.28f, 0.56f),
    rightCheek = FacePoint(0.72f, 0.56f),
    leftJaw = FacePoint(0.35f, 0.74f),
    rightJaw = FacePoint(0.65f, 0.74f),
    leftNose = FacePoint(0.45f, 0.55f),
    rightNose = FacePoint(0.55f, 0.55f),
    chin = FacePoint(0.50f, 0.90f)
)
val processedBitmap = FaceReshapeBitmap.render(
    input = originalSoftwareBitmap,
    landmarks = anchors,
    params = FaceReshapeParams(
        cheekSlim = 0.4f,
        jawSlim = 0.2f,
        eyeEnlarge = 0.15f
    )
)
// Caller owns processedBitmap and must recycle it when done.
```

These coordinates are illustration-only normalized image points, **not** detector output, and only make sense for the correct corresponding face orientation and location. Do not apply this fixed example to users' photos.

## Design limitations

1. **No automatic face detection / body reshape yet.** Input points must be supplied by an approved on-device detector or explicitly adjusted by the user. The engine does not identify people, upload images or require a subscription/API.
2. **CPU photo proof-of-concept only.** It can produce modified still bitmap buffers, but is NOT integrated into the main editor, project JSON, undo/redo, preview or exported MP4 video frames.
3. It assumes a near-frontal upright face and one face per set of landmarks. The new `FaceReshapeFramePlan.prepare(observations)` form should be used before per-frame lookups; it validates/copies source observations once, then uses binary search for each requested frame. A rotated, profile, partly hidden or moving face requires per-frame reliable anchors and temporal stabilization.
4. Maximum input image is 16,777,216 pixels. Rendering a high-resolution image repeatedly on the main UI thread will stall the app. For video, build a corresponding GPU GLES shader consuming the identical displacement formula.
5. Current model has bounded manual adjustments; physically implausible settings and complex neighboring features may look distorted. Needs skin/teeth/hair edge-quality tests across ages/tones/angles. No claims of beauty quality until tested.
6. No dependency/license changes, no Gradle/Manifest changes, no third-party proprietary libraries and no permissions added.

## GPU video implementation (NEW, pending editor integration)

- `FaceReshapeGpuGeometry.kt`: pure source-frame inverse-warp geometry and CPU shader-coordinate oracle. Seven displacement pins + two eye radii, corresponding to the still-image CPU algorithm.
- `FaceReshapeVideoEffect.kt`: standalone SDR-only Media3 `GlEffect` + `BaseGlShaderProgram`, intended to be reused by ExoPlayer preview **and** Transformer export. It handles missing/low-confidence landmarks by copying the incoming frame. HDR is currently rejected, not silently reinterpreted.
- `FaceReshapeEffectFactory.kt`: constructs per-clip Media3 effect lists from the same saved settings. Independent preview and exporter pipelines must create their own instances. **Nothing in the current editor or exporter calls it yet.**
- `FaceReshapePhotoLab.kt`: standalone offline photo preview surface with manual point handles, live CPU preview generated off the UI thread, actual 5 strength sliders, an original/effected view switch and `onCommit` callback. It requires a software bitmap already downsampled to at most 1,048,576 pixels. It is **not shown in the main app**, and the callback does not magically persist anything until A03/A15 integrate it.
- `FaceReshapeEditCodec.kt`: strict version-1 local JSON edit payload, with bounded face-anchor observation count, settings validation and no photo bytes. A03 owns actual project persistence/migrations; this codec is not wired into `ProjectStore`.
- `FaceReshapeLinearClock.kt`: deterministic trim/speed/reverse source clock for a single **constant-speed** clip. For project joins and speed ramps, use existing `layer.sourceAt()` in a validated adapter; never guess Transformer presentation-time semantics.
- Added source-only JVM tests: `FaceReshapeGpuGeometryTest.kt`, `FaceReshapeEditCodecTest.kt`, `FaceReshapeLinearClockTest.kt`.

### Required preview/export hook (DO NOT APPLY without A20/A04/A12 approval)

Use the **same** `FaceReshapeEffectFactory.videoEffects(edit, timeMapper)` with:
- ExoPlayer: `player.setVideoEffects(existingVideoEffects + faceVideoEffects)`.
- Media3 Transformer item: `EditedMediaItem.Builder(item).setEffects(Effects(audioEffects, existingVideoEffects + faceVideoEffects))`.

These are integration instructions, **not existing production wiring**. The `timeMapper` maps the frame's Media3 presentation time to source microseconds, accounting for trim/speed/reverse. Feed samples in original, unmirrored top-left photo coordinates, with per-track confidence.

GLES2 compatibility: radii for symmetric pin pairs are shared to keep active fragment uniform usage below the minimum 16 uniform-vector budget. All uniforms are initialized even when the first frame has no face. This is **not** proof of functioning GPU rendering; Android shader compilation and decoded MP4 pixel tests remain OPEN.

### Test and acceptance status

- Kotlin code has been committed to the isolated A09 branch. **No successful Gradle compilation, running GLES shader, instrumented video, device thermal check or saved-project compatibility proof has been established.**
- Alpha interpolation differs between a CPU alpha-safe bilinear sampler and standard GPU hardware filtering; transparent edges and RGBA composition need actual pixel acceptance.
- The fragment shader assumes the same Media3 orientation convention as the existing TongCut `VideoMatteEffect`; verify portrait/mirrored/rotated clips with synthetic markers.
- No automatic face detection or video landmark tracker is included; tracking observations must come from manually approved or consented on-device tooling.
- No `main` branch changes, no APK release and no fake editor controls.

## Next implementation steps for A09 + coordinator A20

- Obtain legal offline landmark solution (or build touch-based manual landmark-placement editor) with orientation and front-camera mirror normalization.
- Implement manual point/strength controls and save settings in non-destructive project schema; A03 must approve codec integration and migration.
- Define GPU image-space warp shader for **both preview and Media3 export**; coordinate with A12 PiP and A04 export owners for shader ordering and HDR/alpha handling.
- Extend image support to tracked video with per-frame landmarks and stabilization, handling occlusion and face lost/face swapped tracks.
- Validate Kotlin source + JUnit synthetic image tests, then Android instrumented output-vs-preview pixels and device codec/thermal performance.
- Only after physical-device acceptance mark P5 face-reshape gate complete. **Do not advertise or include a non-functional Face Reshape button in the app.**

## Pre-release test commands (deferred until owner-authorized QA stage)

```bash
gradle --no-daemon :app:testDebugUnitTest :app:lintDebug
```

Do not merge into development automatically. Record reviewer feedback, target A09 PR, and synchronize with integration agent A20.
