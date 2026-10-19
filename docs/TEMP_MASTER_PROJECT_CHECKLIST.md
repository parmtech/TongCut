# TongCut — TEMPORARY MASTER DEVELOPMENT AND ACCEPTANCE CHECKLIST

**Status:** ACTIVE / INCOMPLETE — DO NOT DELETE YET
**Branch:** development/capcut-parity
**Last reconciled:** 2026-10-19
**Scope:** P0 through P9; independently implemented CapCut Android 19.8.0 reference workflows plus TongCut improvements.
**Owner rule:** Work on the earliest unresolved dependency first. Do not call a phase completed merely because a source file, button, unit test, emulator test, or APK exists.

> This is a TEMPORARY execution ledger. DELETE THIS FILE ONLY after ALL P0–P9 items below are checked with test evidence, all GitHub acceptance issues are closed and the owner has signed off the installed production app. Never delete because a test APK builds or one phase is done. Keep historical CI evidence in issues/commits after deleting the ledger.

## How to maintain this file

1. Use stable IDs, e.g. P2-06. In each development turn pick open IDs, implement actual behavior, and add tests.
2. [x] means **the stated deliverable/verification has actually passed**. [ ] means incomplete, unverified, blocked, or requires owner device evidence.
3. Each [x] implementation may still have a separate [ ] device-acceptance item. Never confuse source availability with phase acceptance.
4. For every checked item provide proof in the commit message, PR, GitHub issue, test log or adjacent note; record reproducible devices/codecs/test inputs.
5. After a failed test, leave the relevant item unchecked and record the failure; fix it, rerun and link the successful run.
6. Do not silently drop layers, edits, audio or unsupported effects from export. Surface an actionable error.
7. Use synthetic media in public CI. Do not upload the owner's private recordings/photos/screenshots, tokens, signing keys or credentials.
8. Maintain backwards-compatible project schemas and undo/redo through every edit change.
9. Use GitHub issues #1 (product parity), #2 (P1), #3 (P2), #4 (P6), #5 (UI) and #6 (P3) as supplementary evidence. This file is the cross-phase index.
10. The P2 owner TEST APK was built and passed CI; physical phone acceptance remains open. The owner NOW requests: **finish P3 completely and verify its effects BEFORE beginning P4 audio studio**. P4 tasks remain queued, not started. A successful source build is NOT a P3 completion certificate.
11. For P3, document implementation and verification separately: editor control + persistence + GPU export + mixed-media Android pixel test + owner hardware acceptance for each advanced effect.

## Phase completion dashboard

| Phase | Implementation | Device / final acceptance | Status |
|---|---|---|---|
| P0 Reference/foundations | Architecture and initial source inventory | Complete, legally safe feature-by-feature acceptance inventory | OPEN |
| P1 Project editor core | Project catalog, snapshots, history, relink, migration | Real phone restart/relink/edit/export/migration | OPEN |
| P2 True layered timeline | Source largely implemented; several emulator MP4 tests passed | Preview/export parity, real-phone mixed tracks, codecs/performance/accessibility | OPEN — PRIORITY |
| P3 Advanced edit | Keyframes, speed, PiP freeze, photo chroma/masks partly working | Reverse, video masks, blending, transitions, tracking, full parity | OPEN |
| P4 Audio studio | Independent audio lanes and volume in P2 | Voiceover, envelopes, ducking, timing/pitch, noise tools | OPEN |
| P5 Visual/colour | Basic looks and some photo/text effects | HDR, colour controls, licensed visuals/assets, all render parity | OPEN |
| P6 AI studio | Adapter and security architecture | Connected providers, consent, jobs, privacy, contract tests | OPEN |
| P7 Export/performance | Experimental H264/Media3 export | Codecs, FPS/bitrate, resume, device matrix, stress tests | OPEN |
| P8 Security/polish | Initial permissions and local storage guards | Security review, accessibility, privacy and stability | OPEN |
| P9 Final parity/release | Requirements recorded | Full reference-feature acceptance and owner-approved signed release | OPEN |

