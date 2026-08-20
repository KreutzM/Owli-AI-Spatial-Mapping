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
4. EGL-context creation/recreation invokes `Renderer.onSurfaceCreated()`, which creates the external OES camera texture. A usable Android window/EGL surface is a separate lifetime: `Renderer.onSurfaceChanged()` marks that render surface usable and records its positive viewport/display geometry.
5. Only the GLSurfaceView render thread calls `setCameraTextureName`, `setDisplayGeometry`, and `Session.update()` after the Session, camera texture, render surface, and viewport prerequisites are all current.
6. Activity pause first revokes render-surface/update eligibility and calls `GLSurfaceView.onPause()`, then calls `Session.pause()` on the lifecycle caller. A successful ordinary pause retains the Session and, when Android preserves the EGL context, retains the context-owned camera texture for foreground reuse.

`DiagnosticGlSurfaceView` uses `preserveEGLContextOnPause = true`, so the EGL context/camera texture and the Android window/EGL surface must not be treated as the same lifetime. A normal background transition can destroy the EGL window surface while retaining the context. In that case foreground may receive `Renderer.onSurfaceChanged()` for the new window surface without a second `Renderer.onSurfaceCreated()`. Surface loss therefore clears only render-surface readiness and viewport geometry; it does not discard the retained camera texture ID. The next positive `onSurfaceChanged()` restores render eligibility and makes display geometry dirty before `Session.update()` can run. If the EGL context is actually recreated, `onSurfaceCreated()` supplies a new texture ID, resets the per-Session configured texture binding, and `onSurfaceChanged()` is still required before updates resume.

Terminal release, prerequisite loss, pause failure, and frame-stage failure use an explicit asynchronous close boundary:

1. The surface/render loop is stopped before the lifecycle release continuation.
2. Under the controller lock, update eligibility is revoked, `sessionResumed` is cleared, the Session is removed from the active owner slot, render-surface/display-geometry use is invalidated, the detached Session's configured texture binding is reset, and current observation/pose/intrinsics are cleared. A context-owned GL texture may remain valid until actual EGL-context recreation, but no detached Session may use it.
3. If the Session had been resumed, `Session.pause()` runs on the lifecycle caller after leaving the controller lock.
4. `DiagnosticSessionCloseScheduler` moves the process-wide slot from `OWNED` to `CLOSING` and invokes native `Session.close()` on one bounded background worker.
5. The slot becomes available only after native close returns. Until then, a recreated Activity may wait but cannot create another Session.

`Session.close()` is therefore never called from `onDestroy()`, prerequisite callbacks, ordinary pause, the GL/update thread, or a synchronized controller section. Detachment and repeated close requests are idempotent, and the single process-wide slot prevents the same adapter from being scheduled twice or a second Session from overlapping an in-flight close. A single latest availability waiter prevents Activity recreation from creating an unbounded callback queue.

A frame-stage failure revokes update eligibility on the render thread immediately. It posts a lifecycle continuation that stops the surface, pauses the Session on main, detaches ownership, and schedules close. No subsequent frame can use the detached Session.

The GL integration creates only the external texture and viewport required for ARCore updates. It does not draw the camera image and introduces no 3D engine. A non-running Session, missing camera texture, unavailable render surface, or non-positive viewport prevents updates. Display geometry is re-applied after every usable-surface restoration and after display-rotation changes.

Diagnostic observations use a single-slot latest-value handoff and a minimum publication interval of 125 ms (at most eight regular UI observations per second). Tracking-state changes publish immediately. New pending values replace stale pending values; the frame loop never waits for Compose and no growing queue exists.

### Raw Depth diagnostic boundary

Issue #14 extends the same Session adapter with a bounded Raw Depth capability/measurement slice. Depth support is queried explicitly for `RAW_DEPTH_ONLY` and `AUTOMATIC` before the Session is resumed. `RAW_DEPTH_ONLY` is preferred; `AUTOMATIC` is selected only as the documented fallback when raw-only is unavailable, and an unsupported/configuration-failed result does not turn AR Optional into AR Required.

Depth and confidence are acquired only from the current `Frame` inside the existing GL-thread `Session.update()` call while tracking. The adapter acquires raw depth first and confidence second, copies scalar metadata/statistics, and closes every acquired Android `Image` before returning. `Image`, `Image.Plane`, `ByteBuffer`, `Frame`, and `Camera` never enter controller or Compose state. Pixel addressing is `y * rowStride + x * pixelStride`; layouts are validated rather than assumed tightly packed. Raw depth is decoded as unsigned little-endian 16-bit millimetres and zero remains unknown/no estimate.

A fixed-capacity timestamp window distinguishes distinct NEW raw-depth timestamps from repeated/reprojected timestamps and estimates the observed NEW-depth rate. Full pixel statistics are computed only for NEW timestamps; reprojections update bounded counters/timing without rescanning every pixel. Tracking loss, lifecycle pause, configuration failure, and terminal runtime failure clear the current/last scanned depth statistics. UI publication continues through the existing single-slot 125 ms throttle.

CPU-image and GPU-texture intrinsics dimensions plus display/viewport scalars are copied only for diagnostic comparison. This slice does not assert a depth-to-intrinsics pixel alignment or timestamp-alignment rule and does not invoke the live pinhole projector. The open coordinate questions remain governed by `docs/COORDINATE_SYSTEMS.md` until official API evidence and physical S23+ measurements jointly support a deterministic contract.

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
