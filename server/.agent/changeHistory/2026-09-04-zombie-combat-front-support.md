# Zombie combat-front support

- Date: 2026-09-04
- Objective: turn fresh teammate sightings into explicit combat fronts so idle survivor AI outside the local view can reinforce active fights without receiving through-wall firing knowledge.
- Repository: `O:/java/games/CS2D-MultiplayerUDP`
- Branch: `main`
- Baseline commit: `27620e2a1ec6f46c0a0c6d76e3ea12e9d85c2c98`
- Implementation state: changes remain uncommitted in the worktree.

## Files

- `src/main/java/cs2d/AIControl/zombie/ZombieCombatFront.java` (+18/-0): added the immutable active-front model with center, rally point, pressure, staffing requirement, engaged agents, and report age.
- `src/main/java/cs2d/AIControl/zombie/ZombieCombatFrontPlanner.java` (+108/-0): groups fresh shared sightings, merges predicted-lane pressure, identifies current spotters/shooters, sizes each front, and chooses a walkable rally point anchored behind the current fighters.
- `src/main/java/cs2d/AIControl/zombie/ZombieFrontSupportAllocator.java` (+50/-0): holds existing shooters and fills measured staffing deficits from ready idle AI, modestly preferring higher-firepower agents and retaining a reserve except for critical understaffed fronts.
- `src/main/java/cs2d/AIControl/zombie/ZombieTacticalCoordinator.java` (+13/-1): inserts hold/support-front orders ahead of predicted-lane and local clearing tasks while preserving imminent danger, cleanup, and reload-cover priorities.
- `src/main/java/cs2d/AIControl/zombie/ZombieTacticalOrder.java` (+2/-1): adds explicit `HOLD_FRONT` and `SUPPORT_FRONT` tasks.
- `src/main/java/cs2d/AIControl/zombie/ZombieTacticalSnapshot.java` (+10/-2): adds last-shot time to allied units and observer identity to contacts with compatibility constructors for existing callers.
- `src/main/java/cs2d/AIControl/zombie/ZombieIntelBoard.java` (+1/-1): retains the reporting AI identity in each shared visual contact.
- `src/main/java/cs2d/server/ZombieTacticalRuntime.java` (+1/-1): publishes each unit's authoritative last-shot timestamp into the tactical snapshot.
- `src/main/java/cs2d/AIControl/zombie/ZombiePositionPlanner.java` (+10/-0): distinguishes short local safety checks from distant strategic A* goals so a wall no longer collapses a reinforcement order into local wandering.
- `src/main/java/cs2d/AIControl/BG/ZOMBIEcontrol.java` (+2/-2): uses the strategic/local movement distinction while retaining emergency, blocked-fire, hazard, and teammate-spacing overrides.
- `src/test/java/cs2d/AIControl/zombie/ZombieCombatFrontPlannerTest.java` (+44/-0): covers spatial grouping, predicted pressure, rally selection, engaged-agent detection, and rejection of stale or anonymous contacts.
- `src/test/java/cs2d/AIControl/zombie/ZombieFrontSupportAllocatorTest.java` (+36/-0): covers shooter retention, firepower-aware support choice, normal reserve retention, and critical reserve deployment.
- `src/test/java/cs2d/AIControl/zombie/ZombieTacticalCoordinatorTest.java` (+13/-0): verifies that one active shooter is held while three idle blind-area agents receive support orders.
- `src/test/java/cs2d/AIControl/zombie/ZombieIntelBoardTest.java` (+8/-0): verifies that shared contacts retain their spotter identity.
- `src/test/java/cs2d/AIControl/zombie/ZombiePositionPlannerTest.java` (+7/-0): verifies distant cross-wall goals are delegated to A* while nearby blocked segments still use local avoidance.

## Behavior and APIs

- A combat front exists only from a real AI visual report and remains active for the existing three-second team-intel memory window.
- Contacts within 480 units form one front. Required staffing scales from two to six defenders using the larger of visible-contact pressure and the matching predicted zombie-lane pressure.
- The reporting AI and nearby agents that fired within 1.4 seconds count as engaged. Ready engaged agents receive `HOLD_FRONT`; they are not reassigned away from a working firing position.
- `SUPPORT_FRONT` fills only the remaining deficit from ready independent AI. High-firepower weapons may travel modestly farther, while a normal squad keeps one reserve unless a pressure-six-or-higher front has fewer than two engaged defenders.
- Support destinations use a predicted defense point when available; otherwise they rally behind the front toward the currently engaged agents, not toward the zombie center.
- Long-distance tactical goals are passed to existing A* pathfinding so blind-side agents can route around walls. Local emergency movement still uses direct-segment threat, fire, and teammate checks.
- Shared contacts affect movement and watch direction only. The existing local perception and line-of-fire checks still gate every shot.
- No dependency, protocol, persistence schema, configuration, damage, accuracy, or weapon-stat changes.

## Validation

- `mvn -q '-Dtest=ZombieCombatFrontPlannerTest,ZombieFrontSupportAllocatorTest,ZombieTacticalCoordinatorTest,ZombieIntelBoardTest,ZombiePositionPlannerTest' test`: passed; 31 tests, 0 failures, 0 errors.
- `mvn -q package`: passed; 214 tests, 0 failures, 0 errors, 0 skipped; rebuilt `target/cs2d-server.jar`.
- `git diff --check`: passed. Git only reported the repository's existing LF-to-CRLF checkout warning.

## Notes

- The first focused run exposed `lastShotAt=0` being interpreted as recent under synthetic small timestamps; the final implementation explicitly requires a positive shot timestamp and the rerun passed.
- Human players do not manufacture tactical sightings: active fronts originate from AI visual reports. Once reported, all ready survivor AI may use the front for movement coordination.
- Live verification requires restarting the server and observing a split formation where one group has sight and another is behind map geometry.
- Roll back the uncommitted implementation to baseline `27620e2a1ec6f46c0a0c6d76e3ea12e9d85c2c98` without resetting unrelated later work.
