# Galaxy S23+ Validation Record Template

## Completed predecessor

Issue #8 physical Galaxy S23+ validation is complete and recorded on the accepted repository head `31f64d86bde334254ca325deec73cb2b20610012`. Issue #14 must not reuse that record as Depth evidence; its Raw Depth acceptance requires a new exact-head physical run after independent review PASS.

A generic AVD, fake Session, or self-hosted device of unknown model is not Galaxy S23+ evidence.

## Windows build/install/launch helper

On Windows with PowerShell 7, Android platform tools, USB debugging, and an authorized phone connection, run the checked-in helper from any working directory:

```powershell
./scripts/Build-Install-Run.ps1
./scripts/Build-Install-Run.ps1 -Serial R58M...
./scripts/Build-Install-Run.ps1 -Serial R58M... -CaptureLogcat ./artifacts/s23plus-logcat.txt
```

The helper resolves the repository from its own path, builds the current checkout with `gradlew.bat :app:assembleDebug`, selects exactly one online ADB device unless `-Serial` is supplied, prints Git/device/APK provenance, installs `app-debug.apk` with `adb install -r`, and launches `com.owlitech.spatial/.MainActivity` unless `-NoLaunch` is used. Every ADB command after selection is scoped with `-s <serial>`.

ADB discovery order is `-AdbPath`, `ANDROID_SDK_ROOT`, `ANDROID_HOME`, then `adb.exe` on `PATH`. Ambiguous, absent, offline, unauthorized, recovery, or sideload targets fail closed. `-NoBuild` is accepted only for a clean worktree when the exact APK and helper-generated provenance sidecar match current Git HEAD and APK SHA-256; a failed build removes the prior expected APK before Gradle runs and never proceeds to installation.

The helper prepares and launches the exact checkout; it does **not** determine whether Galaxy S23+ validation passed.

## Issue #14 merge-blocking Raw Depth validation

Run this only after independent review returns `PASS`, on the exact unchanged reviewed Draft-PR head. Record facts rather than extrapolating to other devices/configurations.

Exercise at minimum:

1. Verify the exact Git SHA, debug APK SHA-256, Samsung Galaxy S23+ model/model code, Android build, and Google Play Services for AR version.
2. Record `isDepthModeSupported(RAW_DEPTH_ONLY)`, `isDepthModeSupported(AUTOMATIC)`, and the mode actually configured.
3. In a textured indoor scene, record time/motion to first Raw Depth, depth/confidence dimensions and row/pixel strides, several Frame/Depth/Confidence timestamps, and a NEW/REPROJECTED sequence.
4. Keep acquisition running for at least 30 seconds and record distinct NEW-depth count and observed bounded rate. ARCore's typical rate is context only, not an acceptance threshold.
5. Record non-zero depth coverage, confidence coverage/range, diagnostic confidence `>= 128` ratio, and observed non-zero depth min/max millimetres. Approximate scene distances are context only, not calibrated measurements.
6. Repeat coverage/confidence observations for a low-texture/plain-wall scene.
7. Record CPU-image and GPU-texture intrinsics dimensions/aspects next to depth dimensions/aspect. Do not infer a pixel-scaling/alignment rule from similar dimensions alone.
8. Exercise tracking loss/occlusion and recovery; stale depth statistics must disappear while not tracking and fresh diagnostics must recover without a crash.
9. Exercise one background/foreground cycle and one display-rotation cycle.
10. Record any `ResourceExhaustedException`, crashes, ANRs, other exceptions/ARCore errors, battery state, thermals, and limitations. No resource exhaustion is expected if every acquired image is closed.

The record must not claim a point cloud, mapping, occupancy/free-space model, obstacle warning, navigation behavior, or environmental completeness.

## Issue #14 validation record

- Repository head SHA:
- APK variant and SHA-256:
- Device marketing model / model code:
- Android version / full build:
- Google Play Services for AR version:
- Battery / charging / thermals:
- Initial orientation, textured scene, and lighting:
- `RAW_DEPTH_ONLY` supported:
- `AUTOMATIC` supported:
- Selected/configured Depth mode:
- Time/motion to first Raw Depth:
- Depth dimensions / rowStride / pixelStride / format classification:
- Confidence dimensions / rowStride / pixelStride / format classification:
- Representative Frame / Raw Depth / Confidence timestamps:
- Representative NEW / REPROJECTED sequence:
- Continuous measurement duration (minimum 30 s):
- Distinct NEW-depth count / observed rate:
- Textured-scene non-zero depth coverage and min/max mm:
- Textured-scene confidence coverage/range and diagnostic >=128 ratio:
- Low-texture/plain-wall non-zero depth coverage and min/max mm:
- Low-texture/plain-wall confidence coverage/range and diagnostic >=128 ratio:
- Depth/confidence inconsistency counters:
- Approximate physical scene distances (context only):
- CPU-image intrinsics dimensions/aspect:
- GPU-texture intrinsics dimensions/aspect:
- Depth dimensions/aspect relationship observed (no inferred alignment):
- Tracking-loss/recovery result:
- Background/foreground result:
- Display-rotation result:
- Resource exhaustion observed:
- Crashes / ANRs / exceptions / ARCore errors:
- Limitations / unresolved alignment or timestamp questions:
- Validator and date:

No hardware result should be generalized beyond the measured configuration without evidence.
