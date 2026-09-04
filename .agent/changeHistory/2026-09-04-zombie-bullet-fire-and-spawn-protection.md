# Zombie bullet fire and spawn protection

- Date: 2026-09-04
- Objective: let survivor AI keep firing when teammates cross its gun line while retaining explosive friendly-fire safety, and remove all zombie-mode spawn invulnerability.
- Repository: `O:/java/games/CS2D-MultiplayerUDP`
- Branch: `main`
- Baseline commit: `aa73f48f8090279f6ab332e37f0f0befee97aceb`
- Implementation state: changes remain uncommitted in the worktree.

## Files

- `src/main/java/cs2d/AIControl/BG/ZOMBIEcontrol.java` (+5/-10): removed teammate-obstruction filtering from ordinary and LMG crowd fire, and stopped triggering local repositioning solely because a teammate crossed the firing line.
- `src/main/java/cs2d/AIControl/zombie/ZombieHostilityPolicy.java` (+3/-17): replaced geometric teammate line blocking with a narrow bullet-fire authorization that validates only a living hostile zombie target and a survivor shooter.
- `src/main/java/cs2d/playerAndAi/Player.java` (+1/-1): routes normal respawn invulnerability through the mode policy.
- `src/main/java/cs2d/server/AIService.java` (+2/-2): applies the same bullet-fire authorization to the alternate zombie AI execution path.
- `src/main/java/cs2d/server/GameState.java` (+20/-16): applies the spawn-protection policy to round initialization and survivor-to-zombie transformation, and clears any stale invincibility while zombie mode is active.
- `src/main/java/cs2d/server/SpawnProtectionPolicy.java` (+8/-0): adds the single mode boundary that disables spawn invulnerability only for zombie mode.
- `src/test/java/cs2d/AIControl/zombie/ZombieHostilityPolicyTest.java` (+5/-6): verifies that teammates are not valid targets, do not block survivor bullet authorization, and zombies cannot use guns through the survivor policy.
- `src/test/java/cs2d/playerAndAi/PlayerRespawnTest.java` (+23/-0): verifies zombie-mode respawns are vulnerable immediately while team-deathmatch respawns retain protection.
- `src/test/java/cs2d/server/SpawnProtectionPolicyTest.java` (+16/-0): covers disabled zombie protection and unchanged protection in all other modes.

## Behavior and APIs

- Survivor AI no longer stops firing, changes its LMG crowd target, or repositions merely because a teammate is geometrically between it and a zombie.
- The hostility gate still rejects self, teammates, dead targets, converted survivors, and zombie shooters.
- HE grenades, molotovs, and incendiaries continue to use `ZombieGrenadeSafetyPolicy`; their friendly-clearance and active-fire checks were not changed.
- Zombie-mode round spawn, ordinary respawn, and survivor-to-zombie transformation now all start with `isInvincible=false`.
- The behavior update also removes stale invincibility if a player enters zombie mode through an older or indirect state path.
- Team deathmatch, deathmatch, and demolition retain their previous spawn-protection behavior.
- Respawn timestamps and zombie respawn scheduling remain unchanged.

## Validation

- `mvn -q '-Dtest=ZombieHostilityPolicyTest,ZombieGrenadeSafetyPolicyTest,SpawnProtectionPolicyTest,PlayerRespawnTest' test`: passed; 10 tests, 0 failures, 0 errors.
- `mvn -q package`: passed; 217 tests, 0 failures, 0 errors, 0 skipped; rebuilt `target/cs2d-server.jar`.
- `git diff --check`: passed. Git only reported the repository's existing LF-to-CRLF checkout warning.
- Source audit found no remaining active `isInvincible = true` spawn assignment and no remaining `clearShot` teammate-obstruction call.

## Notes

- Live verification requires restarting the server. In zombie mode, stand behind another survivor and confirm both can fire through the same lane; then respawn or transform a zombie and confirm it can take damage immediately.
- Explosive safety remains intentionally conservative because grenades and fire can damage teammates even though bullets cannot.
- Roll back the uncommitted implementation to baseline `aa73f48f8090279f6ab332e37f0f0befee97aceb` without resetting unrelated later work.
