# AGENTS.md

Project-wide instructions for coding agents.

## Goal

Deliver small, measurable steps toward a live local 3D map while preserving explicit uncertainty and reproducibility.

## Required work model

- Plan in chat; execute one small thematic run at a time.
- Use a tracking issue for the MVP track and one child issue per implementation PR.
- Start from current `origin/main` on a thematic branch. Do not work directly on `main`.
- Inspect relevant code, docs, fixtures, and tests before editing.
- Only the parent session or one explicitly chosen worker may write. Specialist subagents remain read-only.
- Keep commits focused and buildable.
- Push review branches when the run is intended for review.
- Do not merge without explicit maintainer/orchestrator instruction.

## Safety and scientific integrity

- Never infer free space from missing, stale, unknown, occluded, or low-confidence observations.
- Do not label experimental output as safe navigation, obstacle avoidance, or medical functionality.
- Separate measured facts, assumptions, and estimates in docs and run reviews.
- Do not silently change coordinate systems, units, timestamp bases, camera conventions, or confidence semantics.
- Hardware claims require physical-device evidence. AVD success does not prove ARCore Depth quality.

## Architecture rules

- `mapping-core` must not import `android.*`, `androidx.*`, or `com.google.ar.*`.
- Android/ARCore objects must be converted immediately into repository-owned immutable DTOs.
- Never retain ARCore frame/image objects beyond the shortest required scope.
- Keep main/render threads free from mapping computation.
- Bound queues, map extent, point count, memory, and processing time.
- Drop work under backpressure rather than allowing unbounded growth.
- Read `docs/COORDINATE_SYSTEMS.md` before geometry changes.
- Add deterministic tests for every transform, unit conversion, and indexing rule.

## Testing

Default fast checks:

```text
./gradlew :mapping-core:test
./gradlew :app:testDebugUnitTest
python tools/check_architecture_guardrails.py
./gradlew :app:lintDebug :app:assembleDebug
```

Run the managed AVD test when Android UI, permissions, manifest, lifecycle, or capability behavior changes:

```text
./gradlew pixel2Api35DebugAndroidTest -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
```

Physical device runs are allowed when the task explicitly requires S23+ validation. Record device model, Android version, ARCore version, app SHA, test procedure, and observed metrics.

## Dependencies

- Do not add or upgrade dependencies without documenting why.
- Prefer Kotlin/Java and existing Android APIs; no NDK or external 3D engine without an ADR and explicit approval.
- Never commit secrets, private location data, identifiable recordings, signing material, or `local.properties`.

## End-of-run output

Always finish with:

```text
RUN REVIEW
Branch: <branch>
Remote push: <yes/no>
Compare/PR URL: <url-or-n/a>

Scope summary:
- ...

Commits:
1. <sha> <subject>

Files changed:
- <path> — <purpose>

Checks run:
- <command> — pass/fail/not run

Behavior impact:
- ...

Coordinate/data-contract impact:
- none / describe

Safety and uncertainty impact:
- none / describe

Risks / review focus:
- ...

Hardware validation:
- not required / not run / result

Manual follow-up:
- ...

Open questions:
- ...
```
