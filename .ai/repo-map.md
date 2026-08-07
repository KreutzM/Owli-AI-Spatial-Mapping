# Repository Map

| Topic | First paths |
|---|---|
| Build/toolchain | `settings.gradle.kts`, `gradle/libs.versions.toml`, module build files |
| Windows ADB validation helper | `scripts/Build-Install-Run.ps1`, `app/src/test/.../tooling/BuildInstallRunScriptTest.kt`, `docs/DEVICE_VALIDATION.md` |
| ARCore capability/install | `app/.../ar/ArCoreCapabilityProbe.kt`, `ArInstallController.kt`, manifest |
| ARCore diagnostic runtime | `app/.../ar/ArDiagnosticSessionController.kt`, `DiagnosticSessionCloseScheduler.kt`, `ArCoreAdapters.kt`, `DiagnosticGlSurfaceView.kt` |
| Raw Depth diagnostics | `app/.../ar/DepthDiagnostics.kt`, `ArCoreAdapters.kt`, `app/src/test/.../ar/DepthDiagnosticsTest.kt`, `BootstrapScreen.kt` |
| App shell and diagnostics UI | `app/.../MainActivity.kt`, `app/.../ui/BootstrapScreen.kt` |
| Coordinate math | `mapping-core/.../geometry/`, `docs/COORDINATE_SYSTEMS.md` |
| Depth projection | `mapping-core/.../depth/`, projector tests |
| AVD tests | `app/src/androidTest/`, `.github/workflows/avd-smoke.yml` |
| Architecture enforcement | `tools/check_architecture_guardrails.py` |
| Repo download packs | `tools/create_repo_pack.py`, `.github/workflows/repo-pack.yml` |
| Post-merge CI handoff | `.github/workflows/post-merge-handoff.yml`, `tools/post_merge_handoff.py` |
| Test-data policy | `docs/DATASET_POLICY.md`, `test-data/README.md` |
| Roadmap | `docs/ROADMAP.md` |
