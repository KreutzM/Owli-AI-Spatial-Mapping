# Spatial Mapping Repository Guide

This repository separates hardware integration from deterministic mapping logic.

- Start Android/ARCore work in `app/src/main/java/com/owlitech/spatial/ar/`.
- Start UI diagnostics in `app/src/main/java/com/owlitech/spatial/ui/`.
- Start geometry/depth work in `mapping-core/src/main/kotlin/` only after reading the coordinate contract.
- Put deterministic small fixtures in `test-data/synthetic/`.
- Put no real-world recording in Git without reviewing `docs/DATASET_POLICY.md`.

Prefer one narrow vertical slice per PR. A useful slice includes its DTO, algorithm, deterministic test, Android adapter only when needed, diagnostics, and documentation impact.
