# TongCut — Codex multi-agent wave 1 (20 workstreams)

**Status: PLANNED + REGISTERED. These are GitHub work assignments, not running Codex sessions.** Created 2026-10-20.

**Project:** private `manbir6-ux/TongCut`
**Upstream integration target:** `development/capcut-parity` (never `main`).
**Goal:** faster, safe parallel feature development toward a well-tested offline TongCut Android editor, not an unverified CapCut clone.
**Owner approval still required:** physical-device acceptance and final signed APK release. Only count a task complete after source, build, relevant tests and review.

## Agent work queue

| Agent | GitHub issue | Agent branch | Dedicated work | Status |
|---|---|---|---|---|
| A01 | [#7](https://github.com/manbir6-ux/TongCut/issues/7) | `agent/A01-home-ui` | Home, gallery and visual identity | Queued |
| A02 | [#8](https://github.com/manbir6-ux/TongCut/issues/8) | `agent/A02-timeline` | Timeline gestures and scrubbing | Queued |
| A03 | [#9](https://github.com/manbir6-ux/TongCut/issues/9) | `agent/A03-project-store` | Autosave, restore and migration | Queued |
| A04 | [#10](https://github.com/manbir6-ux/TongCut/issues/10) | `agent/A04-export-core` | Export engine and codec resilience | Queued |
| A05 | [#11](https://github.com/manbir6-ux/TongCut/issues/11) | `agent/A05-motion-core` | Keyframes, speed and transitions | Queued |
| A06 | [#12](https://github.com/manbir6-ux/TongCut/issues/12) | `agent/A06-audio-studio` | Audio studio and voiceover | Queued |
| A07 | [#13](https://github.com/manbir6-ux/TongCut/issues/13) | `agent/A07-colour-luts` | Colour grading and LUTs | Queued |
| A08 | [#14](https://github.com/manbir6-ux/TongCut/issues/14) | `agent/A08-captions` | Subtitles and caption animation | Queued |
| A09 | [#15](https://github.com/manbir6-ux/TongCut/issues/15) | `agent/A09-face-reshape` | Face/body reshape PoC | Queued |
| A10 | [#16](https://github.com/manbir6-ux/TongCut/issues/16) | `agent/A10-qa-regression` | Automated QA | Queued |
| A11 | [#17](https://github.com/manbir6-ux/TongCut/issues/17) | `agent/A11-photo-overlays` | Photo overlays and mattes | Queued |
| A12 | [#18](https://github.com/manbir6-ux/TongCut/issues/18) | `agent/A12-video-pip` | Video PiP and chroma | Queued |
| A13 | [#19](https://github.com/manbir6-ux/TongCut/issues/19) | `agent/A13-reverse` | Reverse video/audio | Queued |
| A14 | [#20](https://github.com/manbir6-ux/TongCut/issues/20) | `agent/A14-tracking` | Tracking and stabilization | Queued |
| A15 | [#21](https://github.com/manbir6-ux/TongCut/issues/21) | `agent/A15-effects-packs` | Offline original effects packs | Queued |
| A16 | [#22](https://github.com/manbir6-ux/TongCut/issues/22) | `agent/A16-export-profiles` | 4K/FPS/H265 export profiles | Queued |
| A17 | [#23](https://github.com/manbir6-ux/TongCut/issues/23) | `agent/A17-accessibility` | Accessibility and translations | Queued |
| A18 | [#24](https://github.com/manbir6-ux/TongCut/issues/24) | `agent/A18-performance` | Performance and memory | Queued |
| A19 | [#25](https://github.com/manbir6-ux/TongCut/issues/25) | `agent/A19-security` | Security and licensing | Queued |
| A20 | [#26](https://github.com/manbir6-ux/TongCut/issues/26) | `agent/A20-integration` | CI, PR integration and release gates | Queued |

## Operating protocol: never overwrite another agent

1. Run each active coding agent in its own Git **worktree** and branch. One workspace per agent, no shared writable checkout.
2. Read the assigned issue before editing. Treat issue-owned files as exclusively owned until an A20 integration review says otherwise.
3. **Shared hot files are locked:** `EditorWorkspace.kt`, `TongCutApp.kt`, `MainActivity.kt`, `AndroidManifest.xml`, `app/build.gradle.kts` and shared schema/build contracts. A20 coordinates their modifications sequentially; other agents add modular files and request small patches instead.
4. Agents A04 and A16 share codec concerns; A04 owns engine and cancellation, A16 owns codec profiles/probe modules. A05 and A15 share transition concerns; A05 owns timing engine, A15 makes data-driven original presets. A11 owns photo compositing and A12 video compositing. A06 owns audio, A13 reverse audio only within reverse modules, A09 owns beauty prototype.
5. Each agent opens a **PR targeting `development/capcut-parity`**. Commit code + tests + concise limitations; no direct merges or force pushes. Integrator A20 checks base freshness and merges sequentially after review and green CI.
6. A10 is independent QA, not an agent that declares feature completion. Real handset/device tests and pixel/audio export parity are release gates, not implied by building a debug APK.
7. Do not copy proprietary CapCut code, fonts, filters, templates or assets. Do not commit tokens, user media or private signing keys. Keep offline features offline and note any vendor dependencies/licences.
8. If blocked by another module: create a contract/interface proposal or PR comment, do not edit overlapping owned files.
9. **Concurrency advice:** prepare 20 distinct tasks but run 4–6 initially, increasing after build/review stability. 20 simultaneous jobs can exceed device CPU/RAM, rate limits and human review capacity. Execution is not automatic simply because the issues/branches exist.
10. Agents and release gate changes must agree with `docs/TEMP_MASTER_PROJECT_CHECKLIST.md`. Source implemented ≠ accepted feature.

## Prompt for Codex (replace placeholders, one session per agent)

> You are TongCut coding Agent `<AGENT_ID>` assigned to GitHub issue `<ISSUE_URL>`. Check out only branch `agent/<AGENT_ID>-<SLUG>` in a dedicated worktree. Read `AGENTS.md`, `docs/CODEX_AGENT_WAVE1.md`, the specific issue, the master acceptance checklist and relevant existing code. Implement the issue end-to-end using original/appropriately licensed source. Stay inside the issue's owned files and new module files. Do NOT edit common entry points without integration lead A20 approval. Add meaningful tests and run the appropriate Android/Kotlin checks. Fix regressions within your scope; report conflicts and unverified device behavior. Commit your changes in this branch, push branch if repository access exists, and open a PR against `development/capcut-parity`. Never merge your own PR; never claim finished release or APK parity without device acceptance.

## How to start (on the owner's computer)

1. Install and sign in to **Codex desktop** or the **Codex CLI**. Connect GitHub and clone the private repo; the computer needs Git and Android SDK/JDK for media tests.
2. Start with A20 integration coordinator, A10 QA and 2–4 nonoverlapping implementation agents (suggest A03, A06, A07, A08). Each session opens a distinct worktree and uses its own prompt.
3. Expand into the full 20-agent queue as the shared interfaces stabilize. Watch CI, PR dependencies, RAM and plan/rate limits.
4. A20 tracks blockers, reviews PRs and coordinates file patches. No automatic production release.
5. Optionally use `tools/start-codex-wave1.ps1` (when present) on a computer with Codex CLI installed to launch the task queue locally; the script does not run from GitHub or this conversation.

## Status interpretation

- **Issue exists**: work has been defined.
- **Agent branch exists**: isolated Git revision has been reserved.
- **Codex session launched**: only when an actual Codex process/cloud job begins.
- **Work delivered**: code + tests/PR reviewed.
- **Accepted**: on-device verification + checklist signoff.

GitHub issues and branches are *not* autonomous running AI agents. `main` v0.1 alpha APK remains separate from this development work.
