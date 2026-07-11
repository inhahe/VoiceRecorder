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

## Not yet implemented (possible future work)
- In-app playback / list of past recordings.
- Pause/resume during recording (would need manual buffering in the `PcmRecorder` capture loop and,
  for compressed formats, careful timestamping into `MediaCodec`).

## Verification status
Exercised on an Android 10 emulator equivalent (Pixel 7, API 34):
- First-run permission flow, folder navigator, and **create-folder** (auto-navigates into the new
  folder) all work; storage-time readout updates and is format-aware.
- End-to-end recording verified for **all three formats** — the produced files have valid headers:
  M4A (`ftyp`/`moov`/`mdat`/`mp4a`/`esds`), Opus (`OggS`/`OpusHead`), WAV (patched RIFF/WAVE).
- The live frequency analyzer renders while recording and hides on stop. Its DSP is covered by JVM
  unit tests (`SpectrumAnalyzerTest`): silence stays flat, a tone lights a band, the peak band rises
  with frequency, and the FFT matches a naive DFT. (The emulator mic is digital silence, so the
  on-device meter shows flat bars — reactivity is validated by the unit tests, not the emulator.)
