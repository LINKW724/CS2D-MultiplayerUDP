# AI latest-only scheduler and backpressure

- Date: 2026-09-03
- Objective: prevent high-population AI decision work from accumulating stale frames and stop concurrent updates of one AI controller.
- Repository: `O:/java/games/CS2D-MultiplayerUDP`
- Branch: `main`
- Pre-change baseline: `45cba6a7a7efea72f3c442084af95409e8999db1`
- Implementation state: changes remain in the working tree and are not committed.

## Files changed

### `src/main/java/cs2d/server/AIService.java` (+42 / -14)

- Replaced the unbounded fixed executor submission path with the latest-only per-AI scheduler.
- Cancels pending decisions when the game freezes and removes pending work for dead, removed, or player-controlled AIs.
- Rechecks AI ownership and life state before calculation and before publishing an input.
- Captures an immutable sound-event snapshot for every decision frame instead of reading a list that the next AI tick can mutate concurrently.
- Adds a five-second AI scheduler summary with submitted, executed, coalesced, rejected, queue wait, and execution timing metrics.
- Names the AI ticker thread for easier JFR and thread-dump diagnosis.

### `src/main/java/cs2d/server/LatestOnlyAiScheduler.java` (+186 / -0)

- Adds bounded ready-queue backpressure.
- Allows at most one executing decision and one latest pending decision per AI.
- Replaces obsolete pending frames instead of replaying an unbounded historical queue.
- Serializes each AI controller while retaining parallelism between different AIs.
- Keeps worker threads alive across unexpected task failures and exposes resettable runtime statistics.

### `src/test/java/cs2d/server/LatestOnlyAiSchedulerTest.java` (+90 / -0)

- Verifies that one AI is never calculated concurrently.
- Verifies that intermediate frames are coalesced and the newest pending frame executes.
- Verifies that pending work is removed when an AI becomes inactive.

## Behavior changes

- Server physics and AI ticker targets remain at 60 Hz.
- Under AI overload, obsolete AI world frames are now coalesced instead of accumulating for seconds.
- Different AIs can still think in parallel, but mutable controller state for one AI is updated serially.
- The server now reports the actual hidden AI scheduling pressure that was absent from the main game-loop metrics.

## Configuration

- `cs2d.ai.readyQueueCapacity`: bounded ready queue size; defaults to `max(256, threadCount * 32)`.
- `cs2d.ai.schedulerLogMs`: scheduler summary interval; defaults to 5000 ms and has a minimum of 1000 ms.
- No dependencies, protocol schemas, or network packet formats changed.

## Validation

- `mvn -Dtest=LatestOnlyAiSchedulerTest test`: passed, 2 tests.
- `mvn test`: passed, 105 tests, 0 failures, 0 errors, 0 skipped.
- `git diff --check`: passed; Git only reported the repository's existing LF-to-CRLF conversion warning.

## Manual verification

- Restart the server and repeat the large-map approximately 200-AI scenario.
- Inspect `[AI调度/5.0s]` lines. `合并旧帧` may be high under load and is expected; `队列拒绝` should normally remain zero.
- Healthy behavior is bounded `排队最大`, continued gunfire, and no continuously increasing decision delay.
- If average calculation time remains high after eliminating stale work, the next safe optimization is spatially narrowing broad perception while keeping already-engaged combat checks on the fast path.

## Rollback

- The exact pre-change recovery point is commit `45cba6a7a7efea72f3c442084af95409e8999db1`.
- No migration or data conversion is required.
