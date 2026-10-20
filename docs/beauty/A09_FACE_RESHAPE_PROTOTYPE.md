# A09 manual face reshape prototype

Status: isolated proof-of-concept only. P5-08 remains open.

## What this slice does

The new `beauty/ManualFaceWarp.kt` code provides a dependency-free geometry model for manually placed normalized eye, cheek, nose, mouth, and chin anchors. Bounded controls adjust face width, jaw width, nose width, eye scale, and a conservative local softening blend. It maps output pixels back into the source with smooth localized weights, clamps sampling to image bounds, and preserves alpha during interpolation and softening.

The CPU renderer accepts ARGB integer buffers and rejects images over 1.5 million pixels. Neutral settings return an independent pixel copy. This makes the math and pixel behavior testable with synthetic fixtures without Android UI or face imagery.

## Explicit limits

- No face detection, tracking, inference, network access, account requirement, proprietary model, or generative processing.
- Landmarks are manual input; this source does not provide a landmark-placement UI.
- “Skin softening” is only a face-shaped feathered blur blend with manual landmarks and feature exclusions. It is not skin segmentation or a beauty-quality claim.
- Body reshape is not included.
- This is a bounded still-image CPU experiment, not a realtime preview or production video effect.
- No editor, project schema/persistence, undo/redo, timeline, preview, or export integration is present.
- No tests, build, performance benchmark, visual-media comparison, or device validation has been run in this feature pass.

## Integration request

A follow-up integration owner should add a project-compatible manual-beauty value model and serialization with neutral defaults for old projects, then connect a user-facing landmark/control panel and undoable edits. The renderer must be benchmarked on representative device resolutions before any preview use. Preview and export need matching rendering semantics and explicit handling for rotation, crop, pixel aspect ratio, and color format. Keep automatic detection/tracking out of scope unless separately approved.

## Acceptance still required

P5-08 must remain unchecked until the feature is integrated with persistence and undo, preview/export parity is demonstrated, bounded-performance checks pass on target devices, edge cases are covered, and the owner accepts the behavior on-device. The focused test source in `app/src/test/java/au/parmtech/tongcut/beauty/ManualFaceWarpTest.kt` is intentionally unrun per the feature-first instruction.
