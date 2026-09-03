# Stable tactical commitments and walking propagation

## Request

Fix TDM AI that repeatedly walks forward and backward, hesitates during a route,
and runs noisily even when it has not seen an enemy. Keep tactical decisions in
the team-policy layer rather than embedding them in pathfinding or movement.

## Pre-change baseline

- Branch: `main`
- Commit: `96bb0a692de983ccc91a4a0e6f65026c735284c8`
- Baseline message: `chore: snapshot before stable tactical commitments`
- The working tree was clean immediately after the baseline commit.

## Root causes

1. The commitment board retained only the posture enum. A 250 ms team replan
   could still replace route, task, movement target and formation slot.
2. Formation slots were recomputed from sorted live agent IDs. When an earlier
   agent left a task, every following agent moved to a different slot.
3. Ordinary `ADVANCE + ENTRY` orders were interpreted as `RUSH`, even though no
   explicit rush maneuver had been ordered.
4. `TEAM_DEATHMATCHcontrol` correctly returned `AIInput.walking=true`, but
   `GameState` did not copy that value into `Player.isWalking`; physics therefore
   continued to use running speed and emitted normal footsteps.

## Behavior after the change

- A commander commitment now locks the complete execution intent for 3-6
  seconds: task, role, route, objective, formation slot and posture.
- Replanning refreshes only the order lifetime while a commitment is active.
- A safety downgrade from rush to stealth remains immediate, but it does not
  redirect the agent to a new route or target.
- Formation slots are stable per team/task/agent and surviving agents no longer
  jump forward when another agent leaves.
- Normal route movement is `STEALTH_ADVANCE`.
- `RUSH` is reserved for the explicit coordinated respawn-wave maneuver.
- The server now propagates the AI walking flag into player physics and clears
  it while AI input is frozen.

## Architectural boundary

- Team doctrine decides whether a maneuver is stealth or rush.
- The commitment board decides when a high-level order may be replaced.
- The formation policy assigns stable objective slots.
- Existing pathfinding and movement arbitration continue to execute keys; no
  team tactics were added to those low-level modules.
- `GameState` only transports the already-decided walking flag into physics.

## Validation

- Targeted tests: 11 passed, 0 failed.
- Full Maven suite: 121 passed, 0 failed, 0 skipped.
- Main sources and tests compile successfully.
- `git diff --check` reports no whitespace errors (only the repository's normal
  LF-to-CRLF conversion warnings on Windows).

## Per-file changes

Line counts below are additions/removals from the pre-change baseline.

- `src/main/java/cs2d/AIControl/team/ElasticManeuverRefiner.java`: +11 / -2
- `src/main/java/cs2d/AIControl/team/TacticalFormationSlotPolicy.java`: +41 / -4
- `src/main/java/cs2d/AIControl/team/TacticalOrder.java`: +6 / -0
- `src/main/java/cs2d/AIControl/team/TacticalPostureCommitmentBoard.java`: +17 / -12
- `src/main/java/cs2d/AIControl/team/TdmPostureDoctrine.java`: +2 / -5
- `src/main/java/cs2d/server/GameState.java`: +2 / -0
- `src/main/java/cs2d/server/TeamTacticalRuntime.java`: +1 / -0
- `src/test/java/cs2d/AIControl/team/ElasticManeuverRefinerTest.java`: +4 / -0
- `src/test/java/cs2d/AIControl/team/TacticalFormationSlotPolicyTest.java`: +21 / -0
- `src/test/java/cs2d/AIControl/team/TacticalPostureCommitmentBoardTest.java`: +21 / -8
- `src/test/java/cs2d/AIControl/team/TdmPostureDoctrineTest.java`: +12 / -3
- `.agent/changeHistory/2026-09-03-stable-tactical-commitments.md`: +82 / -0

## Manual verification

Restart the server so all AI controllers and commitment state are recreated.
In TDM, observe one normal route group for at least 10 seconds with no direct
enemy sight: movement should remain quiet and should not reverse on every team
planning interval. Then create enough respawns for a wave: that explicitly
ordered wave may run until direct contact or its commitment ends.
