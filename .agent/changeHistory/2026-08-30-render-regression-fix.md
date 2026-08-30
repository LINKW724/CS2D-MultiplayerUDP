# Render Regression Fix

- Date: 2026-08-30
- Objective: remove the large-map fog and moving-viewport regressions without reducing render Hz, FOV rays, FOV precision, or image quality.
- Repository: `O:/java/games/CS2D-MultiplayerUDP_Client`
- Branch: `main`
- Baseline commit: `fb5f6ca3caacc8408a5ef25f16a6da01a046bc31`
- Implementation status: changes remain uncommitted for live visual and JFR verification.

## Files

- `src/main/java/cs2d/client/GameClient.java` — 175 added, 86 deleted.
  - Replaces the separately rasterized 1984x1284 fog Canvas with a persistent JavaFX Path.
  - Reuses pooled `LineTo` elements and mutates coordinates instead of allocating a new path or clearing a full-screen Canvas.
  - Keeps the exact FOV polygon, fog colors, camera transform, 1696 rays, and HUD ordering.
  - Replaces runtime 3072x2304 viewport composition/upload with fixed full-resolution atlas regions built once at map load.
  - A 4392x3840 map produces four atlas nodes below the 4096 texture limit; movement only changes visibility and transforms.
  - Adds `[STATIC-ATLAS]` and `[FOG-GEOMETRY]` diagnostics.
- `src/test/java/cs2d/client/RenderFrameSchedulerTest.java` — 5 added, 28 deleted.
  - Replaces obsolete sliding-viewport tests with fixed-atlas region-count coverage.
- `.agent/changeHistory/2026-08-30-render-regression-fix.md` — 46 added, 0 deleted; this record.
- `cs2d_settings.json` — pre-existing user runtime setting; preserved and deliberately excluded.

## Removed or replaced behavior

- Removes runtime use of the adaptive double-buffer viewport builder and its repeated giant texture uploads.
- Removes per-frame fog Canvas clear/fill commands that were deferred to Prism and hidden from the frame-code timer.
- Does not change audio mixing, player/weapon sprite caches, network state coalescing, gameplay, protocol, or controls.

## Validation

- `mvn -DskipTests compile` — passed.
- `mvn test` — passed: 30 tests, 0 failures, 0 errors.
- `git diff --check` — passed; only the repository LF-to-CRLF advisory was printed.

## Live verification

- Restart the client and retest the 4392x3840 / 744-obstacle / 50-player scenario.
- Verify obstacles, fog hole, rapid turning, camera zoom, death/spectator view, flashbang overlay, and HUD ordering.
- Expected log: `[STATIC-MAP] mode=atlas`, `[STATIC-ATLAS] ... 运动中重建 0`, and no active viewport rebuilds.
- Compare FPS, 1% Low, Marlin samples, and PACE against the faulty 80.0 FPS / 39.5 FPS 1% Low run.

## Rollback

- Restore or compare against baseline snapshot `fb5f6ca` to recover the exact pre-fix experiment.
- No configuration migration or dependency change is required.
