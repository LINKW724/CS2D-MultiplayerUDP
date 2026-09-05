# Kill-feed low-limit event deduplication

- Date: 2026-09-03
- Objective: stop replayed server kill-feed entries from reappearing and reordering when the visible limit is 0–3.
- Repository: `O:/java/games/CS2D-MultiplayerUDP_Client`
- Branch: `main`
- Pre-change baseline: `c2d986d3f527c4e11dc1cb7e5e2b017338920ef4`
- Implementation state: changes remain in the working tree and are not committed.
- Preserved user state: `cs2d_settings.json` remains a separate pre-existing user change and was not edited.

## Files changed

- `src/main/java/cs2d/client/BoundedEventDeduplicator.java` (+37 / -0): adds a reusable, synchronized, fixed-capacity first-observation tracker for arbitrary event identifiers.
- `src/main/java/cs2d/client/EventDeduplicator.java` (+3 / -15): delegates numeric transient-event tracking to the shared bounded implementation while preserving the negative-ID compatibility rule.
- `src/main/java/cs2d/client/GameClient.java` (+10 / -10): makes kill-feed display and local-stat processing share one first-observation decision, so trimmed entries cannot be recreated by later snapshots; saves slider changes immediately.
- `src/test/java/cs2d/client/BoundedEventDeduplicatorTest.java` (+29 / -0): verifies duplicate suppression and oldest-entry eviction.
- `.agent/changeHistory/2026-09-03-kill-feed-low-limit-dedup.md` (+40 / -0): records this change.

## Behavior and compatibility

- Limits 0, 1, 2, and 3 remain valid and now show only genuinely new kills.
- The server's four-entry replay window no longer causes removed rows to flash back, reorder, or replay their fade-in animation.
- Kill statistics and visual rows consume the same deduplication decision, preventing the two paths from drifting.
- Deduplication memory is bounded at 4096 identifiers and is cleared by the existing connection/session reset paths.
- The slider value is persisted when changed rather than relying only on application shutdown.
- No protocol, settings schema, dependencies, fonts, colors, or general HUD layout changed.

## Validation

- `mvn -Dtest=BoundedEventDeduplicatorTest,EventDeduplicatorTest,GameSettingsTest test`: passed, 5 tests.
- `mvn test`: passed, 59 tests, 0 failures/errors/skips.
- `mvn -DskipTests package`: passed, including Windows `jpackage`; existing JavaFX parent-project and assembly artifact-replacement warnings remain.
- Impeccable UI detector on `GameClient.java`: passed with no findings.
- `git diff --check`: passed; Git reported only the repository's LF-to-CRLF conversion warnings.

## Manual verification and rollback

- Fully restart the client, set the kill-feed limit to 0, 1, 2, and 3, and generate more than four kills. Each kill should appear at most once and the chosen limit should survive another restart.
- No migration is required.
- Roll back code to `c2d986d3f527c4e11dc1cb7e5e2b017338920ef4`; preserve the separate `cs2d_settings.json` user change.
