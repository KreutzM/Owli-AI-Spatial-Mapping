# Initial Roadmap

## Track goal

Demonstrate a bounded live local 3D environment map on a Samsung Galaxy S23+ without navigation or safety claims.

## Child issue sequence

1. **Repository bootstrap** — this starter: modules, diagnostics, math contract, tests, CI, AVD, repo packs.
2. **ARCore session diagnostics** — install flow, permission, lifecycle, tracking state, pose and intrinsics.
3. **S23+ Depth capability spike** — raw depth, confidence, resolution, rate and error states.
4. **Deterministic fixture format** — versioned depth/pose frames and golden/tolerance tests.
5. **Live point cloud** — sampled projection, confidence filter, bounded memory, diagnostic renderer.
6. **Rolling voxel grid** — explicit unknown/free/occupied semantics, ageing and hard limits.
7. **Recording/replay spike** — evaluate ARCore dataset playback on physical device and emulator.
8. **MVP integration measurement** — indoor run with latency, memory, thermals and tracking metrics.

## Deferred

Floor plane estimation, obstacle warnings, steps, route localization, GNSS, VPS, semantics, path planning, backend integration, and production Assist integration.
