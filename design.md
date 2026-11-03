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
     ├─ RecordingsActivity     (the save folder's recordings + the player)
     │      └─> RecordingsModel ──> PlayerController ──> ExoOutput (AudioOutput: Media3) ──> MeterSink ──> PlaybackMeterQueue
     ├─ DirectoryPickerDialog  (save folder)
     └─ SettingsActivity       (defaults)
                    all persisted state ──> Settings (SharedPreferences)
```

- **One capture engine.** `PcmRecorder` owns the single `AudioRecord` and tees PCM to both the
  encoder and the analyzer, so the spectrum works for every format (no `MediaRecorder` path).
  It reads a spectrum hop at a time (20 ms, `SpectrumAnalyzer.hopFor`, through
  `CaptureLoop.readBytes`) from an `AudioRecord` buffer of twice the device's minimum: reading the
  whole buffer at once (160 ms on some phones) handed the meter its analyses in bursts, so it moved
  in steps. `AudioRecord` keeps the whole buffer either way, so a late read loses nothing, and both
  encoders take chunks of any size. The MediaCodec encoder takes only the output that is ready
  while recording, rather than waiting up to 10 ms for more after every read, so the capture
  thread spends its time reading. Feeding the codec can still wait for a free input buffer, when
  the codec falls behind.
- **The analyzer belongs to the capture thread,** which pushes to it and also clears it when it
  sees a pause (`CaptureLoop`, the loop apart from `AudioRecord`, so that is tested on the JVM);
  only `takeBandsInto` is called elsewhere. Cleared from the main thread, a buffer the capture thread
  was still reading would light the meter again for the whole pause.
- **What the bars show doesn't depend on the phone or the rate.** `AudioRecord` picks the buffer
  size per device, and the rate is the user's. So the analyzer runs `UPDATES_PER_SECOND` times per
  second of audio, at fixed points in it (inside a buffer when the buffer is longer); each analysis
  looks at a fixed length of time (`WINDOW_SECONDS`, 2048 frames at 48 kHz, zero-padded to a power
  of two for the FFT), at least two hops long, so consecutive Hann windows overlap by half and no
  sound slips between them; and `SpectrumView`, reading `takeSpectrum` on every frame, gets the
  latest analysis, but no lower than the highest each band rose to since the previous read. A
  rise is what a read between analyses would miss (several can land between two frames, a click
  among the first decayed by the last); a fall it can't miss, so a falling bar shows where it is
  now rather than at the oldest of them. Only rises of the last half second count (each band
  keeps its rise per analysis in a ring of the last half second's, so a lower rise behind a higher
  one survives the higher one ageing out), so the screen coming back on doesn't flash a sound long
  past. The view raises a bar at once and eases it down (`BarMotion`, timed rather than per frame,
  so it falls as fast on a 120 Hz screen as on a 60 Hz one), so a click is drawn at its full height
  rather than eased towards it.
- **The meter costs nothing when nothing changes.** It reads from a `Choreographer` frame callback
  that runs only while it is active and visible (`onVisibilityAggregated`: not in the background),
  into its own array (`takeBandsInto`, no array per frame). It redraws only when a bar has moved by
  half a pixel, and the analyzer floors a decayed band to 0, since a peak that shrinks by a factor
  never gets there. When no new audio came in (`takeBandsInto` says so) and the bars have settled,
  as when paused, or the meter is scrolled out of sight, it looks again ten times a second rather
  than on every frame. Hidden, it forgets its bars, so coming back shows the sound now. The 50 ms
  UI tick likewise formats and sets the timer only when its second changes: a `TextView` relays
  itself out on every set, changed or not.
- **After a reset the window starts as silence** and the analyses run on the usual grid from the
  first hop, so the first moments after Record or Resume count as much as any. The rate comes with
  every `push`, and a new one starts the meter afresh.
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
  `pausedForInput` is set, unless the user put it away, and it is raised again on every rebind.
  That rebind is how a loss that happened with the screen off reaches the user. Until then, the
  ongoing notification reads "Paused: input disconnected". `onInputPauseChanged` tells a visible
  Activity whenever the service pauses or resumes on its own.
- **"Stay paused" makes the pause the user's.** It calls `holdPause()`: the input coming back no
  longer resumes the recording (it may have been plugged in for something else), only Resume
  does, as with any pause the user made, and the notification and status line say "paused" rather
  than that the input is missing. Back or a tap outside only hides the prompt; the recording still
  resumes when the input returns.
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

## Recordings and the player

`RecordingsActivity` lists the save folder (`RecordingFiles.list`: audio extensions, newest
first, each file's size and time read once and sorted on, hidden files left out, since MediaStore's
trash and unfinished writes are `.trashed-…` and `.pending-…` files beside the recordings) and reads
each recording's length, one at a time, on one background thread, so a slow card never holds up
the screen. Each listing is numbered: an older one finishing late is dropped, and the lengths it
queued are skipped once a newer listing is asked for, so a listing after a delete or a rename
doesn't wait behind them. Lengths are cached by path, size and time for as long as the app runs
(`RecordingLengths`), so opening the screen again doesn't measure every file again. A WAV's comes
from its header
(`WavLayout`), which a take cut off by the app being killed still has, its sizes still the
0xFFFFFFFF written at the start: read as "to the end of the file", in whole frames, which is what
ffmpeg and libsndfile do with it. Anything else is asked of `MediaMetadataRetriever`, and a file it can't
read shows "?". A `FileObserver` on the folder lists it again 300 ms after the last change, while
the screen is started.

- **The player outlives the screen's recreation.** `RecordingsModel`, an `AndroidViewModel`, holds
  the `PlayerController` and its audio-focus request, so turning the phone doesn't stop a
  recording mid-play. The screen pauses it in `onStop` unless it is only being recreated
  (`isChangingConfigurations`): playing on with the screen gone would leave no way to stop it. A
  recording still opening then is kept from starting (a pause while it opens clears
  `PlayerController`'s play-when-ready, and the play button shows "pause" for one about to play),
  and "Play" from the "Saved" message waits for the screen to be started, and survives its
  recreation.
- **The rules are on the JVM.** `PlayerController` drives an `AudioOutput` (`ExoOutput` on a
  phone, a fake in `PlayerControllerTest`): play once open, pause and resume, the end showing the
  full length with Play starting again from the beginning, an error kept with the file still named
  so Play tries again, a seek kept within the recording. It makes an output per recording and
  ignores callbacks from any it has let go of, so a slow open overtaken by another choice can't
  start playing.
- **Sound etiquette.** It asks for transient audio focus and pauses on any loss, ducking included:
  a voice recording under a call or a navigation prompt is not music to play quietly beneath it.
  Refused focus (a call under way) leaves it paused and says so in the player bar
  (`PlayerController.notice`), rather than Play doing nothing. Headphones unplugged
  (`ACTION_AUDIO_BECOMING_NOISY`) pause it before the speaker takes over. The player and its focus
  request share one set of `AudioAttributes` (`ExoOutput.ATTRIBUTES`), since the system
  decides from them how to treat the sound.
- **Renaming and deleting.** The player opens the file once and reads it only through that open
  file (`ExoOutput.OpenFileDataSource`), however often it opens its source again (each seek does),
  so a rename doesn't interrupt playing, nor a seek after one, and `PlayerController.renamed`
  follows it. A delete first lets
  go of the file (`release`). Renames keep the extension, refuse a name that
  another file has (`Files.isSameFile`: on storage that ignores case, the new case of this file's
  own name is the same file) or that starts with a dot (the file would be hidden, and leave the
  list), and use the record dialog's character rules (`RecordingFiles.ILLEGAL_FILENAME_CHARS`,
  shared with it). A change of case alone goes through a temporary name (`RecordingFiles.rename`):
  on shared storage, which ignores case, renaming straight to it succeeds and changes nothing
  (measured on the emulator), and a rename only counts once the folder lists the new name. The
  temporary name is the new one plus `.renaming`: not audio, so not listed, but not hidden either,
  and a rename the app died in the middle of is finished before the next listing
  (`finishRenames`). A recording that couldn't be opened or played stays named in the player, so
  Play tries again, but isn't marked in the list.
- **The take being recorded is left alone.** `RecordingService.activeFile` is the service's
  current file, so the screen, which doesn't bind to the service, shows it as "Recording…" and
  won't play, measure, rename, share or delete it. Playing anything else aloud while it records
  asks first, unless media goes to headphones (wired, USB, Bluetooth A2DP or LE; not SCO, which is
  for calls; from Android 13, where Android actually routes the player's sound): from the
  speaker, the microphone would record it into the take.
- **Seeking.** A drag seeks when let go; a key or TalkBack's adjust action moves the bar without a
  drag and seeks at once. A bar disabled mid-drag never reports the drag's end, so disabling it
  ends the drag, or the position would stay frozen after.
- **The player is Media3's ExoPlayer** (`ExoOutput`, `androidx.media3:media3-exoplayer`; 1.9 is
  the newest that builds against compileSdk 35). `MediaPlayer` plays a file but never hands over its
  audio, which the spectrum needs. A decoder and `AudioTrack` of the app's own did that in 0.15 and
  0.16, and its review found what an output has to get right on real phones: a track restarted
  with less than its start threshold never starts (a seek near the end hung), the position during
  a seek not yet applied, the output's latency (Bluetooth), a change of format mid-file, float and
  24-bit PCM, buffer sizes in whole frames, more than two channels. ExoPlayer's audio sink handles
  each of these, and has been run on thousands of devices; the APK grew by about 2.8 MB. Only an
  audio renderer is built. Constant-bitrate seeking is on, for files with no index of their own
  (ADTS AAC, some MP3s). At the end the player is paused, so a seek after it (the bar, Back to the
  start) stays paused until Play. Audio focus stays with `PlayerController`'s rules, so ExoPlayer
  doesn't handle it. A file that can't be parsed or decoded says "damaged, or in a format this
  phone doesn't play"; anything else gives the cause's message.
- **The spectrum during playback is what the output has played.** ExoPlayer's own audio sink is
  wrapped (`ExoOutput.MeterSink`, a `ForwardingAudioSink`): each decoded buffer, the first time it
  is handed over, is converted to 16-bit (`Pcm16`, any PCM encoding; one it doesn't read is
  skipped rather than shown as noise) and queued (`PlaybackMeterQueue`) by its presentation time;
  and the time the output has reached is noted each time the player asks the sink, about every
  10 ms while playing. The two are the sink's own timebase (Media3 adds an offset to media times,
  which both carry), and that position allows for the output's latency, so the bars move with
  what is heard. The queue is read on the main thread, as the player bar's `SpectrumView` asks,
  and analysed by the app's own `SpectrumAnalyzer`. A pause holds the bars (the time stands
  still); a seek or a new format flushes the sink, which clears the queue and the position. Times
  are in µs, so a change of rate partway through a file changes nothing. Blocks played more than
  a quarter of a second before a read (the screen was away) are dropped and the analyzer starts
  afresh. Paused, the controller reports nothing new, so the bars settle.
- **A cut-off WAV plays as it is.** Media3's WAV extractor reads a data size of 0xFFFFFFFF, or one
  larger than the file, as running to the end of the file (measured on the API 36 emulator: "Data
  exceeds input length"), in whole frames, as `WavLayout` measures it for the list.
- **Recently deleted.** Delete moves the recording into a hidden `.voicerecorder-trash` folder
  inside its own (`RecordingFiles.trash`), named `<time deleted, 13 digits of ms>-<name>`: a rename
  on the same volume, nothing copied, and hidden, so neither this list nor media apps show it. The
  folder is named for the app, and only files named as it names them, with an audio extension,
  count as its own (`trashed`), so a `.trash` of another app's, or anything else put there, is
  never listed, restored or deleted. A Snackbar offers Undo, which restores what `trash` returned,
  so nothing is asked first. The screen's menu lists what is there (most recent first, read on a
  thread of its own rather than behind the lengths being measured) to restore, under its old name
  or with `_2` if that has been taken since, never over another file, or to delete for good, one
  or all. Each listing deletes what has waited more than 30 days (`purgeTrash`), and adds up what
  is left: the list's summary and the menu item say how much it holds, since it still takes space.
- **Share** goes through a `FileProvider` (`res/xml/file_paths.xml`): shared storage is an
  `external-path`, and a folder on an SD card, outside it under `/storage`, a `root-path`.

## Testing

`app/src/test` holds JVM-only tests (no Android framework): `SpectrumAnalyzerTest` for the DSP,
`FilenameHistoryTest` for the MRU/uniquing rules, `GainTest` for the gain maths and clip counting,
`InputDevicesTest` for the input labelling/key/matching/rate-advice rules and the missing-input
rules (`isUnavailable`, and `stepFor`'s pause/resume table), `CaptureSourceTest` for
the audio-source mapping and its fallback, `AudioFormatSpecTest` for rate labelling, `snap()`, the
per-format encoder mimes and the bytes/second estimate, and `PartialRecordingTest` for what a
take that doesn't end cleanly leaves on disk (`endTake`, and the WAV header before and after
`finish()`; `WavPcmEncoder` is plain `java.io`, so it runs there for real), `RecordingFilesTest`
for the recordings list, the rename rules and a WAV's length (finished, cut off, and claiming more
than is there; the trash, and what it leaves alone), `PlayerControllerTest` for the player's rules over a fake output (a pause while it opens
keeping it from starting among them), `PlaybackMeterQueueTest` for the playback spectrum's timing,
and `Pcm16Test` for every PCM encoding the player's sink may be handed. Anything worth testing
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
  When the launcher icon changes, re-render `icon.png`: `python "D:\visual studio projects\F-Droid tools\render_store_icon.py" .`
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
