# Voice Recorder (Android)

A simple, no-frills voice recorder that records to **WAV**, **M4A (AAC)** or **Opus**, lets you pick
the exact format / sample rate / bitrate per recording, and saves into any folder you choose via a
built-in directory navigator.

## Features

- **Multiple formats:** WAV (lossless PCM), M4A (AAC), Opus (Ogg). *(WMA is not supported — see
  `known-issues.md`.)*
- **Per-recording overrides:** the pre-record dialog lets you change the format, sample rate and
  (for compressed formats) the bitrate for just that recording, on top of the configurable defaults.
- **Sample rates follow the hardware, not a hardcoded list:** the rates offered are probed per format
  on *your* device, so WAV reaches **88.2 / 96 kHz** where the hardware allows (typically a decent
  USB interface) instead of being capped at 48 kHz. The same probe works the other way too — Opus is
  a 48 kHz-family codec, so it is no longer offered at 44.1 kHz, a rate it cannot actually encode.
  Changing format moves the chosen rate to the nearest one that format supports rather than failing.
- **Configurable defaults:** set the default format, sample rate, bitrate, channel count (mono/
  stereo) and filename pattern in **Settings**.
- **Custom folder navigator:** browse the filesystem, drill in/out, **create new folders**, and pick
  where recordings go. Read-only / off-limits folders are greyed out with a lock icon and can't be
  chosen. You're asked to choose a folder on first launch.
- **Pause / resume:** recordings can be paused and resumed mid-capture. Paused spans are excluded
  from the timer and simply produce a gap in the file (the mic stream is stopped while paused), and
  the ongoing notification switches to "Recording paused".
- **Editable filenames:** each recording gets a default name, shown in an editable dialog **before**
  recording starts (never after). The dialog previews the file that will actually be written
  (*"Will save as Interview_3.m4a"*).
- **Filename history (multiple takes):** the app remembers the last 12 names you used. New recordings
  start with the **previous** recording's name by default, and the **▾** button next to the name field
  lists the remembered names (plus a fresh timestamp) to pick from; typing also offers matching names.
  Repeated takes never overwrite — they get `_2`, `_3`, … suffixes. Turn the prefill off, or clear the
  remembered names, in **Settings → Reuse last file name / Remembered file names**.
- **Permissions on first use:** the app asks for microphone and storage access up front. On
  without **All files access** the home screen shows a "Folder access needed" note with exact
  steps for granting it (including the extra step Android 13 and later add for apps installed from
  a downloaded file: App info → ⋮ → "Allow restricted settings"); the note disappears once access
  is granted.
- **Storage capacity readout:** the main screen and the pre-record dialog show approximately how many
  hours of audio will fit in the target folder at the current settings.
- **Input selection:** an **Input** row on the home screen lists every attached capture device —
  the phone's own mic, a wired or Bluetooth headset, and **USB microphones / audio interfaces** —
  and lets you pick which one to record from (default: whatever Android picks). The choice is
  remembered across reconnects (it's stored by device type + name, not by the id Android reshuffles
  on every unplug), the list updates live when you plug something in, and while recording the app
  shows *"Recording from: …"* so you can confirm the USB mic is really the one being used — Android
  treats the choice as a hint, not a guarantee. You can switch inputs mid-recording.
- **No silent fallback to the phone's mic:** if the input you chose isn't connected when you press
  **Record**, the app tells you and asks you to pick one, instead of quietly recording from the
  phone's microphone. Plugging the missing input in also works: the app carries on by itself. The
  Input row turns red while the input is missing. If the input is unplugged *during* a recording,
  the recording **pauses**, so the file gets a gap rather than a stretch from the wrong mic, and the
  app asks again. It resumes on its own when you plug the input back in or choose another. A pause
  you made yourself is never resumed for you, and **Stay paused** in the prompt makes the pause
  yours: plugging the input back in then leaves it paused until you press Resume. With the screen off, the notification reads *"Paused:
  input disconnected"*. **Automatic** is the exception: it means "whatever Android picks", so it
  still uses the phone's mic when nothing else is attached.
- **An error doesn't cost the whole recording:** if capture fails partway (the mic stops
  delivering audio, say), the recording stops at once and everything recorded up to that point is
  saved. The app tells you so (*"Recording ended with an error … saved as …"*), even if it happened
  while the app was in the background. WAV recordings are also written so that one cut off without
  warning, by the app being killed or the phone dying, still opens in ffmpeg- and libsndfile-based
  players and editors.
- **Sample-rate warning:** USB interfaces are often 48 kHz-only, so recording them at the app's
  44.1 kHz default costs a silent resample. The pre-record dialog reads what the input device
  actually supports and warns — *"USB Audio CODEC (USB) records at 48 kHz — tap to record at 48 kHz
  instead"* — with the fix one tap away. It also fires under the default **Automatic** input setting,
  since that's where a plugged-in USB mic gets used without you choosing it.
