# Architecture

## Boundary

The Android module owns device APIs, permissions, lifecycle, ARCore sessions, image acquisition, and rendering. The pure Kotlin module owns deterministic math and mapping behavior.

```text
ARCore / Android
      |
      v
short-lived adapter ----> immutable repository DTOs
                                  |
                                  v
                           mapping-core
                                  |
                         points / map / metrics
                                  |
                                  v
                         diagnostic rendering
```

## Dependency rule

`app -> mapping-core` is allowed. `mapping-core -> app/Android/ARCore` is forbidden and checked by CI.

## ARCore diagnostic runtime

Issue #8 introduces a diagnostic runtime only. `ArDiagnosticSessionController` owns the active repository-level Session adapter. It depends on `DiagnosticSessionFactory`, `DiagnosticSessionPort`, and `DiagnosticSessionCloseScheduler`, so ownership, failure handling, throttling, and lifecycle ordering are JVM-testable without an Android device.

The concrete `ArCoreDiagnosticSessionPort` retains only its owned ARCore `Session`. `Frame`, `Camera`, `Pose`, `CameraIntrinsics`, their arrays, and any ARCore collection exist only as local variables inside one `update()` invocation. Before the adapter returns, it copies the required values into `DiagnosticFrameScalars`; no ARCore frame sub-object enters Compose state or crosses the adapter boundary.

The foreground and pause order is:

1. Activity resume establishes current installation and camera-permission facts.
2. The `GLSurfaceView` starts paused. When both prerequisites are ready and the process-wide Session slot is available, the controller creates at most one Session and calls `Session.resume()`.
3. Only after the Session reaches `Running` does `GLSurfaceView.onResume()` start or resume the render thread.
4. A valid GL surface creates one external OES texture. Surface size and display rotation are recorded as display geometry.
5. Only the GLSurfaceView render thread calls `setCameraTextureName`, `setDisplayGeometry`, and `Session.update()`.
6. Activity pause calls `GLSurfaceView.onPause()` first, then `Session.pause()` on the lifecycle caller. A successful ordinary pause retains the Session for foreground reuse.

Terminal release, prerequisite loss, pause failure, and frame-stage failure use an explicit asynchronous close boundary:

1. The surface/render loop is stopped before the lifecycle release continuation.
2. Under the controller lock, update eligibility is revoked, `sessionResumed` is cleared, the Session is removed from the active owner slot, texture/display-geometry use is invalidated, and current observation/pose/intrinsics are cleared.
3. If the Session had been resumed, `Session.pause()` runs on the lifecycle caller after leaving the controller lock.
4. `DiagnosticSessionCloseScheduler` moves the process-wide slot from `OWNED` to `CLOSING` and invokes native `Session.close()` on one bounded background worker.
5. The slot becomes available only after native close returns. Until then, a recreated Activity may wait but cannot create another Session.

`Session.close()` is therefore never called from `onDestroy()`, prerequisite callbacks, ordinary pause, the GL/update thread, or a synchronized controller section. Detachment and repeated close requests are idempotent, and the single process-wide slot prevents the same adapter from being scheduled twice or a second Session from overlapping an in-flight close. A single latest availability waiter prevents Activity recreation from creating an unbounded callback queue.

A frame-stage failure revokes update eligibility on the render thread immediately. It posts a lifecycle continuation that stops the surface, pauses the Session on main, detaches ownership, and schedules close. No subsequent frame can use the detached Session.

The GL integration creates only the external texture and viewport required for ARCore updates. It does not draw the camera image and introduces no 3D engine. A missing context, texture, or positive viewport prevents updates.

Diagnostic observations use a single-slot latest-value handoff and a minimum publication interval of 125 ms (at most eight regular UI observations per second). Tracking-state changes publish immediately. New pending values replace stale pending values; the frame loop never waits for Compose and no growing queue exists.

## Installation and camera-permission state

A user-initiated `requestInstall(..., true)` attempt is represented by `ArInstallAttemptState`. The pending bit is saved in Activity instance state and restored after Activity recreation. On the next resume it is cleared before exactly one `requestInstall(..., false)` follow-up, preventing both a lost attempt and an install loop.

Camera permission launch state and completed outcomes are separate. Starting a request marks only the current request as in flight. `DENIED_PERMANENTLY` is possible only after the Activity Result callback completed with a denied outcome and Android reports no rationale. A launched-but-interrupted request remains unknown/requestable after recreation. A previously granted permission that is later revoked, one-time-expired, or auto-reset is represented as requestable rather than permanent denial.

## Diagnostic coordinate and timestamp contract

- The pose is `worldFromArCoreCamera`, with ARCore translation copied unchanged in metres.
- ARCore quaternion scalars stored as `(x, y, z, w)` are explicitly constructed as repository `Quaterniond(w, x, y, z)`.
- A pose is current only during `TRACKING`; `PAUSED` and `STOPPED` observations contain no current pose.
- Image intrinsics are ARCore native CPU-image intrinsics (`fx`, `fy`, `cx`, `cy`, width, height) in native image coordinates. They are not represented as aligned with Depth or confidence data.
- The timestamp is the nanosecond value from `Frame.timestamp` and is labelled only as the ARCore frame timestamp. No cross-sensor time-base alignment is asserted.

The coordinate-system contract in `docs/COORDINATE_SYSTEMS.md` is unchanged.

## Runtime rules for future slices

- Copy required frame data immediately and close acquired images in `finally`/`use`.
- Timestamp every observation and make alignment assumptions explicit.
- Use bounded processing with at most one active mapping job unless measurement proves another design.
- Keep diagnostic rendering separate from map truth.
- Map state must distinguish unknown, observed-free, occupied, stale, and invalid where applicable.
- Persist nothing by default during initial experiments.
