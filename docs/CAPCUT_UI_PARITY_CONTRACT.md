# TongCut editor UI/UX visual acceptance contract

## Product target (user requirement)
Match the **CapCut Android 19.8.0 editor's interaction model, navigation hierarchy, screen composition, tool positioning, timeline behavior and ease of use** as closely as technically practical; independently implement it under TongCut branding. Do not ship a mismatched form-based page. Visual styling may use original TongCut assets, fonts and colours instead of duplicating copyrighted logos, illustrations, stickers, templates or proprietary UI artwork.

### Reference collection
- Reference version: user supplied CapCut 19.8.0 APK, package `com.lemon.lvoverseas`; it requires ABI/density splits not yet supplied. Static manifest and assets do not alone prove screen behavior.
- Before marking any screen as complete, capture reference screens and gesture videos from a legitimate installed CapCut test instance. Store a UI interaction inventory *not* vendor assets. Test portrait + landscape and small + large Android displays.
- Build golden tests/screen recordings for user flows. Mock screenshots cannot pass functional acceptance.

## Screen-level visual/behavior checklist
- [ ] Home: project grid, search/sorting, new project and recent drafts, relevant tools at top, clear account/privacy/settings routes.
- [ ] Media picker: albums/videos/photos, multi-select counts and chosen order, device SAF/media access.
- [ ] Main edit: dark immersive fullscreen/near-fullscreen canvas, correct Android inset treatment, uncluttered preview with zoom/pan gestures and play controls, persistent back/undo/redo/export.
- [ ] Multitrack timeline: fixed central playhead, preview/timecode, scrub, frame strips, waveforms, layers, contextual selection and handles, snapping, pinch zoom, long-press drag, multi-select. Genuine renderer, no dummy layers.
- [ ] Bottom toolbar: horizontally scrollable icon+label tools with expected Edit / Audio / Text / Stickers / Overlay / Effects / Captions / Filters / Adjust / Canvas / AI grouping; selected-clip-specific tools.
- [ ] Context sheets: trimming, split, keyframes, animation, masks, blending, speed curves, chroma key, transitions, audio envelopes, copy/paste effects, text styles; consistent gesture/back behavior.
- [ ] Effects/filters library: live render preview, strength sliders, category search and thumbnail previews, licensed or original assets.
- [ ] Audio editor: track waveforms, timeline-aligned mixing, beats, SFX/voiceover, undo, ducking, fade handles.
- [ ] Captions/text: rich typography, animated subtitle templates, safe-area snapping, SRT import/export, word timing, preview/export parity.
- [ ] Export: resolution, frame rate, bitrate/codec, HDR, destination, estimated size, progress and recovery.
- [ ] Advanced Settings: AI provider management with encrypted API credentials, model/capability lists, upload consent, budget and job status. Keep advanced model settings distinct from normal export tab.
- [ ] Project maintenance: autosave state clearly indicated, multi-project support, offline edits, restore/relink, settings and trash/archive policies.

## Visual quality gates
- Body text is not outlined or stroked; readable at system accessibility font sizes.
- System status and navigation bars never cover toolbar, preview or controls.
- Layout maintains correct touch target size (>=48dp), contrast, horizontal/vertical insets and consistent spacing.
- Navigation and editing gestures reproduce familiar muscle memory from reference editor while keeping original iconography/branding.
- No entire-screen vertical form; tool drawer and timeline scroll independently.
- Golden screenshot comparisons for each major flow and real rendered-media test for each visible control.
- Zero fake buttons or inaccessible controls. No feature considered completed until its action changes edit state, correct preview and exported result.
- Head-to-head on-device usability review, including beginner tasks and advanced multilayer tasks, before candidate release.

## Release restriction
Until ALL phases and feature/UI gates are verified, do not package APKs in CI, distribute test APK links to user, create public Releases or publish to store. CI may compile and execute unit tests; no assembleDebug/assembleRelease/bundle tasks are permitted. Restore packaging only by explicit final release change after acceptance gates have passed.

## Architecture implementation sequence
1. Move current Compose UI from monolithic `MainActivity.kt` into themed editor shell, timeline, toolbar, inspector, preview, project browser, shared state holder.
2. Add genuine multitrack model, action history and persistent autosave.
3. Build shared preview/export render graph; never show effects that only work after export without clearly marking limitation during development.
4. Add panels and tools from feature matrix, with tested preview and output behavior.
5. Implement AI Studio provider adapters and secure storage; integrate results into tracks.
6. Finish accessibility, reference-screen diffs, performance, device matrices and license audit before any final build.
