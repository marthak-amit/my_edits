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
| **Templates** | 12 Edits-style, slot-based templates in 5 categories (Trending, Travel, Cinematic, Memories, Lifestyle). Pick one, add up to *N* clips and get a finished edit: clips cut to the slot lengths, transitions, Ken-Burns motion, captions, colour look, cinema bars / vignette and a music bed with sound effects. Animated template posters on the Templates tab and a rail on the Projects tab |
| **Insights** | Projects, exports, footage edited, day streak and a 7-day activity chart (from on-device activity) |
| **Create** | Photo-picker import (videos + photos, up to 30), in-app **camera** (CameraX: record, flip, torch, 3s/10s timer), blank project |
| **Timeline** | Playhead-centred, pinch-to-zoom, fling scrubbing, ruler, video thumbnails, real audio waveforms, drag handles to trim clips / audio / layers, drag layers to move them |
| **Clip tools** | Split, trim, speed 0.1×–10×, volume 0–200 %, mute, fade in/out, rotate, flip, fit/fill, zoom + pan (crop & position), **freeze frame**, duplicate, delete, reorder, extract audio |
| **Transitions & motion** | Per-cut transitions (fade, flash, zoom punch, slide, spin) with adjustable length; clip animations (zoom in/out, pan left/right, pulse); text & sticker entrance/exit animations (fade, pop, slide) and opacity |
| **Effects** | Blur, vignette and cinema bars per clip (or all clips) |
| **Look** | 13 filters with intensity, 6 manual adjustments (brightness, contrast, saturation, exposure, warmth, tint), "apply to all clips" |
| **Canvas** | 9:16, 1:1, 4:5, 16:9, 3:4 |
| **Text** | Six fonts, colours, background (solid / rounded), alignment, outline, shadow, drag / pinch / rotate on the preview, split on the timeline |
| **Stickers** | Emoji stickers and photo overlays, same gestures |
| **Audio** | Your own audio files, 4 built-in music beds + 9 effects (synthesised on-device), voiceover recording, text-to-speech, per-track volume and fades, **voice effects (pitch: chipmunk, deep, giant…)**, split, trim |
| **Captions** | Type a script, one caption per line, and they are spread over the video as timed, animated captions |
| **Undo / redo** | 80 steps; slider drags merge into one step |
| **Export** | H.264 + AAC MP4 at 720p / 1080p / 4K (only offered if the device can encode it), original / 30 / 24 fps, saved to *Movies/My Edits*, share sheet, share to Instagram |

Preview and export are driven by the **same Media3 `Composition`**, so what you see is what you export.
Text and stickers are rendered by one renderer used by both the live preview and the export overlays.

## Not included (needs servers or models this app does not have)

* Instagram's licensed music library and trending audio — replaced by on-device synthesised sounds and your own files.
* Automatic captions / speech recognition, AI cutouts, green screen, background removal.
* Reverse playback, keyframes, video overlays (photo overlays are supported), beat auto-detection (template timings are fixed, not analysed from the music).
* Real Instagram analytics — *Insights* shows your own activity in this app.

## Verified on a real Android system

The app was installed and driven on an Android 9 (API 28, x86_64) emulator, which found and fixed bugs that
JVM tests cannot see. Confirmed working there: import of videos and photos, preview playback, scrubbing, the
timeline, panels, project covers, and **export** of a project combining three clips, a photo, a speed change,
filters, fades, text, a sticker and an audio track (`dist/sample_export_from_device_720p.mp4`) — also from the
minified (R8) build that the release APK is built from.

Also verified on the emulator in 1.1: building a project from a template (`--es seed_template`), exporting it with zoom/spin transitions, Ken-Burns motion, music + sound effects, cinema bars, vignette and captions (`dist/template_*_export_from_device_720p.mp4`, frames inspected), the minified release build launching and showing the Templates tab, and the play button (play, pause, replay after the end, five rapid taps, judged from the preview log; the emulator is too slow to judge smoothness).

Play-button fix: the preview now stops itself at the end (Media3's CompositionPlayer kept "playing" past the last frame without ever reporting *ended*, which left the button showing pause over a frozen picture), keeps the button state equal to the user's intent, debounces pipeline rebuilds and recreates the player after a graphics/codec failure.

Not verified on a device: the in-app camera could start recording on the emulator but the emulator was too slow
to finish a take, voiceover recording (emulator audio was disabled), text-to-speech (no engine on the image),
4K export, and any hardware-codec behaviour (the emulator only has software codecs). Treat those as untested.

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
* `TemplatesAndMotionTest` — template catalogue + layout, transition / animation maths, new timeline ops, composition effects.
* `HomeUiTest`, `EditorUiTest` — Robolectric + Compose tests that drive the real screens (taps, sliders, text input, panels, undo/redo, autosave, export sheet).
* `ScreenshotTest` — Roborazzi renders every main screen to `screenshots/jvm/`.
* Debug builds accept `adb shell am start -n com.myedits.app.debug/com.myedits.app.MainActivity --esa seed_media /sdcard/a.mp4,/sdcard/b.jpg`
  to create a project from local files for on-device testing; add `--es seed_template beat_drop` to build it from a template.
