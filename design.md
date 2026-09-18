# Voice Recorder — design

Internal design notes. `README.md` is the user-facing doc (features, build, file-by-file table);
this file records *how the pieces fit together* and *why*. Keep both current when features change.

## Component map

```
MainActivity ──binds──> RecordingService ──owns──> Recorder (PcmRecorder)
     │                        │                        ├─> InputDevices (which mic)
     │                        │                        ├─> Gain (in place, first)
     │                        │                        ├─> PcmEncoder (Wav | MediaCodec)
     │                        └─> SpectrumAnalyzer ──> SpectrumView (via MainActivity poll)
     ├─ PreRecordDialog        (name + per-recording format overrides)
     ├─ DirectoryPickerDialog  (save folder)
     └─ SettingsActivity       (defaults)
                    all persisted state ──> Settings (SharedPreferences)
```

- **One capture engine.** `PcmRecorder` owns the single `AudioRecord` and tees PCM to both the
  encoder and the analyzer, so the spectrum works for every format (no `MediaRecorder` path).
- **Service owns the recording, Activity owns the UI.** The Activity can die/rebind; recording state
  lives in the foreground service and the UI re-derives itself in `refreshUi()`.
- **Settings is the only persistence.** No database; everything is SharedPreferences-backed
  properties on `Settings`.

## Recording start flow

1. `MainActivity.onRecordPressed()` — permission / save-folder gates, then shows `PreRecordDialog`
   seeded with `Settings.startingBaseName()`.
2. `PreRecordDialog` resolves the *spec* (format / sample rate / bitrate / channels) starting from
   `Settings.defaultSpec()`, letting the user override it for this recording only.
3. `beginRecording(baseName, spec)` resolves the output path with `StorageUtil.uniqueFile()`, starts
   the service, and — only once `startRecording` succeeded — calls `Settings.rememberBaseName()`.

## Filename model

Three separate concepts, deliberately kept apart:

| Concept | Where | Notes |
|---|---|---|
| **Pattern** | `Settings.filenamePattern` → `suggestedBaseName()` | `SimpleDateFormat` pattern; the "fresh timestamp" name. |
| **Starting name** | `Settings.startingBaseName()` | What the dialog prefills: the most recent remembered name when `reuseLastFilename` is on, else a fresh timestamp. |
| **On-disk name** | `StorageUtil.nextAvailableName()` / `uniqueFile()` | Adds `_2`, `_3`, … until free. **Never overwrites.** |

- The **MRU list** (`Settings.recentBaseNames`, JSON in prefs, newest first, capped at
  `MAX_RECENT_NAMES`) stores the *base name the user typed*, not the suffixed on-disk name — so a
  second take of "Interview" starts from "Interview" again and lands on `Interview_2.m4a`.
- `Settings.mergeRecent()` and `StorageUtil.nextAvailableName()` are pure (no Context, no
  filesystem) so they're covered by JVM unit tests in `FilenameHistoryTest`.
- The dialog shows the resolved on-disk name live ("Will save as …"), recomputed on every keystroke
  and on format change (the extension is part of the collision check).
- The **▾ button** opens a full list of remembered names plus a "New timestamp" escape hatch; the
  field's `AutoCompleteTextView` adapter additionally offers prefix matches while typing. Both exist
  because autocomplete only prefix-matches and is invisible once the field is prefilled.

## Input device selection

Android exposes no "record from device X" constructor argument. `AudioRecord.setPreferredDevice()`
is a **routing hint**, so the whole feature is built around that hint being refusable:

- **Persist a key, not an id.** `AudioDeviceInfo.getId()` is reassigned every time a device is
  unplugged and replugged, so storing it would silently lose the user's USB mic on the next session.
  `Settings.inputDeviceKey` stores `InputDevices.InputOption.key` — `"$type:$productName"` — and the
  live `AudioDeviceInfo` is re-resolved from it at each `startRecording()`.
- **Matching degrades, it doesn't fail.** `InputDevices.match()` tries the exact key, then *any*
  device of the same type. "Record from USB" keeps meaning USB even if the interface reports a
  different product string, instead of quietly reverting to the phone mic.
- **The selection lives on the service, like gain.** `RecordingService.inputDeviceKey` re-resolves
  and pushes into `Recorder.preferredInput`, which `AudioRecord` accepts on a *running* stream — so
  switching inputs mid-recording re-routes without breaking the file.
- **Show what actually happened.** Because the hint can be refused, `PcmRecorder.routedInputLabel`
  reads `AudioRecord.getRoutedDevice()` and the home screen displays "Recording from: …" (with an
  explicit "requested device unavailable" variant). Polled once a second off the existing 50 ms
  tick — routing changes rarely and the read is a native call.
- **Duplicates are collapsed.** Phones report several `TYPE_BUILTIN_MIC` capsules; `dedupe()` keys
  them down to one row so the picker isn't three identical "Phone microphone" entries.
