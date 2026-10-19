# TongCut — TEMPORARY MASTER DEVELOPMENT AND ACCEPTANCE CHECKLIST

**Status:** ACTIVE / INCOMPLETE — DO NOT DELETE YET
**Branch:** development/capcut-parity
**Last reconciled:** 2026-10-20
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
10. The P2 owner TEST APK was built and passed earlier CI; physical phone acceptance remains open. **Updated owner direction (2026-10-20): prioritize implementation of features across all P3–P9 phases before comprehensive testing and bug-fix cycles.** Avoid repeated full QA campaigns during feature construction; use minimal compilation safeguards when practical. Keep every unverified acceptance item unchecked. Do not label an untested feature or phase complete.
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
- [ ] **P3-06** Full variable-speed audio time/pitch correspondence, continuous Bezier handles and audio/video preview/export parity still pending.
- [x] **P3-06a** AUDIO 0.25×–4× source-clock step speed ramps, source-aware preview/export, source trim/split and persistence implemented at 621220ed and model fixed in [db89ddea](https://github.com/manbir6-ux/TongCut/commit/db89ddea67bacbb73d8f6e861e73e72fc0bdd505). [Source CI PASS](https://github.com/manbir6-ux/TongCut/actions/runs/39245233612), [14/14 API35 emulator MP4 tests PASS](https://github.com/manbir6-ux/TongCut/actions/runs/39245233600), including encoded 2× audio audible for 1.5 s and silent to the 3 s base-video end. This is synthetic emulator evidence, NOT owner phone/pitch quality approval.
- [x] **P3-06b** AUDIO editable source-clock speed graph with keyframe change/move/delete, Hero/Pulse/Rush/Dream/Reset presets, undo and 3 new tests [commit 0b1f542d](https://github.com/manbir6-ux/TongCut/commit/0b1f542d3d53bcc192207a69fa72442ddbd317b7). [Source/JVM/Lint PASS](https://github.com/manbir6-ux/TongCut/actions/runs/39245783970); [14/14 Android emulator regression PASS](https://github.com/manbir6-ux/TongCut/actions/runs/39245784142). Verified source and synthetic Android render; owner-device UI gestures still unverified and remain P3-13/P3-14.
- [x] **P3-06c** AUDIO 2× pitch preservation regression: first decoded-PCM test [FAILED](https://github.com/manbir6-ux/TongCut/actions/runs/39246566130) when 440 Hz became 880 Hz; [fix 33993ec0](https://github.com/manbir6-ux/TongCut/commit/33993ec063b805b328749824a175106b4e94a3cf) uses SpeedParameters(provider, true). [Source CI PASS](https://github.com/manbir6-ux/TongCut/actions/runs/39247865880) and [14/14 Android emulator encoded-media tests PASS](https://github.com/manbir6-ux/TongCut/actions/runs/39247866062), including decoded-PCM pitch regression. Real-device pitch quality, codecs, onset, and preview parity remain in P3-06/P3-13.
- [ ] **P3-07** Full VIDEO alpha masks, freeform/custom geometry, animated feather and preview/export parity still pending.
- [x] **P3-07a** Circle/rounded/wipe VIDEO matte applied to individual Media3 PiP inputs before compositor; source setting/persistence/UI at [25f2d803](https://github.com/manbir6-ux/TongCut/commit/25f2d8038b2fb3fdc8be4cd08c5d470ee893a794), corrected test at b1c6497a, [source tests/Lint PASS](https://github.com/manbir6-ux/TongCut/actions/runs/39235430235) and [13/13 Android emulator MP4 tests PASS](https://github.com/manbir6-ux/TongCut/actions/runs/39235077668) including chroma video. Added separate CIRCLE, ROUNDED_RECT and LINEAR_WIPE decoded-H.264 MP4 mask pixel acceptance tests at [commit 0d76508b](https://github.com/manbir6-ux/TongCut/commit/0d76508b375d9e4b8a69a7a2db65da0856d65a8e); [emulator run](https://github.com/manbir6-ux/TongCut/actions/runs/39262540865) PENDING. Individual preview/owner-device acceptance remains open.
- [ ] **P3-07c** Feature-first implementation checkpoint (2026-10-20): ELLIPSE and DIAMOND masks, per-mask editable X/Y center and independent width/height, rotation, inversion and backwards-compatible project persistence added for both photo and VIDEO overlays. CPU photo matte and Media3 preview/export shader share geometry formulas. Commits [53f5bf8c](https://github.com/manbir6-ux/TongCut/commit/53f5bf8cff014888de8cf7fffff1fe720d30b507), [02e8ab40](https://github.com/manbir6-ux/TongCut/commit/02e8ab40509c169c1e1ad407526309a1129f6f64), [fe8daf22](https://github.com/manbir6-ux/TongCut/commit/fe8daf227990f29b6c0098fd453367c418a37881), [4170d096](https://github.com/manbir6-ux/TongCut/commit/4170d096c0356bc199f5d0c7c2f7fde32096010a), and follow-on invert-mask commits. Per owner's feature-first direction, no new comprehensive Gradle/emulator/device test campaign or final APK is claimed at this checkpoint. Build, persistence migration, and rendered-pixel preview/export parity remain to be verified before Phase 3 acceptance.
- [ ] **P3-08** GPU video blend modes (multiply/screen/overlay/etc.) with exact preview and export.
- [ ] **P3-09** Full VIDEO chroma key with sampled colour, refined edges/spill suppression and preview/export parity still pending.
- [x] **P3-09a** GPU VIDEO green/blue/custom chroma shader with tolerance/feather and saved/undoable clip settings. [Export MP4 test PASS 13/13](https://github.com/manbir6-ux/TongCut/actions/runs/39235077668), then [shared preview/export shader 16/16 API35 Android tests PASS](https://github.com/manbir6-ux/TongCut/actions/runs/39253664583). Further HDR, custom spill suppression, and physical device coverage are P3-09/P3-13.
- [x] **P3-09b** Live ExoPlayer GPU video chroma/mask and actual-window PixelCopy proof. Initial [preview failure](https://github.com/manbir6-ux/TongCut/actions/runs/39252688887) showed cyan RGB(1,255,255) over blue base caused by unpremultiplied transparent green RGB. [Shader fix d992785d](https://github.com/manbir6-ux/TongCut/commit/d992785df74ebb40fa100ef9e36f7deb253d0489) passed [source tests/Lint](https://github.com/manbir6-ux/TongCut/actions/runs/39253664671) AND [16/16 emulator preview/export pixel tests](https://github.com/manbir6-ux/TongCut/actions/runs/39253664583). Check limited to synthetic SDR and emulator; full real-device/HDR/per-mask parity remains open.
- [x] **P3-09c** Custom original-video source colour picker with tap coordinates, 512px bounded frame, clip persistence. [Implementation bb238185](https://github.com/manbir6-ux/TongCut/commit/bb238185347ab376f8956affdd28c0baa09ff9f7), [source unit/Lint PASS](https://github.com/manbir6-ux/TongCut/actions/runs/39253112068), [original-source green/red pixel test PASS as part of 16/16 emulator suite](https://github.com/manbir6-ux/TongCut/actions/runs/39253664583). EOF-safe sampling + state-key correction [6e6fd067](https://github.com/manbir6-ux/TongCut/commit/6e6fd0671d63521d17ef84ccf114e22657ac06d9) has [source check](https://github.com/manbir6-ux/TongCut/actions/runs/39254809567) PASS and [emulator regression](https://github.com/manbir6-ux/TongCut/actions/runs/39254809575) PASS; owner phone UX still P3-13.
- [x] **P3-09d** PHOTO/VIDEO chroma despill, persisted clip setting, shared ExoPlayer/Media3 GPU shader, CPU pixel matching and H.264 before/after checks. [Commit 2ba2ab33](https://github.com/manbir6-ux/TongCut/commit/2ba2ab33e3eea65bd7d4544c85f5858c38c821e5); [unit/Lint PASS](https://github.com/manbir6-ux/TongCut/actions/runs/39264660020), [Android encoded-MP4 PASS](https://github.com/manbir6-ux/TongCut/actions/runs/39264660371). Actual owner-device key-colour/edge quality remains unverified under P3-09/P3-13.
- [x] **P3-07b** VIDEO circle/rounded/wipe decoded-MP4 pixel tests: earlier wrong rounded sampling point failed in [39262540865](https://github.com/manbir6-ux/TongCut/actions/runs/39262540865), corrected mask tests in [commit 9928c3b6](https://github.com/manbir6-ux/TongCut/commit/9928c3b6defb20a8c75638d93839cc9c4d5dd88b), and [latest Android regression PASS](https://github.com/manbir6-ux/TongCut/actions/runs/39265308422). Do not infer physical phone/HDR acceptance.
- [x] **P3-09e** Chroma edge-feather controls and guarded editing in [d21c9934](https://github.com/manbir6-ux/TongCut/commit/d21c9934009ca2d8b81ab48a57114fa09521568e), independent edge/mask-feather JVM regression [c3605ac7](https://github.com/manbir6-ux/TongCut/commit/c3605ac7f53a60103259531ae78f351921cce118); [source CI PASS](https://github.com/manbir6-ux/TongCut/actions/runs/39266488235), [emulator CI PASS](https://github.com/manbir6-ux/TongCut/actions/runs/39266443202). Real-device edge-quality approval remains open under P3-13.
- [ ] **P3-09f** Source-frame chroma picker: safe timestamp math and malformed-duration overflow regression. On 2026-10-20, overflow-safe playhead conversion landed in [da2ed66e](https://github.com/manbir6-ux/TongCut/commit/da2ed66e1d7d992e6b5463e323e83f3d1f43a9e9), picker integration in [03765253](https://github.com/manbir6-ux/TongCut/commit/03765253e7404bbd5b690d2cb38b1cd66ac2115d), duration/playhead JVM regression cases in [4290f90f](https://github.com/manbir6-ux/TongCut/commit/4290f90f852c27280903357062071917588f8a60), and source-clock wraparound fix/tests in [9eaeada8](https://github.com/manbir6-ux/TongCut/commit/9eaeada852d902e8e5b9117bd8d29089bcdf7026) / [3c6dd84f](https://github.com/manbir6-ux/TongCut/commit/3c6dd84fbd14b66c319ae19bae57a4e140c01522). 19 isolated pure-Kotlin boundary assertions passed; full Gradle/JUnit, Android Lint, emulator MP4 and owner phone results on this HEAD are still UNVERIFIED. Do not check off until source CI and real-device picker/source-vs-preview parity evidence are recorded.
- [ ] **P3-10** General seamless transitions/crossfades with valid overlap/duration, primary footage handling and audio timeline policy; not complete.
- [x] **P3-10a** VIDEO PiP source-over red-to-green dissolve implementation and **encoded MP4 pixel acceptance PASSED** after fixing actual Media3 input front/back order. [Fix commit 6aa2ed1b](https://github.com/manbir6-ux/TongCut/commit/6aa2ed1b658a7c89bb577f7779e3ae4794f73d99), [source tests and Lint passed](https://github.com/manbir6-ux/TongCut/actions/runs/39233847439), [Android emulator pixel run passed](https://github.com/manbir6-ux/TongCut/actions/runs/39233847424). The previous attempt at e0d0cbb0 FAILED a midpoint pixel test; fixed by fading the actual front-most track.
- [ ] **P3-10b** Main-sequence transitions, audio crossfade/onset continuity, multi-format HDR/VFR, live-preview/device comparison and user-configurable duration/curve still pending.
- [ ] **P3-11** Object/face/camera motion tracking with confidence, editable motion keyframes and failure handling.
- [x] **P3-12a** Immutable clip duplicate, time-safe source slip, same-lane ripple and multi-clip group-shift editing primitives with toolbar controls for duplicate/slip/ripple; preserves masks/fades/speed ramps and undo/redo. [Commit 4bd03f30](https://github.com/manbir6-ux/TongCut/commit/4bd03f3048ae789095498e5311221789d457cc8a), [source compilation/tests/Lint passed](https://github.com/manbir6-ux/TongCut/actions/runs/39232116313).
- [x] **P3-12b** Cross-panel VIDEO/IMAGE/TEXT/AUDIO multi-select and group ±0.5s timeline move controls implemented at [543469e5](https://github.com/manbir6-ux/TongCut/commit/543469e59103077e8ab6c47aff4a45b08e49cda3); [source CI PASS](https://github.com/manbir6-ux/TongCut/actions/runs/39236062701) and [Android emulator regression PASS](https://github.com/manbir6-ux/TongCut/actions/runs/39236062725).
- [ ] **P3-12c** Direct multi-finger/group drag gesture handling, accessibility and full owner phone gesture acceptance still pending.
- [ ] **P3-13** P3 full API29–35/device/codec pixel tests, security audit, migration, cancellation, background, performance and preview/export parity.
- [ ] **P3-14** Owner accepts P3 end-to-end. Close issue #6 only with per-effect evidence.

## P4 — Audio studio

- [x] **P4-01** P2 groundwork: multiple local independent audio lanes, track gain, timeline placement and mixed export.
- [ ] **P4-02** Detach video audio, multitrack editing, split/trims, file/library music and editable SFX workflow.
- [ ] **P4-02a** Feature-first source: detach selected primary-video clip audio into a persistent, editable audio lane at the matching source trim and project time; source mute is saved, respected by preview and applied per item during export. Added JVM regression coverage for trim/source anchoring, sequential timeline placement, immutability, and duplicate-detach rejection. Gradle verification is pending because this workstation lacks Java/Gradle/Android SDK; phone sync/export and post-detach split/reorder parity remain unverified.
- [ ] **P4-03** Local voiceover capture, monitoring, waveform alignment, permission and interruption handling.
- [ ] **P4-03a** Feature-first source (2026-10-20): local AAC voiceover recorder, runtime microphone permission and Record/Stop/Discard toolbar wired to timeline audio import. Commit [780f3394](https://github.com/manbir6-ux/TongCut/commit/780f33942501309cc40725b87073c8daa978e57b). Physical microphone, app lifecycle, pause/resume, sync and exporter acceptance remain UNVERIFIED.
- [ ] **P4-04** Keyframed gain envelopes, fades, music ducking/side-chain and automatic loudness guard.
- [ ] **P4-04a** Feature-first source (2026-10-20): per-clip linear gain-envelope keyframes persist in schema 9, remain continuous through split/trim, drive the independent audio monitor and Media3 export `GainProcessor`, with editor controls. Ducking, automatic loudness protection and device/export acceptance remain unverified.
- [ ] **P4-04b** Feature-first source: editable 0.5s clip fade-in/fade-out actions authored as envelope points and rendered by the same preview/export paths. Device audibility, export ramp smoothness and interaction with custom curves remain unverified.
- [ ] **P4-05** Time-stretch/pitch, clean voice/noise filtering, equalization and channel layout handling.
- [ ] **P4-06** PCM-/frame-alignment tests for source sample rates, seek, offsets, gaps, drift and exported mix; low-end decoder tests.

## P5 — Visual, colour, text, templates

- [x] **P5-01** Basic grayscale/invert/warm/cool looks, cropped canvas settings and simple text/photo overlay prototype exist.
- [ ] **P5-02** HDR/SDR colour-managed render and displays; exposure/contrast/white balance/HSL/curves.
- [ ] **P5-02a** Feature-first source: persistent exposure, contrast, saturation and warmth controls for primary footage and VIDEO/IMAGE overlays; shared SDR GL math for video preview/export and matching bounded bitmap processing for photos; project-schema migrations and safe neutral defaults; JVM numeric, codec, matte and export-option regressions added. HDR/SDR colour management, encoded-pixel parity and device acceptance remain open; this revision has not yet been run by Gradle.
- [ ] **P5-03** LUT import/apply with safe size bounds, presets, strength and GPU preview/export parity.
- [ ] **P5-04** Blur/sharpen/grain/stabilisation/crop/flip and layered original visual effects with tests.
- [ ] **P5-05** Real editable stickers, texts, font/typography, animated templates, titles and licensed resource browser.
- [ ] **P5-05a** Feature-first source: original TongCut caption palette, bold and size controls; project schema8, editor and Media3 styled text integrated. Current files: `CaptionStyle.kt`, `LayerTimelineCodec.kt`, `LayerTools.kt`, `TimedTextEffects.kt`, `EditorWorkspace.kt`. UI/exact preview-export typography not verified.
- [ ] **P5-06** SRT/caption import/edit/export, multilingual subtitle timing and optional offline auto-caption UX.
- [ ] **P5-06a** Feature-first source: strict local SRT parsing, import as persistent TEXT clips with overlapping lanes, document export and interactive editor captions panel. Source [d9d654cd](https://github.com/manbir6-ux/TongCut/commit/d9d654cd4ae81505e48c53b672742a848a0f36ae) and editor integration [40762572](https://github.com/manbir6-ux/TongCut/commit/40762572b063aab2d136107ab5030717fabf3a0d). Real-device and boundary input audit still needed.
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
- [ ] **P7-02a** Feature-first source: device-reported H.264/H.265 encoder choices, explicit H.265 MIME selection, bounded bitrate presets, persisted export preferences, and user-visible Media3 fallback notice. AV1 is not exposed because current Media3 Transformer supported output types omit AV1; per-resolution encoder support, FPS/audio controls and actual codec fallback/pixel verification remain open. JVM policy tests added; Gradle not yet run.
- [ ] **P7-02b** Feature-first source: filter 720p/1080p/2160p short-edge choices using the selected codec's Android-reported encoder size capabilities at the actual project canvas/source aspect ratio; preserve resolution selection in the project; probe media dimensions and enumerate codec/size capabilities off the UI thread; withhold choices if SOURCE dimensions cannot be read and disable export when no codec/size pair is reported. Pure JVM geometry/policy regressions added. This is only a preflight capability hint—Media3 fallback, FPS/audio settings, source/device matrices, encoded-output validation, and Gradle verification remain open.
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
- [ ] **P9-02a** Feature-first editor workflow milestone: real selected-layer ID passed from `SequenceStrip` into `EditorWorkspace`, selected highlight, clip-specific quick edit rail, undoable Split/Trim/Duplicate/Keyframe/Mute/Delete, Back to global category toolbar, inspector shows selected clip first. Implementation [0b963ab5](https://github.com/manbir6-ux/TongCut/commit/0b963ab506eee6e5f13bbfda2fccd0a538b2480c); design contract [TONGCUT_UI_IDENTITY](TONGCUT_UI_IDENTITY.md). No claim of pixel-for-pixel CapCut imitation, no runtime acceptance yet.
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

