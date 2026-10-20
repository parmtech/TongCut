# Isolated VFR reverse PTS indexer

This slice adds a new `au.parmtech.tongcut.reverse` module without changing the existing editor/export paths.

## Contract

- `MediaExtractorVfrIndexer.index(context, uri, inUs, outUs)` reads the selected video track's observed `MediaExtractor.sampleTime` values. It does not synthesize timestamps from a nominal frame rate or decode/copy video frames.
- Trims are half-open: `[inUs, outUs)`. An empty trim or trim with no actual PTS returns `null`.
- Extractor order is allowed to be non-monotonic (for example, demux/decode ordering); the bounded collector sorts paired PTS/sample-ordinal arrays into presentation order and reports whether observed order was strictly increasing.
- Negative timestamps, duplicate PTS, and more than 1,000,000 samples are rejected instead of silently truncated. Duplicate PTS is ambiguous for source-time reversal and is a hard failure.
- `VfrPresentationIndex.reverseAt()`, `reverseOrder()`, and `sourceTimeUs()` expose descending original PTS while retaining each original selected-track sample ordinal.
- Call from a worker thread. Cancellation is cooperative between MediaExtractor sample advances; the extractor is released in `finally` for success, cancellation, and failures. No files are created by this indexer, so there are no temp files to clean up.

The two retained primitive arrays are bounded to roughly 12 MiB at the one-million-sample cap. The indexer scans the complete selected video track before sorting; it does not assume PTS order and therefore does not early-exit at the trim end.

## Integration status and limits

This is an index-only foundation. It is not connected to reverse preview, frame decoding/cache, reverse audio, timeline persistence, or MP4 export. A caller must map the retained PTS to an exact decoded frame, use actual per-frame durations for VFR output timing, and maintain the existing cancellation/cache lease contract.

The base branch also has a legacy root-package `ReversePtsIndexer`/plan. This new package is deliberately isolated; A13 integration should consolidate or adapt that code rather than wire two independent indexers into the editor. No test/build/device run is claimed, and no P3 checklist item is checked off.
