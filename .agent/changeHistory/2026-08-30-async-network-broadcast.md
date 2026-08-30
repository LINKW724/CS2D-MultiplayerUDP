# Async Network Broadcast

- Date: 2026-08-30
- Objective: prevent 30 Hz UDP snapshot serialization, chunking, and send spikes from blocking the 120 Hz game loop.
- Repository: `O:/java/games/CS2D-MultiplayerUDP`
- Branch: `main`
- Baseline commit: `1f44c1e154e04aee4ad7173d7ab5fdbb7a5d7350`
- Implementation status: changes remain uncommitted for live 50-player verification.

## Files

- `src/main/java/cs2d/server/GameServer.java` — 13 added, 3 deleted.
  - Shuts down the broadcast worker during server shutdown.
  - Reports synchronous snapshot-capture time, asynchronous JSON/chunk/send time, and coalesced snapshot count.
- `src/main/java/cs2d/server/NetworkBroadcaster.java` — 106 added, 87 deleted.
  - Captures an independent state JsonObject on the authoritative game thread at the selected simulation tick.
  - Moves Gson serialization, GZIP/Base64/CRC chunk preparation, and UDP sends to one daemon worker.
  - Uses a one-slot latest-wins mailbox so a slow network cannot create a historical packet flood.
  - Preserves forced full snapshots against replacement by routine small snapshots.
  - Keeps sequence and serverTick metadata attached at capture time.
- `src/test/java/cs2d/server/NetworkProtocolTest.java` — 8 added, 0 deleted.
  - Covers forced-full snapshot priority in the latest-wins mailbox.
- `.agent/changeHistory/2026-08-30-async-network-broadcast.md` — 47 added, 0 deleted; this record.

## Removed or replaced behavior

- The game loop no longer performs blocking JSON serialization, compression, chunk construction, or socket sends.
- Existing 120 TPS simulation, 30 Hz snapshots, full-update cadence, catch-up limit, protocol metadata, and chunk checksum remain unchanged.
- No AI, physics, bullet penetration, weapon pickup cooldown, map, or client rendering behavior changed.

## Validation

- `mvn -DskipTests compile` — passed.
- `mvn test` — passed: 22 tests, 0 failures, 0 errors.
- `git diff --check` — passed; only the repository LF-to-CRLF advisory was printed.

## Live verification

- Restart the server and repeat the 50-player large-map fight.
- Expected monitor: Tick work should track snapshot capture rather than the asynchronous 7-10 ms send spikes.
- Watch `网络[捕获 ... /后台总计 ... /合并旧快照 ...]`; occasional coalescing is intentional under a slow socket.
- Verify client state remains monotonic and forced full updates still arrive after joins, bot changes, and disconnects.

## Rollback

- Baseline `1f44c1e` is the clean pre-change server recovery point.
- No protocol version, configuration, dependency, or data migration is required.