- **Labels live in code, not `strings.xml`**, so the whole enumerate/label/match core stays pure and
  JVM-testable (`InputDevicesTest`) — the same trade-off `Gain.label` makes. The *sentences* (the
  rate warning, the processing summaries) are resources; only device names are code.

### Sample-rate advice

`AudioDeviceInfo.getSampleRates()` says what the input can do natively. `InputDevices.rateAdvice()`
turns that plus the requested rate into a nullable advisory, which the pre-record dialog renders
under the rate/bitrate buttons and makes tappable to adopt the device's rate.

**Two lines, two temperaments.** Resampling in the two directions deserves different treatment, so
there are two independent advisories and the pre-record dialog has a `TextView` for each:

| | Function | Row | Look | Tappable |
|---|---|---|---|---|
| Asking for **more** than the input has | `rateAdvice()` | `pr_rate_warning` | `warn_red`, 13sp | yes — adopts the device's rate |
| Asking for **less** than the input has | `downsampleFrom()` | `pr_rate_note` | `dim_text`, 12sp | no |

Upsampling is pure waste — a bigger file carrying no more information — so it gets a warning with
the fix one tap away. Downsampling is the ordinary reason to pick a low rate at all (a deliberate
size/quality trade), so it gets a statement of fact and nothing to press.

- **The two are mutually exclusive by construction, not by sequencing.** `downsampleFrom()` returns
  null unless the requested rate is one the device declares — which is exactly the case `rateAdvice`
  returns null for. Neither caller has to check the other, and `InputDevicesTest` asserts the
  disjointness across the whole candidate list rather than trusting the argument.
- **The note's "from" rate is the top declared rate, which is a proxy.** `getSampleRates()` reports
  what the platform will *deliver*, including rates it manufactures by resampling — not what the ADC
  runs at. Devices list everything at or below native, so the maximum is the best estimate on offer.
  This is also why plain downsampling can't be detected the way upsampling is: the requested rate is
  a member of the list either way, so set membership alone says nothing.
- **Empty means "unspecified", not "supports nothing".** The platform lets a device decline to
  enumerate, so an empty list must be read as "anything goes". `dedupe()`'s `mergeCaps` honours the
  same rule: merging a known list with an unspecified one yields unspecified, rather than falsely
  narrowing the surviving row to the one capsule that happened to answer.
- **The suggestion is intersected with what the app offers.** `rateAdvice`'s `offered` argument is
  the *per-format* list from `SampleRateSupport`, not the raw candidates, so tapping the fix can
  never select a rate the chosen format can't encode. A device whose only rate the format rejects
  produces a warning with no fix — `RateAdvice.suggested` is nullable for exactly that case. Now that
  WAV can reach 96 kHz this is rarer than it was: a 96 kHz-only interface is offerable in WAV, and
  only stays unfixable in Opus/AAC.
- **The advice follows `effective()`, not `match()`.** Under the default *Automatic* selection there
  is no chosen device, but a plugged-in USB mic is precisely what Android will route to — so
  `effective()` stands in Android's priority (external ahead of built-in). It is advisory only and
  never used for routing; being wrong costs a warning shown or missed.

## Which sample rates are offered

`AudioFormatSpec.SAMPLE_RATES` is a list of *candidates*, not of choices. It was originally the
choice list — five hardcoded values applied to all three formats — which was wrong in both
directions at once: it capped WAV at 48 kHz on hardware that can do 96, **and** it offered Opus at
22.05 / 44.1 kHz, which a 48 kHz-family codec cannot encode. `SampleRateSupport.supported(format,
channels)` narrows the candidates per format and per device, and that narrowed list is what every
picker, snap and advisory uses.

- **Two independent vetoes, intersected.** `AudioRecord.getMinBufferSize` answers whether the
  *capture* path will open at that rate/channel combination; `MediaCodecList.findEncoderForFormat`
  answers whether an *encoder* exists for that exact mime/rate/channels. WAV has `mime == null`, so
  it skips the second veto entirely — which is precisely why 96 kHz can appear for WAV and nowhere
  else. `AudioFormat.mime` being nullable is what makes "no codec in this path" explicit rather than
  a special case spelled `if (format == WAV)`.
- **The probe and the encoder resolve through the same call.** `MediaCodecPcmEncoder.createEncoderFor`
  uses `findEncoderForFormat` too, rather than `createEncoderByType(mime)`. If it picked a codec by
  mime alone it could land on one that doesn't handle the probed rate, and the offered list would be
  describing a different encoder from the one configured. This is load-bearing on real hardware: an
  S22 has three AAC encoders with three different rate ceilings, and `createEncoderByType` returns
  the highest-ranked one, not the capable one.
- **Declared capability is verified, not believed.** The encoder veto doesn't stop at
  `findEncoderForFormat`; it configures and starts the codec and reads `outputFormat`'s sample rate
  back. Codecs lie — Samsung's AAC encoder advertises 96 kHz, accepts `configure()` at 96 kHz, and
  then runs at 44.1 kHz. Since the capture loop keeps feeding 96 kHz PCM, believing the advert
  yielded a file stretched and pitched down by 96000/44100, with no error anywhere. See
  `known-issues.md`.
