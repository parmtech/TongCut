# A08 typography: Drop and Zoom entrances

Added two TongCut-original deterministic caption entrances using only existing shared pose fields. Drop starts above the authored position and settles with the common 300 ms easing; Zoom starts at 122% scale and settles to authored scale. Both fade in with the same clip-relative curve, finish within short caption durations, and preserve existing enum IDs (new IDs are appended).

Compose preview and Media3 export both call CaptionEntranceMotion.at and consume alpha/scale/Y, so no shared rendering host changes are needed. LayerTools enumerates CaptionEntrance.entries, and LayerTimelineCodec resolves unknown or malformed names to NONE. This slice is source-only; no tests/build or device/export visual verification was run.
