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

Issue #8 introduces a diagnostic runtime only. `ArDiagnosticSessionController` is the single session owner. It depends on `DiagnosticSessionFactory` and `DiagnosticSessionPort`, so session ownership, failure handling, throttling, and lifecycle ordering are JVM-testable without an Android device.

The concrete `ArCoreDiagnosticSessionPort` retains only its owned ARCore `Session`. `Frame`, `Camera`, `Pose`, `CameraIntrinsics`, their arrays, and any ARCore collection exist only as local variables inside one `update()` invocation. Before the adapter returns, it copies the required values into `DiagnosticFrameScalars`; no ARCore frame sub-object enters Compose state or crosses the adapter boundary.

The lifecycle order is:

1. Activity resume establishes current installation and camera-permission facts.
2. The `GLSurfaceView` starts in a paused lifecycle state. When both prerequisites are ready, the controller creates at most one Session and calls `Session.resume()`.
3. Only after the Session reaches `Running` does `GLSurfaceView.onResume()` start or resume the render thread.
4. A valid GL surface creates one external OES texture. Surface size and display rotation are recorded as display geometry.
5. Only the GLSurfaceView render thread calls `setCameraTextureName`, `setDisplayGeometry`, and `Session.update()`.
6. Activity pause calls `GLSurfaceView.onPause()` first, then `Session.pause()`. Losing installation or permission prerequisites uses the same GL-first order before pausing and closing the Session. No update can run after the surface thread is paused.
7. Activity destruction performs the same pause ordering if needed, then closes the owned Session. Pause and close are idempotent.

A paused Session is retained for foreground resume, so pause/resume does not create a second Session. If installation or permission prerequisites cease to be true, the controller pauses and releases the owned Session; a later valid attempt may create a new one. Recomposition never constructs or resumes a Session.

The GL integration creates only the external texture and viewport required for ARCore updates. It does not draw the camera image and introduces no 3D engine. A missing context, texture, or positive viewport prevents updates.

Diagnostic observations use a single-slot latest-value handoff and a minimum publication interval of 125 ms (at most eight regular UI observations per second). Tracking-state changes publish immediately. New pending values replace stale pending values; the frame loop never waits for Compose and no growing queue exists.

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
