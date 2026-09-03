# Low-population AI mobility

- Date: 2026-09-03
- Objective: prevent small TDM teams from becoming permanently stationary after route or moving-support objectives are reached
- Repository: `O:/java/games/CS2D-MultiplayerUDP`
- Branch: `main`
- Baseline commit: `9da3d61a6b497f9e8c4632b09b6bdc91b2bb2e27`
- Implementation state: changes remain in the working tree and are not committed

## Files changed

- `src/main/java/cs2d/AIControl/team/TacticalTask.java` (+28/-2)
  - Added an explicit `CompletionPolicy` to separate arrival-completed tasks from continuous tasks.
  - Preserved the former constructors so existing coordinators and tests remain source compatible.
  - `ADVANCE`, `FLANK`, and `ASSEMBLE` remain arrival-completed; route control, support, regroup, contact response, and suppression remain active until replaced or expired.
- `src/main/java/cs2d/AIControl/team/TacticalTaskBoard.java` (+4/-8)
  - Replaced task-type inference with the task's explicit completion policy.
  - Removed the behavior that completed `SUPPORT` and `REGROUP` merely because an agent entered the current arrival radius.
- `src/main/java/cs2d/AIControl/team/LowPopulationMobilityFallback.java` (+58/-0)
  - Added a separate locomotion fallback for teams of one to three living independent AIs.
  - Supplies explicit `FREE_PATROL` orders only when no active commander order exists.
  - Releases a solo route controller after reaching its route-control objective.
  - Never overwrites active combat, sound-response, support, or other commander orders.
- `src/main/java/cs2d/server/TeamTacticalRuntime.java` (+5/-1)
  - Inserted the mobility fallback between task reconciliation and formation/posture commitment.
- `src/test/java/cs2d/AIControl/team/TacticalTaskBoardTest.java` (+27/-7)
  - Added regression coverage showing `SUPPORT` and `REGROUP` stay active and adopt a moved objective after first arrival.
- `src/test/java/cs2d/AIControl/team/LowPopulationMobilityFallbackTest.java` (+75/-0)
  - Covers missing-order patrol, existing-order preservation, solo route completion, and the normal-team-size boundary.
- `.agent/changeHistory/2026-09-03-low-population-ai-mobility.md` (+57/-0)
  - Records this change and its validation.

## Behavior changes

- Small teams no longer turn a temporary gap in tactical work into an indefinite ambush.
- A two-agent support/regroup order continues tracking its teammate as the teammate moves.
- Route-less patrol and authored-route execution remain separate from task lifecycle decisions.
- Teams with more than three living independent AIs retain the existing no-order behavior.
- Direct sight and commander-issued sound response continue to take precedence.

## API and compatibility

- `TacticalTask` now exposes `completionPolicy()` and the `CompletionPolicy` enum.
- Existing constructor signatures are retained as compatibility overloads.
- No network schema, persistence format, configuration, dependency, or client change is required.

## Validation

- `mvn -q -Dtest=TacticalTaskBoardTest,LowPopulationMobilityFallbackTest,AdaptiveTeamTacticalCoordinatorTest test` — passed after correcting a test fixture that had constructed `FLANK` instead of `REGROUP`.
- `mvn -q test` — passed twice; final run includes both continuous `SUPPORT` and `REGROUP` regression coverage.
- `git diff --check` — passed. Git emitted only the repository's existing LF-to-CRLF conversion notices.

## Deployment and rollback

- Restart the server process to load the updated AI classes; no client restart or migration is required.
- Roll back the uncommitted implementation by reverting only the files listed above to baseline `9da3d61a6b497f9e8c4632b09b6bdc91b2bb2e27` and removing the two new Java files and this record.
- Recommended manual check: run a large authored-route map with one, two, and three AIs per team, wait for route arrival, then verify they resume patrol and that a support AI follows again after its anchor moves.
