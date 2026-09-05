# Single-source reload audio

- Date: 2026-09-03
- Objective: eliminate duplicate reload audio caused by one reload being played from both an authoritative server event and a client-side state transition.
- Repository: `O:/java/games/CS2D-MultiplayerUDP_Client`
- Branch: `main`
- Pre-change baseline: `b7229eb81853025ffd62511795c58c80b0e1656c`
- Implementation state: included in the final implementation commit created for this task.
- Preserved user state: the pre-existing `cs2d_settings.json` change remains untouched and excluded.

## Files changed

- `src/main/java/cs2d/client/GameClient.java` (+2 / -39): removes reload-sound playback, edge bookkeeping, and the obsolete commented state-inference implementation; `isReloading` remains available for HUD and gameplay presentation.
- `.agent/changeHistory/2026-09-03-single-source-reload-audio.md` (+35 / -0): records this change.

## Behavior and architecture

- Reload audio now has one source of truth: the server's `RELOAD` transient sound event.
- The authoritative event continues through event-ID deduplication and the per-source repeated-world-sound guard.
- Full and incremental player-state snapshots now update continuous reload state without causing audio side effects.
- Simultaneous reloads by different players remain independent because no global audio cooldown was introduced.
- No protocol, asset, volume, PCM backend, configuration, dependency, or server behavior changed.

## Validation

- `mvn -Dtest=EventDeduplicatorTest,RepeatedWorldSoundGateTest,GameClientProtocolTest test`: passed, 26 tests.
- `mvn test`: passed, 59 tests, 0 failures/errors/skips.
- `mvn -DskipTests package`: passed, including Windows `jpackage`; existing JavaFX parent-project and assembly artifact-replacement warnings remain.
- `git diff --check`: passed; Git reported only the repository's LF-to-CRLF conversion warning.

## Manual verification and rollback

- Fully restart the client and server, reload once with the local player, then with a nearby bot. Each reload should produce one sound while separate players may still overlap naturally.
- No migration is required.
- Roll back to `b7229eb81853025ffd62511795c58c80b0e1656c`; preserve the separate `cs2d_settings.json` user change.
