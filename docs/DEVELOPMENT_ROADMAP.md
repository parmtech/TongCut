# TongCut — phased development and release gates

**Goal:** Independent Android editor focused on verified non-AI, offline CapCut Android 19.8.0 workflows plus original TongCut editing tools, privacy and professional export controls. AI providers, cloud services and other network-dependent editing features are intentionally deferred to a separately scoped future P6 release.

**Do not publish an APK as the finished app until all parity and quality checks are complete.** Debug CI artifacts are for internal testing; owner requested no additional interim deliverables.

| Phase | Deliverables | Exit gate |
|---|---|---|
| P0 — Reference & foundations | Inventory CapCut 19.8.0 screens, split APK limitations, license audit, architecture, regression media | Feature matrix has test cases, owners, baselines |
| P1 — Project editor core | On-device autosave, recovery, undo/redo, split, reorder, proper edit UI, frame-aware timeline model | Tests for edits and draft restore; source preview/export still correct |
| P2 — True timeline | Persistent multiple independent layers (video/audio/text/stickers), thumbnails, waveform, edit gestures, frame-accurate scrub, snapping | Layer composite preview equals rendered output |
| P3 — Advanced edit | Keyframes, motion/transform, speed curves, reverse, freeze, blend, masking, chroma, transitions, tracking | Tests for every effect and transition |
| P4 — Audio studio | Separate audio tracks, voiceovers, ducking, volume envelopes, time/pitch, isolation & cleanup | A/V synchronization tests across frame rates |
| P5 — Visual/colour | HDR/SDR colour pipeline, curves/HSL/LUTs, 2D/3D effects, text/stickers, animated typography, templates | Screenshot/video comparisons on representative devices |
| P6 — Future AI/online studio (deferred) | User-entered provider adapters, secure keys, costs, jobs and approvals, only after the offline release | Separate owner-approved product scope, privacy/security review and vendor contract tests |
| P7 — Export & performance | H.264/H.265/AV1 where supported, FPS/bitrate controls, colour/HDR handling, robust resume/cancel | Device matrix benchmarks; playable files and no corruption |
| P8 — Security & polish | Accessibility, translations, telemetry opt-in, privacy tests, device and thermal performance | All critical bugs fixed, no data-loss regression |
| P9 — Offline release audit | Item-by-item acceptance of in-scope non-AI/offline CapCut-like workflows, original TongCut tools, and owner-requested improvements | No unverified in-scope features; owner-approved signed production release. Deferred P6 is not a current release gate. |

## Current progress
- ✅ Existing **v0.1-alpha on main:** basic video import, sequential preview, trim, simple filters/crop/MP4 export (tested on owner's Android device).
- ✅ Independent reference architecture and an issue-based full feature checklist.
- 🚧 P1 implemented incrementally on `development/capcut-parity`; do not confuse branch CI with a final release.
- ⬜ Remaining phases pending. The current release deliberately targets verified offline/non-AI workflows; it does not claim feature-for-feature parity with cloud, AI, region-gated, or regularly updated CapCut services.

## Engineering rules
- Use permissively licensed upstream *only after checking license compatibility and actual code*, with attribution. GPL/AGPL use triggers different distribution obligations; do not paste proprietary CapCut binaries/assets into this repository.
- Make every new feature actually edit the project/render, not just display a button.
- Every merge needs unit test and build, manual device test for codec and rendering changes, plus migration check of older saved drafts.
- Keep the current release offline-first, minimize permissions, and do not add network-dependent editing features. If/when the future P6 AI scope is authorized, require explicit upload consent and encrypted credentials.
- AI provider APIs are distinct; each adapter must check supported tasks, rate limits, job status and budget. No unsupported universal 'any API works' promises.
- Use issues/PRs and tests to track each phase. No deceptive "100% ready" release claims.
