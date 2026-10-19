# Phase 2 end-to-end acceptance — 2026-10-19

**Current status: SOURCE IMPLEMENTED, DEVICE ACCEPTANCE PENDING. NOT COMPLETE.**
Work branch: `development/capcut-parity`. Latest source verification: [CI 39201885976](https://github.com/manbir6-ux/TongCut/actions/runs/39201885976) — **passed**. This is not a production release.

## Implemented in code
- [x] LayerTimeline typed video, photo, audio, text and sticker records with project JSON and undo/redo snapshots
- [x] Trimmed main track and timestamp-based timeline ruler with central playhead
- [x] Source video thumbnails at sampled times, scroll zoom, and audio waveform extracted from locally decoded PCM (90-second scan cap)
- [x] Drag-to-move layered clips with rational-frame snapping and 120 ms magnetic alignment to other clip boundaries
- [x] Lock/unlock, show/hide, mute and independent audio lane gain
- [x] Multi-track audio planning, silence gaps, separate audio decoding and experimental Media3 mix export
- [x] Time-gated text captions preview and experimental burn-in export
- [x] Separate video/photo overlay import, timeline placement and PiP preview; PHOTO post-composition export implemented experimentally. VIDEO PiP export is explicitly blocked until a correct layering compositor is implemented.
- [x] Guard against unsupported sticker rendering instead of silently omitting it
- [x] Unit tests for snapping, waveform bucket mathematics, visual/audio planning and serialization; Kotlin + Android Lint CI passed

## Real-device acceptance still required — no completion claim without these
- [ ] Device test on user's Android device: overlay movie + photo + captions + audio together, ensuring pixel/video duration/audio all present and aligned after export
- [ ] Verify multiple simultaneous video sources when phone supports available hardware decoders; handle low-end codec exhaustion gracefully
- [ ] Prove text placement/font and visual overlay PiP coordinates match between preview and exported MP4
- [ ] Verify audio alignment within 1 video frame across multiple source sample rates, 23.976/29.97/30/60 FPS VFR videos and mixed portrait/landscape content
- [ ] Verify waveform accuracy for audio clips longer than the current 90-second scan cap (currently incomplete)
- [ ] Verify crop/transforms, colour space/HDR, image orientation and alpha blending in the same render model
- [ ] Test no data loss on app background/force-stop/relaunch and revoked media permissions; relink non-primary media sources
- [ ] Verify long sessions, track count limits, memory pressure, low-end devices, battery/thermal load and accessible text sizes
- [ ] Implement and test split/trim gestures on every independent lane, video/image effects compositing and true preview/export parity
- [ ] Complete P1 acceptance tasks and UI comparison of the referenced CapCut workflows

## Suggested practical phone test
1. Create a new 15–20-second project with two source videos; trim and split the main video.
2. Add one overlay video (first 3–5 seconds) and one separate still photo (start ~8 seconds). Inspect PiP during playback.
3. Add an MP3 track in the middle; set it to 40% volume. Add a second audio clip and set it to 80%. Verify both can be heard.
4. Add two captions at distinct positions, drag one to a video cut and confirm it snaps.
5. Export at 720p; compare with preview and inspect saved MP4 in the phone's Files/Gallery.
6. Close app, reopen, undo/redo, change overlay timing and export again.
7. Report specific mismatch with screenshot, media duration, Android version and export error text; do not upload personal media to public repo.

## Release policy
Any developer/test APK must remain a separately named **TongCut TEST** build, never overwrite the owner's older app and never be called Phase 2 completed merely because it installs. No full-featured CapCut equivalent is claimed at this milestone.

## Visual compositor correction
A source-level review of AndroidX Media3's `DefaultCompositorGlProgram.drawFrame` confirmed that input frames are drawn in reverse index order; primary input (index 0) is drawn **last**, obscuring secondary opaque streams. As a result, native multi-video sequence composition could silently omit picture-in-picture video overlays when primary footage is fully opaque. The editor therefore **blocks export** while any visible VIDEO PiP layer is present.

Static IMAGE overlays are now applied via a separately time-gated `BitmapOverlay` as post-composition effects, on top of the opaque base, alongside captions. Images are decoded at bounded resolution to reduce memory use. This path still needs device testing for orientation, colour and timing.

- [x] Verify compositor drawing-order behavior from AndroidX source
- [x] Guard export against unsupported visible video/sticker overlays
- [x] Add post-composition alpha-gated bitmap photo effects for still images
- [x] Keep visual imports editable and previewable; do not claim mixed-video MP4 export works
- [ ] Implement hardware-accelerated video PiP compositor with verified primary/secondary z-order
- [ ] Verify actual photo + caption + mixed-audio MP4 export on a phone


## 2026-10-19: Experimental native Media3 video PiP export and emulator pixel gate
- [x] Source implementation: piP video lanes registered before the opaque main video (Media3 draws in reverse input order), project video base determines output size, generated VIDEO gap frames receive alpha 0, and photo/title post-effects are drawn after the videos.
- [x] Source implementation: all-visible-lane scheduling handles overlay clip timing, per-frame keyframe pose, image/title alpha, and mixed audio with separate independent sources.
- [x] New **CompositorVideoPlanTest** proves video lane ordering, transparent gap intervals, no photo-as-video registration, and range validation; source Kotlin compilation, JVM tests and Android Lint have passed.
- [ ] Real Android output **must** show visible PiP pixels at the correct times and no opaque rectangle outside a layer. The experimental emulator test is [P2 Internal Emulator Pixel Acceptance](https://github.com/manbir6-ux/TongCut/actions/workflows/internal-device-pixel-test.yml); run this and fix any failure before accepting P2.
- [ ] Multiple simultaneous videos, mixed portrait/landscape, audio+captions+photos, VFR and HDR codec matrices; seek/playback vs output pixel/timing parity.
- [ ] Correct PiP location and z-order across all supported aspect ratios and screen sizes; performance on limited devices.

**This update is not Phase 2 completion.** It increases coverage and explores the hardware path, but some acceptance steps depend on actual mobile codecs and exported video pixels. P3 acceptance is tracked in [issue #6](https://github.com/manbir6-ux/TongCut/issues/6).


## 2026-10-19 — long-audio waveform coverage and stable visual locations

- [x] Instead of truncating visual waveform peaks at the first 90 seconds, a new `WaveformScanPlan` scans **all** audio frames of shorter clips and samples 80 evenly spaced windows across the complete trimmed source of long recordings. Each long-media window is at most 750ms, so the decoder scans at most 60 seconds of distributed audio, keeping CPU and memory bounded.
- [x] `WaveformScanPlanTest` verifies full short-clip scan, 90s boundary, long 20-minute coverage, scan budget, trim offsets and overflow validation. [Successful source CI](https://github.com/manbir6-ux/TongCut/actions/runs/39219120154).
- [x] Added a shared `VisualOverlaySlots` model across live Compose preview, VIDEO compositor and photo post-effects to prevent visual repositioning when neighbouring clips start/end or tracks are hidden. A captured frozen still persists its source PiP slot through project JSON **schema 7**, backward compatible with 1–6.
- [x] Persisted PiP freeze timeline model, snapshot capture and VIDEO continuation; [API35 emulator exported-MP4 freeze test passed](https://github.com/manbir6-ux/TongCut/actions/runs/39220329025) with **5/5 synthetic device tests** including PiP, speed and chroma.
- [ ] Long-track waveform is a **sampled estimate**, not a full per-frame peak scan: sparse transients in unsampled intervals may be missed. Validate seek/PCM decoding across AAC, MP3, VBR, very long files and slow hardware before waveform acceptance.
- [ ] Latest stable PiP slot/source migration commit needs its own complete [source CI](https://github.com/manbir6-ux/TongCut/actions/runs/39221289717) and [Android emulator](https://github.com/manbir6-ux/TongCut/actions/runs/39221289722) results plus real-phone comparison.
- [ ] Real device media, frame-synchronization, long playback, project recovery, source permission revoke, HDR/VFR, resource usage, text/bitmap/video composites and UI parity remain OPEN.

**P2 remains OPEN.** Do not publish the owner-facing Phase 2+3 TEST APK until both full phase acceptance gates pass.
