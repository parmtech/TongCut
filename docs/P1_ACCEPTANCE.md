# P1 verification gate — NOT YET COMPLETE

This checklist is for the owner's agreed policy: only report a phase complete after ALL exit criteria pass. No APK packaging or distribution is permitted before the final release gate.

## Changes implemented in source (not verified on real devices)
- [x] Atomic per-project edit snapshots, with local-only storage
- [x] Migration of old v0.1 single-project draft without deleting original
- [x] Multiple projects: create, open, duplicate, rename, delete
- [x] Indexed project recovery from orphaned draft files
- [x] Corrupt catalog recovery choice, backup before overwriting an old index
- [x] Autosave and guarded project switching so drafts aren't silently saved under wrong IDs
- [x] Non-destructive split/trim/reorder, undo/redo and bounded edit history
- [x] Missing media inspection and user-driven file relink
- [x] Source-aware timeline position conversion with unit tests
- [x] Source compile and JVM unit tests; NO APK assembly

## Remaining P1 exit criteria
- [ ] Android runtime test: update from legacy v0.1 app, preserve source video and trims
- [ ] Android runtime test: cold restart loads all projects and retains SAF grants where possible
- [ ] Android runtime test: source revoked -> relink -> preview -> export correct clip
- [ ] Android runtime test: trim, split, reorder, undo, redo, reopen and export matches saved edits
- [ ] Android UI test: correct system bars, accessibility scaling, portrait/landscape, low memory
- [ ] Atomic index failover and corrupted draft recovery test with synthetic fixtures
- [ ] Edit history rapid gesture tests and process-death consistency
- [ ] Source preview/export parity for normal clip processing
- [ ] Regression test across multiple devices, different codecs and silent videos

## CI policy
`gradle testDebugUnitTest` builds/executes source tests only. No `assembleDebug`, `assembleRelease`, Play publishing or public APK artifacts before all release phases finish. Device tests are final-release gated by the owner and will be reflected in this checklist once allowed.

P1 must remain OPEN until remaining tests pass. P2 foundation work may proceed on the development branch without declaring P1 completed.
