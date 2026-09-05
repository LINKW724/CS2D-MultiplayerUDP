# Zombie kiting, corner escape, and separation

- Date: 2026-09-04
- Objective: make LMG survivors stow heavy weapons while kiting, make pressured SMG survivors escape corners immediately, and prevent melee-range zombies from collapsing back into overlapping stacks.
- Repository: `O:/java/games/CS2D-MultiplayerUDP`
- Branch: `main`
- Baseline commit: `0a4b6f91673431b9d41c80e0033f9986de44065f`
- Implementation state: committed together with this record at the user's request.

## Files

- `src/main/java/cs2d/AIControl/BG/ZOMBIEcontrol.java` (+39/-11): integrates damage-aware escape, LMG pistol kiting with hysteresis, corner flood-search goals, kiting-aware grenade recovery, and continued surround movement while zombies are in melee range.
- `src/main/java/cs2d/AIControl/zombie/ZombieEscapePlanner.java` (+83/-0): adds a bounded local flood search that can turn through a corner exit, rejects occupied or hazardous goals, and prevents escape edges from crossing through zombies.
- `src/main/java/cs2d/AIControl/zombie/ZombieSurvivorMobilityPolicy.java` (+24/-0): adds the pure emergency/kiting state policy, including separate enter and exit distances and a short zombie-damage reaction window.
- `src/main/java/cs2d/playerAndAi/Player.java` (+6/-2): publishes the attacker's team with the existing damage-source position and clears all damage memory on respawn.
- `src/main/java/cs2d/server/DamageMovementPolicy.java` (+12/-0): separates generic hit slowing from zombie-melee movement rules.
- `src/main/java/cs2d/server/GameState.java` (+36/-5): records authoritative damage-source memory, removes generic 70% movement slowing from zombie claws, and performs collision solving as three globally re-indexed passes in deterministic order.
- `src/test/java/cs2d/AIControl/zombie/ZombieEscapePlannerTest.java` (+39/-0): covers turning through a single corner exit, teammate-occupied goals, and paths that would cross a threat.
- `src/test/java/cs2d/AIControl/zombie/ZombieSurvivorMobilityPolicyTest.java` (+29/-0): covers LMG pistol retreat, restore-distance hysteresis, and SMG escape without a pointless weapon switch.
- `src/test/java/cs2d/playerAndAi/PlayerRespawnTest.java` (+18/-0): verifies that respawn clears stale damage-threat memory.
- `src/test/java/cs2d/server/DamageMovementPolicyTest.java` (+20/-0): covers zombie claw mobility and unchanged gun/other-mode hit slowing.

## Behavior and APIs

- Negev and M249 survivors switch to their pistol when a zombie enters 270 units, when standing in fire, or after zombie damage. They switch back only after reaching 390 units with no recent damage or hazard, preventing slot oscillation.
- SMG survivors retain their full-speed primary weapon. A zombie hit now creates a 1.2-second emergency escape state even when the attacker is outside current vision.
- Emergency movement uses an eight-step bounded flood search rather than only fixed radial samples, so the selected goal can route through the only viable corner exit.
- Zombie melee no longer continually refreshes the generic 300 ms movement slow; gunfire and other modes retain the existing slow.
- Damage-source team is recorded separately so friendly grenade damage is not mistaken for a zombie approaching from the thrower's position.
- AI zombies continue moving toward stable surround slots while attacking, and use an 8-unit arrival radius instead of treating a 30-unit surround slot as already reached.
- Collision solving now rebuilds the spatial grid between three global passes. Descending entity order lets wall corrections occur before the pair owner performs the final separation, reducing wall-corner re-overlap.
- No client protocol, dependency, persistence schema, weapon damage, fire rate, or grenade safety changes.

## Validation

- `mvn -q '-Dtest=ZombieSurvivorMobilityPolicyTest,ZombieEscapePlannerTest,DamageMovementPolicyTest,ZombiePursuitPlannerTest,PlayerPairSeparationTest,ZombiePositionPlannerTest,PlayerRespawnTest' test`: passed; 26 tests, 0 failures, 0 errors, 0 skipped.
- `mvn -q package`: passed; 225 tests, 0 failures, 0 errors, 0 skipped; rebuilt `target/cs2d-server.jar`.
- `git diff --check`: passed. Git only reported the repository's existing LF-to-CRLF checkout warning.

## Notes

- Restart the server before live verification; no client rebuild is required.
- Recommended live cases are an LMG survivor approached from open ground, an SMG survivor clawed in a wall corner, and several zombies attacking a survivor against a wall.
- Collision resolution is deterministic and globally repeated, but final visual verification under a very large live horde remains necessary because the unit tests do not instantiate a full map physics tick.
- The pre-change recovery point is baseline `0a4b6f91673431b9d41c80e0033f9986de44065f`; preserve unrelated later work when reverting.
