# Zombie separation and cleanup hunt

- Date: 2026-09-04
- Objective: prevent zombie units from permanently overlapping, reduce pursuit congestion, recover stuck pursuers, and make survivor AI actively clear the final zombies using authoritative remainder information.
- Repository: `O:/java/games/CS2D-MultiplayerUDP`
- Branch: `main`
- Baseline commit: `a61de10ddfec0300f7ac09cbb5030028364d6e62`
- Implementation state: changes remain uncommitted in the worktree.

## Files

- `src/main/java/cs2d/server/PlayerPairSeparation.java` (+42/-0): added pure deterministic circle separation, including a stable fallback normal for identical centres.
- `src/main/java/cs2d/server/GameState.java` (+21/-12): replaced the zero-distance-skipping collision branch, publishes same-team congestion, added an authoritative remaining-zombie count, and uses that same value for client UI state.
- `src/main/java/cs2d/AIControl/zombie/ZombiePursuitPlanner.java` (+91/-0): added stable multi-ring surround slots, neighbour repulsion, exact-overlap divergence, blocked-slot fallback, and lateral progress-timeout detours.
- `src/main/java/cs2d/AIControl/BG/ZOMBIEcontrol.java` (+30/-1): integrated pursuit slots, collision-aware detours, and one-second no-progress recovery into zombie locomotion.
- `src/main/java/cs2d/AIControl/zombie/ZombieTacticalSnapshot.java` (+9/-2): added remaining count and explicit cleanup leads while preserving existing compatibility constructors.
- `src/main/java/cs2d/AIControl/zombie/ZombieTacticalOrder.java` (+1/-1): added the `HUNT_REMAINDER` task.
- `src/main/java/cs2d/AIControl/zombie/ZombieTacticalCoordinator.java` (+29/-1): assigns at most two ready survivor AIs per final zombie and leaves unassigned survivors guarding.
- `src/main/java/cs2d/server/ZombieTacticalRuntime.java` (+11/-1): activates cleanup only when 1–3 zombies remain and no additional zombies are pending spawn; supplies authoritative cleanup positions only in that phase.
- `src/test/java/cs2d/server/PlayerPairSeparationTest.java` (+23/-0): covers identical-centre opposite corrections and non-overlapping pairs.
- `src/test/java/cs2d/AIControl/zombie/ZombiePursuitPlannerTest.java` (+56/-0): covers unique surround slots, exact-overlap divergence, walkable fallback, multi-ring crowd distribution, and lateral timeout recovery.
- `src/test/java/cs2d/AIControl/zombie/ZombieTacticalCoordinatorTest.java` (+20/-0): covers limited final-zombie cleanup assignments and verifies that normal wave sizes do not reveal cleanup targets.

## Behavior and APIs

- Player pairs at exactly the same coordinate now receive deterministic opposite corrections instead of bypassing collision resolution.
- Same-team collision state is reset each physics tick and published to AI movement as immediate congestion feedback.
- Pursuers use six slots per ring; larger packs form additional rings rather than compressing into one circle.
- A zombie that moves less than 14 units for one second takes a 750 ms lateral detour and then resumes pursuit.
- New `GameState.getRemainingZombieCount()` is the single authoritative count for the client and AI strategy; it includes alive generated zombies, alive player zombies, and pending spawns.
- Cleanup mode starts only at 1–3 remaining zombies when all remaining zombies are already alive. It does not activate while a wave is still spawning.
- Cleanup locations are an explicit late-wave server hint, separate from ordinary perception contacts. At most two ready independent AIs hunt each target; other survivors retain their guard tasks.
- No dependency, schema, configuration, or client protocol field-name changes.

## Validation

- `mvn -q '-Dtest=PlayerPairSeparationTest,ZombiePursuitPlannerTest,ZombieTacticalCoordinatorTest' test`: passed.
- `mvn -q package`: passed; 194 tests, 0 failures, 0 errors; rebuilt `target/cs2d-server.jar`.
- `git diff --check`: passed. Git only reported the repository's existing LF-to-CRLF checkout warning.

## Notes

- Physical separation is global because the zero-distance defect affected every player pair; pursuit coordination and cleanup behavior are zombie-mode-specific.
- The cleanup threshold and agents-per-target limits are explicit coordinator constants, not hidden timing heuristics.
- Full live-game visual verification still requires restarting the server and playing a crowded wave plus a final-zombie cleanup scenario.
- Roll back the uncommitted implementation to baseline `a61de10ddfec0300f7ac09cbb5030028364d6e62` without resetting unrelated later work.
