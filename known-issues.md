# Known Issues & Tech Debt

## WMA is not supported
The spec asked for WMA "if possible". It is **not** possible on-device: Android's platform encoders
(`MediaRecorder` / `MediaCodec`) do not ship a Windows Media Audio encoder. Producing WMA would
require bundling a third-party native encoder (e.g. FFmpeg with the proprietary WMA codec), which is
out of scope. Only WAV, M4A (AAC) and Opus are offered. See `AudioFormatSpec.kt`.

## Opus requires Android 10+
All formats now share one `AudioRecord` PCM capture path (`PcmRecorder`); the format only chooses
the `PcmEncoder` sink. Opus is encoded with `MediaCodec` (`audio/opus`) and muxed to Ogg with
`MediaMuxer`'s `MUXER_OUTPUT_OGG`, both of which are API 29+. On older devices Opus is hidden from
the format lists via `AudioFormat.isSupportedOnThisDevice`. WAV and M4A cover API 26-28.

Side note: the Opus bitstream's `OpusHead` reports a 48 kHz "input sample rate" regardless of the
chosen capture rate — Opus always operates internally at 48 kHz and that header field is only
informational, so this is expected and harmless.

## Storage permission model
On Android 11+ the app requests **MANAGE_EXTERNAL_STORAGE** ("All files access") so recordings can be
written to any user-chosen folder via the custom directory navigator. This is a sensitive permission;
Google Play restricts its use to apps that genuinely need broad file access (a general-purpose
recorder arguably qualifies, but a Play submission would need justification). An alternative design
using the Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) would avoid the permission but can't
present the custom navigator/behaviour the spec asked for (create-folder + off-limits indicators).

## Storage-time estimate is approximate
The "available recording time" figure divides free bytes by the encoded bytes/second. For compressed
formats it uses the target bitrate and ignores container overhead, so real files are marginally
larger. For WAV it is exact (PCM). Good enough for a capacity hint.

## Input selection is a hint
`AudioRecord.setPreferredDevice()` asks the platform to route capture to a given device; it can be
refused (returns false, or routing silently lands elsewhere). That is why the home screen reports
`getRoutedDevice()` while recording rather than assuming the request took. If a device is refused
there is nothing further the app can do — the fallback is Android's own mic priority.

## The "effective device" under Automatic is a guess
The pre-record sample-rate warning needs to know which device a recording will use. With an explicit
input selection that's exact; under the default **Automatic** setting there is no selection, so
`InputDevices.effective()` stands in Android's own priority (any attached external mic ahead of the
built-in one). That matches observed behaviour but isn't guaranteed. It is used **only** for the
advisory — never for routing — so the worst case is a warning shown or missed, and `getRoutedDevice()`
still reports the truth once recording starts.

## The downsampling notice infers the capture rate rather than reading it

`InputDevices.downsampleFrom` reports the *highest rate the device declares* as what a lower-rate
recording will be resampled down from. That is an inference, not a measurement: `getSampleRates()`
enumerates what the platform will **deliver** — including rates it produces by resampling — not what
the ADC runs at, and there is no API that exposes the latter. On every device seen so far the list
runs from 8 kHz up to the native rate, so the maximum is a good estimate; a device that declared a
rate *above* its native one would make the notice overstate the loss.

Two consequences worth knowing:

- **It fires on every below-ceiling recording, by design.** Choosing 16 kHz for a voice memo is
  normal and deliberate, so the line will be there nearly every time a low rate is picked. That is
  why it is dim, 12sp and non-clickable rather than a warning — see `pr_rate_note` versus
  `pr_rate_warning` in `dialog_pre_record.xml`.
- **It says nothing about resampling quality.** The platform's resampler is a black box; the notice
  reports only that a conversion happens.

This is also why plain downsampling can't be detected the way upsampling is. `rateAdvice` works by
set membership — a rate the device doesn't list will be resampled — and a below-native rate *is*
listed, so membership alone can't see it. The two advisories are therefore mutually exclusive by
construction, which `InputDevicesTest` asserts across the whole candidate list.

## Encoders lie about their sample rates, so the probe configures them for real
Declared codec capabilities cannot be trusted. Measured on the Galaxy S22 (SM-S908U, Android 16):
`c2.sec.aac.encoder` advertises `<Limit name="sample-rate" ranges="8000-96000"/>`,
`MediaCodecList.findEncoderForFormat` accordingly returns it for 96 kHz AAC, and `configure()`
succeeds without complaint — but the codec then runs at 44.1 kHz
(`C2SecAacEnc: setAudioParams: 44100 Hz`). Because the capture loop keeps feeding 96 kHz PCM, the
result is a file whose audio is pitched down and stretched by 96000/44100: a 23-second recording
produced a 49.3-second file. Nothing failed and nothing was logged as an error.

`SampleRateSupport.encoderSupports` therefore creates the codec, configures it, starts it, and reads
`outputFormat`'s `KEY_SAMPLE_RATE` back, accepting the rate only when it matches what was asked. The
asymmetry is deliberate: a rate is rejected **only** on a successfully-read mismatch, while any
exception leaves it in place, so a codec that is merely busy is never mistaken for one that can't do
the rate.

