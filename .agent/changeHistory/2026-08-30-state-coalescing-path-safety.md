# State Coalescing and Path Safety

- Date: 2026-08-30
- Objective: prevent high-player-count stalls caused by state backlog and stop single-node AI paths from throwing.
- Repository: `O:/java/games/CS2D-MultiplayerUDP`
- Branch: `main`
- Baseline commit: `1541fdae3744b90d5e44a62d21a8fd863a1f6a35`
- Implementation status: changes remain in the working tree.

## Files

- `src/main/java/cs2d/AIControl/A/PathfindingModule.java` — 20 added, 8 deleted.
  - Serializes each bot's path mutation/update entrypoints on the module instance.
  - Adds a final index guard that safely stops movement when a path or index is invalid.
- `src/test/java/cs2d/AIControl/A/PathfindingModuleConcurrencyTest.java` — 29 added, 0 deleted.
  - Covers null, empty, negative, valid, and one-element out-of-range paths.
  - Verifies the primary path mutation entrypoints remain synchronized.
- `.agent/changeHistory/2026-08-30-state-coalescing-path-safety.md` — 34 added, 0 deleted.

## Behavior

- Removed behavior: a concurrent target/reset could change `currentPathIndex` between the update check and list access, producing `IndexOutOfBoundsException` for a one-node path.
- Added behavior: operations for one bot are serialized; different bots still run independently.
- No protocol, configuration, dependency, schema, or user-visible movement tuning changes.

## Validation

- `mvn test` — passed, 21 tests, 0 failures, 0 errors.
- `git diff --check` — passed; only the repository's existing LF-to-CRLF advisory was printed.

## Follow-up and rollback

- Run a 50-player large-map match and confirm there are no `PathfindingModule.followPath` exceptions.
- The implementation can be compared or reverted back to baseline commit `1541fda` before it is committed.
