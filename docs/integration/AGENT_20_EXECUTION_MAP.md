# TongCut — 20 independent development branches

**State:** 20 GitHub branches created, execution TASKS QUEUED. Creating branches does **not** launch Codex workers. Work/Codex orchestration must explicitly start and track agents. No claim of running agents or completed features.

**Source of truth:** `development/capcut-parity`, not the old `main` alpha.
**Merge target:** `development/capcut-parity`.
**No force pushes, auto-merge, public release or production APK without review/QA.**
**Starting status:** development README describes experimental implementation; full P0–P9 acceptance is OPEN and recent source CI failures must be triaged.

## Exclusive branch/task ownership

| Branch | Responsibility | Canonical GitHub issue(s) |
|---|---|---|
| `agent/a01-home-gallery` | Home, media picker and visual identity | #7 |
| `agent/a02-timeline-gestures` | Timeline gestures, scrubbing and snapping | #8 |
| `agent/a03-project-recovery` | Project saving, restoration and migrations | #9 |
| `agent/a04-export-codecs` | Export engine and codec capability validation | #10, #22 |
| `agent/a05-keyframes-speed` | Keyframes, speed curves and fades | #11 |
| `agent/a06-audio-studio` | Audio tracks, voiceover, ducking and EQ | #12 |
| `agent/a07-caption-titles` | Captions, SRT, typography and text animation | #14 |
| `agent/a08-color-grading` | Color grading, filters, LUTs, curves and HSL | #13 |
| `agent/a09-masks-chroma` | GPU PiP/masks, chroma and visual-compositor parity | #18 |
| `agent/a10-reverse-freeze` | Reverse playback/audio/export and freeze | #19 |
| `agent/a11-face-reshape` | Face/body reshape foundations and landmark tracking | #15 |
| `agent/a12-effects-library` | Original effects and transition preset library | #21 |
| `agent/a13-stickers-templates` | Original stickers, photo effects and templates | #17, #21 |
| `agent/a14-media-performance` | Playback, media performance, stability, tracking feasibility | #24, #20 |
| `agent/a15-editor-ui` | Editor inspector, contextual controls and tool rail | #5 |
| `agent/a16-project-assets` | Media import, asset handling and photo relink | #17, #9 |
| `agent/a17-security-licenses` | Security, privacy and license checks | #25 |
| `agent/a18-test-automation` | Synthetic media tests, CI and hardware acceptance matrix | #16 |
| `agent/a19-accessibility-i18n` | Accessibility, languages and multiple screen sizes | #23 |
| `agent/a20-integration-release` | Cross-team interfaces, PR integration and release gates | #26 |

## Anti-conflict policy (enforced by reviewer, not GitHub itself)

1. Every worker must checkout its own branch; each PR contains only changes necessary for its issue.
2. **Shared hotspots require exclusive ownership**: `EditorWorkspace.kt` and `TongCutApp.kt` (A15 integration); `VideoExporter.kt` and `ExportService.kt` (A04); `ProjectStore.kt`, `ProjectLibrary.kt`, project and layer codecs (A03); `app/build.gradle.kts`, `AndroidManifest.xml`, `.github/workflows/*` (A20, except A18 on new dedicated QA workflows).
3. Other agents implement their features in separate package/source files and request an integration hook or interface from A20. **Do not edit someone else's hot files or silently overwrite an implementation.**
4. A20 must serialize hot-file merge work, review branch drift, merge one PR at a time, rerun relevant checks and preserve old-schema draft compatibility.
5. No mock/dummy features, false claims of tests or pseudo-CapCut assets. Original independent functionality; all required assets must have appropriate licensing.
6. Run targeted JVM/Lint checks in workers when suitable; integration must run clean tests, synthetic actual encoded MP4 checks, then owner phone QA. Successful compilation alone is NOT feature acceptance.
7. Face reshape is planned/prototype-only until implemented with visual proofs. Do not claim general object tracking, 4K, HDR or face beauty are working without export/device evidence.
8. Work allocation is concurrency-limited by actual plan/tool quota, CI resources and cross-file dependencies; **20 simultaneous active agents is a target, not guaranteed**.

## Codex/Work launch brief

Open this private repo in Work or Codex. Launch available coding workers on the 20 existing branches above, referencing the canonical GitHub issues. Before editing, fetch latest branch commits and inspect open PRs. Queue additional workers if tool concurrency is lower. Capture each worker's concrete job ID, active/queued/blocked state and PR links. A20 approves shared-file changes. Never claim all 20 are running unless verified.

## Integrator exit conditions

Merge checks green; no dropped video/audio layers; export respects saved edits, output codecs/bitrate/FPS where supported; real device preview/output acceptance, permission recovery, no-crash/freeze/low-memory tests and signed release signoff. Keep original master checklist in place until fulfilled.