- **Downsampling notice:** the opposite case — asking for *less* than the mic captures — gets a
  quieter line in dim grey: *"Phone microphone captures up to 48 kHz; this will be resampled down to
  8 kHz."* There's no tap-to-fix, because there's nothing to fix: recording below the hardware's
  ceiling is usually the whole point (smaller files), so the app states the trade rather than
  warning about it. Only one of the two lines ever shows at a time.
- **Microphone processing:** **Settings → Microphone processing** chooses between Android's tuned
  voice chain (noise reduction + auto-gain, best for speech into the phone's own mic) and raw
  capture (best for a USB or line-level mic that's already clean). On devices with no truly
  unprocessed mode the app uses the lowest-processing input available and says so, rather than
  pretending the request was honoured. Fixed for the duration of a recording, so it applies from the
  next one.
- **Mic gain:** a −20 … +24 dB slider on the home screen, adjustable **before and during** recording
  (changes take effect on the next captured buffer). Tap the dB readout to snap back to 0 dB. Gain is
  applied in software to the captured samples, so the file and the spectrum meter match. The
  setting persists between recordings.
- **Clip warnings:** two red **CLIP** lights, which show where audio clipped. The one beside
  **Mic gain** lights while the gain is pushing samples past full scale, which a lower gain fixes.
  The one beside **Input** lights while the audio arrives already clipped, at any gain, 0 dB
  included: the sound is too loud for the mic or the source feeding it, and no gain setting can
  undo that, so move the mic back or turn the source down. If anything clipped, the message after
  the recording says so, and where, including after a recording that ended on an error.
- **Live frequency analyzer:** while recording, a small real-time spectrum meter (FFT-based bars)
  is shown on the home screen for *every* format, and hidden again when you stop.
- **Recordings and player:** **Recordings** on the home screen lists the recordings in the save
  folder, newest first, with each one's length, size and date, and follows the folder while it is
  open. Tap one to play it in the player at the bottom: tap it again, or the play button, to pause;
  drag the bar to move through it; ⏮ goes back to the start. The one in the player is marked (a
  speaker while it plays, a pause sign while paused), and the player shows the spectrum of what is
  playing, in step with what you hear. Its **⋮** menu shares it to another app, renames it (the
  extension is kept, and nothing is ever overwritten) or deletes it, with an **Undo**: a deleted
  recording waits under **Recently deleted** (the screen's own **⋮** menu) for 30 days, to be
  restored or deleted for good, then goes by itself; the list's summary and the menu say how much
  it holds, since it still takes space until then. It keeps what was deleted in other folders too,
  should you change the save folder, and restores each to the folder it came from. The player plays the formats Android plays
  (WAV of any bit depth, M4A, Opus, MP3, FLAC, AMR and more), with any number of channels. The "Saved" message after a recording has a **Play**
  button that opens it there. Playing pauses when you leave the screen, when a call or another app
  takes the sound (and says so if one has it when you press Play), and when headphones are
  unplugged; turning the phone doesn't interrupt it. The
  recording being made shows as "Recording…" and can't be played, renamed or deleted until it is
  finished, and playing another one aloud meanwhile asks first unless headphones are plugged in,
  since the microphone would record it into the take. A WAV cut off by the app being killed shows its real length and plays.
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

Convenience scripts (Windows, run from the project folder):

| Script | What it does |
|---|---|
| `build-apk.bat` | Builds and drops `VoiceRecorder-<version>.apk` in the project folder (`build-apk.bat clean` for a clean build; `build-apk.bat release` also fails unless it is signed with the release key). |
| `update-phone.bat` | Builds, then installs onto the phone connected over USB (`adb install -r`, app data kept). |
| `publish-release.bat` | Builds and publishes a new GitHub release `v<version>` with the APK attached, using the [GitHub CLI](https://cli.github.com/). |

The version lives in `app/build.gradle.kts` (`versionName` / `versionCode`) and drives the APK name
and the release tag; bump it with every change.

`local.properties` must point at your Android SDK. Use **forward slashes** (it's a Java `.properties`
file, so `\U`, `\A`, etc. are eaten as escape sequences and will corrupt a Windows path):

```
sdk.dir=C:/Users/you/AppData/Local/Android/Sdk
```

To build APKs that update existing installs, which the GitHub releases need, it must also name
the keystore every release was signed with (forward slashes again; a relative path is taken from
the project folder). Without it, debug builds are signed with your machine's own debug key, and
`publish-release.bat` refuses to publish them:

```
debugKeystore=D:/path/to/debug.keystore
```

## How it works (architecture)

| File | Role |
|---|---|
| `MainActivity.kt` | Home screen: record/stop, timer, storage readout, input picker + the "input not connected" prompt, permission + first-run flow, service binding. |
| `SettingsActivity.kt` | Configure default format / sample rate / bitrate / channels / folder / filename pattern. |
| `RecordingsActivity.kt` | The recordings screen: the save folder's recordings (listed and measured off the main thread, followed with a `FileObserver`), share / rename / delete, and the player bar. `RecordingsModel` (in the same file) keeps the player through a rotation. |
| `PlayerController.kt` | The player's rules (open, play, pause, the end, errors, a recording renamed or deleted under it) over an `AudioOutput`, so they are tested on the JVM. |
| `ExoOutput.kt` | The `AudioOutput` a phone uses: Media3's ExoPlayer, with its audio sink wrapped so each decoded buffer is queued for the spectrum, and the file read through the descriptor opened once. |
| `PlaybackMeterQueue.kt` | The playback spectrum: decoded blocks queued by the time they start at, analysed once the output has reached them. |
| `Pcm16.kt` | Decoded PCM of any encoding (8/16/24/32-bit, either byte order, float) as the 16-bit samples the spectrum takes. |
| `RecordingFiles.kt` | Which files are recordings, the rename rules, Recently deleted (the trash), the length clock, a share's MIME type. |
| `Views.kt` | Small view helpers shared by the screens (`setTextIfChanged`, for text set on a tick). |
| `WavLayout.kt` | A WAV's length from its header, a cut-off one included. |
| `RecordingService.kt` | Foreground (`microphone`) service that owns the active recorder + the live [`SpectrumAnalyzer`], and pauses the recording if its chosen input is unplugged. |
| `Recorder.kt` | `Recorder` interface + `RecorderFactory`. |
| `PcmRecorder.kt` | The single capture engine: one `AudioRecord` reads PCM and tees it to the encoder + analyzer. |
| `CaptureLoop.kt` | The capture thread's loop (read, deliver, clear the meter when a pause is seen), apart from `AudioRecord` so it is tested on the JVM. |
| `PcmEncoder.kt` | `PcmEncoder` sinks — `WavPcmEncoder` (RIFF/WAVE) and `MediaCodecPcmEncoder` (AAC-in-MP4, Opus-in-Ogg) — plus `endTake`, which finalises whatever a take captured, even after an error. |
| `Gain.kt` | Software input gain: dB↔linear, in-place scaling of 16-bit PCM, clip counting (at the input and by the gain). |
| `Clipping.kt` | `ClipCount` (one buffer's clipping, by kind), `ClipRecord` (a recording's totals, the hold behind the two CLIP lights, and where it clipped, for the message after it). |
| `InputDevices.kt` | Capture-device enumeration, labelling, the reconnect-stable key the chosen input is persisted as, the missing-input rules (ask, never fall back; when to pause and resume), and the device-vs-requested sample-rate advice. |
| `CaptureSource.kt` | Processed vs raw capture: maps the preference onto a `MediaRecorder.AudioSource`, with the fallback for devices lacking `UNPROCESSED`. |
| `SpectrumAnalyzer.kt` | Windows/FFTs the PCM stream into log-spaced 0..1 magnitude bands. |
| `SpectrumView.kt` | Custom view that eases/animates the analyzer bands: while recording, and in the player bar. |
| `Fft.kt` | Dependency-free radix-2 FFT. |
| `AudioFormatSpec.kt` | Format enum (incl. each format's encoder mime) + resolved spec; candidate rate/bitrate lists, kHz labelling, rate snapping, bytes/second estimation. |
| `SampleRateSupport.kt` | Probes which sample rates this device can actually record in each format — capture and encoder each get a veto. Cached per format+channels. |
| `Settings.kt` | SharedPreferences-backed defaults, save dir, filename pattern, recent-filename MRU list. |
| `StorageUtil.kt` | Free-space / available-recording-time helpers and unique-filename resolution. |
| `PermissionUtil.kt` | Mic / notification / storage (incl. All-files-access) permission logic. |
| `DirectoryPickerDialog.kt` | The custom folder navigator (browse, create, off-limits marking). |
| `PreRecordDialog.kt` | Pre-recording dialog: filename (+ history dropdown & resolved-name preview) + format/rate/bitrate + storage estimate. |

## Notes

- Formats and their bytes/sec:
  - **WAV:** `sampleRate × channels × 2` (16-bit PCM), exact.
  - **M4A / Opus:** `bitrate ÷ 8`, approximate (ignores container overhead).
- See `known-issues.md` for limitations (WMA, Opus version gate, storage-permission trade-offs) and
  possible future work.


## License

MIT - see [LICENSE](LICENSE). Free to use, modify and redistribute; provided
as-is, with no warranty.
