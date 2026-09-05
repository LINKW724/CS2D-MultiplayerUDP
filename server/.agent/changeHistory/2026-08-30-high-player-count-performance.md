# High-player-count server performance repair

- Date: 2026-08-30
- Objective: prevent the 120 TPS server from entering an unbounded catch-up/UDP burst loop with 50-100 players.
- Repository: `O:\java\games\CS2D-MultiplayerUDP`
- Branch: `main`
- Baseline commit: `a937280c8cf10f86255608217cf9dd076cd4be28`
- Implementation status: changes remain in the worktree; no implementation commit was requested.

## Files changed

- `src/main/java/cs2d/server/GameServer.java` — 37 additions, 9 deletions. Keeps physics at 120 TPS, caps one outer-loop catch-up pass at four ticks, discards only expired whole ticks, separates network snapshots to 30 Hz, and adds dropped-catch-up plus network phase diagnostics.
- `src/main/java/cs2d/server/NetworkBroadcaster.java` — 24 additions, 18 deletions. Accepts the authoritative simulation tick, sends full snapshots at approximately 10 Hz within the 30 Hz stream, avoids reparsing state JSON solely to copy chunk metadata, and replaces common-pool parallel sending with deterministic sequential UDP sends.
- `src/test/java/cs2d/server/GameLoopLoadSheddingTest.java` — new file, 30 lines. Verifies the 30 Hz snapshot cadence and bounded expired-tick shedding policy.
- `.agent/changeHistory/2026-08-30-high-player-count-performance.md` — new documentation record.

## Behavior changes

- Physics/gameplay TPS remains 120 Hz.
- Network state production is now 30 Hz; full snapshots are approximately 10 Hz. Clients continue rendering and interpolating independently.
- A scheduler stall can no longer trigger seconds of historical tick replay and a corresponding UDP chunk flood.
- The five-second performance line now exposes expired catch-up ticks and network JSON/chunk/send costs.
- Protocol version and packet schema are unchanged; no migration is required.

## Validation

- `mvn test` — passed: 19 tests, 0 failures, 0 errors.
- `git diff --check` — passed; only the repository's existing LF-to-CRLF checkout warning was reported.
- Final source review confirmed no unrelated server files were changed.

## Rollback and manual verification

- Roll back to baseline commit `a937280c8cf10f86255608217cf9dd076cd4be28` if required.
- Restart the server before testing.
- Re-run a 50-100 player large-map session and confirm loop intervals remain near 8.333 ms, expired catch-up stays near zero, and network phase timings no longer grow into multi-second bursts.