- **Permissive when it can't answer, strict when it can.** A throwing probe *keeps* the rate and an
  empty result set falls back to the full candidate list, because hiding a usable rate is worse than
  one that fails loudly at `start()` — an empty list means the probe failed, not that the device
  records nothing, the same reading `InputDevices` applies to `getSampleRates()`. But a trial that
  *succeeds* and reports a different rate is decisive, because that case doesn't fail loudly at all;
  it silently corrupts. Hence `actualRateOf` returns a nullable, and only non-null mismatches reject.
- **Cached per `format:channels`.** The probes are native calls — and, since the trial configures a
  codec, not cheap ones — but the answer can't change for a given build, so `SampleRateSupport`
  memoises. The pickers can therefore call `supported()` on every refresh without thought, and the
  cost is paid once per format per process.

### Keeping a stored rate valid

A rate is chosen against one format and can be invalidated by a later format change, so it is
**snapped**, never trusted, at each of the three points it can enter a recording:

| Where | What snaps |
|---|---|
| `PreRecordDialog.refreshControls()` | The per-recording rate, on every format change. |
| `SettingsActivity.refresh()` | The stored default, written back when it moves. |
| `Settings.defaultSpec()` | The last line of defence — also validates the stored *format*. |

`AudioFormatSpec.snap(value, supported)` is pure (prefers an exact match, else nearest, else leaves
the value alone when `supported` is empty) and JVM-tested. `defaultSpec()` snapping matters beyond
tidiness: prefs can arrive from an older build or another device via backup restore, and without it
a 96 kHz WAV default followed by a switch to Opus would fail at `start()`.

## Capture source (voice processing)

`MediaRecorder.AudioSource` is fixed when `AudioRecord` is constructed, which is what separates this
setting from gain and input device: it **cannot** change mid-recording, so it lives in Settings (a
default) rather than on the home screen, and the UI says it applies from the next recording.

`CaptureSource.androidSource()` is the whole rule, and it's pure: `PROCESSED` → `MIC`; `RAW` →
`UNPROCESSED` when `AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` says the device has one,
otherwise `VOICE_RECOGNITION`. The fallback is the point — `UNPROCESSED` is optional, and
`VOICE_RECOGNITION` is the documented "least processing available" source. The settings row prints a
different summary in that case rather than implying the request was honoured.

## Input gain

Android has no mic pre-amp API, so gain is **software gain on the captured PCM** (`Gain.apply`),
applied in `PcmRecorder`'s capture loop **before** the buffer reaches either the encoder or the
analyzer. That ordering is the important part: the file, the spectrum meter and the clip indicator
are then guaranteed to describe the same signal.

- **Live changes.** `Recorder.gainDb` is `@Volatile`; the UI writes `RecordingService.gainDb`, which
  forwards to the running recorder, so a drag on the slider affects the very next buffer. The dB
  value is cached as a linear multiplier on write so the loop never calls `pow()`.
- **Unity is a fast path.** `Gain.linear(0)` returns exactly `1f` and `Gain.apply` returns
  immediately, so a user who never touches the slider pays nothing.
- **Clipping is data, not a guess.** `Gain.apply` returns the number of clamped samples;
  `PcmRecorder` keeps a running total plus the `elapsedRealtime()` of the last clip.
  `RecordingService.isClipping()` turns that into a ~1.2 s hold for the CLIP indicator (polled by the
  existing 50 ms UI tick), and the total drives the post-save warning. The tally is copied out of the
  recorder in `stopRecording()` before the recorder is dropped.
- **What gain cannot do.** It is post-ADC, so boosting amplifies whatever noise the converter
  captured and cannot recover clipping that happened in the analogue path. Negative gain is the
  reliable direction; that's why the range is asymmetric but bounded (−20 … +24 dB).

## Testing

`app/src/test` holds JVM-only tests (no Android framework): `SpectrumAnalyzerTest` for the DSP,
`FilenameHistoryTest` for the MRU/uniquing rules, `GainTest` for the gain maths and clip counting,
`InputDevicesTest` for the input labelling/key/matching/rate-advice rules, `CaptureSourceTest` for
the audio-source mapping and its fallback, `AudioFormatSpecTest` for rate labelling, `snap()`, the
per-format encoder mimes and the bytes/second estimate. Anything worth testing should be factored
into a pure function like these rather than tested through Android classes.

`SampleRateSupport` itself is deliberately *not* tested: every branch is a native probe, so there
would be nothing left to assert once they were stubbed. What it feeds — `snap()` and the rate
advisory — is pure and covered.

Note that `AudioDeviceInfo.TYPE_*` and `MediaRecorder.AudioSource.*` are `static final int`
constants, so they inline at compile time and are safe to reference from these tests even though
there's no Android framework underneath.

## Known limitations

See `known-issues.md` (WMA unsupported, Opus needs API 29+, storage-permission trade-offs).
