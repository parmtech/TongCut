# A09 / P5-08 manual IMAGE beauty integration (in progress)

This branch advances the A09 manual landmark proof-of-concept into the IMAGE layer path. It is still an incomplete feature slice, not P5-08 acceptance.

## Implemented here

- Manual, normalized landmark and bounded strength data are stored with IMAGE photo effects; missing payloads decode to neutral defaults, and the layer codec schema advances to 27.
- Layer decoding rejects non-neutral manual beauty settings on VIDEO items. This fail-closed policy prevents still-image CPU settings from changing existing video behavior.
- The selected IMAGE inspector exposes manual landmark placement and bounded face, jaw, nose, eye and softening controls. Dragging a landmark commits once when the gesture ends through the normal photo-effects history callback.
- The existing ManualFaceWarp renderer is invoked from PhotoBitmapDecoder after EXIF orientation and before matte, color grade, LUT and other photo processing. Both VisualOverlayPreview and Media3 photo export call that decoder, so the same IMAGE transform reaches both paths without host edits.
- The warp sampler preserves alpha, and softening preserves the warped pixel alpha. The renderer has a 1.5 MP CPU limit; the shared decoder currently downsamples inputs to at most 1024 pixels per side (about 1.05 MP).

## Deliberate exclusions and remaining acceptance

No automatic face detection, network/AI processing, body reshape, VIDEO-layer processing, or real-time video support is included. The panel source view is a landmark-placement view, not a processed before/after preview. Render quality, performance, decoder/export parity, transparent-edge behavior and undo/redo still require focused testing, including device tests. P5-08 remains open until A20 review and integration QA approve those paths.
