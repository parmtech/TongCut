# A05 → A20 handoff: primary-track transition gap

## Status

A05's motion, speed-ramp, layer-fade, and VIDEO PiP crossfade helpers do not implement transitions between ordinary clips on the primary footage track. The PiP crossfade must not be described as primary-sequence transition support. P3-10 and P3-10b remain unchecked and incomplete.

This note is an integration request only. It does not add an editor control, persist transition state, change project schema, alter preview/export, or claim rendering acceptance.

## A20 integration request

Please own the shared-file integration for any primary-transition implementation. The required shared surfaces include:

- `EditorDraft.kt`: stable boundary identity, undoable transition state, trim/reorder/split/delete behavior, old-project neutral defaults.
- `ProjectStore.kt`: versioned persistence and migration for transition kind/duration and stable adjacent-clip references.
- `EditorWorkspace.kt` and `SequenceStrip.kt`: controls, playhead clock, seek behavior, incoming-source preview and interaction with reverse playback.
- `EditorModel.kt`, `ExportService.kt`, `VideoExporter.kt`, and compositor settings: source handles, project-clock duration, full-frame incoming/outgoing composition, and export capability preflight.

The transition contract should:

1. Attach to a specific adjacent pair of stable primary clip IDs, not a transient array index.
2. Validate that both trimmed source handles can provide the requested overlap and preserve a non-transition portion of each clip. Reject or clearly constrain invalid durations.
3. Define the project-clock equation explicitly: total primary duration equals the sum of trimmed clip durations minus accepted overlap durations. Preview, seek, export, overlays, captions, audio and selected-clip behavior must use that same clock.
4. Render a real full-frame primary-footage blend using a dedicated preview/export path. Existing `VideoCrossfadeEdits` changes VIDEO PiP layer timing/fades only and is not a substitute.
5. Define source-audio behavior separately. Media3 Composition's documented video/audio track crossfade limitations mean an unsupported audio mix must fail clearly or remain an explicit gap; it must not be silently treated as a completed audio crossfade.
6. Fail closed with an actionable message for unsupported combinations such as reverse video, additional video/image compositor lanes, or source formats that cannot preserve the authored transition. Never silently export a hard cut after the user authored a transition.

## Acceptance still required

Before marking P3-10/P3-10b complete, add migration and undo tests, source-clock and boundary/handle tests, preview seek tests, decoded synthetic MP4 pixel checks at transition start/midpoint/end, audio onset/gain verification for the selected policy, and mixed-media/reverse/PiP compatibility coverage. Run the supported Android test/build campaign after the feature-construction pass, then obtain owner device acceptance separately. This handoff itself provides no test, build, encoded-pixel, or device evidence.
