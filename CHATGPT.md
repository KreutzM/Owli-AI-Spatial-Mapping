# Repository Assistant Entry Point

`KreutzM/Owli-AI-Spatial-Mapping` is an independent Android research repository for live local spatial mapping. It is not yet part of the production Owli-AI Assist runtime.

Read in this order:

1. `AGENTS.md`
2. `.ai/repo-guide.md`
3. `.ai/repo-map.md`
4. `docs/ARCHITECTURE.md`
5. `docs/COORDINATE_SYSTEMS.md` for any geometry, pose, depth, camera, or mapping task
6. `docs/TESTING.md`
7. `docs/DATASET_POLICY.md` before touching recordings or fixtures

Source-of-truth rules:

- Code and deterministic tests govern current behavior.
- Coordinate conventions are governed by `docs/COORDINATE_SYSTEMS.md` and matching tests.
- Unknown sensor state is never equivalent to free space.
- `mapping-core` must remain independent from Android and ARCore types.
- Hardware claims require a recorded S23+ validation result; AVD results are not a substitute for Depth validation.

For coordinated future integration with Assist, start in `KreutzM/Owli-AI-Assist-WS` only after this repository has a reviewed, green runtime commit.
