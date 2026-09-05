# Zombie crowd fire control

- Date: 2026-09-04
- Objective: make survivor LMG AI exploit aligned zombie groups, sweep broad crowds at a controlled cadence, preserve recoil/reaction state while changing targets inside one engagement, and retain immediate-threat and friendly-fire safety.
- Repository: `O:/java/games/CS2D-MultiplayerUDP`
- Branch: `main`
- Baseline commit: `35464ccdc6d8b835d85c60b8b9575aa58d25d36d`
- Implementation state: changes remain uncommitted in the worktree.

## Files

- `src/main/java/cs2d/AIControl/zombie/ZombieCrowdFirePlanner.java` (+114/-0): added a stateful, mode-specific LMG target director with penetration-ray scoring, close-threat override, profitable-ray stability, bounded fan sweeping, and generation-safe engagement identities.
- `src/main/java/cs2d/AIControl/BG/ZOMBIEcontrol.java` (+27/-6): routes only survivor LMG targeting through the crowd planner, excludes teammate-blocked candidates when another clear target exists, forwards the engagement key to the firing layer, and resets crowd state on lifecycle reset.
- `src/main/java/cs2d/AIControl/A/AttackModule.java` (+8/-1): added a compatibility-preserving overload accepting an engagement key so same-crowd target changes do not reset reaction time, burst accounting, or recoil progression.
- `src/test/java/cs2d/AIControl/zombie/ZombieCrowdFirePlannerTest.java` (+54/-0): covers multi-zombie ray selection, immediate-threat priority, controlled wide-crowd sweeping, complete target replacement, and weapon-interruption identity renewal.
- `src/test/java/cs2d/AIControl/A/ZombieAttackExecutionTest.java` (+16/-0): verifies same-crowd target changes retain firing readiness and Negev recoil progress while a genuinely new engagement resets both.

## Behavior and APIs

- LMG survivor AI evaluates every clear visible zombie as a ray and strongly prefers directions intersecting multiple zombies, allowing the existing server penetration model to damage a queue efficiently.
- A zombie within 170 units always overrides distant multi-hit opportunities.
- A profitable ray intersecting two or more visible zombies remains locked instead of oscillating every AI update.
- When at least three visible zombies form a broad fan and no ray intersects more than one, aim advances to another real visible target every 550 ms rather than applying an arbitrary blind-fire offset.
- Visible targets sharing continuity retain one engagement key. Switching among them no longer restarts reaction delay or resets the Negev/M249 recoil sequence.
- Losing every visible member or interrupting the weapon state creates a new engagement key; stale firing state is not reused after a grenade or weapon switch.
- Existing `AttackModule.update` overloads are unchanged for all other modes and weapons. No dependency, protocol, configuration, schema, or client changes.

## Validation

- `mvn -q '-Dtest=ZombieCrowdFirePlannerTest,ZombieAttackExecutionTest' test`: passed; 9 tests, 0 failures, 0 errors.
- `mvn -q package`: passed; 207 tests, 0 failures, 0 errors, 0 skipped; rebuilt `target/cs2d-server.jar`.
- `git diff --check`: passed. Git only reported the repository's existing LF-to-CRLF checkout warning.

## Notes

- Crowd selection uses only currently visible, physically unobstructed hostile targets; it does not grant blind fire or through-wall knowledge.
- The planner optimizes existing hitscan penetration rather than changing weapon damage, penetration, magazine size, or fire rate.
- Live verification requires restarting the server and observing both a single-file corridor horde and a wide open-area horde with a Negev or M249 survivor AI.
- Roll back the uncommitted implementation to baseline `35464ccdc6d8b835d85c60b8b9575aa58d25d36d` without resetting unrelated later work.
