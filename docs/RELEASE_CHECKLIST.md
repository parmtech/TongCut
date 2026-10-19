# Release checklist

- [ ] Gradle compiles with JDK 17 and Android SDK 36.
- [ ] Local unit tests pass.
- [ ] Pixel/Samsung/low-end-device tests: import 1 MP4, trim, export, play output.
- [ ] Merge 2 videos with matching codecs/audio tracks; test mixed channel layouts and silent clips.
- [ ] 9:16 and 16:9 output dimensions and orientation are correct.
- [ ] All looks visually match expectations and no HDR washout occurs.
- [ ] Memory use/thermal stability on 5+ minute 1080p videos.
- [ ] Confirm cancellation and no empty gallery files.
- [ ] Accessibility labels, keyboard/large text support.
- [ ] No proprietary CapCut code/assets or inappropriate reuse of unlicensed templates.
- [ ] Versioned release keystore and signed release build (never commit keystore/passwords).
