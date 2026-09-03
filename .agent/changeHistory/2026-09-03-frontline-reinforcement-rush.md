# Frontline reinforcement rush

## Objective

Allow rear TDM agents to quickly reinforce a friendly-held frontline without
turning every normal advance into a noisy rush or reintroducing posture jitter.

## Repository baseline

- Date: 2026-09-03
- Repository: `O:/java/games/CS2D-MultiplayerUDP`
- Branch: `main`
- Baseline commit: `e80a751e9ce801bccf4826d84f65ec4df40fce9b`
- Baseline message: `chore: snapshot before frontline reinforcement rush`
- The working tree was clean immediately after the baseline commit.

## Added behavior

- Added a standalone team-plan refiner for frontline reinforcement.
- Agents compare route progress instead of straight-line distance, so curved
  authored routes still identify the real front and rear correctly.
- A rear agent starts rushing when a same-route friendly is at least 520 world
  units ahead.
- Once rushing, it continues until the gap falls to 280 units, creating
  hysteresis and preventing run/walk toggling near a single threshold.
- The agent returns to quiet movement before reaching the frontline.
- Anchors never abandon their assignment to perform a reinforcement rush.
- Recently damaged agents and agents with a contact within 360 units do not
  blindly rush.
- Direct local sight remains owned by the existing combat execution policy.

## Architecture

- The new rule implements `TacticalPlanRefiner` and is injected into the default
  TDM coordinator after the existing elastic maneuver refiner.
- No pathfinding, movement arbitration, collision, weapon or perception module
  was modified.
- Existing full-order commitment remains responsible for command stability.

## Replaced behavior

- Previously only an explicit respawn wave could request `RUSH`; all ordinary
  rear reinforcements stayed in `STEALTH_ADVANCE` regardless of route spacing.
- The new policy keeps normal movement quiet while adding a separate,
  evidence-based safe-corridor rush case.

## Validation

- Focused Maven tests: 18 passed, 0 failed.
- Full Maven suite: 125 passed, 0 failed, 0 skipped.
- Main and test sources compile successfully.
- `git diff --check` reports no whitespace errors; Windows only reports the
  repository's existing LF-to-CRLF conversion warning.

## Per-file line changes

- `src/main/java/cs2d/AIControl/team/AdaptiveTeamTacticalCoordinator.java`: +2 / -1
- `src/main/java/cs2d/AIControl/team/FrontlineReinforcementRefiner.java`: +150 / -0
- `src/test/java/cs2d/AIControl/team/FrontlineReinforcementRefinerTest.java`: +90 / -0
- `.agent/changeHistory/2026-09-03-frontline-reinforcement-rush.md`: +71 / -0

## Operational notes

- The implementation remains uncommitted for gameplay verification.
- Restart the server before testing so the coordinator and its hysteresis state
  are recreated.
- Manual check: place two or more bots on the same authored route. A rear bot
  more than 520 units behind should run to close the safe gap, then resume
  walking near the frontline. Nearby contact or recent damage should suppress
  that rush.
- Rollback to the baseline commit if the behavior is not desired.