## P0 — Reference inventory, project foundations

- [x] **P0-01** Independent Android/Kotlin/Compose/Media3 architecture documented; avoid copying proprietary CapCut code/assets.
- [x] **P0-02** Initial CapCut 19.8.0 comparison notes and screen reference documents exist.
- [ ] **P0-03** Capture complete *verified* Android 19.8.0 screen/gesture feature inventory, version/device/build details, offline vs cloud distinction, and reference results.
- [ ] **P0-04** Audit reference split APK limitations, third-party codec/effect licenses, and reusable upstream libraries before import.
- [ ] **P0-05** Map each reference operation to independent implementation ID, expected input, UI, preview, export, error case and reproducible test.
- [ ] **P0-06** Establish legal/asset notices, synthetic regression-media catalog and owner-approved parity scope.
- [ ] **P0-07** Owner signs off scope and reference workflows; close outstanding P0 comparisons.

## P1 — Project editor core (dependency of P2)

- [x] **P1-01** Atomic multi-project draft save/load with indexed recovery and backwards migration exists in source.
- [x] **P1-02** Non-destructive main-sequence split/trim/reorder and bounded undo/redo implemented; JVM tests exist.
- [x] **P1-03** Missing primary media detection and user relink implemented in source.
- [ ] **P1-04** Real-device cold restart / force-stop restores all projects, selected project and SAF access or clear relink route.
- [ ] **P1-05** Legacy v0.1 update/migration preserves source media, trims, history and ability to export; never overwrite old app in testing.
- [ ] **P1-06** Corrupt project/index/interrupt-write fault injection and failure recovery on multiple Android versions.
- [ ] **P1-07** Main-track split/reorder/trim/undo/redo preview and output agree across silent / mixed-codec / portrait media.
- [ ] **P1-08** Project import/export/save-as and portability with missing-source reporting, stable schema and secure URI handling.
- [ ] **P1-09** Fast scrubbing, repeated switching, external permissions revoke, rotation and process death cause no stale async write or data loss.
- [ ] **P1-10** Acceptance-tested Home projects, gallery selection, accessibility/insets and UI workflows with owner; close issue #2.

## P2 — Non-destructive multi-layer timeline (CURRENT PRIORITY)

### P2 implementation and editing

