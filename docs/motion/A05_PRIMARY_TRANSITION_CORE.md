# A05 primary transition timing core

This is a math-only first slice for an ordinary primary-video hard cut. It introduces stable boundary identity from the outgoing/incoming clip IDs, bounded 100–3000 ms duration, typed cross-dissolve kind and LINEAR/SMOOTHSTEP curves, a source-handle checked plan, and project-time sampling for both source clocks plus the incoming mix weight.

## Clock semantics

The transition window is centered on the hard cut. For duration `D`, the incoming source head handle covers `floor(D/2)` ms before the cut and the outgoing source tail handle covers the remaining `ceil(D/2)` ms after it. The selected clip bodies keep their original project start/end positions. The mixer is expected to substitute the two handle streams only within this window; therefore this plan declares an unchanged project duration (`delta = 0`). It rejects insufficient source handles, missing adjacency, mismatched clip IDs, overflow, and samples outside the transition window.

This defines timing math only. No project codec/schema, timeline control, undo, primary-video preview/compositor, Media3 export compositor, audio crossfade/onset policy, speed/reverse interactions, HDR/VFR/crop handling, or rendered-pixel parity is implemented here. Current primary playback/export paths are not wired to this plan, so users do not yet have functional in-editor primary transitions. The existing PiP dissolve remains unrelated and is not primary-transition support.

## Integration request

A20 should coordinate schema/persistence and undo with A03, then add selected-boundary controls in the editor and a full-frame transition compositor to preview/export. Both render paths must use the same source-clock sample and curve semantics. Audio remains an unchanged hard cut until a separately specified, tested audio policy is implemented; do not imply audio crossfade. Export must fail with an actionable unsupported-effect error until it can honor the saved primary transition rather than silently render a hard cut.

Focused JVM regression source is in `app/src/test/java/au/parmtech/tongcut/motion/PrimaryTransitionTimelineTest.kt`; it is intentionally unrun for this feature pass. No build or device acceptance is claimed. Leave P3-10/P3-10b and issue #11 open.
