# Large-map AI and network optimization

- Timestamp: 2026-08-31 19:20 Asia/Shanghai
- Baseline commit: `1c58ec3b3cb7583cd4fae574040f562223907029`
- Baseline message: `chore: snapshot before large-map performance optimization`
- Scope: protocol bandwidth, AI perception, grenade planning, and server spatial-query allocation.

## Summary

Introduced protocol v4 and replaced repeated-field JSON objects in routine player/zombie updates with fixed-order field-level arrays while retaining 30 Hz snapshot scheduling and periodic full-state correction. Moved non-alerted AI view-angle rejection ahead of wall/smoke work, reused per-worker Quadtree candidate buffers, cached immutable obstacle bounds, and removed recursive ray-object allocation. Grenade simulation now queries only Quadtree ray candidates and reuses cached primitive obstacle edges across all 73 candidate angles and trajectory steps, preserving the existing 16-segment ellipse geometry and collision formulas.

## File changes

- `src/main/java/cs2d/server/GameServer.java` — added 1 line, removed 1. Advanced the UDP protocol version from 3 to 4.
- `src/main/java/cs2d/server/GameState.java` — added 24 lines, removed 29. Serialized routine player/zombie changes as compact fixed-order delta arrays instead of repeated-key objects.
- `src/main/java/cs2d/server/AIService.java` — added 34 lines, removed 13. Added behavior-equivalent early view-cone rejection, per-thread candidate-list reuse, allocation-free candidate scanning, and constant-time normal angle correction.
- `src/main/java/cs2d/AIControl/A/GrenadeModule.java` — added 133 lines, removed 104. Added Quadtree candidate selection, per-module immutable edge caching, primitive segment intersection, and reusable candidate buffers; removed per-step full-map scans and edge-object rebuilding.
- `src/main/java/cs2d/server/MapData.java` — added 15 lines, removed 7. Cached immutable ShapeWrapper AABBs as transient runtime data.
- `src/main/java/cs2d/server/QuadtreeNode.java` — added 18 lines, removed 29. Reused one `Line2D` throughout recursive ray queries and consumed cached bounds.
- `src/test/java/cs2d/server/NetworkProtocolTest.java` — added 10 lines, removed 2. Updated protocol assertions and verifies hot-query bounds reuse.
- `src/test/java/cs2d/AIControl/A/GrenadeModuleGeometryTest.java` — new, 35 lines. Verifies rectangle, polygon, and 16-segment ellipse edge flattening.

## Validation

- `mvn test` — passed: 31 tests, 0 failures, 0 errors.
- `git diff --check` — passed; Git only reported the repository's existing LF-to-CRLF checkout warning.

## Notes

- Server simulation remains 60 Hz, network snapshots remain 30 Hz, and Sub-tick input handling is unchanged.
- AI sight and grenade collision outcomes retain the prior geometric rules; the optimization removes impossible work and repeated allocation.
- Implementation changes intentionally remain uncommitted after the baseline snapshot for review and 50-player gameplay profiling.
