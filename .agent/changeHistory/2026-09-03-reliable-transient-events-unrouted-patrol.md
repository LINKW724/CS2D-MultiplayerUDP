# Reliable transient events and unrouted AI patrol

- Date: 2026-09-03
- Objective: preserve one-shot events across latest-wins network coalescing and restore intentional AI movement on maps without authored routes.
- Repository: `O:/java/games/CS2D-MultiplayerUDP`
- Branch: `main`
- Pre-change baseline: `0e8ef33` (`chore: snapshot before reliable events and unrouted patrol`)
- Implementation state: changes remain in the working tree and are not committed.

## Files changed

- `src/main/java/cs2d/server/TransientEventJournal.java` (+78 / -0): adds a bounded 500 ms journal that assigns monotonic `eventId` values and replays sound, private sound, damage, flash, and footstep-reveal events across snapshots.
- `src/main/java/cs2d/server/BroadcastAccumulator.java` (+94 / -0): adds a one-slot latest-state accumulator that merges unsent transient events by id while preserving forced full snapshots.
- `src/main/java/cs2d/server/NetworkBroadcaster.java` (+12 / -20): replaces the raw latest-wins atomic mailbox with the accumulator and attaches the journal replay window before enqueueing snapshots.
- `src/main/java/cs2d/AIControl/team/TacticalOrder.java` (+43 / -7): adds `LocomotionDirective` with authored-route, target, free-patrol, and hold modes while retaining compatibility constructors.
- `src/main/java/cs2d/AIControl/team/TacticalIdlePolicy.java` (+6 / -1): decides activity from the explicit locomotion directive rather than target nullability alone.
- `src/main/java/cs2d/AIControl/movement/PatrolTargetProvider.java` (+14 / -0): defines the geometry-independent patrol objective boundary.
- `src/main/java/cs2d/AIControl/movement/WalkablePatrolTargetProvider.java` (+45 / -0): supplies deterministic, per-agent, bounded and walkability-checked patrol objectives.
- `src/main/java/cs2d/AIControl/BG/TEAM_DEATHMATCHcontrol.java` (+9 / -30): delegates fallback patrol target selection to the provider and removes embedded random/unwalkable fallback logic.
- `src/test/java/cs2d/server/TransientEventJournalTest.java` (+72 / -0): covers stable replay ids, expiry, capacity, and distinct ids.
- `src/test/java/cs2d/server/BroadcastAccumulatorTest.java` (+59 / -0): covers event-preserving state replacement, forced-full preservation, ordering, and deduplication.
- `src/test/java/cs2d/AIControl/movement/WalkablePatrolTargetProviderTest.java` (+40 / -0): covers walkability, minimum distance, per-agent dispersion, and safe failure.
- `src/test/java/cs2d/AIControl/team/TacticalIdlePolicyTest.java` (+9 / -0): covers explicit free patrol on unrouted maps.
- `src/test/java/cs2d/AIControl/team/AdaptiveTeamTacticalCoordinatorTest.java` (+12 / -0): verifies an empty route catalog produces `FREE_PATROL` rather than an implicit hold.

## Behavior and compatibility

- Continuous world state remains latest-wins; slow network output still cannot build a historical state backlog.
- One-shot events survive pending-snapshot replacement and are repeated briefly to tolerate UDP snapshot loss.
- The JSON event schema gains an optional numeric `eventId`; legacy clients ignore the extra property. The protocol version is unchanged.
- Visual effects remain on their existing stateful lifecycle and are not journaled as one-shot events.
- Maps with authored routes retain route advance and post-task ambush behavior.
- Maps without authored routes issue explicit free-patrol orders and choose only walkable A* targets.
- A failed patrol search returns no target rather than sending an AI toward an unwalkable random point.

## Validation

- `mvn -q -Dtest=TransientEventJournalTest,BroadcastAccumulatorTest,NetworkProtocolTest,TacticalIdlePolicyTest,WalkablePatrolTargetProviderTest,AdaptiveTeamTacticalCoordinatorTest test`: passed.
- `mvn -q test`: passed, 147 tests, 0 failures, 0 errors, 0 skipped.
- `mvn -q -DskipTests package`: passed.
- `git diff --check`: passed; Git printed only existing LF-to-CRLF advisories.

## Restart and rollback

- Restart the server to load the new event journal, accumulator, and AI movement classes.
- No data or configuration migration is required.
- Roll back implementation changes to baseline `0e8ef33`; do not reset or discard unrelated work.
- Manual live verification should cover a high-player-count firefight and a TDM map with no authored route catalog.
