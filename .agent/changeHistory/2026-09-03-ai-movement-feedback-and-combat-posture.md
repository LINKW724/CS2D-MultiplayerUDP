# AI movement feedback and combat posture separation

- Date: 2026-09-03
- Objective: replace proactive teammate repulsion with final-input movement feedback, and keep combat aim independent from locomotion.
- Repository: `O:/java/games/CS2D-MultiplayerUDP`
- Branch: `main`
- Pre-change baseline: `99c7fc243393f07bc6bcd474113257eba3ff5ba0`
- Implementation state: changes remain in the working tree and are not committed.

## Files changed

### `src/main/java/cs2d/AIControl/A/PathfindingModule.java` (+12 / -0)

- Adds `requestRepath()` to invalidate only the current local path.
- Preserves the authored preset route and final tactical destination while requesting a fresh local route.

### `src/main/java/cs2d/AIControl/BG/TEAM_DEATHMATCHcontrol.java` (+167 / -319)

- Removes proactive per-update teammate repulsion from the team-deathmatch execution chain.
- Removes the old coarse 1.5-second total-position stuck detector and temporary world-space detour target.
- Observes the final movement keys after `MovementArbiter`, so attack, grenade, reload, freeze, and other overrides are not mistaken for a blocked path.
- Starts recovery only after three failed 200 ms progress windows.
- Classifies a confirmed obstruction as a nearby teammate or a static/path obstruction.
- Uses deterministic ID-based right of way only after a real block; the yielding AI commits to one lateral or backward recovery for 500 ms instead of changing direction every frame.
- Requests a local re-path for non-teammate obstructions without replacing the team tactic or preset route.
- Feeds only direct visual contact and recent damage position into combat posture memory.
- Allows movement and aim to disagree: an AI can retreat or strafe while keeping its weapon toward the recent engagement.
- Prevents sound-only hunting state from forcing the attack module's aim when the AI is not actually shooting.

### `src/main/java/cs2d/AIControl/movement/CombatPosturePolicy.java` (+97 / -0)

- Adds a standalone, stateful combat-facing policy.
- Accepts only visible-enemy and damage-source evidence; remote gunshots have no input path.
- Retains a reliable threat for 1.8 seconds.
- Keeps aim on that threat while stationary, retreating, or moving laterally, while normal forward travel keeps its locomotion-facing result.

### `src/main/java/cs2d/AIControl/movement/MovementProgressWatchdog.java` (+173 / -0)

- Adds a steering-free execution feedback component.
- Integrates the expected direction and distance from final movement keys over each 200 ms window.
- Projects actual displacement onto the intended direction and confirms blocking below an 18% progress ratio for three consecutive coherent windows.
- Ignores rapidly reversing input, idle input, ineligible states, and observation gaps above 750 ms to avoid false positives.
- Reports immutable assessments and leaves recovery choices to the team-deathmatch controller.

### `src/test/java/cs2d/AIControl/movement/CombatPosturePolicyTest.java` (+55 / -0)

- Verifies retreat-facing, forward-facing, sound exclusion, and memory expiry.

### `src/test/java/cs2d/AIControl/movement/MovementProgressWatchdogTest.java` (+66 / -0)

- Verifies sustained blocking detection, normal progress, reversing-input exclusion, and ineligible-state reset.

## Architecture and behavior

- Tactical route selection remains in the team-mode controller and existing tactical modules.
- `MovementProgressWatchdog` only answers whether issued movement produced real progress; it does not know tactics, teammates, or paths.
- `CombatPosturePolicy` only decides facing from reliable threat memory; it does not choose movement keys.
- The controller performs post-detection classification and a short committed recovery, then returns control to the existing route task.
- The legacy `LocalAvoidancePlanner` source remains in the repository for compatibility but has no production caller in the team-deathmatch path.
- No tick rate, movement speed, weapon behavior, FOV, protocol, dependency, or persistent data format changed.

## Validation

- Focused movement-policy suite: passed, 26 tests, 0 failures, 0 errors.
- `mvn test`: passed, 113 tests, 0 failures, 0 errors, 0 skipped.
- `git diff --check`: passed; Git only reported LF-to-CRLF conversion warnings.

## Manual verification

- Rebuild and restart the server, then test team deathmatch with multiple bots entering a narrow corridor from opposite directions.
- A moving group should no longer react merely because teammates are nearby. Recovery should start only after about 600 ms of coherent failed movement.
- During a confirmed teammate conflict, one bot should keep right of way and the other should commit to one recovery direction for about 500 ms without left-right oscillation.
- After firing or losing direct sight, a retreating bot should keep facing the engagement for at most 1.8 seconds; distant sound alone must not turn its aim.
- The 200 ms sampling, three-failure threshold, and 500 ms recovery duration are behavior-tuning values and may require live-map adjustment, but they are isolated from tactical planning.

## Rollback

- The exact pre-change recovery point is commit `99c7fc243393f07bc6bcd474113257eba3ff5ba0`.
- No migration or data conversion is required.
