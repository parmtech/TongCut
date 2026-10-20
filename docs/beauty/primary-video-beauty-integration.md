# Primary footage manual beauty integration proposal

Status: source contract only; no editor, persistence, shader, unit, or device checks have been run.

## Contract

The new PrimaryVideoBeauty module defines an explicit normalized top-left face ellipse and bounded signed face-width, jaw-width, nose-width, eye-scale, and softening values. It performs no face detection, tracking, network access, generation, or asset loading. A missing region or all-zero settings are a no-op; the effect helper returns null in that case so callers can avoid any pixel change.

The pure normalized source-position mapper and SDR Media3 GlEffect use the same bounded warp constants. The shader is designed to be inserted into the primary-video effect chain used by both ExoPlayer and Transformer. It keeps input alpha and fails closed for HDR until a separate HDR implementation is validated.

## Small A20 integration patch needed

1. Add a primaryBeauty field to EditorDraft and ExportOptions, defaulting to PrimaryVideoBeauty() with no region. Add constructor validation only if it preserves this neutral no-op.
2. In ProjectStore, bump the project schema from 14 to 15. Encode a nested primaryBeauty object with region center/size and each control. When the object, region, or individual field is absent, malformed, or out of bounds, decode to neutral settings without rejecting an otherwise readable project.
3. Add region set/move/resize, five bounded controls, clear-region, reset, and undoable Adjust-panel actions in EditorWorkspace. Route all changes through the existing draft/history reducer so preview-only slider frames are not separately committed. The current Player.setVideoEffects LaunchedEffect must include primaryBeauty and append primaryVideoBeautyEffectOrNull only when non-null.
4. Add the same optional effect to VideoExporter.primaryClipVideoEffects so regular and cached reverse-video export use identical settings. Keep it on the primary clip only, before composition overlays and captions.
5. The reverse-frame preview currently renders bounded CPU bitmaps rather than ExoPlayer video effects. Either apply PrimaryVideoBeautyMath.sourcePositionForOutput and an equivalent bounded CPU sampling/softening pass to that preview path, or explicitly keep beauty inactive during reverse-preview frames and surface that limitation in UI. Do not claim preview/export parity for reverse playback until the CPU path is wired.
6. Add unit coverage for defaults/no-op, bounds, region geometry, symmetry, neutral mapping, and edge clamping; add a synthetic Android pixel test to validate preview/export shader behavior when the feature-construction phase ends. Keep P5-08 and P9 acceptance leaves unchecked until those tests and owner-device acceptance pass.

## Known limits

- Geometry is manually placed and stays fixed in source-frame coordinates; there is no tracking, so a moving face may leave the region.
- This is a localized SDR video warp/softening prototype, not a complete beauty studio or body-reshape feature.
- CPU/GPU pixel parity, Android shader compilation, performance, HDR, reverse-preview parity, and device/visual quality have not been verified.
- The default is neutral and should produce no effect at all; callers must skip the shader when settings are neutral.
