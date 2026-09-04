# Team damage-feedback audience

- Date: 2026-09-04
- Objective: expose survivor-versus-zombie damage events to eligible teammates without duplicating private damage logs or widening feedback to other modes.
- Repository: `O:/java/games/CS2D-MultiplayerUDP`
- Branch: `main`
- Pre-change baseline: `0fdcf66797fbeeb743fad0333c74eec53a91fd70`
- Implementation state: committed in the next-task baseline snapshot.

## Files changed

- `src/main/java/cs2d/server/GameState.java` (+17 / -3): attaches an optional `feedbackTeam` audience to the attacker's existing event wrapper only when a survivor damages a zombie in Zombie Mode.
- `src/test/java/cs2d/server/DamageFeedbackAudienceTest.java` (+25 / -0): verifies survivor teams are shared and zombie attacks, friendly victims, and other modes are excluded.
- `.agent/changeHistory/2026-09-04-team-damage-feedback.md`: records this change.

## Behavior and compatibility

- The authoritative `damage_event` payload is unchanged; `feedbackTeam` is optional wrapper metadata.
- No second damage event is created, so direct recipients and teammates cannot receive duplicate feedback from parallel wrappers.
- Victim-targeted wrappers remain private and never carry a team audience.
- Team sharing is currently restricted to Zombie Mode survivor damage against zombies. Other modes remain closed until their own visibility policy is defined.
- Older clients ignore the optional wrapper field. No protocol-version bump, dependency, damage calculation, or game-balance change was introduced.

## Validation

- Focused server suite: passed, 2 tests.
- `mvn test`: passed, 216 tests, 0 failures/errors/skips.
- `git diff --check`: passed; Git reported only LF-to-CRLF conversion warnings.

## Manual verification and rollback

- Restart the server and clients. Enable teammate damage numbers on one survivor client, damage a zombie from another survivor, and confirm the observer receives one number while zombies receive none.
- Disable the client option and confirm network events no longer create teammate feedback.
- No migration is required. Roll back the listed implementation files to baseline `0fdcf66797fbeeb743fad0333c74eec53a91fd70` if team feedback must be removed.
