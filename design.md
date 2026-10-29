# Voice Recorder — design

Internal design notes. `README.md` is the user-facing doc (features, build, file-by-file table);
this file records *how the pieces fit together* and *why*. Keep both current when features change.

## Component map

```
MainActivity ──binds──> RecordingService ──owns──> Recorder (PcmRecorder)
     │                        │                        ├─> InputDevices (which mic)
     │                        │                        ├─> Gain (in place, first)
     │                        │                        ├─> PcmEncoder (Wav | MediaCodec)
     │                        ├─> AudioDeviceCallback  (pauses if the chosen input goes)
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
- **Dialogs answer with fragment results, never callbacks.** A `DialogFragment` can outlive the
  Activity that opened it. After the process is killed in the background (Samsung does this to
  apps left asleep) or on a configuration change, Android recreates the Activity *and* the open
  dialog, building the dialog fresh from its saved arguments. A callback the old Activity had set
  on it is gone. `PreRecordDialog` and `DirectoryPickerDialog` used to work that way, and after a
  sleep "Start recording" (or "Use this folder") closed the dialog and silently did nothing. Now
  each dialog posts a `FragmentManager.setFragmentResult` under its `REQUEST_KEY`, and each host
  registers a listener in `onCreate`. Every instance has one, including a recreated one. Each
  dialog also saves the choices it holds in fields (the format, rate and bitrate overrides; the
  folder browsed to), so a recreated dialog carries on where the user was. Views with IDs, such as
  the name field and the format spinner, restore themselves.

## Recording start flow

1. `MainActivity.onRecordPressed()` — permission / save-folder gates, then the input gate (if the
   chosen input isn't attached, the "Input not connected" prompt opens instead — see *A missing
   input asks*), then shows `PreRecordDialog` seeded with `Settings.startingBaseName()`.
2. `PreRecordDialog` resolves the *spec* (format / sample rate / bitrate / channels) starting from
   `Settings.defaultSpec()`, letting the user override it for this recording only. Confirming
   posts the name and spec as a fragment result (`PreRecordDialog.REQUEST_KEY`). The listener
   `MainActivity.onCreate` registered turns that into step 3, even when the Activity has been
   recreated since the dialog opened.
3. `beginRecording(baseName, spec)` re-checks the input (it can be unplugged while the dialog is
   open), resolves the output path with `StorageUtil.uniqueFile()`, starts the service, and — only
   once `startRecording` succeeded — calls `Settings.rememberBaseName()`.

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
- **Matching degrades within a type, never across one.** `InputDevices.match()` tries the exact
  key, then *any* device of the same type. "Record from USB" keeps meaning USB even if the interface
  reports a different product string. When nothing of that type is attached the answer is null, and
  the phone's mic is not offered in its place (next section).
- **The selection lives on the service, like gain.** `RecordingService.inputDeviceKey` re-resolves
  and pushes into `Recorder.preferredInput`, which `AudioRecord` accepts on a *running* stream — so
  switching inputs mid-recording re-routes without breaking the file.
- **Show what actually happened.** Because the hint can be refused, `PcmRecorder.routedInputLabel`
  reads `AudioRecord.getRoutedDevice()` and the home screen displays "Recording from: …" (with an
  explicit "requested device unavailable" variant, which now only means an *attached* device that
  the platform routed past — an absent one pauses instead). Shown only while capturing, since a
  paused stream has no meaningful route. Polled once a second off the existing 50 ms tick —
  routing changes rarely and the read is a native call.
- **Duplicates are collapsed.** Phones report several `TYPE_BUILTIN_MIC` capsules; `dedupe()` keys
  them down to one row so the picker isn't three identical "Phone microphone" entries.
- **Labels live in code, not `strings.xml`**, so the whole enumerate/label/match core stays pure and
  JVM-testable (`InputDevicesTest`) — the same trade-off `Gain.label` makes. The *sentences* (the
  rate warning, the processing summaries) are resources; only device names are code.

### A missing input asks; it never falls back

Until 0.13 a chosen input that wasn't attached resolved to `null`, which meant "set no preference",
which meant Android's default route: the phone's own mic. That is the one substitution the app must
not make quietly, so a chosen input that isn't attached now **stops and asks**, at every point
where a recording can begin or carry on.

- **Three outcomes, not a nullable device.** `InputDevices.resolve()` returns `Resolved.Automatic`
  (set no preference), `Resolved.Device` (route to it) or `Resolved.Unavailable` (ask). "No
  preference" is right for Automatic and wrong for a missing device. Folding both into `null` *was*
  the bug, so the type keeps them apart and every caller's `when` has to say what it does with
  `Unavailable`. The pure equivalent, over `InputOption`s, is `InputDevices.isUnavailable()`.
- **Before recording: the prompt.** `MainActivity.onRecordPressed()` checks before the pre-record
  dialog opens, and `beginRecording()` checks again. Either one opens the "Input not connected"
  prompt: the inputs attached now, plus Automatic, under a header naming the missing device.
  Choosing one carries on with the step that was interrupted. Plugging the missing device back in
  while the prompt is up does the same, unasked. AlertDialog can't show a message and a list
  together, so the header is a custom title (`dialog_input_prompt_title.xml`).
- **The service refuses too.** `RecordingService.startRecording()` throws
  `InputUnavailableException` on `Unavailable`. It's the last line of defence, so a future caller
  that skips the UI check still can't start a recording on a stand-in.
- **Mid-recording: pause, don't re-route.** When a preferred device disappears, Android moves the
  stream to its default by itself. So the service registers an `AudioDeviceCallback` for the life
  of each recording, and `syncInput()` applies `InputDevices.stepFor()` after every device change
  and every selection change:

  | State | Input attached? | Step |
  |---|---|---|
  | capturing | no | **pause**, and set `pausedForInput` |
  | paused (either kind) | no | keep — nothing is being captured |
  | paused for the input | yes (back, or another chosen) | **resume** |
  | paused by the user | yes | keep — a reconnect never ends the user's pause |
  | capturing | yes | keep (re-pointed at the fresh device) |

  The file keeps a gap rather than a stretch from a mic nobody chose. `stepFor` is pure and
  JVM-tested. The service only does what it says.
- **Resume asks as well.** `resumeRecording()` resolves again (an unplug and replug during the pause
  changes the device id) and returns false while the input is missing. It also turns the pause into
  a `pausedForInput` one, so the prompt appears and plugging the device back in finishes the resume
  the user asked for.
- **The mid-recording prompt mirrors the service.** "Input disconnected" is up exactly while
  `pausedForInput` is set, unless the user chose "Stay paused", and it is raised again on every
  rebind. That rebind is how a loss that happened with the screen off reaches the user. Until then,
  the ongoing notification reads "Paused: input disconnected". `onInputPauseChanged` tells a visible
  Activity whenever the service pauses or resumes on its own.
- **Automatic is exempt by definition.** It means "whatever Android picks", so unplugging a USB mic
  under Automatic still lands on the phone's mic, as it always did.

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

## A failed take keeps what it captured

Before 0.13.2 a capture error threw the whole recording away. The capture loop's catch block called
`PcmEncoder.abort()`, which deletes the output, and the screen said "Recording failed". An hour of
audio could vanish over a read error in its last second.

- **Every take ends through `endTake()`.** The capture loop no longer finishes or aborts the
  encoder itself. Whatever was captured is finalised, error or not. Only a take that failed before
  capturing anything is discarded, since there's nothing in it to keep. The error reported is the
  capture failure if there was one, else any failure to finalise. A failure to finalise never
  deletes the file either: a part-written file can be repaired, and a deleted one can't. `endTake`
  is pure and JVM-tested against the real WAV encoder (`PartialRecordingTest`).
- **The recording stops when capture does.** The recorder calls `Recorder.onCaptureFailed`, and
  the service stops at once. Before, nothing noticed: the capture thread just ended, and the screen
  kept saying "Recording…" with the timer running until the user pressed Stop.
- **The report gets through.** `onStopped` now passes the kept file *and* the error, and the screen
  says both: "Recording ended with an error … saved as X". The wording fits the rarer case too,
  where Stop was pressed and only finalising failed, which isn't "stopping early". If no screen is
  listening, as when the failure happens in the background, the service holds a `StopReport` that
  the Activity takes on its next bind. `lastError` was meant for this, but nothing ever read it,
  so it's gone.
- **`finish()` never deletes.** `MediaCodecPcmEncoder.finish()` used to call `abort()` when nothing
  was open, so finishing twice deleted the file the first call had just saved. It also now always
  reaches `MediaMuxer.stop()`, even after a codec failure. That call writes the MP4 index, and
  without it nothing captured would play.
- **A WAV survives never being finished.** Its header is written complete at `start()`, with both
  sizes set to 0xFFFFFFFF ("unknown"), and filled in at `finish()`. A take cut off by the app being
  killed therefore still opens, because ffmpeg and libsndfile read the unknown sizes as "to the end
  of the file". The 44 zero bytes that used to sit there aren't recognised as WAV at all. A size of
  0 wouldn't do as the marker: libsndfile reads that as an empty file.
- **Cancel still discards.** `Recorder.cancel()` deletes by definition, and is only used when a
  recording fails to start.

## Testing

`app/src/test` holds JVM-only tests (no Android framework): `SpectrumAnalyzerTest` for the DSP,
`FilenameHistoryTest` for the MRU/uniquing rules, `GainTest` for the gain maths and clip counting,
`InputDevicesTest` for the input labelling/key/matching/rate-advice rules and the missing-input
rules (`isUnavailable`, and `stepFor`'s pause/resume table), `CaptureSourceTest` for
the audio-source mapping and its fallback, `AudioFormatSpecTest` for rate labelling, `snap()`, the
per-format encoder mimes and the bytes/second estimate, and `PartialRecordingTest` for what a
take that doesn't end cleanly leaves on disk (`endTake`, and the WAV header before and after
`finish()`; `WavPcmEncoder` is plain `java.io`, so it runs there for real). Anything worth testing
should be factored into a pure function like these rather than tested through Android classes.

`SampleRateSupport` itself is deliberately *not* tested: every branch is a native probe, so there
would be nothing left to assert once they were stubbed. What it feeds — `snap()` and the rate
advisory — is pure and covered.

Note that `AudioDeviceInfo.TYPE_*` and `MediaRecorder.AudioSource.*` are `static final int`
constants, so they inline at compile time and are safe to reference from these tests even though
there's no Android framework underneath.

## Known limitations

See `known-issues.md` (WMA unsupported, Opus needs API 29+, storage-permission trade-offs).

## Publishing

Prepared for F-Droid, which builds the app itself from a `v*` tag:

- `fastlane/metadata/android/en-US/` is the store listing F-Droid reads from this repository:
  title, short and full description, `images/icon.png` (the launcher icon rendered at 512 px:
  F-Droid cannot read an icon from the APK, whose icon is vector-only), and one
  `changelogs/<versionCode>.txt` per version. Each version bump needs its changelog file.
- `metadata/com.lithic.voicerecorder.yml` is the build recipe for F-Droid's fdroiddata
  repository, kept exactly as `fdroid rewritemeta` writes it (fdroiddata's CI requires that, so
  it has no comments). It is a draft until the app is accepted; then fdroiddata's copy is the
  one F-Droid uses, and this one can go.
- `app/build.gradle.kts` keeps the Google-encrypted dependency block out of the APK
  (`dependenciesInfo`), which F-Droid's scanner rejects, and the VCS info file out of release
  builds (`vcsInfo`). Both must stay.
- F-Droid builds the unsigned release APK and signs it with its own key, so an F-Droid install
  and a GitHub (debug-signed) install cannot update each other.
- `publish-release.bat` tags the commit the APK was built from (`--target`), so the tag F-Droid
  builds is the released code; GitHub refuses a commit that was not pushed.
