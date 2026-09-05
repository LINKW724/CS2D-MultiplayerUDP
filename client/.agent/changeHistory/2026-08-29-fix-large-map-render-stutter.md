# Fix large-map obstacle rendering and FOV stutter

- Date: 2026-08-29
- Objective: Restore static obstacles on the 4392x3840 map and remove rapid-turn FOV stutter without lowering 165 Hz rendering, the 106-degree FOV, or 424-ray precision.
- Repository: `O:\java\games\CS2D-MultiplayerUDP_Client`
- Branch: `main`
- Pre-change baseline: `560e26cb134e54ef6181c344920c76f4cc1d2d86`

## Evidence and root causes

- JavaFX reported `Maximum texture size clamped to 4096`, while the client attempted to snapshot one 4392x3840 obstacle canvas.
- The log showed the D3D VRAM pool growing beyond 350 MB and then `RTTexture.createGraphics()` failing during a second identical map initialization.
- Identical `map_data` arrived twice in one session and rebuilt both the quadtree and GPU cache twice.
- During rapid turning, each FOV calculation tested all 744 static obstacles, took about 5.4-8.1 ms, and discarded roughly 85-92% of completed results. Only about 12-45 results were published per two seconds, causing visible low-frequency FOV jumps despite high main-loop FPS.

## Files changed

### `src/main/java/cs2d/client/GameClient.java` (+145 / -68)

- Replaced the single whole-map obstacle canvas/image with 1024x1024 cached texture tiles.
- Reused one temporary tile canvas while baking to bound transient render-target memory.
- Added camera-view tile culling; full-map mode still renders every relevant tile.
- Added same-session map-payload signatures so duplicate UDP static-map packets reuse the existing quadtree and texture cache.
- Cleared map signatures and texture tiles when connecting, disconnecting, or changing server sessions.
- Published every sequentially completed FOV result instead of discarding it merely because a newer request was waiting.
- Changed FOV invalidation to use the actual source angle rather than an unused mouse world coordinate.
- Added a conservative bounding-circle/106-degree-cone candidate filter. It cannot reject a possible ray hit and does not modify ray count or intersection precision.
- On the 4392x3840 Mirage map, an offline sweep estimated candidate reduction from 740 obstacles to about 219 on average (range 2-537 depending on facing direction).

### `src/test/java/cs2d/client/GameClientProtocolTest.java` (+37 / -0)

- Added coverage for conservative FOV candidate rejection, near-source obstacles, ray range, duplicate map signatures, changed map payloads, and cache tile size safety.

### `.agent/changeHistory/2026-08-29-fix-large-map-render-stutter.md` (+56 / -0)

- Records the evidence, implementation, validation, rollback point, and required runtime checks for this repair.

## Validation

- `mvn -q "-Dmaven.repo.local=O:\maven-repository" test` — passed.
- 15 tests executed; 0 failures, 0 errors, 0 skipped.
- `git diff --check` — passed; only the existing Git LF-to-CRLF warning was emitted.
- Static map sweep — average candidate obstacles reduced by about 70% without changing 424 rays.

## Remaining manual verification

- Restart the JavaFX client and enter the 4392x3840 / 744-obstacle map.
- Confirm logs show one map initialization followed, if a duplicate arrives, by `忽略同一会话中重复到达的 map_data`.
- Confirm cache logs show a 5x4 grid of 1024-sized tiles and no `RTTexture.createGraphics()` exception.
- Rapidly rotate while checking that FOV `发布` closely follows `实算`, `过期结果` remains zero, candidate obstacles are below 744 in normal directions, and visual fog movement is continuous.

## Rollback and commit state

- Roll back to `560e26cb134e54ef6181c344920c76f4cc1d2d86` to return to the state immediately before this repair.
- The implementation was runtime-verified by the user and committed as the recovery baseline before the next large-map optimization.
- The user-owned `cs2d_settings.json` modification was not changed or included in this repair.
