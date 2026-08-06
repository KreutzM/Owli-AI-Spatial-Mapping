# Testing Strategy

## Required layers

- **JVM:** geometry, depth projection, indexing, confidence semantics, voxel/map logic.
- **Android unit:** diagnostic conversion, installation and permission states, ARCore error mappings, session ownership/lifecycle, asynchronous close gating, GL prerequisites, failure handling, UI publication limits, and text/state mappings.
- **Generic AVD:** app launch, accessibility-visible diagnostic cards, permission action, lifecycle/relaunch behavior, and the unsupported/unavailable AR path.
- **ARCore emulator spike:** session and virtual-scene tracking only after a dedicated issue.
- **Galaxy S23+:** real ARCore tracking, pose, native image intrinsics, lifecycle, rotation, and camera-indicator validation for Issue #8; later Depth or mapping claims require their own issue and evidence.

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
