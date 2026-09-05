# Zombie flow forecasting and defense coordination

- Date: 2026-09-04
- Objective: make idle survivor AI forecast aggregated zombie approach routes, reinforce active fronts, establish defense lines before contact, and retain a mobile reserve without overriding immediate survival or reload-cover duties.
- Repository: `O:/java/games/CS2D-MultiplayerUDP`
- Branch: `main`
- Baseline commit: `c690411f3f10bb91504809a1d58b8cbaef9e0a1a`
- Implementation state: changes remain uncommitted in the worktree.

## Files

- `src/main/java/cs2d/AIControl/zombie/ZombieAttackLane.java` (+15/-0): added the immutable aggregate route, pressure, ETA, interception point, and watch direction model.
- `src/main/java/cs2d/AIControl/zombie/ZombieDefenseLinePlanner.java` (+49/-0): selects a walkable, hazard-free choke point from the middle of a predicted route using bounded perpendicular probes.
- `src/main/java/cs2d/AIControl/zombie/ZombieFlowForecaster.java` (+62/-0): groups zombies and survivors into spatial cells, requests at most eight aggregate routes, estimates pressure and arrival time, and emits stable lane identities.
- `src/main/java/cs2d/AIControl/zombie/ZombieDefenseAllocator.java` (+53/-0): converts lane urgency into bounded fortify/reinforce assignments and preserves one mobile reserve for squads of four or more.
- `src/main/java/cs2d/server/ZombieTacticalRuntime.java` (+33/-3): runs route forecasting every 750 ms on a dedicated pathfinder, supplies lanes to the commander, and clears stale forecasts during cleanup or reset.
- `src/main/java/cs2d/AIControl/zombie/ZombieTacticalSnapshot.java` (+8/-3): carries predicted attack lanes while retaining compatibility constructors.
- `src/main/java/cs2d/AIControl/zombie/ZombieTacticalOrder.java` (+8/-2): adds fortify, reinforce, and reserve tasks plus an explicit watch point.
- `src/main/java/cs2d/AIControl/zombie/ZombieTacticalTaskBoard.java` (+6/-1): preserves watch points in orders through a compatibility overload.
- `src/main/java/cs2d/AIControl/zombie/ZombieTacticalCoordinator.java` (+21/-4): integrates defense allocation with priority ordered as imminent danger, cleanup hunt, nearby reload cover, predicted deployment, local clearing, then guard/return.
- `src/main/java/cs2d/AIControl/BG/ZOMBIEcontrol.java` (+6/-1): faces the predicted upstream watch point while a deployed survivor is stationary and has no visible target.
- `src/test/java/cs2d/AIControl/zombie/ZombieDefenseLinePlannerTest.java` (+31/-0): covers choke selection and active-fire rejection.
- `src/test/java/cs2d/AIControl/zombie/ZombieFlowForecasterTest.java` (+37/-0): covers route aggregation and empty-survivor behavior.
- `src/test/java/cs2d/AIControl/zombie/ZombieDefenseAllocatorTest.java` (+47/-0): covers pressure-based staffing, reserve retention, fortification, and the four-agent per-lane cap.
- `src/test/java/cs2d/AIControl/zombie/ZombieTacticalCoordinatorTest.java` (+16/-0): verifies coordinated reinforcement, mobile reserve assignment, and upstream watch propagation.

## Behavior and APIs

- Forecasting uses authoritative live zombie positions only to predict movement flow; ordinary firing and local threat decisions still use the existing perception/intel path.
- Nearby zombies share one route forecast per 360-unit source cell. Survivors share target sectors per 480-unit cell, avoiding per-zombie/per-tick pathfinding.
- Forecasting runs at approximately 1.3 Hz, is capped at eight lanes, and uses a dedicated pathfinder so it cannot overwrite an individual AI's active navigation state.
- Defense points prefer narrow, walkable route segments between 35% and 82% route progress and reject active fire or pending explosion areas.
- Lane staffing is pressure-based, capped at four agents per lane. Squads with at least four ready members keep one mobile reserve near the weighted defense center.
- Imminent threats, final-zombie cleanup, and nearby reload cover override deployment orders. Moving defenders can still engage visible enemies through the existing independent combat layer.
- No dependency, configuration, persistence schema, or client protocol changes.

## Validation

- `mvn -q '-Dtest=ZombieDefenseLinePlannerTest,ZombieFlowForecasterTest,ZombieDefenseAllocatorTest,ZombieTacticalCoordinatorTest,ZombieTacticalTaskBoardTest' test`: passed.
- `mvn -q package`: passed; 201 tests, 0 failures, 0 errors, 0 skipped; rebuilt `target/cs2d-server.jar`.
- `git diff --check`: passed. Git only reported the repository's existing LF-to-CRLF checkout warning.

## Notes

- Live-game verification still requires restarting the server and observing a split survivor formation while one side receives a wave.
- The forecast is deliberately strategic rather than omniscient combat targeting: it predicts approach lanes but does not grant through-wall shooting information.
- Roll back the uncommitted implementation to baseline `c690411f3f10bb91504809a1d58b8cbaef9e0a1a` without resetting unrelated later work.
