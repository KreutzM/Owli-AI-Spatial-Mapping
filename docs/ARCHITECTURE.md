# Architecture

## Boundary

The Android module owns device APIs, permissions, lifecycle, ARCore sessions, image acquisition, and rendering. The pure Kotlin module owns deterministic math and mapping behavior.

```text
ARCore / Android
      |
      v
short-lived adapter ----> immutable repository DTOs
                                  |
                                  v
                           mapping-core
                                  |
                         points / map / metrics
                                  |
                                  v
                         diagnostic rendering
```

## Dependency rule

`app -> mapping-core` is allowed. `mapping-core -> app/Android/ARCore` is forbidden and checked by CI.

## Runtime rules for future slices

- Copy required frame data immediately and close acquired images in `finally`/`use`.
- Timestamp every observation and make alignment assumptions explicit.
- Use bounded processing with at most one active mapping job unless measurement proves another design.
- Keep diagnostic rendering separate from map truth.
- Map state must distinguish unknown, observed-free, occupied, stale, and invalid where applicable.
- Persist nothing by default during initial experiments.
