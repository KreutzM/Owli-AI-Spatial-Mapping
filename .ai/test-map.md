# Test Map

1. Pure math and map logic: JVM tests in `mapping-core/src/test`.
2. Android mapping adapters without hardware: app unit tests with repository-owned DTOs/fakes.
3. UI, permission, manifest and lifecycle: managed AVD instrumented tests.
4. ARCore session/virtual scene: dedicated emulator spike; do not assume Depth support.
5. Raw Depth, confidence, thermals and real mapping quality: physical Galaxy S23+ validation.
6. Replay datasets: integration tests with tolerances; never use nondeterministic ARCore poses as exact golden values.
