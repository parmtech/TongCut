# Feature status

| Feature | Current status | Notes |
|---|---|---|
| Offline import | Source implemented | Android SAF; no storage permissions |
| Source preview | Source implemented | ExoPlayer; no live filter compositing |
| Multi-clip ordering | Source implemented | Simple reorder, no transitions |
| Trim | Source implemented | 100ms minimum window |
| MP4 export | Source implemented | Media3 Transformer; codec/device dependent |
| Filters | Source implemented | Original, monochrome, invert, warm, cool |
| Canvas | Source implemented | Source, 16:9, 9:16, 1:1 via crop |
| Resolution | Source implemented | Short edge 720/1080/2160, subject to hardware |
| Audio mute | Source implemented | Entire export |
| MediaStore save | Source implemented | Movies/TongCut |
| Frame-accurate timeline scrub | Planned | Source player alone currently |
| Transitions | Planned | Needs compositor design |
| Text/stickers | Planned | Keyframes, renderer |
| Multi-track audio | Planned | Audio processing, sync |
| Colour LUTs | Planned | Efficient GPU LUT pipeline |
| Stabilisation | Planned | Sensor/optical flow |
| Smooth slow motion | Planned | Frame interpolation is separate from changing playback speed |
| Face reshape | Planned | ML feature, no promises of realtime speed |
| AI generation | Planned | Optional external APIs; opt-in |
| Templates | Planned | Proper attribution and third-party licenses |
| 8K export | Not promised | Device hardware and codec limitations |

Every feature marked "Source implemented" requires real Android device verification before release.
