# Voice Recorder — design

Internal design notes. `README.md` is the user-facing doc (features, build, file-by-file table);
this file records *how the pieces fit together* and *why*. Keep both current when features change.

## Component map

```
MainActivity ──binds──> RecordingService ──owns──> Recorder (PcmRecorder)
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

## Testing

`app/src/test` holds JVM-only tests (no Android framework): `SpectrumAnalyzerTest` for the DSP,
`FilenameHistoryTest` for the MRU/uniquing rules. Anything worth testing should be factored into a
pure function like these rather than tested through Android classes.

## Known limitations

See `known-issues.md` (WMA unsupported, Opus needs API 29+, storage-permission trade-offs).
