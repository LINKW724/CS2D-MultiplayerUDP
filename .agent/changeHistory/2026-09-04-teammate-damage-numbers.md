# Teammate damage numbers

- Date: 2026-09-04
- Objective: add an optional, per-mode teammate source to the existing damage-number feedback system.
- Repository: `O:/java/games/CS2D-MultiplayerUDP_Client`
- Branch: `main`
- Pre-change baseline: `b38ddd3a12c1e8e7b351a21b540afff864d0882a`
- Implementation state: committed in the next-task baseline snapshot.
- Preserved user state: the pre-existing runtime preferences in `cs2d_settings.json` remain untouched and excluded from implementation files.

## Files changed

- `src/main/java/cs2d/client/GameClient.java` (+72 / -21): routes team-audience damage events separately from private logs and statistics, recognizes a controlled bot's team, and adds the dependent Esc checkbox.
- `src/main/java/cs2d/client/GameSettings.java` (+12 / -0): exposes per-mode teammate damage-number settings.
- `src/main/java/cs2d/client/DamageNumberEvent.java` (+3 / -0): records attacker identity and whether an event came from a teammate.
- `src/main/java/cs2d/client/DamageNumberModePolicy.java` (+45 / -4): persists `teammateDamageModes` independently from the main mode enablement and defaults it off.
- `src/main/java/cs2d/client/DamageNumberSystem.java` (+11 / -2): prevents hits by different attackers from merging and can clear teammate effects without clearing personal effects.
- `src/test/java/cs2d/client/DamageNumberModePolicyTest.java` (+8 / -0): verifies defaults, future-mode round trips, and old-setting fallback.
- `src/test/java/cs2d/client/DamageNumberSystemTest.java` (+21 / -4): verifies attacker separation and selective teammate clearing.
- `src/test/java/cs2d/client/GameSettingsTest.java` (+6 / -0): verifies nested teammate-mode persistence and restoration.
- `.agent/changeHistory/2026-09-04-teammate-damage-numbers.md`: records this change.

## Behavior and architecture

- Esc Settings now contains `Include Teammate Damage` beneath `Zombie Mode Damage Numbers`; it defaults off and is disabled whenever the parent option is off.
- Teammate numbers use the same white body-hit and red headshot presentation as personal numbers.
- Team feedback is accepted only when the server's audience matches the local player or controlled bot team. The attacker must not be the local combat actor.
- Teammate events never enter `currentDamageLogEntries` and never update the local player's damage or hit statistics.
- Same-target hits merge only when attacker and headshot type also match, keeping simultaneous teammates' output distinct.
- Turning off only the teammate option removes existing teammate numbers while preserving personal numbers.
- The JSON schema remains extensible: `damageNumbers` now contains independent `enabledModes` and `teammateDamageModes` arrays. Older settings without the new array default teammate feedback to off.

## Validation

- Focused client suite: passed, 31 tests.
- `mvn test`: passed, 68 tests, 0 failures/errors/skips.
- Impeccable detector: passed with no findings.
- `git diff --check`: passed; Git reported only LF-to-CRLF conversion warnings.

## Manual verification and rollback

- Restart both applications. In Zombie Mode, toggle `Include Teammate Damage` and confirm teammate body/headshot numbers appear once without changing the local scoreboard totals.
- Confirm personal damage remains visible when the teammate option is turned off, and both kinds clear when the parent option is disabled.
- Confirm controlled-bot viewing follows the controlled bot's team and old settings load with teammate feedback disabled.
- No migration is required. Roll back implementation files to baseline `b38ddd3a12c1e8e7b351a21b540afff864d0882a` while preserving the separate `cs2d_settings.json` preferences.
