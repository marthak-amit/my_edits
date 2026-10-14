# My Edits

A native Android video editor in the spirit of Instagram's **Edits** app — projects/drafts, a multi-track
timeline, text, stickers, audio, filters and HD export — built with Kotlin, Jetpack Compose and Media3.

> Not affiliated with or endorsed by Instagram or Meta. It is an independent app that reproduces the
> *workflow* of Edits, not its code, assets, music catalogue or cloud features.

## Features

| Area | What works |
| --- | --- |
| **Home** | Projects grid with thumbnails, sort (recent / created / name), rename, duplicate (deep copy incl. media), delete with confirmation, autosaved drafts |
| **Ideas** | Jot video ideas, tick them off, delete, or turn one into a new project |
| **Inspiration** | Six starter looks (canvas, filter, fades, caption) applied to your first clips |
| **Insights** | Projects, exports, footage edited, day streak and a 7-day activity chart (from on-device activity) |
| **Create** | Photo-picker import (videos + photos, up to 30), in-app **camera** (CameraX: record, flip, torch, 3s/10s timer), blank project |
| **Timeline** | Playhead-centred, pinch-to-zoom, fling scrubbing, ruler, video thumbnails, real audio waveforms, drag handles to trim clips / audio / layers, drag layers to move them |
| **Clip tools** | Split, trim, speed 0.1×–10×, volume 0–200 %, mute, fade in/out, rotate, flip, fit/fill, zoom, duplicate, delete, reorder, extract audio |
| **Look** | 13 filters with intensity, 6 manual adjustments (brightness, contrast, saturation, exposure, warmth, tint), "apply to all clips" |
| **Canvas** | 9:16, 1:1, 4:5, 16:9, 3:4 |
| **Text** | Six fonts, colours, background (solid / rounded), alignment, outline, shadow, drag / pinch / rotate on the preview, split on the timeline |
| **Stickers** | Emoji stickers and photo overlays, same gestures |
| **Audio** | Your own audio files, 4 built-in music beds + 9 effects (synthesised on-device), voiceover recording, text-to-speech, per-track volume and fades, split, trim |
| **Undo / redo** | 80 steps; slider drags merge into one step |
| **Export** | H.264 + AAC MP4 at 720p / 1080p / 4K (only offered if the device can encode it), original / 30 / 24 fps, saved to *Movies/My Edits*, share sheet, share to Instagram |

Preview and export are driven by the **same Media3 `Composition`**, so what you see is what you export.
Text and stickers are rendered by one renderer used by both the live preview and the export overlays.

## Not included (needs servers or models this app does not have)

* Instagram's licensed music library and trending audio — replaced by on-device synthesised sounds and your own files.
* Automatic captions / speech recognition, AI cutouts, green screen, background removal.
* Reverse playback, keyframes, per-clip transitions other than fade, video overlays (photo overlays are supported).
* Real Instagram analytics — *Insights* shows your own activity in this app.

## Build

Requirements: JDK 17+, Android SDK 35 (build-tools 35), network access to Google Maven.

```bash
./scripts/make-keystore.sh                 # once: creates release.keystore for sideloading
./gradlew :app:assembleDebug               # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease             # app/build/outputs/apk/release/app-release.apk (minified, signed)
./gradlew :app:testDebugUnitTest           # 120+ unit / Robolectric UI tests
./gradlew :app:recordRoborazziDebug        # same tests + regenerates screenshots/jvm/*.png
```

Without `release.keystore` the release build falls back to the debug signing key.

## Architecture

```
data/      Project model (immutable, kotlinx.serialization), file-based repository, settings, templates
engine/    ProjectOps (pure timeline operations), ColorMath/filters, CompositionBuilder (model -> Media3),
           Exporter + GallerySaver, PreviewPlayer, gain/fade audio processor, text & sticker renderer
audio/     Sound synthesiser, voice recorder, text-to-speech
util/      Media import (copies into app storage), thumbnails, waveforms, sharing, device capabilities
ui/        Compose screens: home, editor (timeline, preview, panels, export sheet), camera, settings
```

* Every edit is `Project -> Project`; undo/redo is a stack of snapshots, so the engine is trivially testable.
* Projects live in `files/projects/<id>/` (JSON + imported media), saved atomically and debounced.
* The editor `PreviewController` interface lets JVM tests swap the video surface for a still frame.

## Testing

* `ProjectOpsTest`, `ColorAndAudioMathTest`, `RepositoryAndSoundTest` — engine, colour/audio maths, persistence, synthesised audio.
* `HomeUiTest`, `EditorUiTest` — Robolectric + Compose tests that drive the real screens (taps, sliders, text input, panels, undo/redo, autosave, export sheet).
* `ScreenshotTest` — Roborazzi renders every main screen to `screenshots/jvm/`.
* Debug builds accept `adb shell am start -n com.myedits.app.debug/com.myedits.app.MainActivity --esa seed_media /sdcard/a.mp4,/sdcard/b.jpg`
  to create a project from local files for on-device testing.
