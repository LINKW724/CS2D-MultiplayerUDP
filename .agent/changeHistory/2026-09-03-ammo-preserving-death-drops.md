# Ammo-preserving death drops

- Date: 2026-09-03
- Objective: make weapons dropped on death preserve the actual live magazine and reserve ammunition, including AI-held weapons.
- Repository: `O:/java/games/CS2D-MultiplayerUDP`
- Branch: `main`
- Pre-change baseline: `ec82516f57b0bd84e2b98cd7a95e8abf3f48600d`
- Implementation state: included in the final implementation commit created for this task.

## Files changed

- `src/main/java/cs2d/playerAndAi/WeaponAmmoSnapshot.java` (+11 / -0): adds an immutable weapon-and-ammunition value object with non-negative ammunition normalization.
- `src/main/java/cs2d/playerAndAi/Player.java` (+29 / -0): adds `snapshotWeaponSlot(int)`, which returns live counters for the selected slot and stored counters for a holstered slot.
- `src/main/java/cs2d/server/GameState.java` (+11 / -11): routes both death drops and active weapon drops through the same snapshot instead of reading potentially stale primary-slot fields.
- `src/test/java/cs2d/playerAndAi/WeaponAmmoSnapshotTest.java` (+49 / -0): verifies selected-primary live ammo and holstered-primary stored ammo behavior.
- `.agent/changeHistory/2026-09-03-ammo-preserving-death-drops.md` (+38 / -0): records this change.

## Behavior and compatibility

- A bot or player dying while holding a partly used primary weapon now drops that exact magazine and reserve count.
- A holstered primary weapon still uses its authoritative stored slot counters.
- Manual weapon drops use the same snapshot path, removing a second, order-dependent ammunition synchronization rule.
- Pickup behavior and dropped-item JSON fields remain unchanged, so clients and protocol compatibility are preserved.
- No schema, dependency, configuration, map, AI decision, or respawn rule changed.

## Validation

- The first focused-test compile exposed incorrect test fixture imports/constructor arguments; the fixture was corrected before successful validation.
- `mvn -Dtest=WeaponAmmoSnapshotTest,DroppedWeaponPickupCooldownTest test`: passed, 5 tests.
- `mvn test`: passed, 138 tests, 0 failures/errors/skips.
- `mvn -DskipTests package`: passed; existing JavaFX parent-project and assembly artifact-replacement warnings remain.
- `git diff --check`: passed; Git reported only the repository's LF-to-CRLF conversion warnings.

## Manual verification and rollback

- Restart the server, let an AI fire several rounds, kill it, then pick up its primary weapon. The HUD should show the remaining live magazine and reserve count rather than spawn-full ammunition.
- No migration is required.
- Roll back code to `ec82516f57b0bd84e2b98cd7a95e8abf3f48600d`.
