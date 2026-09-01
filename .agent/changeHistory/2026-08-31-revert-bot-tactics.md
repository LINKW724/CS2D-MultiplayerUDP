# Revert experimental bot tactics

- Date: 2026-08-31 22:44 Asia/Shanghai
- Objective: remove the unsuccessful uncommitted route diversification, cover, firing-line, and forward-corridor steering experiment.
- Repository: `O:/java/games/CS2D-MultiplayerUDP`
- Branch: `main`
- Restored baseline commit: `ea29819074de7ad2be0a5284eb5ee9d158338f8c`
- Baseline message: `perf: optimize large-map fog network and AI`

## Reverted files

- `src/main/java/cs2d/AIControl/A/PathfindingModule.java` — reverted pending diff of 35 added and 2 deleted lines; restored the original preset-path behavior.
- `src/main/java/cs2d/AIControl/A/PresetPathModule.java` — reverted pending diff of 12 added and 8 deleted lines; restored random preset-route selection.
- `src/main/java/cs2d/AIControl/BG/TEAM_DEATHMATCHcontrol.java` — reverted pending diff of 238 added and 144 deleted lines; removed experimental cover commitments, firing-line holds, route crowd penalties, and forward-corridor steering.
- `src/test/java/cs2d/AIControl/A/TacticalRouteDiversityTest.java` — removed the 34-line uncommitted experiment-only test.
- `src/test/java/cs2d/AIControl/BG/TdmTacticalMovementTest.java` — removed the 65-line uncommitted experiment-only test.
- `.agent/changeHistory/2026-08-31-diversify-bot-tactics.md` — removed the superseded uncommitted experiment record.

## Validation

- `git diff --exit-code` — passed after restoration; tracked source and tests match `ea29819074de7ad2be0a5284eb5ee9d158338f8c` exactly.
- `mvn test` — passed: 31 tests, 0 failures, 0 errors.

## Notes

- Previously committed performance, network, 60 Hz simulation, 30 Hz snapshots, and Sub-tick input work remain intact.
- No new AI behavior replaces the reverted experiment.
- Restart the server to load the restored AI classes.
- The rollback itself requires no implementation commit because all tracked code returned exactly to the existing baseline; this history record remains uncommitted.
