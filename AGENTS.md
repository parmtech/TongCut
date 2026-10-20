# TongCut — Codex team rules

## Exactly three agents
The only approved agents are **Master**, **Worker 1**, and **Worker 2**. Master is ALSO a working programmer who writes, tests, and commits code. Do not spawn or schedule other coding, QA, review, planning or integration agents. Legacy agent/A01...A20 and agent/a01...a20 branches are RETIRED and must not be assigned new work or treated as active agents.

## Roles and branches
- Master: `agent/master`. Owns planning, architecture, task assignment, hands-on code changes, integration, conflict resolution, review, and merge. Writes production code and tests, not only instructions.
- Worker 1: `agent/worker-1`. Owns UI/UX, import, preview, timeline, trimming, project data, captions and accessibility; tests changed code.
- Worker 2: `agent/worker-2`. Owns Media3 playback/render/export pipelines, audio, effects, formats, export diagnostics and performance; tests changed code.
- `main` is stable. Each active agent uses a separate worktree and its named branch; Master merges reviewed pull requests into main.

## One iteration
1. Master reads README, `docs/FEATURE_MATRIX.md` and `docs/RELEASE_CHECKLIST.md`; inspects latest code and existing tests. Master defines a small independently testable task for each worker AND one coding task for themselves.
2. Master writes clear acceptance criteria, proposed file ownership, interface changes and priority in `docs/THREE_AGENT_WORKFLOW.md` or GitHub Issues. Avoid shared file edits and mutually blocking work.
3. Workers implement on their own branches/worktrees, commit code and tests, and open PRs into main. Include test results and limitations; do not silently merge or force push main.
4. Master reviews, checks CI, resolves conflicts, writes required glue code on `agent/master`, and merges only passing, reviewed changes. Keep main releasable.
5. Master updates the feature matrix, records follow-up work and repeats. No claim of success without evidence.

## Quality and safety
- Kotlin/Compose + Media3 Android project. Preserve applicationId and existing supported functionality.
- Run `gradle --no-daemon :app:testDebugUnitTest` where SDK/JDK are available; require Android device testing before claiming a feature works on devices.
- Do not claim CapCut feature parity, 4K guarantees or finished APK without evidence. Alpha debug APK workflow is separate from production release.
- Never overwrite another agent's branch, reset others' commits, commit secrets or signed keystores, or copy proprietary assets.
- Existing workflows `.github/workflows/android.yml` and `build-apk.yml` are preserved.
- **Instructions/configuration do not start Codex agents automatically**. An operator must launch the three Codex sessions explicitly. Do not assume an agent is running just because a branch exists.
