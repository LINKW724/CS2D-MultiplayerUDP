# Detached spectator damage feedback

- Date: 2026-09-04
- Objective: make Zombie Mode `All` damage-number scope work for the no-faction spectator selected from the team-selection screen.
- Repository: `O:/java/games/CS2D-MultiplayerUDP_Client`
- Branch: `main`
- Pre-change baseline: `dfb21f219f99360e7bf8cd9bedd0ebf5939f66f8`
- Implementation state: committed in the next-task baseline snapshot.
- Preserved user state: the pre-existing runtime preferences in `cs2d_settings.json` remain untouched and excluded from implementation files.

## Files changed

- `src/main/java/cs2d/client/GameClient.java` (+11 / -4): identifies the team-selection screen's detached spectator state and delegates team-event admission to a dedicated policy.
- `src/main/java/cs2d/client/DamageFeedbackAudiencePolicy.java` (+25 / -0): defines viewer-aware admission for normal players, controlled actors, team spectators, and detached spectators.
- `src/test/java/cs2d/client/DamageFeedbackAudiencePolicyTest.java` (+39 / -0): verifies detached `All`, detached `Team`, mode isolation, team matching, and local-attacker rejection.
- `.agent/changeHistory/2026-09-04-detached-spectator-damage-feedback.md`: records this change.

## Behavior and safety boundaries

- Selecting `Spectate` from team selection clears `myPlayerId` and `me`; this state is now explicitly recognized while the client is playing.
- A detached spectator with Zombie Mode scope `All` accepts all server-authorized survivor-versus-zombie feedback events, even without a local team.
- Detached `Mine` and `Team` remain empty because that spectator owns neither an actor nor a faction.
- Ordinary players and dead team spectators still require an exact `feedbackTeam` match.
- Local-player events are not reclassified as teammate events, preventing duplicate numbers.
- Detached global admission is restricted to `ZOMBIE_MODE`; future PvP modes do not inherit global damage visibility.
- No server, protocol, setting-schema, dependency, renderer, or damage-calculation change was introduced.

## Validation

- Focused audience, mode, and protocol suite: passed, 27 tests.
- `mvn test`: passed, 71 tests, 0 failures/errors/skips.
- `git diff --check`: passed; Git reported only LF-to-CRLF conversion warnings.

## Manual verification and rollback

- Restart the client, enter Zombie Mode, choose `Spectate`, set damage-number scope to `All`, and confirm survivor body/headshot damage appears in Free, Full, and Follow views.
- Confirm `Mine` and `Team` show nothing for the no-faction spectator, while a dead survivor using team spectate still sees matching team feedback.
- No migration is required. Roll back the listed implementation files to baseline `dfb21f219f99360e7bf8cd9bedd0ebf5939f66f8` while preserving `cs2d_settings.json`.
