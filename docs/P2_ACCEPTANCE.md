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
- [ ] Real per-frame thumbnails extracted at temporal offsets; current gallery thumbnails repeat one poster per clip.
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
