# A15 offline effect preset slice

This branch adds an original, data-driven still-image effect catalog under `effects/presets/`:

- Sea Glass: restrained cool/aqua lift.
- Sun Print: warm highlights and cooler deep tones.
- Paper Mono: low-strength warm monochrome.
- Each preset has a stable ID, one bounded strength parameter, original TongCut provenance metadata, and matched deterministic preview/export kernel descriptors.
- `EffectPresetCodec` provides strict schema-1 JSON round-trip for this isolated value object. Unknown IDs/parameters, unsupported schema and out-of-range values are rejected.
- `OfflineEffectRenderer` applies the same descriptor-named kernel to bounded ARGB_8888 still buffers (1.5 MP maximum), keeps alpha unchanged, and has neutral strength identity behavior.

This is an offline still-image reference renderer and preset contract, not a video filter, color-managed/HDR effect, editor control, or shipped effect pack. The actual Compose editor, project schema, undo history, Media3 GPU preview/export, and output pixel parity are not wired in this slice. No transition presets are added here; A05 owns transition timing behavior and needs separate coordination before A15 can safely describe a usable transition preset. No third-party visual assets or proprietary CapCut material is used.

Focused test source was added at `app/src/test/java/au/parmtech/tongcut/effects/presets/OfflineEffectPresetsTest.kt`, but it was intentionally not run and no build was performed during the feature-construction pass. P5 effect acceptance remains open pending integration, test/build, preview/export comparison and device checks.
