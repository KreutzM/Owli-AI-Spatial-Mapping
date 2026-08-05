# Owli-AI Spatial Mapping

Independent Android research app for the pre-development of a local live 3D environment map for Owli-AI Assist. The initial reference device is the Samsung Galaxy S23+.

## Current scope

This bootstrap repository deliberately implements only:

- an Android app that runs on devices and ordinary CI AVDs,
- ARCore capability diagnostics in **AR Optional** mode,
- camera permission diagnostics,
- a pure Kotlin `mapping-core` module,
- deterministic geometry and depth-projection tests,
- CI, AVD smoke tests, agent guidance, and downloadable WebAgent repo packs.

It does **not** yet start an ARCore session, read camera frames, build a point cloud, estimate a floor plane, detect obstacles, or claim that a path is clear.

## Safety invariant

> Unknown, unavailable, stale, or low-confidence sensor information is never interpreted or presented as free space.

## Modules

- `app/` — Android/Compose diagnostic shell and future ARCore adapter.
- `mapping-core/` — platform-independent geometry, depth projection, and later rolling-map algorithms. It must not import Android, AndroidX, or ARCore classes.

## First build

Requirements:

- JDK 17
- Android SDK Platform API 36.1 for the compile SDK
- `targetSdk` remains API 36 and is not the compile SDK package requirement
- internet access on the first run to obtain the Gradle distribution and dependencies

PowerShell:

```powershell
.\gradlew.bat :mapping-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Linux/macOS/WSL:

```bash
chmod +x gradlew scripts/*.sh
./gradlew :mapping-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The repository includes the verified Gradle 8.13 wrapper JAR. The existing bootstrap script remains the trusted recovery path when the JAR is absent and verifies the pinned SHA-256 checksum before use. See `gradle/wrapper/README.md`.

## AVD smoke test

```bash
./gradlew pixel2Api35DebugAndroidTest \
  -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
```

The AVD verifies the safe unsupported/diagnostic path. ARCore Depth is hardware-dependent and must later be validated on the S23+.

## Start here

- Humans: `docs/ROADMAP.md`, then `docs/ARCHITECTURE.md`.
- Repository assistants: `CHATGPT.md`, then `AGENTS.md` and `.ai/repo-map.md`.
- Coordinate work: read `docs/COORDINATE_SYSTEMS.md` before changing any math.
- Test data: read `docs/DATASET_POLICY.md` before adding recordings.

## Planned workspace role

After the bootstrap is green on GitHub, this repository can be added to `KreutzM/Owli-AI-Assist-WS` as the independent `spatial/` submodule. The workspace remains coordination-only; this repository remains independently buildable.

## Status

Research prototype. No production release, navigation assurance, medical-device claim, or replacement for established mobility aids.
