# TongCut — original editor UX and visual identity

**Status:** development contract, not screenshot-accepted or device-tested.
**Priority:** familiar professional Android editing workflow, original visual language.

## What stays familiar
1. A clear Home -> media picker -> editor path.
2. An immersive preview above a time-accurate multitrack timeline.
3. Tap a clip to select it and expose immediate contextual editing tools; Back restores global tools.
4. Central playhead, thumbnails/waveforms, drag, trim handles, zoom, snap, undo/redo, export.
5. Bottom horizontally scrolling icon+label rail and contextual tool drawer with predictable back behavior.
6. Editing controls must change actual project state, preview, and encoded export wherever a renderer exists.

These interaction conventions are not exclusive to any video editor. Use an independently implemented interaction model, not proprietary reference source/assets.

## TongCut identity (not a reskin of a third-party app)
- **Aurora Aqua** `#00C9DE` is the primary action accent, with soft highlight `#83E5E3`.
- **Deep Graphite** `#14191D`, Stage `#1F272C`, Raised `#293238`, and Slate outline `#41565C` support timeline depth.
- **Ember Coral** `#FF939A` indicates potentially destructive actions.
- Original geometry: round-corner panels, compact clip pills, bright selected-edge strokes, Tonal caption colours.
- Distinctive `TONGCUT / [layer type]` contextual editing header; first-party editor navigation and visual token classes.
- Use permitted Material icons as placeholders for *original icon design*. Do not extract/import CapCut logos, stickers, artwork, templates, fonts or proprietary animations.
- Maintain 48 dp minimum active touch targets and accessible contrast; no decorative fake editing controls.

## Implemented in source
- Timeline tap selects an exact VIDEO/AUDIO/IMAGE/TEXT layer by ID; selected item gets a TongCut Aqua highlight.
- Bottom rail switches from global tool categories to selected-clip actions: Split, Trim in/out, Duplicate, Keyframe (visual), Mute (audio), Tools, Delete and Back.
- Actions are bound to actual `LayerEdits` or `AdvancedClipEdits` calls; failures report actionable messages.
- Inspector lists the selected clip first while keeping other clips editable; all edits remain non-destructive and undoable.
- The project text inspector supports `CaptionStyle` tone, weight, size; Compose preview, project codec and Media3 exported text consume the same saved style.
- Source policy and schema-migration tests have been added but **not re-run in a complete Gradle/emulator/device cycle** in this feature-first development stage.

## Acceptance still open
- No pixel-identical imitation claim. Reference UX and gesture tests must show familiar tasks are easy without vendor asset copying.
- Verify the exact selection/tap/drag precedence on physical devices; back-stack, selected layer after undo/split/remove, layout at 320–1440 dp, insets, TalkBack, foldables/tablets, RTL.
- Verify caption styling in both preview and exported encoded pixels across colour spaces and crop ratios.
- Full P0–P9 functionality, professional audio/effects, unique icon set and signed release remain incomplete.
