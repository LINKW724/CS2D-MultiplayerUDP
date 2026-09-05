# Team tactical posture state machine

- Date: 2026-09-03
- Objective: Stop frontline oscillation by introducing mutually-exclusive team postures, reliable engagement gating, posture commitment, and distinct route-safe formation slots.
- Repository: `O:\java\games\CS2D-MultiplayerUDP`
- Branch: `main`
- Pre-change baseline: `ced3bdda68678c7dbc71c5b937740915e256c0b8`
- Implementation status: changes remain uncommitted in the working tree.

## Files changed

- `.agent/changeHistory/2026-09-03-team-tactical-posture.md` (+78/-0)
  - Records the baseline, per-file changes, validation, limitations, restart steps, and rollback point.
- `src/main/java/cs2d/AIControl/BG/TEAM_DEATHMATCHcontrol.java` (+58/-6)
  - Resolves one effective posture before locomotion arbitration.
  - Allows combat strafing only for a directly visible target inside the current weapon's effective range.
  - Treats ENGAGE as an exclusive movement owner, including explicit stationary shooting frames, so path movement cannot leak through between counter-strafes.
  - Continues team movement for sound-only, occluded, or out-of-range targets; stealth and ambush postures control walking and holding.
- `src/main/java/cs2d/AIControl/team/AdaptiveTeamTacticalCoordinator.java` (+21/-3)
  - Adds an injectable posture doctrine extension point and applies it after all tactical plan refiners.
- `src/main/java/cs2d/AIControl/team/TacticalOrder.java` (+42/-3)
  - Adds `Posture` (`STEALTH_ADVANCE`, `RUSH`, `AMBUSH`, `ENGAGE`) and posture commitment expiry.
  - Preserves source compatibility through existing constructors and adds immutable posture/target copy methods.
- `src/main/java/cs2d/server/TeamTacticalRuntime.java` (+9/-1)
  - Applies route-safe formation slots and posture commitments after task lifecycle reconciliation.
  - Clears posture state with the rest of the tactical runtime.
- `src/main/java/cs2d/AIControl/team/TacticalFormationSlotPolicy.java` (+110/-0)
  - Assigns agents sharing an authored route distinct longitudinal slots instead of one identical coordinate.
- `src/main/java/cs2d/AIControl/team/TacticalPostureCommitmentBoard.java` (+80/-0)
  - Locks posture decisions for a deterministic 3-6 seconds.
  - Allows immediate safety downgrades, so an uncertain sound response can turn a rush into stealth but cannot repeatedly promote agents back into a rush.
- `src/main/java/cs2d/AIControl/team/TacticalPostureDoctrine.java` (+9/-0)
  - Defines the game-mode posture selection extension point.
- `src/main/java/cs2d/AIControl/team/TacticalPostureExecutionPolicy.java` (+38/-0)
  - Converts a committed team posture plus reliable local evidence into hold, silent movement, rush, or combat movement behavior.
- `src/main/java/cs2d/AIControl/team/TdmPostureDoctrine.java` (+21/-0)
  - Makes only entry-role ADVANCE orders rush; support, flank, regroup, assembly, suppression, and sound response remain stealthy until arrival.
- `src/test/java/cs2d/AIControl/team/TacticalFormationSlotPolicyTest.java` (+52/-0)
  - Verifies distinct slots remain on an authored route.
- `src/test/java/cs2d/AIControl/team/TacticalPostureCommitmentBoardTest.java` (+55/-0)
  - Verifies 3-6 second commitment, delayed aggressive promotion, and immediate safety downgrade.
- `src/test/java/cs2d/AIControl/team/TacticalPostureExecutionPolicyTest.java` (+58/-0)
  - Verifies sound-only information cannot enter ENGAGE, direct sight also requires effective range, and arrival becomes a silent ambush.
- `src/test/java/cs2d/AIControl/team/TdmPostureDoctrineTest.java` (+31/-0)
  - Verifies only entry advances receive RUSH.

## Behavior changes

- Hearing a gunshot no longer grants combat-movement control. Assigned responders approach quietly.
- Seeing a target outside shotgun/SMG effective range produces a stealth approach or hold instead of combat strafing.
- Direct sight plus effective range temporarily promotes the agent to ENGAGE.
- An ENGAGE frame with no strafe keys explicitly stops instead of falling back to path movement.
- Reaching an assigned target produces AMBUSH behavior: no locomotion and silent state while aim remains independent.
- Multiple agents on one authored route receive staggered, route-safe target positions.
- The 250 ms commander observation interval remains unchanged, while posture changes are committed for 3-6 seconds.

## Architecture and compatibility

- Team doctrine, commitment, execution translation, and slot allocation are separate policies.
- Pathfinding and `AttackModule` do not depend on team posture types.
- Existing `TacticalOrder` constructors remain available; no external migration or dependency change is required.
- No network protocol, persistence schema, configuration, or dependency was changed.

## Validation

- `mvn "-Dtest=TacticalPostureCommitmentBoardTest,TacticalPostureExecutionPolicyTest,TacticalFormationSlotPolicyTest,TdmPostureDoctrineTest,AdaptiveTeamTacticalCoordinatorTest,ElasticManeuverRefinerTest,TacticalIdlePolicyTest,TacticalTaskBoardTest,SoundPursuitGateTest" test`
  - Passed: 39 tests, 0 failures, 0 errors.
- `mvn test`
  - Passed: 120 tests, 0 failures, 0 errors.
- `git diff --check`
  - Passed. Git only reported the repository's existing LF-to-CRLF conversion notice.

## Manual verification and rollback

- Restart the server before testing because all behavior is server-side.
- Verify on a route-heavy TDM map with overview enabled: responders should walk silently toward sound, entry agents should maintain a rush, agents at assigned slots should stop, and only direct in-range contact should enable strafing.
- Automated tests do not replace a live multi-agent movement observation; formation spacing near unusually sparse authored route points should be checked visually.
- To return to the exact pre-change state, preserve any later work first and restore the working tree to baseline commit `ced3bdda68678c7dbc71c5b937740915e256c0b8`.
