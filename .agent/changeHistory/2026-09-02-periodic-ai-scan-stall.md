# Periodic AI cover-scan stall fix

- Date: 2026-09-02
- Objective: Remove rhythmic server-main-thread spikes introduced by synchronized local cover scans while preserving backpedal, cover, and corridor behavior.
- Repository: `O:/java/games/CS2D-MultiplayerUDP`
- Branch: `main`
- Baseline commit: `6960be1d7b08741b42b8c9fb2342a2b587042594`
- Implementation state: staged in the worktree, not committed.

## Root cause

- Each cover candidate called `PathfindingModule.Pathfinder.hasLineOfSightToPointFromPoint()`.
- That fallback iterates every obstacle on every ray.
- On a 744-obstacle map, 36 candidate rays multiplied by many bots created a large synchronous main-loop burst.
- Bots shared nearly identical 600 ms schedules, so the work aligned into a visible periodic client freeze.

## Files changed

- `src/main/java/cs2d/AIControl/BG/TEAM_DEATHMATCHcontrol.java` (`+16/-22`)
  - Replaces the all-obstacle cover probe adapter with the Quadtree adapter.
  - Adds a deterministic per-bot initial phase for ordinary cover scans.
  - Applies a shared two-scans-per-16-ms admission budget to ordinary and urgent scans.
  - Keeps denied urgent decisions pending for a later simulation tick instead of blocking the current tick.
- `src/main/java/cs2d/AIControl/movement/CoverScanBudget.java` (`+34/-0`)
  - Adds a synchronized, bounded scan admission controller independent of game-mode logic.
- `src/main/java/cs2d/AIControl/movement/QuadtreeCoverGeometryProbe.java` (`+59/-0`)
  - Queries only obstacle wrappers intersecting each candidate ray.
  - Reuses one candidate list per bot and keeps the existing full-scan fallback when no Quadtree exists.
- `src/test/java/cs2d/AIControl/movement/CoverScanBudgetTest.java` (`+19/-0`)
  - Verifies synchronized scans are capped and the following time window reopens.
- `.agent/changeHistory/2026-09-02-periodic-ai-scan-stall.md` (`self-record`)
  - Records cause, implementation, validation, and rollback details.

## Behavior and compatibility

- Backpedal-facing retention, deterministic cover scoring, and narrow-corridor queueing remain enabled.
- Cover quality, search radii, AI tick rate, network tick rate, and map data are unchanged.
- No protocol, dependency, client, configuration, or save-data changes.

## Validation

- Command: `mvn test`
  - Result: success; 103 tests, 0 failures, 0 errors.
- `git diff --check`
  - Result: success; only existing line-ending conversion warnings were reported.

## Manual verification

- Restart the server; no client rebuild is required.
- Reproduce the same high-bot-count large-map fight for at least two minutes.
- Verify server loop interval remains close to 16.667 ms and the client no longer freezes rhythmically.
- If a freeze remains, capture both server and client performance windows covering one freeze; the cover-scan burst has now been removed from the likely causes.

## Scope and rollback

- The user-edited `maps/d3_2.5x无粗.json` remains unstaged and was not modified by this fix.
- Baseline recovery point: `6960be1d7b08741b42b8c9fb2342a2b587042594`.
- The implementation remains staged and intentionally uncommitted until live verification.
