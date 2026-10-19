# TongCut — Android video editor

**Status: 0.1.0-alpha / source project.** Offline Android editor under active development. It is **not yet** a feature-complete CapCut replacement. No CapCut proprietary code, subscriptions, effects, or trademarks are included.

## Implemented in source

- Import multiple videos using Android's Storage Access Framework, without broad storage permissions.
- Preview/select individual clips, trim their in/out points, reorder or remove clips.
- Concatenate selected clips in timeline order via the official Media3 Transformer composition API.
- Export to H.264 MP4 at 720, 1080, or 2160 px **short-side** resolutions, dependent on hardware encoder support. 2160 is not a guaranteed 4K long edge preset.
- Export grayscale/invert/warm/cool filters and 16:9, 9:16, or square crop.
- Optional source-audio mute; export progress, cancel, error display; save under Movies/TongCut using scoped storage.
- Branded dark Compose UI and native TongCut vector app icon.
- Simple pure Kotlin trim-range unit tests and CI pipeline that attempts to build a debug APK.

**Limitations:** preview is the original clip, not a realtime render of filters/crop or multi-track composite. Export uses device codecs; some videos can fail based on format, HDR, audio layout, or available encoder. Export is foreground-only and ends if app exits. Multi-clip audio compatibility is device/source dependent. This source has not been compiled or device-tested in this workspace; verify GitHub Actions before calling it a working APK.

**Not implemented yet:** face/body reshape, keyframes, transitions, text overlays, subtitles, audio mixing, stabilization, optical-flow slow motion, custom LUTs, professional 4K/8K guarantees, templates, cloud/AI features, or a complex multi-track timeline.

## Build

Requirements: Android SDK Platform 36, JDK 17, Gradle 8.13, Android Gradle Plugin 8.13.2. After opening this directory as a project in Android Studio and syncing, run:

```bash
gradle --no-daemon testDebugUnitTest assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions workflow `.github/workflows/android.yml` runs tests and builds, then attaches a **debug-signed APK** as `TongCut-debug-apk` to successful workflow runs. **Do not distribute the debug-signed APK as a production release.**

## Architecture

- `MainActivity.kt` — Compose UI, import/preview, timeline editing, export controls.
- `EditorModel.kt` — clips, editing options, range validation.
- `VideoExporter.kt` — Media3 Transformer composition/export and Android MediaStore saving.
- `EditorModelTest.kt` — initial model tests.

Source code MIT-licensed. AndroidX Media3 and other upstream libraries retain their own respective open-source licences. Keep third-party notices when redistributing.

## Roadmap

1. Device-tested playback/encoder compatibility and CI fixes.
2. Persist projects and render accurate multi-clip preview.
3. Layered text, stickers, audio tracks, and undo/redo.
4. Transitions, timeline keyframes, audio waveforms, LUTs and better filters.
5. Optional ML face adjustments (consent and on-device safeguards), performance profiling, accessibility, wider codec compatibility.
