# P2 Multitrack timeline — implementation log (IN PROGRESS)

## Implemented on development/capcut-parity
- Immutable, non-destructive LayerTimeline model with independent VIDEO, AUDIO, IMAGE, TEXT, STICKER lanes.
- Layer records with stable IDs, timeline start and duration in microseconds, media source in-points, optional known source duration, typed text, track visibility/lock/mute state and z-order.
- Independent lane overlap (e.g. titles and audio above video), validation against accidental same-lane collision, split/move/resize/delete and active-layer lookup.
- Rational timebase helper for NTSC 30000/1001 fps and integer frame boundary snapping, avoiding floating-point drift in persisted edits.
- Version 2 atomic ProjectStore snapshots persist timeline state using LayerTimelineCodec; schema 1 legacy projects open with an empty layer timeline. Existing clips stay present as primary video track during migration.
- Unit tests for NTSC frame mapping, overlap, split, media source bounds, locked tracks, round-trip codec, and malicious/corrupt references.
- Explicit export guard: until a shared renderer can render all layered content, an edit that contains overlay layers will not silently export only the primary video.

## P2 acceptance criteria still NOT complete
- [x] Source timeline thumbnails sample distinct source timestamps via Android's MediaMetadataRetriever with bounded in-memory cache (device testing pending).
- [ ] Multi-track UI: independent draggable video/audio/image/text lanes, lock/mute/solo, visible layer selection.
- [ ] Native audio waveform generation, caching, zoom and snapping gesture accuracy.
- [ ] Shared composited video preview and exported video output for all supported layer types.
- [ ] Independent text/image/video overlays with correct simultaneous blending and coordinate transforms.
- [ ] Sample-exact A/V sync, audio mixing, project FPS and colour handling.
- [ ] Performance optimizations and on-device tests with portrait, landscape, HDR and long clips.
- [ ] Undo/redo and autosave verification across every layered operation, including process death.
- [ ] Head-to-head CapCut-like timeline interaction audit and visual regression on real devices.

## Caveat and release gate
This is **P2 model + persistence foundation only**, not phase completion. Legacy sequential export is maintained for projects with zero new layers. Never mark a button complete unless both preview and final export perform it. The owner prohibited APK packaging until all phases and acceptance gates finish; automated CI runs compile and JVM tests only. P1 also remains open pending physical-device verification.


## Phase 2 increment: editable text and audio lanes (19 October 2026)
- [x] Interactive Compose text/audio lanes in timeline; persistent project model on add/edit/move/duration/delete/hide.
- [x] Text add/edit at playhead; stored on microsecond timeline; undo/redo and autosave use immutable EditorDraft snapshots.
- [x] Android SAF audio import with persistable URI grant, source duration retrieval, bounded project placement. Audio mixing/output is **NOT** implemented.
- [x] Preview displays time-gated text overlay based on the same timeline model used by export.
- [x] Experimental Media3 TextOverlay burn-in path; uses constant text bitmaps with alpha-gated visibility rather than empty-size bitmaps.
- [x] Explicitly reject unsupported audio/video/image/sticker compositing on export; no silently missing tracks.
- [x] JVM operations tests and source CI updated to run unit tests plus lint, without APK packaging.
- [ ] Real-device verify frame-by-frame text video export after caption start/end, multi-caption overlaps, text legibility and varied aspect ratios; source-only tests do not prove graphical output.
- [ ] Simultaneous timeline audio playback and audio mixing export with volume, ducking and precise sync.
- [ ] Preview/export typography and positioning parity, transitions, images, true compositor for stacked video overlays and live gesture editor.
- [ ] Genuine waveform preview, track actions, draggable layers with snapping, automated UI/end-to-end video output tests.
**Do not mark P2 complete** until all of the above gates and P1 device regression checks pass.


## P2 increment — multiple audio layers (2026-10-19)

- [x] Build deterministic audio composition schedule from timeline model with independent lanes, silence gaps, source trims, and exact microsecond placement math
- [x] Assemble Media3 composition with separate audio-only sequences, mixer with primary video/audio and up to 8 unmuted audio lanes
- [x] Respect per-lane mute state during export; hidden lane visibility is a UI concern, not an audio mute
- [x] Add clock-following on-device audio preview players with seek resynchronisation; decoder timing is not guaranteed sample-accurate and needs device testing
- [x] Add tests for overlap across lanes, gaps, timing, mute, zero-source and out-of-bounds media
- [x] Explicitly reject unsupported visual (video/image/sticker) overlay effects rather than silently dropping them
- [x] Avoid hard-crashing if an older project contains more audio lanes than the preview limit; surface a warning instead
- [ ] Device verification: import music and voiceover, listen while scrubbing, check audio sync, test different sample rates, silent videos and mono/stereo files
- [ ] Sample-level latency compensation, per-track gain and ducking; audio-processing graph remains incomplete
- [ ] Robust export regression across device encoders, long duration, effects, source media with no audio and hardware thermal limits
- [ ] Precise text-preview/export appearance parity and real multi-video compositing
- [ ] Rendering pixel/audio tests and screenshot comparisons with reference editor

**Status:** Audio mixing integration is an initial feature implementation with source-level tests only. P2 remains OPEN; P1 has device acceptance tasks pending. No new APK has been built or published in this increment.
