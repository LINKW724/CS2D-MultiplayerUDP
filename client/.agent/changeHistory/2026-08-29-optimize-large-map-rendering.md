# Optimize large-map rendering

- Date: 2026-08-29
- Objective: Improve the 4392x3840 map's full-view GPU submission cost and FOV intersection cost without changing 165 Hz, 106-degree FOV, 424-ray precision, or follow-view obstacle detail.
- Repository: `O:\java\games\CS2D-MultiplayerUDP_Client`
- Branch: `main`
- Pre-change baseline: `90bb8fa12686ba97cbae8c747168854927ed6bd6`

## Evidence

- The verified tiled-cache build completed without RTTexture errors and duplicate `map_data` reused the existing cache.
- Stable large-map windows still delivered roughly 97-129 FPS while Java frame code took only about 0.6-0.9 ms; JavaFX/D3D presentation wait dominated.
- Full-map mode submitted all 20 high-resolution obstacle tiles every frame.
- Depending on view direction, FOV still examined about 80-365 candidate obstacles and spent about 0.8-3.0 ms in background intersection work.

## Files changed

### `src/main/java/cs2d/client/GameClient.java` (+102 / -16)

- Added a full-map overview texture capped at 2048 pixels on its longest axis.
- Full-map camera mode now submits one screen-resolution overview image instead of all 20 high-resolution tiles.
- Follow and free camera modes continue using camera-culled 1024 tiles at original detail.
- Reset the overview texture together with tiled caches on disconnect, reconnect, and session replacement.
- Reordered static FOV work from ray-first/all-candidates to obstacle-first/possible-ray-range traversal.
- Added conservative bounding-circle-to-discrete-ray-range calculation. Exact edge intersection math and all 424 ray directions remain unchanged.
- The actual Mirage map sweep across 72 headings reduced potential ray-obstacle checks from 6,687,752 to 45,792 (about 99.3%).

### `src/test/java/cs2d/client/GameClientProtocolTest.java` (+18 / -0)

- Added centered, behind-view, and source-surrounding ray-range boundary coverage.
- Added a safety assertion that the overview texture cap remains within JavaFX's 4096 texture limit.

### `.agent/changeHistory/2026-08-29-optimize-large-map-rendering.md` (+56 / -0)

- Records evidence, implementation details, validation, runtime checks, and rollback information.

## Validation

- `mvn -q "-Dmaven.repo.local=O:\maven-repository" test` — passed.
- 15 tests executed; 0 failures, 0 errors, 0 skipped.
- `git diff --check` — passed; only the existing LF-to-CRLF warning was emitted.
- Offline 72-heading sweep against `mirage_3x无粗.json` — about 99.3% fewer static ray-obstacle combinations before unchanged edge intersection tests.

## Manual verification

- Restart the JavaFX client and load the 4392x3840 map.
- Confirm the cache log includes `全图概览纹理: 2048x...` and no RTTexture exception.
- Test full-map, follow, and free camera modes for complete obstacle rendering.
- Rapidly rotate in follow mode and compare `[FOV-BG] 求交` with the previous roughly 0.8-3.0 ms range.
- Compare `[REAL]` FPS and 1% Low in full-map mode; one short startup window will still contain one-time map/texture initialization.

## Rollback and commit state

- Roll back to `90bb8fa12686ba97cbae8c747168854927ed6bd6` to return to the user-verified obstacle/FOV repair before this optimization.
- Runtime GPU/visual verification succeeded; this optimization is committed as the recovery baseline before increasing FOV ray density.
- The user-owned `cs2d_settings.json` modification was not changed or included.
