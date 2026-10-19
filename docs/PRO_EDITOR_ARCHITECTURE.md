# TongCut next-generation editor — development architecture (not a release)

Status: Design under evaluation. The existing v0.1 alpha is NOT CapCut-equivalent. Keep release artifacts gated until user-approved feature parity with reference CapCut Android 19.8.0 and additional functionality passes tests. Do not claim that reverse engineering a base APK exposes remotely hosted AI features.

## Reference audit
- User supplied CapCut_19.8.0.apk (base package com.lemon.lvoverseas); prior static analysis: 119,414,299 bytes; 29 DEX, 18,563 entries; split types base__abi/base__density required.
- This permits behavioral/format study, UI walkthrough comparisons and extraction of class/API signatures. DO NOT copy/distribute proprietary assets, native models, templates, icons, or code without separately-established rights.
- CapCut cloud AI, billing and models reside on external servers; API providers can supply independently implemented equivalents, but endpoints, credits and supported tasks vary.
- Keep a versioned acceptance spreadsheet for every discovered editing button/operation with screenshots/test videos and device test results. Do not mark implemented based solely on existence of an icon.

## Editing architecture
1. Kotlin + Compose UI shell, immersive landscape/portrait pro workspace and accessible high contrast controls. Main destinations: Projects, Editor, Library, AI Studio, Exports, Advanced Settings.
2. Nondestructive project data: Project, Canvas, MediaAsset, Track, ClipInstance, Layer, SourceTrim, Transform, Mask, KeyframeTrack, Transition, EffectStack, AudioBus, SubtitleTrack, ExportPreset. Stable GUIDs and schema migrations.
3. Editor action/event model: immutable operations and reversible CommandStack (undo/redo); autosave transaction queue; restore from crash without lost edits.
4. Time model: int64 microseconds, sampled frame/timebase mapping, source vs timeline timestamps, out points, variable fps, audio PTS; never use rounded seconds to drive cut points.
5. Multi-track timeline: precise pointer gestures, horizontal pixel/second scale, virtualized thumbnails/waveforms, playhead, snapping, markers, trim handles, split, ripple, gap handling, reordering and grouped layers.
6. Playback/render parity: effect-graph compiler shared by preview and export. Preview in OpenGL ES/Vulkan where available; adaptive proxies/caching. Composition renderer and optimized codec decode-encode with fallback.
7. Audio subsystem: sample-accurate mixing and envelopes, effects, side-chain ducking, recording and voiceover; audio-video timestamp synchronization and latency compensation.
8. Text/stickers/effects: offline shader packs with clear licenses, animated typography, SRT/ASS parser, masks, blending, curves, LUTs, chroma and layered captions. Add local model processing where usable.
9. Export services: persistent WorkManager foreground job, progress, cancellation and recovery, hardware codec capability checks, output metadata and target aspect presets. Profile energy/thermal performance.
10. License architecture: do not combine GPL-3/AGPL-3 code into TongCut MIT repo without explicit copyleft relicensing and third-party notice review.

## Candidate upstream evaluations (not imported yet)
- LibreCuts (MIT): Android editing UI, masks, overlays, chroma, audio, non-destructive project format; inspect architecture, current maintainability and third-party binary licensing before reuse.
- trinity (Apache-2.0): Kotlin/C++ engine, older Android Studio/NDK/FFmpeg dependencies require substantial modernization and codec-license review.
- CapCraft Android (AGPL-3.0-or-later): community CapCut-style reimplementation, extensive claimed UI features, but adopting its code obligates license compliance and proper independent testing; do not infer feature parity from its README.
- Open Video Editor (GPL-3.0): small editor; copying into MIT repo incompatible without changing project terms.
Any third-party code reuse must be attributed and reviewed. Source availability != permission for uncredited copying.

## Advanced AI model API settings
- Provider registry: provider id/name, API base URL, transport type (async job, sync, polling/webhooks), supported task types, model list, per-task configuration and verified capability flags.
- Plugin adapters: structured capabilities (text-to-video, image-to-video, video-edit-to-video, upscale, inpaint, object-removal, cutout, subtitle, translate, text-to-speech, dubbing, music), not assume a universal OpenAI-compatible video interface.
- Credentials: Android Keystore-wrapped encrypted local storage; NEVER commit keys or print them in logs; optional secure self-hosted proxy for APIs that require secrets not exposed to mobile clients.
- Media privacy: never upload media without affirmative user action or pre-approved job settings; show remote vendor identity, intended data use and size. Cancel, retry, rate limits, timeout, upload resumability and lifecycle handling.
- Credits: estimate cost before job, configure per-provider monthly caps and per-job approval, report actual use when available. Never silently auto-spend.
- Job orchestration: SQLite/Room persistent queue, input hashes, idempotency keys where supported, provider job IDs, progress statuses, expiry and asset cleanup.
- Result import: completed AI outputs import as new editable clips, tracks or assets with provenance metadata, allowing independent source comparison.

## UI requirement
- No stroked/outlined body text; system status/navigation bars must not overlap editor content.
- Full-bleed preview with fit/fill toggle; playhead and transport always reachable.
- Dedicated fixed-height, scrollable/zoomable multitrack timeline above stable horizontal tool shelf; panels and inspector draw from the bottom or side.
- Clear icon+label semantics, consistent icon sizing, haptics, 48dp touch targets and proper orientation/layout behavior.
- Save/undo/export visible without scrolling entire form. Advanced features accessed contextually, not as a giant page of sliders.

## Acceptance gate
- Exact CapCut 19.8.0 version-scoped feature inventory (including cloud-dependent availability) approved.
- Every included feature implemented, unit/UI/integration tests pass, verified with real output comparisons on several devices.
- Document deliberate product differentiators (custom AI provider adapters, budget controls, privacy, stronger export, clear ownership).
- Continuous performance/regression benchmarks pass; release signing, accessibility/security and privacy tests complete.
- Until all are complete: no release marked "full", "CapCut complete", or "better than CapCut"; alpha branch remains an experimental baseline.
