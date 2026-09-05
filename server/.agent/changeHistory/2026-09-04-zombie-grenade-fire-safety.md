# Zombie grenade and fire safety

- Date: 2026-09-04
- Objective: prevent zombie-mode survivor AI from throwing HE/fire grenades into allies and make both commander and local movement avoid active fire.
- Repository: `O:/java/games/CS2D-MultiplayerUDP`
- Branch: `main`
- Baseline commit: `e26681893e5a6e9a67a8c9ef5a0f38a3ab11545e`
- Implementation state: changes remain uncommitted in the worktree.

## Files

- `src/main/java/cs2d/AIControl/A/GrenadeModule.java` (+38/-14): retained ballistic calculation, added predicted landing to plans/commands, added an optional post-calculation validator, and preserved the calculated throw angle before state reset.
- `src/main/java/cs2d/AIControl/BG/ZOMBIEcontrol.java` (+54/-11): selects safe cluster targets, snapshots allies for asynchronous validation, rechecks live allies immediately before throwing, cancels grenade work when standing in fire, and performs immediate fire escape.
- `src/main/java/cs2d/AIControl/zombie/ZombieAreaHazard.java` (+16/-0): added immutable, expiring area-hazard data shared by tactical and local movement layers.
- `src/main/java/cs2d/AIControl/zombie/ZombieGrenadeSafetyPolicy.java` (+56/-0): added a pure tactical policy for enemy-density benefit, HE/fire friendly clearances, and duplicate-fire rejection.
- `src/main/java/cs2d/AIControl/zombie/ZombiePositionPlanner.java` (+32/-6): added hazard-aware route and candidate evaluation; a unit already inside fire may only move outward to safety.
- `src/main/java/cs2d/AIControl/zombie/ZombieTacticalCoordinator.java` (+8/-4): incorporated active hazards into guard, reposition, and clearing destinations.
- `src/main/java/cs2d/AIControl/zombie/ZombieTacticalSnapshot.java` (+6/-1): added immutable hazards while retaining the three-argument compatibility constructor.
- `src/main/java/cs2d/server/ZombieTacticalRuntime.java` (+7/-1): converts authoritative server fire patches into the 4 Hz tactical snapshot.
- `src/test/java/cs2d/AIControl/zombie/ZombieGrenadeSafetyPolicyTest.java` (+35/-0): covers friendly-area rejection, safe clusters, predicted-impact revalidation, and existing-fire rejection.
- `src/test/java/cs2d/AIControl/zombie/ZombiePositionPlannerTest.java` (+24/-0): covers fire crossing, outward escape, and expired hazards.

## Behavior and APIs

- `GrenadeModule.requestThrow(Item, Point2D.Double)` remains compatible.
- New overload: `requestThrow(Item, Point2D.Double, Predicate<GrenadeThrowPlan>)` lets a caller approve the calculated impact without placing tactical rules in the ballistics module.
- `GrenadeThrowPlan` and `GrenadeCommand` now carry the predicted landing point for final live safety validation.
- HE uses its authoritative 350-unit blast radius plus safety margin; fire uses a conservative footprint covering generated fire patches and player size.
- A throw requires at least three affected zombies and no survivor inside the relevant friendly clearance.
- Active fire is treated as dynamic danger rather than permanent map geometry. Expired fire automatically stops blocking movement.

## Validation

- `mvn -q -DskipTests compile`: passed.
- `mvn -q '-Dtest=ZombieGrenadeSafetyPolicyTest,ZombiePositionPlannerTest,ZombieTacticalCoordinatorTest' test`: passed.
- `mvn -q package`: passed; 185 tests, 0 failures, 0 errors; rebuilt `target/cs2d-server.jar`.
- `git diff --check`: passed. Git only reported the repository's existing LF-to-CRLF checkout warning.

## Notes

- No dependency, schema, configuration, or client-protocol changes.
- The fix intentionally affects zombie-mode tactical calls only; existing TDM callers continue using the compatible unvalidated overload.
- Safety is based on authoritative active fire and teammate positions at request time, predicted-impact completion, and the final throw frame. Full future teammate trajectory prediction is outside this change.
- Restart the server with the rebuilt JAR to load the change. Roll back by reverting the worktree changes to baseline commit `e26681893e5a6e9a67a8c9ef5a0f38a3ab11545e`; do not reset unrelated later work.
