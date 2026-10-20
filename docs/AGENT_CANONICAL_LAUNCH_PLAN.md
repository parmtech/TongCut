# TongCut 20-worker canonical agent map — 20 October 2026

## Scope and verified status

The existing **canonical** branches are `agent/A01-*` through `agent/A20-*`, and tasks are GitHub issues #7–#26. Only these branches should be used by Codex workers.

**No Codex worker has been started from this chat.** GitHub branches and issues are planning artifacts, not running jobs. This document does not claim feature completion or a usable APK.

A second set of *lowercase* `agent/a01-*` … `agent/a20-*` branches was inadvertently created while checking setup. These are **superseded, unused, and must not receive coding changes**. They may be deleted later after confirming no PRs/commits depend on them. The original `agent/A*` branches and all existing code must be preserved.

## Branch-to-task map

| Canonical branch | Existing issue | Exclusive focus |
|---|---:|---|
| `agent/A01-home-ui` | [#7](https://github.com/manbir6-ux/TongCut/issues/7) | Home UI, media picker, branding |
| `agent/A02-timeline` | [#8](https://github.com/manbir6-ux/TongCut/issues/8) | Timeline gestures and editing |
| `agent/A03-project-store` | [#9](https://github.com/manbir6-ux/TongCut/issues/9) | Autosave/recovery/migration |
| `agent/A04-export-core` | [#10](https://github.com/manbir6-ux/TongCut/issues/10) | Media export & recovery |
| `agent/A05-motion-core` | [#11](https://github.com/manbir6-ux/TongCut/issues/11) | Keyframes/speed/transitions |
| `agent/A06-audio-studio` | [#12](https://github.com/manbir6-ux/TongCut/issues/12) | Voiceover/audio mixing |
| `agent/A07-colour-luts` | [#13](https://github.com/manbir6-ux/TongCut/issues/13) | Color grading/LUT |
| `agent/A08-captions` | [#14](https://github.com/manbir6-ux/TongCut/issues/14) | Text and caption animations |
| `agent/A09-face-reshape` | [#15](https://github.com/manbir6-ux/TongCut/issues/15) | Offline face/body reshape prototype |
| `agent/A10-qa-regression` | [#16](https://github.com/manbir6-ux/TongCut/issues/16) | QA, synthetic media and device acceptance |
| `agent/A11-photo-overlays` | [#17](https://github.com/manbir6-ux/TongCut/issues/17) | Photo effects & overlays |
| `agent/A12-video-pip` | [#18](https://github.com/manbir6-ux/TongCut/issues/18) | GPU PiP/chroma and masks |
| `agent/A13-reverse` | [#19](https://github.com/manbir6-ux/TongCut/issues/19) | Reverse video/audio export |
| `agent/A14-tracking` | [#20](https://github.com/manbir6-ux/TongCut/issues/20) | Tracking/stabilization |
| `agent/A15-effects-packs` | [#21](https://github.com/manbir6-ux/TongCut/issues/21) | Original effects/transitions library |
| `agent/A16-export-profiles` | [#22](https://github.com/manbir6-ux/TongCut/issues/22) | 4K/FPS/H.265 export controls |
| `agent/A17-accessibility` | [#23](https://github.com/manbir6-ux/TongCut/issues/23) | Accessibility/i18n |
| `agent/A18-performance` | [#24](https://github.com/manbir6-ux/TongCut/issues/24) | Performance, thermals and memory |
| `agent/A19-security` | [#25](https://github.com/manbir6-ux/TongCut/issues/25) | Privacy, licensing and security |
| `agent/A20-integration` | [#26](https://github.com/manbir6-ux/TongCut/issues/26) | Integration, PR review, release gates |

## Worker startup contract

1. Fetch `development/capcut-parity` and all existing pull requests; check any branch drift before coding.
2. Launch each available worker into its **own canonical branch** and corresponding issue. Record an actual job/task identifier before marking RUNNING.
3. For shared Kotlin screen or renderer files, propose an isolated API/hook first. A20 coordinator owns merge queue and serializes modifications to `EditorWorkspace.kt`, `TongCutApp.kt`, `MainActivity.kt`, `VideoExporter.kt`, `ProjectStore.kt`, `LayerTimelineCodec.kt`, Gradle and Manifest; domain owner must review the relevant changes.
4. No force pushes or automated merging to development. Each worker opens a reviewed PR containing real code, targeted tests and clear limitations.
5. Do not mark a feature done because a code path or button exists; compare preview with rendered exported frames/audio and require Android-device evidence. Existing `docs/TEMP_MASTER_PROJECT_CHECKLIST.md` remains authoritative.
6. Obey the concurrency limit of the connected Codex account. Twenty distinct branches/tasks do not imply 20 simultaneous jobs.
7. Do not use proprietary CapCut source, fonts, artwork, videos or premium effects. TongCut must implement original or properly licensed tools.
8. Face reshape and object tracking are **not accepted implemented features** until independently validated.
9. Keep the old `main` alpha untouched. Signed release and user-facing 100%-parity claims remain blocked until acceptance.

## Current review gate

GitHub PR creation from this chat returned HTTP 403 (`Resource not accessible by integration`). Workers launched in Codex/Work must create their PRs under their own supported permissions. Draft documentation from this chat is not a running worker.

## One-session launch instruction

Open repository `manbir6-ux/TongCut` in Codex/Work, load this file, start as many independent workers as concurrent account limits allow on the **canonical uppercase 20 branches** and issues #7–#26. Queue remaining workers, respect file ownership, give a status board with RUNNING/QUEUED/FAILED and actual job IDs. Ask for approval before merges/production release. Run unit and synthetic tests after each coherent integration and complete physical Android acceptance before final APK.
