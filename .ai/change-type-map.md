# Change Type Map

- **Geometry/pose:** read `COORDINATE_SYSTEMS.md`; add exact numeric tests first.
- **Depth/confidence:** define units, unknown encoding, alignment, and lifetime before implementation.
- **ARCore session:** keep ARCore types inside `app/ar`; add lifecycle and failure-path tests.
- **Rendering:** rendering is diagnostic only; never let visual interpolation change map truth.
- **Recording/replay:** review dataset privacy, determinism limits, and storage bounds.
- **Map representation:** require an ADR for voxel size, extent, ageing, occupancy semantics, or persistence.
- **Safety wording:** run `safety_reviewer`; avoid “clear”, “safe”, or “free” unless semantics are explicitly defined and validated.