- [x] **P2-01** Typed video/photo/audio/text tracks, immutable IDs, z-order, clip trim/source anchors, non-overlap rules and JSON migration implemented.
- [x] **P2-02** Multitrack timeline ruler, thumbnails, editable lanes, zoom/scroll and central playhead implemented in source.
- [x] **P2-03** Independent video/photo/audio/text import, positioning, hide/lock, mute/gain controls and drag-to-move with frame/magnetic snapping in source.
- [x] **P2-04** Split, left/right trim handles and undoable immutable layer edits (photo/text remain static, video/audio preserve source trim) in source.
- [x] **P2-05** Audio waveform source decoding; entire long source covered by bounded sampled windows. NOT full sample-perfect transient coverage.
- [x] **P2-06** Audio lane scheduler, silent gaps, bounded lane count, per-track gain, preview players and Media3 mixed export in source.
- [x] **P2-07** Time-gated captions and bounded static photo overlays, motion/fade effects, composited into MP4 output in source.
- [x] **P2-08** Native video PiP GPU compositor with corrected reverse input order, transparent gaps, three visual lanes and alpha/slot policies implemented.
- [x] **P2-09** Shared stable screen slots between Compose preview / PiP video / photo effects and preserved freeze photo slot in project schema.
- [x] **P2-10** Overlay-media relink, source trim preservation, codec errors surfaced rather than dropping overlays.
- [x] **P2-11** Baseline Android API 35 synthetic PiP encoded-pixel tests have passed, including 2× speed and freeze at earlier revisions.
- [x] **P2-12** Android API35 synthetic *combined* export video/photo/caption/two 44.1/48 kHz audio tracks tested and passed: https://github.com/manbir6-ux/TongCut/actions/runs/39226856013
- [x] **P2-12a** Simultaneous dual VIDEO PiP + video-only base mixed with external audio added and **11/11 synthetic emulator tests passed** on source a96ae48c: https://github.com/manbir6-ux/TongCut/actions/runs/39228992713
- [x] **P2-12b** Per-export photo bitmap cleanup on Media3 GL release implemented in 8db31fb1; source CI passed: https://github.com/manbir6-ux/TongCut/actions/runs/39229357315
- [x] **P2-12c** Audio-preview codec failure quarantine and retry-on-relink implemented in 837bb9b5; source [SUCCESS](https://github.com/manbir6-ux/TongCut/actions/runs/39230007295), emulator [SUCCESS](https://github.com/manbir6-ux/TongCut/actions/runs/39230007112). Real-device failure recovery still requires P2-20.
- [x] **P2-13** Audio preview player resource-release path hardened on prepare exceptions; source CI passed. Evidence: https://github.com/manbir6-ux/TongCut/actions/runs/39227459234

### P2 acceptance — DO NOT CLOSE UNTIL ALL CHECKED

- [x] **P2-14** P2 APK source at 536b6e49 (same Kotlin code as 837bb9b5) passes 184/184 JVM tests, Android Lint 0 errors / 22 warnings and assembly [QA run](https://github.com/manbir6-ux/TongCut/actions/runs/39230378258); latest code-specific 837bb9b5 passed [source checks](https://github.com/manbir6-ux/TongCut/actions/runs/39230007295) and [11/11 API35 instrumentation tests](https://github.com/manbir6-ux/TongCut/actions/runs/39230007112). The release-workflow and documentation commits after 837bb9b5 do NOT modify Kotlin media code.
- [ ] **P2-15** Owner Android handset: one export containing base video + 2 VIDEO PiP tracks + photo/chroma/mask + 2 captions + at least 2 mixed audio tracks; inspect actual MP4 pixels and audible tracks against preview.
- [ ] **P2-16** Quantitative preview/export parity: photo/PiP size and anchors, text font/placement, fades, transparency, z-order, rotation and timing for SOURCE, 16:9, 9:16, square and portrait source.
- [ ] **P2-17** Audio onset and gain measured within one video frame for AAC/MP3, 44.1/48 kHz, muted/hidden source, different clip trims; ensure no silent missing lane.
- [ ] **P2-18** Variable frame rate, 23.976/29.97/30/60fps and rotated/portrait/landscape source tests; progress, duration, seek and edit-clock parity.
- [ ] **P2-19** SDR/HDR (HLG/PQ where supported) colour/alpha/image EXIF/crop test; avoid washed-out or corrupted output and explain codec-specific failures.
- [ ] **P2-20** Multiple hardware decoder contention, unsupported formats and low-end phones degrade safely with clear errors; no system freeze or silent clip omission.
- [ ] **P2-21** Long recordings (>90s), very long video/audio and sparse audio transients: representative waveform accuracy and smooth scrolling, memory/cancel protection.
- [ ] **P2-22** Force-stop, background, app rotation, permission revoke/relink and restart retain edit changes, independent layer source URIs and project history.
- [ ] **P2-23** Session/stress: 5–20min timeline, maximum tracks, rapid seek/undo, thermal/memory, slow filesystem and cancellation leaves no orphan/empty MediaStore output.
- [ ] **P2-24** UX: touchscreen trim gestures on independent lanes, hit areas >=48dp, accessibility font sizes, insets/RTL/tablet, no overlaps and reference workflow parity.
- [ ] **P2-25** Automated instrumentation for preview/output shape/anchor comparison, project recovery, media revoke handling and combined audio+video *on real hardware*, not only emulator.
- [ ] **P2-26** P1 dependencies accepted; reproduce full device test checklist and close #3 only after signed-off results.
- [x] **P2-27a** Built **isolated P2 TEST APK** from 536b6e49 with 184/184 unit tests, Lint 0 errors, ZIP/DEX/Manifest integrity, package check: au.parmtech.tongcut.p2test. [QA](https://github.com/manbir6-ux/TongCut/actions/runs/39230378258), [artifact](https://github.com/manbir6-ux/TongCut/actions/runs/39230378258/artifacts/12176059405); APK 68,000,066 bytes, SHA-256 f09b6619ff3956fb24c9723c13eafd72d7f89b8bcd50a3b606933f338b8cbce7.
- [ ] **P2-27b** Install on owner's phone, verify Android app opens, imported media permissions, simultaneous mixed-media export and recovery. APK is for diagnostic acceptance, NOT evidence Phase 2 is complete.
- [ ] **P2-28** Record owner hands-on acceptance (device model, Android version, samples and reproducible results); then and only then mark P2 complete.

## P3 — Advanced edits and effects

- [x] **P3-01** Motion keyframe model and editor graph for position, zoom, opacity, rotation with LINEAR / SMOOTH / HOLD interpolation and undoable operations.
- [x] **P3-02** Constant and source-clock step-ramped VIDEO PiP speed with speed graph, editing presets and exported-media source clock.
- [x] **P3-03** VIDEO PiP one-second freeze via captured persistent frame with original source continuation; emulator smoke test.
- [x] **P3-04** Photo-only circle/rounded/wipe masks, feather, keyed green/blue colour and timeline fades in preview/export.
- [ ] **P3-05** True reverse video + reverse audio with seek/cache/cancel/codec handling and MP4 acceptance.
- [ ] **P3-06** Variable-speed audio time/pitch correspondence, step-to-continuous ramp curves / editable easing handles and export-accurate timing.
- [ ] **P3-07** Full VIDEO alpha masks, freeform/custom geometry, animated feather and preview/export parity still pending.
- [ ] **P3-07a** Circle/rounded/wipe VIDEO matte applied to individual Media3 PiP inputs before compositor; code/UI/persistent schema at [25f2d803](https://github.com/manbir6-ux/TongCut/commit/25f2d8038b2fb3fdc8be4cd08c5d470ee893a794). Source test failed only because a pre-existing test assumed VIDEO mattes must be rejected; corrected at b1c6497a. Await current source+device pixel CI before checking.
- [ ] **P3-08** GPU video blend modes (multiply/screen/overlay/etc.) with exact preview and export.
- [ ] **P3-09** Full VIDEO chroma key with sampled colour, refined edges/spill suppression and preview/export parity still pending.
- [ ] **P3-09a** GPU per-frame VIDEO chroma (green/blue, tolerance/feather) implemented using a Media3 GlEffect with source-level save/undo controls and synthetic red-object/green-background MP4 pixel test. [Commit 25f2d803](https://github.com/manbir6-ux/TongCut/commit/25f2d8038b2fb3fdc8be4cd08c5d470ee893a794), [source CI rerun](https://github.com/manbir6-ux/TongCut/actions/runs/39235430235), [emulator run](https://github.com/manbir6-ux/TongCut/actions/runs/39235077668) both pending at note. This effect is currently EXPORT-ONLY with an editor warning; don't mark P3-09 complete until matching live preview and device acceptance.
- [ ] **P3-10** General seamless transitions/crossfades with valid overlap/duration, primary footage handling and audio timeline policy; not complete.
- [x] **P3-10a** VIDEO PiP source-over red-to-green dissolve implementation and **encoded MP4 pixel acceptance PASSED** after fixing actual Media3 input front/back order. [Fix commit 6aa2ed1b](https://github.com/manbir6-ux/TongCut/commit/6aa2ed1b658a7c89bb577f7779e3ae4794f73d99), [source tests and Lint passed](https://github.com/manbir6-ux/TongCut/actions/runs/39233847439), [Android emulator pixel run passed](https://github.com/manbir6-ux/TongCut/actions/runs/39233847424). The previous attempt at e0d0cbb0 FAILED a midpoint pixel test; fixed by fading the actual front-most track.
- [ ] **P3-10b** Main-sequence transitions, audio crossfade/onset continuity, multi-format HDR/VFR, live-preview/device comparison and user-configurable duration/curve still pending.
- [ ] **P3-11** Object/face/camera motion tracking with confidence, editable motion keyframes and failure handling.
- [x] **P3-12a** Immutable clip duplicate, time-safe source slip, same-lane ripple and multi-clip group-shift editing primitives with toolbar controls for duplicate/slip/ripple; preserves masks/fades/speed ramps and undo/redo. [Commit 4bd03f30](https://github.com/manbir6-ux/TongCut/commit/4bd03f3048ae789095498e5311221789d457cc8a), [source compilation/tests/Lint passed](https://github.com/manbir6-ux/TongCut/actions/runs/39232116313).
- [ ] **P3-12b** Multi-selection/group gestures and full Android phone editing gesture acceptance; cross-kind selection and UI usability still pending.
- [ ] **P3-13** P3 full API29–35/device/codec pixel tests, security audit, migration, cancellation, background, performance and preview/export parity.
- [ ] **P3-14** Owner accepts P3 end-to-end. Close issue #6 only with per-effect evidence.

## P4 — Audio studio

- [x] **P4-01** P2 groundwork: multiple local independent audio lanes, track gain, timeline placement and mixed export.
- [ ] **P4-02** Detach video audio, multitrack editing, split/trims, file/library music and editable SFX workflow.
- [ ] **P4-03** Local voiceover capture, monitoring, waveform alignment, permission and interruption handling.
- [ ] **P4-04** Keyframed gain envelopes, fades, music ducking/side-chain and automatic loudness guard.
- [ ] **P4-05** Time-stretch/pitch, clean voice/noise filtering, equalization and channel layout handling.
- [ ] **P4-06** PCM-/frame-alignment tests for source sample rates, seek, offsets, gaps, drift and exported mix; low-end decoder tests.

## P5 — Visual, colour, text, templates

- [x] **P5-01** Basic grayscale/invert/warm/cool looks, cropped canvas settings and simple text/photo overlay prototype exist.
- [ ] **P5-02** HDR/SDR colour-managed render and displays; exposure/contrast/white balance/HSL/curves.
- [ ] **P5-03** LUT import/apply with safe size bounds, presets, strength and GPU preview/export parity.
- [ ] **P5-04** Blur/sharpen/grain/stabilisation/crop/flip and layered original visual effects with tests.
- [ ] **P5-05** Real editable stickers, texts, font/typography, animated templates, titles and licensed resource browser.
- [ ] **P5-06** SRT/caption import/edit/export, multilingual subtitle timing and optional offline auto-caption UX.
- [ ] **P5-07** Original/licensed templates, export visuals and screenshot/video comparisons on multiple devices.

## P6 — AI studio and APIs

- [ ] **P6-01** User-selectable provider registry, secure settings, base URL/model/task capabilities; no claimed universal schema.
- [ ] **P6-02** Keystore-backed credentials; no token leakage to log, storage backup, URL or repository.
- [ ] **P6-03** Explicit consent to upload private media, vendor/data disclosure, privacy and approval.
- [ ] **P6-04** Supported adapters for actual providers: text/image/video/audio/upscale/subtitle tasks with contract tests and robust errors.
- [ ] **P6-05** API job queue with idempotency, timeout, retries, cancellation, cost/budget caps, rate limits and persistent status.
- [ ] **P6-06** Generated assets import as editable tracks with provenance; fallback/offline project preserved.
- [ ] **P6-07** Security/privacy/cost and UX acceptance, supported-provider test credentials managed outside GitHub; close issue #4.

## P7 — Export engine, format support, performance

- [x] **P7-01** H264/MP4 Media3 alpha export with gallery save/progress/cancel and limited tested synthetic overlays.
- [ ] **P7-02** H264/H265/AV1 capability checks and safe fallbacks; expose supported resolutions, FPS, codec, bitrate and audio settings.
- [ ] **P7-03** Device-tested 720p/1080p/4K presets (where supported); no ungrounded 8K claims.
- [ ] **P7-04** Correct aspect, frame pacing, HDR/SDR metadata, audio duration, colour and orientation across codecs.
- [ ] **P7-05** Background/persistent foreground job and resume/cleanup after interruption; no invalid partial gallery files.
- [ ] **P7-06** Large projects, >5 min 1080p, battery/thermal/memory and disk usage benchmarks; hardware-specific failure reporting.
- [ ] **P7-07** Independent output validation via external players and file/stream probe on representative Android devices.

## P8 — Security, accessibility and stability

- [ ] **P8-01** Full static/data-flow audit: URI permissions, intent/provider handling, corrupted input, exception cleanup and dependency/license issues.
- [ ] **P8-02** Project and media privacy, Android backup rules, AI credentials, consent and network logging review.
- [ ] **P8-03** Accessibility: contrast, screenreader labels, large font, 48dp touch, keyboard, insets, RTL and rotation.
- [ ] **P8-04** Device matrix: Pixel/Samsung/low-end Android 10–latest, emulator and owner phone; audio, video, battery and thermal.
- [ ] **P8-05** Crash/ANR/perf monitoring with opt-in only; secure offline first behavior.
- [ ] **P8-06** P0–P7 regression tests, automation report archive, all critical and data-loss issues closed.

## P9 — CapCut reference parity, final release and retirement of this file

- [ ] **P9-01** Item-by-item owner-approved parity reference checklist verified with independently built TongCut functionality.
- [ ] **P9-02** UI screenshot/gesture matches for Home, gallery, editor, panels, tool rails and export on actual phones; close issue #5.
- [ ] **P9-03** All source/build/unit/Lint/instrumentation/integration/security/load tests green on final commit SHA.
- [ ] **P9-04** All phases P0–P8 signed off, issues #1–#6 and outstanding acceptance issues closed with documented evidence.
- [ ] **P9-05** Release signing, version/migration/upgrade test, privacy/policy/notices, crash rollback plan and owner approval.
- [ ] **P9-06** Install, edit, export, verify output, uninstall/upgrade regressions and final owner hands-on signoff.
- [ ] **P9-07** ONLY NOW delete this TEMP_MASTER_PROJECT_CHECKLIST.md via a commit. Keep permanent docs, CI run URLs and issues for historical evidence.

## Evidence and known limitations as of the checkpoint

- Latest source verification at commit 266d4e43: https://github.com/manbir6-ux/TongCut/actions/runs/39227459234 — PASS.
- Previous latest combined emulator verified revision 9d4dbaa0: https://github.com/manbir6-ux/TongCut/actions/runs/39226856013 — PASS.
- Same-HEAD 266d4e43 emulator: https://github.com/manbir6-ux/TongCut/actions/runs/39227459187 — PASS.
- Dual PiP + silent-base-audio synthetic emulator (11 tests): https://github.com/manbir6-ux/TongCut/actions/runs/39228992713 — PASS.
- Photo memory release 8db31fb1 source: https://github.com/manbir6-ux/TongCut/actions/runs/39229357315 — PASS.
- Latest Kotlin source 837bb9b5: https://github.com/manbir6-ux/TongCut/actions/runs/39230007295 — PASS; emulator https://github.com/manbir6-ux/TongCut/actions/runs/39230007112 — PASS (11/11).
- Isolated 536b6e49 P2 TEST APK: https://github.com/manbir6-ux/TongCut/actions/runs/39230378258 — PASS; 184/184 JVM, Android Lint 0 errors / 22 warnings, 68,000,066-byte APK, SHA-256 f09b6619ff3956fb24c9723c13eafd72d7f89b8bcd50a3b606933f338b8cbce7.
- A prior emulator failure at e99b18c7 was caused by a synthetic PNG fixture size gate and fixed in subsequent revision, not evidence of full runtime parity.
- Audio preview follows global playhead using separate ExoPlayers; it is NOT sample-accurate. Export uses Media3 and millisecond-aligned clipping.
- Long-audio waveforms estimate peaks by sampling distributed windows rather than decoding every frame.
- A successful emulator render does not verify the owner's real Android device or production readiness.
- Debug APKs, including any P2 TEST acceptance build, are diagnostic artifacts, not a release nor proof that P0–P9 are done.
