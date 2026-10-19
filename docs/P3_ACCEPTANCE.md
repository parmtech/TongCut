# TongCut P3 — advanced editing acceptance (NOT COMPLETE)

Work branch: `development/capcut-parity`.

The owner explicitly requires **both P2 and P3 complete** before a comprehensive bug, static lint, runtime and export audit, and only then an owner-facing TongCut TEST APK. Do not tag P2/P3 complete or deliver an interim APK.

## Source that exists
- [x] Motion keyframes on layered clips: X/Y, scale, rotation and opacity; frame snapping and easing (linear, smooth, hold)
- [x] Schema 2 migration and motion serialization; split-safe preservation of the animated pose
- [x] Non-destructive UI actions with undo/redo snapshots for motion edits
- [x] Photo and text native overlay effects use motion coordinates/opacity; experimental Media3 PiP export applies motion pose to video overlay lanes
- [x] Unit tests for interpolation, keyframe bounds/validation, project roundtrip, splits, compositing layer order and gaps
- [x] Source Kotlin and Android Lint checks on development branch

## Still REQUIRED
- [ ] Speed ramps and curves with real source time remapping, pitch handling and matching render/preview
- [ ] Reverse video/audio effect (not merely reverse thumbnails)
- [ ] Freeze-frame generation and export with source clip continuity
- [ ] Blend modes supported by real GL shader pipeline
- [ ] Shape/freeform masks with feather, crop and animated bounds
- [ ] Chroma key/green screen with eyedropper, thresholds and spill removal
- [ ] Seamless adjacent-clip transitions with calibrated overlaps and soundtrack timing
- [ ] Optical flow/object/face tracking with editable motion paths and confidence handling
- [ ] Effect settings surfaced in a genuinely editable responsive interface
- [ ] Match every active effect between preview and output on a hardware Android device
- [ ] Hardware codec/thermal/long video/HDR/VFR/portrait/landscape/sticker regression
- [ ] P2 acceptance passed: multitrack visual compositor and audio export on-device + no dropped layers
- [ ] Repository-wide code, data migration, security and error audit, then owner-labelled TEST APK

## Relevant tracking
[P2 acceptance](https://github.com/manbir6-ux/TongCut/issues/3) and [P3 acceptance](https://github.com/manbir6-ux/TongCut/issues/6).

Source compilation passing is necessary but insufficient. Hardware validation has to compare pixels, output duration and audio sync against saved timeline state.


## 2026-10-19 Speed increment (verified source, pending rendered output)
- [x] VIDEO overlay constant playback speed 0.25×–4×: non-destructive timeline duration recalculation, source position mapping and trimmed source validation
- [x] Independent visual lane plan carries speed and Media3 compositor applies constant SpeedProvider on the source
- [x] ExoPlayer preview uses the saved speed and maps editor playhead into trimmed source position
- [x] Speed changes preserve relative motion-keyframe positions, undo/redo and persist in schema 3; schema 1/2 loads speed 1×
- [x] Unit tests for 2×, 0.5×, bounds, source cursor on splitting, saved draft migration
- [x] Source verification [CI 39208642257](https://github.com/manbir6-ux/TongCut/actions/runs/39208642257) **passed** Kotlin, JVM tests and Android Lint
- [ ] Test actual exported variable media codecs on Android for A/V sync and 0.25×–4× source speed
- [ ] Speed **curves** and ramp UI (this increment is *constant speed only*)
