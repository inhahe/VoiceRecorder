# Voice Recorder (Android) — project instructions

Kotlin/Gradle Android app. `README.md` is the user-facing doc, `design.md` the internal design
notes, `known-issues.md` the bug/tech-debt log. Keep all three current as features change.

## Version bumping (required on every change)

`app/build.gradle.kts` is the **single source of truth** for the version — the build, install and
release scripts all parse it from there. Whenever you make a change to the app, bump it in the same
commit:

- **`versionName`** — semver, e.g. `"0.8.0"`. Becomes the GitHub release tag `v0.8.0` and the APK
  filename `VoiceRecorder-0.8.0.apk`.
  - patch (`0.8.0` → `0.8.1`) for bug fixes and small tweaks,
  - minor (`0.8.0` → `0.9.0`) for a new or noticeably changed feature,
  - major for a rewrite or a breaking change to files/settings.
- **`versionCode`** — plain integer, **must strictly increase** or Android refuses to install the
  update. Increment by 1 per release (it does not have to mirror `versionName`).

Never reuse a `versionName` that has already been released — `publish-release.bat` refuses to
overwrite an existing release and will tell you to bump.

## Scripts (run from this folder)

| Script | What it does |
|---|---|
| `build-apk.bat` | `gradlew assembleDebug`, then copies the APK here as `VoiceRecorder-<versionName>.apk`. Pass `clean` for a clean build. |
| `update-phone.bat` | Builds, then `adb install -r` onto the connected phone, keeping app data. Finds adb on PATH / in `local.properties`' `sdk.dir` / in the usual SDK locations, and gives a clear message for "no device", "unauthorised" and signature/downgrade failures. |
| `publish-release.bat` | Builds and publishes a **new** GitHub release `v<versionName>` on `inhahe/VoiceRecorder` with the APK attached, via `gh`. Notes come from the first argument, else from commit subjects since the last local release tag. Prompts before doing anything; tags the local repo on success. |
| `copy-to-github.bat` | Copies the source (minus build outputs/scratch files) to `D:\github\voicerecorder`, from where the user pushes. |
| `_version.bat` | Internal helper that parses `versionName`/`versionCode`; the other scripts `call` it. |

APKs are debug-signed on purpose: that's the signature already on the phone and on earlier releases,
so updates install straight over the top. Don't switch to a release keystore without also planning
the one-time uninstall/reinstall that a signature change forces.

`git push` is the user's action — `publish-release.bat` only talks to the GitHub API via `gh` and
never pushes source.

## Testing

`app/src/test` is JVM-only (no Robolectric, no device). Prefer factoring logic into pure functions
that can be tested there — see `SpectrumAnalyzerTest` (DSP) and `FilenameHistoryTest` (filename MRU
and `_2`/`_3` uniquing). Run with `gradlew.bat testDebugUnitTest`.
