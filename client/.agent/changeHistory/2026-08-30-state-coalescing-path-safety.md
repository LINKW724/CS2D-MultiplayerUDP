# State Coalescing and Background JSON Parsing

- Date: 2026-08-30
- Objective: prevent FX-thread freezes caused by replaying and deep-copying accumulated UDP state snapshots.
- Repository: `O:/java/games/CS2D-MultiplayerUDP_Client`
- Branch: `main`
- Baseline commit: `d27e5aa95e77b3dd0e1a5cd378cd059f1b1d684b`
- Implementation status: changes remain in the working tree.

## Files

- `src/main/java/cs2d/client/GameClient.java` — 120 added, 44 deleted.
  - Parses complete JSON messages on the network thread instead of the JavaFX frame thread.
  - Keeps only the latest full and small state snapshots, then consumes at most two in sequence order per frame.
  - Removes full-tree `JsonObject.deepCopy()` from state receipt and uses a shallow, read-only top-level merge.
  - Adds `[NET-STATE]` monitoring for replaced snapshots, pending state, and queued events.
- `src/test/java/cs2d/client/GameClientProtocolTest.java` — 25 added, 0 deleted.
  - Verifies snapshot replacement, stale rejection, ordering, counters, and drain behavior.
- `.agent/changeHistory/2026-08-30-state-coalescing-path-safety.md` — 37 added, 0 deleted.
- `cs2d_settings.json` — pre-existing user runtime change; deliberately preserved and excluded from this implementation.

## Behavior

- Removed behavior: one render frame could parse and apply up to 512 historical state messages and copy an entire 50-player JSON tree for each full update.
- Added behavior: handshake and event messages retain their queue; replaceable world snapshots use two bounded latest-value slots.
- Server/network/render rates, FOV ray count, FOV precision, graphics quality, protocol, dependencies, and settings are unchanged.

## Validation

- `mvn test` — passed, 24 tests, 0 failures, 0 errors.
- `git diff --check` — passed; only the repository's existing LF-to-CRLF advisory was printed.

## Follow-up and rollback

- Run the same 50-player large-map scenario and inspect `[NET-STATE]`, FPS, 1% Low, `[M1]`, `[M2]`, and `[PACE]`.
- Expected result: `覆盖旧快照` may increase during a stall, while `待消费状态` stays at 0–2 and Full Update no longer spikes to tens of milliseconds.
- The implementation can be compared or reverted back to baseline commit `d27e5aa` before it is committed.
