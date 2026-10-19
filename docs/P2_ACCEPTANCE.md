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
- [x] Separate video/photo overlay import, timeline placement, PiP preview and experimental Media3 visual composition
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
