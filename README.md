# Voice Recorder (Android)

A simple, no-frills voice recorder that records to **WAV**, **M4A (AAC)** or **Opus**, lets you pick
the exact format / sample rate / bitrate per recording, and saves into any folder you choose via a
built-in directory navigator.

## Features

- **Multiple formats:** WAV (lossless PCM), M4A (AAC), Opus (Ogg). *(WMA is not supported — see
  `known-issues.md`.)*
- **Per-recording overrides:** the pre-record dialog lets you change the format, sample rate and
  (for compressed formats) the bitrate for just that recording, on top of the configurable defaults.
- **Configurable defaults:** set the default format, sample rate, bitrate, channel count (mono/
  stereo) and filename pattern in **Settings**.
- **Custom folder navigator:** browse the filesystem, drill in/out, **create new folders**, and pick
  where recordings go. Read-only / off-limits folders are greyed out with a lock icon and can't be
  chosen. You're asked to choose a folder on first launch.
- **Pause / resume:** recordings can be paused and resumed mid-capture. Paused spans are excluded
  from the timer and simply produce a gap in the file (the mic stream is stopped while paused), and
  the ongoing notification switches to "Recording paused".
- **Editable filenames:** each recording gets a timestamped default name, shown in an editable dialog
  **before** recording starts (never after).
- **Permissions on first use:** the app asks for microphone and storage access up front. On
  sideloaded builds the home screen shows a "Folder access needed" note with exact steps for
  granting **All files access** (Android hides the switch behind App info → ⋮ → "Allow restricted
  settings" until you unlock it); the note disappears once access is granted.
- **Storage capacity readout:** the main screen and the pre-record dialog show approximately how many
  hours of audio will fit in the target folder at the current settings.
- **Live frequency analyzer:** while recording, a small real-time spectrum meter (FFT-based bars)
  is shown on the home screen for *every* format, and hidden again when you stop.
- **Foreground service:** recording runs in a `microphone` foreground service with an ongoing
  notification, so it survives screen-off / backgrounding.

## Requirements

- Android 8.0 (API 26) or newer. Opus recording requires Android 10 (API 29)+.
- Built with AGP 8.7.2 / Gradle 8.13 / Kotlin 1.9.25, `compileSdk`/`targetSdk` 35.

## Building

Open the project in Android Studio (Giraffe or newer) and let it sync, **or** from the command line:

```
# Windows
gradlew.bat assembleDebug
# macOS / Linux
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

`local.properties` must point at your Android SDK. Use **forward slashes** (it's a Java `.properties`
file, so `\U`, `\A`, etc. are eaten as escape sequences and will corrupt a Windows path):

```
sdk.dir=C:/Users/you/AppData/Local/Android/Sdk
```

## How it works (architecture)

| File | Role |
|---|---|
| `MainActivity.kt` | Home screen: record/stop, timer, storage readout, permission + first-run flow, service binding. |
| `SettingsActivity.kt` | Configure default format / sample rate / bitrate / channels / folder / filename pattern. |
| `RecordingService.kt` | Foreground (`microphone`) service that owns the active recorder + the live [`SpectrumAnalyzer`]. |
| `Recorder.kt` | `Recorder` interface + `RecorderFactory`. |
| `PcmRecorder.kt` | The single capture engine: one `AudioRecord` reads PCM and tees it to the encoder + analyzer. |
| `PcmEncoder.kt` | `PcmEncoder` sinks — `WavPcmEncoder` (RIFF/WAVE) and `MediaCodecPcmEncoder` (AAC-in-MP4, Opus-in-Ogg). |
| `SpectrumAnalyzer.kt` | Windows/FFTs the PCM stream into log-spaced 0..1 magnitude bands. |
| `SpectrumView.kt` | Custom view that eases/animates the analyzer bands while recording. |
| `Fft.kt` | Dependency-free radix-2 FFT. |
| `AudioFormatSpec.kt` | Format enum + resolved spec; bytes/second estimation. |
| `Settings.kt` | SharedPreferences-backed defaults, save dir, filename pattern. |
| `StorageUtil.kt` | Free-space and available-recording-time helpers. |
| `PermissionUtil.kt` | Mic / notification / storage (incl. All-files-access) permission logic. |
| `DirectoryPickerDialog.kt` | The custom folder navigator (browse, create, off-limits marking). |
| `PreRecordDialog.kt` | Pre-recording dialog: filename + format/rate/bitrate + storage estimate. |

## Notes

- Formats and their bytes/sec:
  - **WAV:** `sampleRate × channels × 2` (16-bit PCM), exact.
  - **M4A / Opus:** `bitrate ÷ 8`, approximate (ignores container overhead).
- See `known-issues.md` for limitations (WMA, Opus version gate, storage-permission trade-offs) and
  possible future work.
