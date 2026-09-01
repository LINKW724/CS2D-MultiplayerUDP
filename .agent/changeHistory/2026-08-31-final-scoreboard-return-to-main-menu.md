# Final scoreboard return to main menu

- Date: 2026-08-31 22:17 Asia/Shanghai
- Objective: make the final-scoreboard exit button return directly to the client main menu.
- Repository: `O:/java/games/CS2D-MultiplayerUDP_Client`
- Branch: `main`
- Baseline commit: `d29d6395155736a5c42936022355818d427c076a`
- Baseline message: `fix: publish smoke state atomically`

## Summary

Changed the final scoreboard button from a UI-only transition to the connected lobby into the client's complete disconnect-and-reset flow. The old behavior left the UDP session and `isGameOver` state active, so a later server packet could immediately force the client back to the final scoreboard. The button now closes the finished session, clears match state and media, and displays the IP-entry main menu. Its label now reads `Back to Main Menu` to match the destination.

## File changes

- `src/main/java/cs2d/client/GameClient.java` — added 4 lines, removed 2. Replaced `setClientState(LOBBY)` with `disconnect()` for the final scoreboard button and updated its label and rationale comment.

## Validation

- `mvn test` — passed: 41 tests, 0 failures, 0 errors.
- `git diff --check -- . ":(exclude)cs2d_settings.json"` — passed; Git only reported the repository's existing LF-to-CRLF checkout warning.

## Notes

- The user-owned runtime file `cs2d_settings.json` remains modified and was not changed, staged, or included in this work.
- Restart the JavaFX client to load the updated button behavior.
- The implementation remains uncommitted after the baseline snapshot for manual verification.
- Rollback point: `d29d6395155736a5c42936022355818d427c076a`.
