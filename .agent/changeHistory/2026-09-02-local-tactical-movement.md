# Local tactical movement and cover awareness

- Date: 2026-09-02
- Objective: Decouple path movement from facing, add deterministic local cover selection, and prevent teammate piles in narrow corridors.
- Repository: `O:/java/games/CS2D-MultiplayerUDP`
- Branch: `main`
- Baseline commit: `2a5463d591bca846f84804bdb5251df988b6daab`
- Implementation state: staged in the worktree, not committed.

## Files changed

- `src/main/java/cs2d/AIControl/BG/TEAM_DEATHMATCHcontrol.java` (`+185/-82`)
  - Composes independent locomotion-facing and local-cover policies.
  - Preserves a short guarded facing direction during sharp route reversals.
  - Gives committed cover movement priority over idle and team-route orders.
  - Responds to recent damage and reloads with a time-bounded cover commitment.
  - Detects teammates ahead instead of applying symmetric ID-only repulsion.
  - Detects lateral wall clearance and converts blocked narrow passages into an intentional queue.
  - Suppresses false stuck recovery while the bot is intentionally waiting in that queue.
  - Throttles non-urgent ranged cover scans to 600 ms while keeping damage/reload reactions immediate.
- `src/main/java/cs2d/AIControl/movement/LocalAvoidancePlanner.java` (`+33/-4`)
  - Adds geometry-aware left/right steering availability.
  - Emits a stable `corridor-queue` stop intent when neither side is traversable.
- `src/main/java/cs2d/AIControl/movement/LocalCoverPlanner.java` (`+87/-0`)
  - Adds a deterministic local cover scorer behind a small geometry interface.
  - Rejects exposed, blocked, out-of-bounds, and teammate-occupied cover slots.
  - Prefers nearby cover aligned away from the threat.
- `src/main/java/cs2d/AIControl/movement/LocomotionFacingPolicy.java` (`+72/-0`)
  - Adds a stateful, independently testable facing policy.
  - Uses short backpedal-facing retention for reversals over 125 degrees and turns normally after 900 ms.
- `src/main/java/cs2d/AIControl/movement/MovementArbiter.java` (`+2/-1`)
  - Allows an exclusive zero-key intent to intentionally own and stop locomotion.
- `src/main/java/cs2d/AIControl/movement/MovementIntent.java` (`+5/-0`)
  - Adds the `stop(sourceId, priority)` intent factory.
- `src/test/java/cs2d/AIControl/movement/LocalAvoidancePlannerTest.java` (`+22/-0`)
  - Covers narrow-corridor queueing and use of the only open lateral side.
- `src/test/java/cs2d/AIControl/movement/LocalCoverPlannerTest.java` (`+61/-0`)
  - Covers hidden-point selection, exposed-point rejection, and teammate slot separation.
- `src/test/java/cs2d/AIControl/movement/LocomotionFacingPolicyTest.java` (`+35/-0`)
  - Covers short backpedal retention, eventual long-turn behavior, and ordinary route turns.
- `src/test/java/cs2d/AIControl/movement/MovementArbiterTest.java` (`+10/-0`)
  - Covers exclusive intentional stopping without fabricated movement keys.
- `.agent/changeHistory/2026-09-02-local-tactical-movement.md` (`+82/-0`)
  - Records this implementation, validation, rollback point, and manual checks.

## Behavior replaced

- Replaced unconditional path-facing during every non-combat move with a separate facing policy.
- Replaced eight random cover probes with deterministic local cover scoring.
- Replaced symmetric nearby-teammate yielding with forward-progress-aware yielding.
- Replaced futile lateral wall pushing in single-width passages with intentional waiting.
- Replaced combat strafing ownership during a committed cover move with path ownership while preserving combat aim.

## APIs and configuration

- New API: `MovementIntent.stop(String sourceId, int priority)`.
- New extension boundary: `LocalCoverPlanner.GeometryProbe`.
- No protocol, save-data, map-schema, dependency, or configuration changes.
- No client changes are required.

## Validation

- Focused command: `mvn -Dtest=MovementArbiterTest,LocalAvoidancePlannerTest,LocomotionFacingPolicyTest,LocalCoverPlannerTest test`
  - Result: success; 21 tests, 0 failures, 0 errors.
- Full command: `mvn test`
  - Result: success; 102 tests, 0 failures, 0 errors.
- `git diff --check`
  - Result: success; only existing line-ending conversion warnings were reported.

## Manual verification

- Restart the server; the client does not need to be rebuilt for this server-side AI change.
- In a single-width corridor, verify the rear bot waits behind the front bot instead of repeatedly sidestepping into walls.
- Force a short path reversal and verify the bot initially moves backward while keeping its guarded facing direction.
- Damage or reload a bot near an obstacle and verify it chooses an unoccupied point hidden from the threat.
- A live match is still required to tune the 900 ms facing hold and local cover search radii against subjective game feel.

## Scope and rollback notes

- The user-edited `maps/d3_2.5x无粗.json` remains unstaged and was not included in the baseline or implementation.
- Baseline recovery point: `2a5463d591bca846f84804bdb5251df988b6daab`.
- This implementation is staged but intentionally not committed because no final implementation commit was requested.
