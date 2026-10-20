# TongCut — three-agent implementation workflow

**Effective: 2026-10-20. Team size: exactly 3 agents (not 3 workers plus a manager).**

## Responsibilities
| Agent | Own branch | Works on code? | Other responsibility |
|---|---|---|---|
| Master | `agent/master` | **Yes** | Designs plan, delegates two tasks, reviews, integrates and merges |
| Worker 1 | `agent/worker-1` | Yes | Kotlin/Compose editor, media import and timeline/UI |
| Worker 2 | `agent/worker-2` | Yes | Media3 renderer/export, sound, video effects and tests |

Workers must coordinate API and data-model changes through Master. Master also makes a third, independent code contribution every cycle; do not reduce Master to a planning-only agent.

## Branch/worktree setup
These are **commands for an operator running Codex locally**; the GitHub file alone does not execute them.
```bash
git fetch origin
git worktree add ../tongcut-master agent/master
git worktree add ../tongcut-worker-1 agent/worker-1
git worktree add ../tongcut-worker-2 agent/worker-2
```
If worktree branches are not available locally, fetch the named branches first. Each Codex session opens its own corresponding folder and reads root `AGENTS.md`.

## Current milestone: verified usable alpha APK before more features
- **Master coding:** repair common architecture/integration defects, keep model/export APIs coherent, improve tests and verify CI; review and integrate both workers.
- **Worker 1:** prioritize usable polished gallery/editor UX and robust preview, clip controls and no regressions.
- **Worker 2:** prioritize import-to-export reliability, device/codec error paths and accurate 720p/1080p/2160-short-edge descriptions.
- Later: save/restore projects, multilayer editing, transitions, keyframes, audio tracks, motion, facial tools and templates. Do not mark a feature implemented until coded, tested and documented.
- For every feature: scope -> implementation -> focused tests -> PR -> Master review/merge -> integration tests -> documented status.
- Avoid concurrent modifications to `MainActivity.kt`, `EditorModel.kt`, `VideoExporter.kt`: Master assigns ownership before work.

## PR acceptance checklist
- [ ] Feature behavior and acceptance criteria described.
- [ ] No unrelated modifications; no conflict with other agent's owned files.
- [ ] Source/tests included; Gradle unit tests pass or failure explained accurately.
- [ ] Dependencies, privacy, licensing and security reviewed.
- [ ] Limitations and device checks documented; no invented completion status.
- [ ] Master reviewed the PR and merged only after green checks.

## Legacy 20-agent project
All previously created `agent/A01...A20` and `agent/a01...a20` branches are retired. **Do not launch or assign sessions for them.** Both historical 20-branch sets were archived to Git tags under `archive/retired-agents-20261020/` and removed by GitHub Actions on 2026-10-20. The archived source remains recoverable. Old branches did not themselves represent running sessions. Retired branches must not be used to bypass the three-agent policy. Existing APK build and test actions continue to operate independently of Codex.

## Operating reality
Use `.codex/config.toml` to cap this repository's single Master Codex session at two concurrent spawned workers; the main thread is the hands-on coding Master. Alternatively use three separate sessions/worktrees, but never also spawn subagents in them. This repository stores the protocol; it does not itself launch a Codex session. GitHub Actions can check code but cannot, by this configuration alone, open or terminate Codex sessions, create continuous autonomous work or remove external running sessions.
