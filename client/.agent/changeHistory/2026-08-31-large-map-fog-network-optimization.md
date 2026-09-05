# Large-map fog and network optimization

- Timestamp: 2026-08-31 19:20 Asia/Shanghai
- Baseline commit: `db2c9aed5e22cb3b2af7aff542d56e28e98960c7`
- Baseline message: `chore: snapshot before large-map performance optimization`
- Scope: JavaFX client rendering, FOV spatial query, compact state protocol, and regression coverage.

## Summary

Moved fog rasterization off the JavaFX pulse thread into a latest-result, double-buffered full-resolution pixel-mask pipeline. Only the exact union of the previous and next FOV-hole bounds is uploaded to the displayed texture. Kept all 1696 FOV rays and tightened static-obstacle selection with Quadtree node-level discrete-ray pruning and conservative AABB angular ranges. Added protocol-v4 compact player/zombie field deltas while preserving the legacy object decoder branch. Added diagnostics for mask requests, replacements, background time, publications, and dirty pixels.

## File changes

- `src/main/java/cs2d/client/GameClient.java` — added 271 lines, removed 71. Replaced FX-thread Canvas/Marlin fog rebuilding with background mask requests, generation-safe double buffering, dirty-region texture upload, and fog metrics; switched supported protocol to v4; decoded packed player/zombie deltas without adding stale x/y fields; selected FOV candidates through the new Quadtree query.
- `src/main/java/cs2d/client/QuadtreeNode.java` — added 33 lines, removed 1. Added recursive `queryFov` pruning using the exact discrete ray set.
- `src/main/java/cs2d/client/FogMaskRasterizer.java` — new, 239 lines. Full-resolution premultiplied-ARGB rasterizer with two independent buffers, antialiased polygon holes, and exact old/new dirty-bound unions.
- `src/test/java/cs2d/client/FogMaskRasterizerTest.java` — new, 59 lines. Covers opaque exterior, antialiased transparent hole, dirty-region reduction, and independent double-buffer history.
- `src/test/java/cs2d/client/GameClientProtocolTest.java` — added 24 lines, removed 1. Updated protocol fixture to v4 and verified packed deltas never invent position fields.

## Validation

- `mvn test` — passed: 40 tests, 0 failures, 0 errors.
- `git diff --check` — passed; Git only reported the repository's existing LF-to-CRLF checkout warning.

## Notes

- Render target, ray count, FOV precision, map pixel resolution, and snapshot frequency were not reduced.
- Implementation changes intentionally remain uncommitted after the baseline snapshot for review and gameplay profiling.