Residual limits:
- **The trial costs a configure per candidate rate** (~10 for AAC), so the first open of a rate
  picker for a given format does real work. It is cached per `format:channels` for the process
  lifetime, so it happens once. It has not been observed to cause visible jank, but it runs on the
  main thread; if that ever bites, warm the cache off-thread at startup.
- **It still only proves the codec honours the rate, not that the microphone does.** A 96 kHz WAV on
  the built-in mic is genuinely written at 96 kHz, but upsampled by the platform from the 48 kHz the
  hardware actually captures — real bandwidth doesn't appear. That is what `InputDevices.rateAdvice`
  warns about, and it is advisory by design.
- **The capture veto is not device-specific.** `getMinBufferSize` reports the platform's general
  capability, not the currently-routed input's, so the cache does not need invalidating when a USB
  interface is plugged in — but it also means capture is the weaker of the two vetoes.

## Channel-count mismatches are not flagged
`AudioDeviceInfo.getChannelCounts()` is read and merged into `InputOption`, but nothing warns when
you ask for stereo from a mono input (or vice versa) — only sample rate is checked. Channels are a
Settings-screen default rather than a per-recording choice, so there's no natural place to offer a
one-tap fix the way the rate warning does. The data is already there if this becomes worth doing.

## Raw capture may not be raw
**Settings → Microphone processing → Raw** maps to `MediaRecorder.AudioSource.UNPROCESSED`, which is
an *optional* platform feature. On a device that doesn't declare
`PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED`, the app uses `VOICE_RECOGNITION` — the documented
lowest-processing alternative, but still not guaranteed to be free of AGC or noise suppression. The
settings summary states this outright. There is no API to verify what processing is actually applied.

## Not yet implemented (possible future work)
- In-app playback / list of past recordings.

## Verification status

### On real hardware — Galaxy S22 Ultra (SM-S908U, Android 16), v0.12.2
Sample-rate work verified on-device, files pulled and headers parsed:
- **Offered rates match the hardware, per format.** WAV 8 – 96 kHz (all ten candidates); M4A 8 –
  48 kHz; Opus 8 / 12 / 16 / 24 / 48 kHz — the last exactly matching
  `c2.android.opus.encoder`'s declared `8000,12000,16000,24000,48000`. Before this change all three
  formats offered the same five rates, so Opus was being offered two it cannot encode.
- **96 kHz WAV records correctly:** header reports `sampleRate 96000`, `byteRate 192000`, and 7.28 s
  of audio for a ~7 s recording — no time dilation.
- **48 kHz M4A is unregressed:** `esds` reports 48 kHz, 13.48 s for a ~13 s recording.
- **The 96 kHz AAC trap is closed.** Before the trial-configure probe, M4A offered 96 kHz and
  produced a 44.1 kHz file 2.18x too long; it is no longer offered. See "Encoders lie" above.
- **Stored settings are repaired on upgrade:** a default left at M4A / 96 kHz by the previous build
  snapped to 48 kHz on first open of Settings, via `AudioFormatSpec.snap`.
- **The rate advisory fires when it should and only then:** at 96 kHz it read "Phone microphone
  records at 8 / 11.025 / 12 / 16 / 22.05 / 24 / 32 / 44.1 / 48 kHz — tap to record at 48 kHz
  instead"; at 48 kHz no warning appeared.
- Not covered: the USB microphone itself (not connected during this session), and stereo — every
  check above was mono.

Sample-rate advisories re-checked on the same phone at **v0.12.3**, after the downsampling notice was
added. All four states behave, and the two lines were never both on screen:

| Rate (WAV, phone mic) | Shown |
|---|---|
| 8 kHz | note: *"Phone microphone captures up to 48 kHz; this will be resampled down to 8 kHz."* |
| 44.1 kHz | the same note, reading "down to 44.1 kHz" |
| 48 kHz | nothing — the mic's ceiling |
| 96 kHz | the red warning, *"…tap to record at 48 kHz instead"*, and no note |

Tapping the 96 kHz warning moved the rate to 48 kHz and left both lines hidden, so the note's own
refresh path is exercised too.

### On an Android 10 emulator equivalent (Pixel 7, API 34)
- First-run permission flow, folder navigator, and **create-folder** (auto-navigates into the new
  folder) all work; storage-time readout updates and is format-aware.
- End-to-end recording verified for **all three formats** — the produced files have valid headers:
  M4A (`ftyp`/`moov`/`mdat`/`mp4a`/`esds`), Opus (`OggS`/`OpusHead`), WAV (patched RIFF/WAVE).
- The live frequency analyzer renders while recording and hides on stop. Its DSP is covered by JVM
  unit tests (`SpectrumAnalyzerTest`): silence stays flat, a tone lights a band, the peak band rises
  with frequency, and the FFT matches a naive DFT. (The emulator mic is digital silence, so the
  on-device meter shows flat bars — reactivity is validated by the unit tests, not the emulator.)
