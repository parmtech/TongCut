# TongCut — repository guidance for Codex agents

Work only from `development/capcut-parity` or your assigned `agent/Axx-*` branch. Never mistake `main` 0.1-alpha for the advanced implementation.

Read `docs/CODEX_AGENT_WAVE1.md` and the corresponding issue #7 through #26; own only the files in that issue. Never edit existing high-contention files `EditorWorkspace.kt`, `TongCutApp.kt`, `MainActivity.kt`, `AndroidManifest.xml`, `app/build.gradle.kts` except with issue A20 integration coordination.

Implement behavior, not cosmetic fake controls. Respect backwards-compatible project persistence and non-destructive edits. Preview/output parity and audio sync are essential; document what remains untested.

Use independently licensed code and original assets only. Do not commit secrets, production signing keys, user media, or trademarked CapCut assets.

Keep one branch/worktree per agent, commit changes with tests, and open PRs toward `development/capcut-parity`; do not merge or force-push. Treat a build or emulator pass as insufficient for handset acceptance. Do not call a finished/production APK without owner verification.
