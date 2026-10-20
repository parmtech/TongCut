# Offline IMAGE effect presets

TongCut currently exposes three original offline presets for still-image overlays:

| Stable ID | Look | Render kernel |
| --- | --- | --- |
| `sea-glass` | Restrained cool lift with an aqua tint | `sea-glass-v1` |
| `sun-print` | Warm highlight bias with cooler deep tones | `sun-print-v1` |
| `paper-mono` | Neutral monochrome with a subtle warm paper cast | `paper-mono-v1` |

Each preset has one bounded strength value (0–1). Selection and strength are stored in the IMAGE layer's photo effects and changed through the normal undoable layer edit path. The shared `PhotoBitmapDecoder` invokes the deterministic A15 ARGB renderer after the existing photo adjustments; the same decoded bitmap path is used by editor preview and exported image overlays. Alpha is preserved by the preset renderer.

Schema 28 stores a versioned preset configuration; older timelines decode the absent value as null (no preset). Malformed configurations are rejected. Non-neutral preset values on VIDEO layers are rejected because this integration is still-image-only.

This is not whole-project effects parity: the presets do not apply to VIDEO overlays or primary footage, and there is no video/GPU preset renderer. Pixel-parity, Android build, and device checks are deferred; no production acceptance is claimed.
