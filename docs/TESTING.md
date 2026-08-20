# Testing Strategy

## Required layers

- **JVM:** geometry, depth projection, indexing, confidence semantics, voxel/map logic.
- **Android unit:** diagnostic conversion, installation and permission states, ARCore error mappings, session ownership/lifecycle, asynchronous close gating, GL prerequisites, failure handling, UI publication limits, text/state mappings, and deterministic developer-tooling process tests.
- **Generic AVD:** app launch, accessibility-visible diagnostic cards, permission action, lifecycle/relaunch behavior, and the unsupported/unavailable AR path.
- **ARCore emulator spike:** session and virtual-scene tracking only after a dedicated issue.
- **Galaxy S23+:** real ARCore tracking/lifecycle evidence for completed Issue #8 and exact-head Raw Depth capability/confidence/timestamp/rate evidence for Issue #14. Mapping claims remain out of scope.

## Issue #8 deterministic coverage

The app unit suite uses fake installation/session ports and injected close executors. It verifies:

- exactly one owned Session across repeated resume-style events;
- no creation or resume without installed ARCore and camera permission;
- a user-initiated install request with one non-looping resume follow-up;
- restoration of the pending install attempt across controller/Activity recreation;
- explicit installation and Session error mappings;
- exact create/resume/surface/pause/release ordering, including GL-first prerequisite loss;
- synchronous update revocation and ownership detachment before asynchronous native close;
- native close runs on a worker thread rather than the lifecycle caller;
- no second Session is created while a prior Session is owned or closing;
- close is queued exactly once and ordinary pause/terminal close remain idempotent;
- no updates after pause, runtime error, detachment, or close request;
- render-thread failures delegate surface pause and Session pause to the lifecycle path;
- valid texture and positive viewport requirements;
- tracking-state and failure-reason mappings;
- removal of current pose and intrinsics for `PAUSED` and `STOPPED`;
- exact `(x, y, z, w)` to `(w, x, y, z)` quaternion conversion and unchanged metric translation;
- rejection of non-finite or invalid native image intrinsics;
- at most eight regular diagnostic publications per second plus immediate state changes;
- single-slot UI backpressure;
- launched/in-flight versus completed permission outcomes;
- permanent denial only after a completed denied result with no rationale;
- interrupted requests and revocation/one-time expiration/auto-reset after grant remain requestable.

The focused `ArDiagnosticSurfaceLifecycleTest` models EGL-context and EGL-window-surface events separately. Without sleeps, it verifies:

- initial context creation plus positive surface geometry is required before the first update;
- pause revokes render-surface eligibility before `Session.pause()`, and an update attempt at that boundary is blocked;
- pause clears current ARCore and Raw Depth observations;
- a preserved-context foreground succeeds with no second renderer `onSurfaceCreated()` callback: the existing texture is retained, a new `onSurfaceChanged()` restores the render surface/display geometry, the same Session resumes, and updates continue;
- foreground recovery does not require Activity/controller recreation and does not create a second Session;
- true EGL-context recreation supplies a new texture and rebinds it before the next update rather than reusing the old texture state;
- repeated pause/resume cycles are idempotent and continue to use one Session and one preserved texture when the context survives; and
- updates remain gated until Session and render-surface prerequisites are all ready.

## Issue #14 Raw Depth deterministic coverage

The app unit suite uses repository-owned fake depth-image/plane ports with fixed byte buffers and deliberate row/pixel padding. It verifies:

- unsupported raw/automatic modes remain non-fatal;
- `RAW_DEPTH_ONLY` preference and the documented `AUTOMATIC` fallback;
- no acquisition without tracking or the existing Session/update eligibility gates;
- separate transient/not-tracking/illegal-state/deadline/resource-exhausted/layout/unexpected outcomes;
- depth close after confidence-acquire failure and exactly-once close of both images on success;
- only immutable scalar/statistical data escapes the adapter boundary;
- unsigned 16-bit little-endian depth, including values above 32767, with zero preserved as unknown;
- unsigned confidence `0..255`, including the purely diagnostic `>= 128` statistic;
- arbitrary validated row/pixel strides for both planes and explicit rejection of mismatched dimensions;
- explicit depth/confidence inconsistency counters;
- repeated raw-depth timestamp = reprojection, changed timestamp = NEW, with repeated values excluded from the distinct-depth counter;
- fixed-capacity rolling rate state;
- clearing of current depth statistics on tracking/lifecycle loss; and
- bounded Compose publication through the existing latest-value/throttle path.

The generic `pixel2Api35` managed AVD remains an unsupported/unavailable-path check only. It must launch without crash, keep existing diagnostics usable, expose Raw Depth as unavailable/unsupported when applicable, and continue to state that no point cloud or map is constructed. It is not evidence for real Depth support, pixel values, confidence, timestamps, update rate, or S23+ behavior.

## Issue #11 deterministic tooling coverage

`BuildInstallRunScriptTest` runs the real `scripts/Build-Install-Run.ps1` under `pwsh` while replacing only its external `git`, `gradlew.bat`, and `adb.exe` processes with temporary hardware-independent fakes. It verifies:

- `-Help` exits successfully and lists the supported parameters;
- no device and ambiguous multiple-device selection fail closed;
- one valid online device is selected and receives the exact APK;
- a requested serial never falls back to another target;
- offline and unauthorized targets are rejected;
- ADB discovery precedence is explicit path, `ANDROID_SDK_ROOT`, `ANDROID_HOME`, then `PATH`;
- a failed Gradle build deletes the prior expected APK and never contacts ADB;
- `-NoBuild` rejects a missing APK or an APK without matching checkout provenance;
- every post-selection ADB command includes the exact `-s <serial>` pair;
- install failure prevents launch and launch failure is non-zero;
- app-data clear, camera grant, and logcat capture remain opt-in;
- the bounded logcat snapshot is app-PID-focused and leaves no background process;
- repository, ADB, APK, and output paths containing spaces are preserved.

Run the focused helper checks from the repository root:

```powershell
pwsh -NoProfile -File ./scripts/Build-Install-Run.ps1 -Help
./gradlew.bat :app:testDebugUnitTest --tests com.owlitech.spatial.tooling.BuildInstallRunScriptTest
```

The `-Help` command is intentionally device-free and must complete before any Gradle, Git, or ADB side effect.

Run the required local checks from the repository root:

```bash
python tools/check_architecture_guardrails.py
./gradlew \
  :mapping-core:test \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug \
  --stacktrace --no-daemon
./gradlew \
  pixel2Api35DebugAndroidTest \
  -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect \
  --stacktrace --no-daemon
git diff --check
```

The managed AVD demonstrates only launch, accessibility, permission/lifecycle behavior, and an understandable unsupported/unavailable path. It does not demonstrate real ARCore tracking, pose quality, native-device intrinsics, Depth support, or mapping.

## Video and replay

Do not couple core tests to camera-video emulation. Prefer repository-owned deterministic frame fixtures. ARCore Recording & Playback may later supplement integration testing, but repeated poses can vary and must not become exact golden values.

## Test data quality

Every fixture requires a manifest describing:

- producer and version,
- coordinate convention,
- units,
- dimensions and pixel format,
- timestamp source,
- expected result or tolerance,
- privacy review.
