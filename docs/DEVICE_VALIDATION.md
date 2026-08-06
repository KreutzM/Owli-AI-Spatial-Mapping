# Galaxy S23+ Validation Record Template

## Issue #8 status

Physical Galaxy S23+ validation is **pending maintainer** until a completed record is attached to the Draft PR. A generic AVD, fake Session, or self-hosted device of unknown model is not Galaxy S23+ evidence.

Install the `app-debug.apk` produced from the exact Draft-PR head. Before running, record and verify:

- full 40-character PR head SHA;
- APK build variant (`debug`) and APK SHA-256;
- device marketing model and Android model code;
- Android version and full build identifier;
- Google Play Services for AR version;
- battery level, charging state, display orientation, scene, and lighting.

Exercise and record all of the following:

1. Start from the actual ARCore installation/update state and note whether the user-initiated install flow was required, completed, or already satisfied.
2. During an install prompt, recreate the Activity where practical and confirm the restored pending attempt performs one non-looping follow-up after return.
3. Exercise camera grant, denial/retry, and settings paths. Where the OS/device permits it, revoke or auto-reset a prior grant and confirm the UI remains requestable rather than calling it permanent denial.
4. Measure time from foreground resume to first `TRACKING` in a suitable scene.
5. Keep the diagnostic Session running for at least 60 seconds.
6. Confirm the ARCore frame timestamp progresses.
7. Deliberately translate and rotate the device; record representative translation values in metres and quaternion values in displayed `(w, x, y, z)` order.
8. Record native image `fx`, `fy`, `cx`, `cy`, width, and height.
9. Background and foreground the app once. Confirm diagnostics resume without a duplicate Session or crash and note the camera privacy indicator while paused.
10. Recreate the Activity once while a Session is being released. Confirm the new instance waits for native close and does not create an overlapping Session.
11. Rotate the display once and confirm continued operation.
12. Where reproducible, cause tracking loss and recovery; confirm a lost-tracking observation does not retain a current pose or intrinsics.
13. Record crashes, exceptions, unexpected status transitions, close duration, and all limitations.

The record must not infer Depth support, point-cloud behavior, mapping quality, navigation behavior, or environmental conditions from this diagnostic run.

## Validation record

- Repository head SHA:
- APK variant and SHA-256:
- Device model / model code:
- Android version / build:
- Google Play Services for AR version:
- Battery / charging:
- Initial orientation, scene, and lighting:
- ARCore install/update state and exercised recreation flow:
- Camera-permission state and exercised flow:
- Time to first `TRACKING`:
- Continuous run duration (minimum 60 s):
- Frame timestamp progression:
- Translation observation during deliberate motion:
- Rotation observation during deliberate motion:
- Native image intrinsics and dimensions:
- Background/foreground result and paused camera indicator:
- Activity recreation while closing:
- Display-rotation result:
- Tracking-loss/recovery result:
- Native close duration/state transition:
- Crashes, exceptions, and limitations:
- Validator and date:

No hardware result should be generalized beyond the measured configuration without evidence.
