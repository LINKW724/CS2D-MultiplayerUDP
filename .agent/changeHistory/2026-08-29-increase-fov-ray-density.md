# Increase FOV ray density

- Date: 2026-08-29
- Objective: Increase the 106-degree client FOV from 424 to 1696 exact rays without reducing render rate, FOV angle, obstacle detail, or intersection precision.
- Repository: `O:\java\games\CS2D-MultiplayerUDP_Client`
- Branch: `main`
- Pre-change baseline: `a6d7f7c7bff49c5e5372a463e40e6cc25a888d97`

## Files changed

### `src/main/java/cs2d/client/GameClient.java` (+2 / -2)

- Increased `FOV_RAY_COUNT` from `106 * 4` (424 rays) to `106 * 16` (1696 rays).
- Improved angular spacing from about 0.25 degrees to about 0.0625 degrees per ray.
- Kept the 106-degree FOV, 8000-unit ray length, conservative obstacle range filter, and exact segment intersection unchanged.

### `src/test/java/cs2d/client/GameClientProtocolTest.java` (+1 / -1)

- Updated the FOV precision invariant to require exactly 1696 rays.
- Existing ray-range and exact intersection tests continue to cover the unchanged geometry.

### `.agent/changeHistory/2026-08-29-increase-fov-ray-density.md` (+50 / -0)

- Records the baseline, scope, expected performance impact, validation, and rollback path.

## Expected performance impact

- Only ray-dependent FOV intersection and polygon work scale close to four times; map queries, networking, interpolation, and obstacle texture rendering are unchanged.
- The optimized Mirage 72-heading sweep is expected to rise from about 45,792 to about 183,168 ray-obstacle combinations, still about 97.3% below the former unfiltered 6,687,752 combinations.
- Based on the latest runtime log, background FOV work is expected to rise from about 0.47-0.70 ms to roughly 1.1-1.9 ms. Runtime measurement is still required because JavaFX polygon submission and garbage collection depend on the local GPU/JVM.
- The generated polygon grows from 425 to 1697 points, increasing short-lived point allocation and fill-polygon submission cost.

## Validation

- `mvn -q "-Dmaven.repo.local=O:\maven-repository" test` - passed.
- 15 tests executed; 0 failures, 0 errors, 0 skipped.
- `git diff --check` - passed; only existing LF-to-CRLF warnings were emitted.

## Manual verification

- Restart the JavaFX client and rapidly rotate on the 4392x3840 / 744-obstacle map.
- Check `[FOV-BG]` total, query, intersection, calculated/published/stale counts and final polygon vertex count.
- Check `[REAL]` FPS, 1% Low, `[B]`, `[P]`, and GC/spike behavior for at least 30 seconds.
- Confirm the final FOV polygon reports about 1697 points and that no edge gaps or flicker appear.

## Rollback and commit state

- Roll back this worktree change to baseline `a6d7f7c7bff49c5e5372a463e40e6cc25a888d97` to return to the verified 424-ray implementation.
- Runtime performance and visual verification completed; the 1696-ray implementation is committed as the recovery baseline before allocation-neutral FOV optimization.
- The user-owned `cs2d_settings.json` modification was not changed or included.
