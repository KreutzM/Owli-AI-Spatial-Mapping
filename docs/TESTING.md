# Testing Strategy

## Required layers

- **JVM:** geometry, depth projection, indexing, confidence semantics, voxel/map logic.
- **Android unit:** pure adapters, text/state mappings, failure handling.
- **Generic AVD:** app launch, accessibility-visible diagnostics, permissions, lifecycle, unsupported AR path.
- **ARCore emulator spike:** session and virtual-scene tracking only after a dedicated issue.
- **Galaxy S23+:** Depth support, confidence, resolution, thermal behavior, frame rate, drift, and real mapping quality.

## Video and replay

Do not couple core tests to camera-video emulation. Prefer repository-owned deterministic frame fixtures. ARCore Recording & Playback may later supplement integration testing, but repeated poses can vary and must not become exact golden values.

## Test data quality

Every fixture requires a manifest describing:

- producer and version,
- coordinate convention,
- units,
- dimensions and pixel format,
- timestamp source,
- expected result or tolerance,
- privacy review.
