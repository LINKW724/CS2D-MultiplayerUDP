# Damage-number protocol fields

- Date: 2026-09-04
- Objective: expose authoritative headshot and hit-position data through existing targeted damage events.
- Repository: `O:/java/games/CS2D-MultiplayerUDP`
- Branch: `main`
- Pre-change baseline: `04c94456284a4ebd63ee47b08ac1a6bbf88f82ea`
- Implementation state: remains uncommitted in the working tree.

## Files changed

- `src/main/java/cs2d/playerAndAi/Player.java` (+5 / -1): extends `DamageLogEntry` with headshot and world-position fields and serializes `hs`, `hitX`, and `hitY`.
- `src/main/java/cs2d/server/GameState.java` (+4 / -1): captures the target position and authoritative headshot decision when creating the damage event.
- `src/test/java/cs2d/playerAndAi/DamageLogEntryTest.java` (+29 / -0): verifies the complete backward-compatible JSON payload.
- `.agent/changeHistory/2026-09-04-damage-number-protocol.md` (+34 / -0): records this change.

## Behavior and compatibility

- Existing `damage_event` payloads retain `atk`, `vic`, `dmg`, `ts`, `kill`, and `type` unchanged.
- Optional `hs`, `hitX`, and `hitY` fields let the client render the correct color and location even after a killed target is removed.
- Damage values remain actual health lost after armor, falloff, headshot multiplier, and health clamping.
- No protocol-version bump, dependency, migration, AI, weapon-balance, or network-routing change was introduced; old clients ignore the added fields.

## Validation

- `mvn -Dtest=DamageLogEntryTest test`: passed, 1 test.
- `mvn test`: passed, 215 tests, 0 failures/errors/skips.
- `git diff --check`: passed; Git reported only LF-to-CRLF conversion warnings.

## Manual verification and rollback

- Restart the server and client together, then compare body-hit and headshot payloads while killing a zombie on the final hit.
- No data migration is required.
- Roll back the listed source files to baseline `04c94456284a4ebd63ee47b08ac1a6bbf88f82ea` if the optional event fields must be removed.
