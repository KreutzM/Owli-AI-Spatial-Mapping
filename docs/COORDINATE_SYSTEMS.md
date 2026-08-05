# Coordinate Systems Contract

Changing this document requires matching deterministic tests in the same commit.

## Units

- Position and depth after conversion: metres (`Double`).
- Raw depth DTO: unsigned millimetres (`Int`), where `0` means unknown.
- Confidence: integer `0..255`; threshold semantics belong to the consuming algorithm.
- Time: nanoseconds from one explicitly documented monotonic source per stream.

## Optical camera coordinates

The pure pinhole projector first produces optical coordinates:

- `+X`: image right
- `+Y`: image down
- `+Z`: forward through the image plane
- pixel origin: top-left

For pixel `(u, v)` and depth `z`:

```text
x = (u - cx) * z / fx
y = (v - cy) * z / fy
z = depth
```

## ARCore camera coordinates

ARCore camera coordinates are treated as:

- `+X`: right
- `+Y`: up
- camera looks along `-Z`

The explicit conversion is:

```text
(x_optical, y_optical, z_optical)
    ->
(x_optical, -y_optical, -z_optical)
```

Only after this conversion may the ARCore world-from-camera pose be applied.

## Transform naming

`parentFromLocal` transforms points expressed in `local` into `parent`. `worldFromArCoreCamera` therefore maps ARCore camera coordinates into world coordinates.

## Quaternion storage

`Quaterniond` stores `(w, x, y, z)`, normalizes before rotation, and applies `q * p * conjugate(q)`.

## Open questions deferred to ARCore integration

- Exact alignment and resolution relationship between raw depth, confidence, and camera intrinsics.
- Display rotation versus sensor/native image coordinates.
- Timestamp alignment tolerance between pose and depth acquisition.

These must be resolved from measured/runtime API data, not guessed.
