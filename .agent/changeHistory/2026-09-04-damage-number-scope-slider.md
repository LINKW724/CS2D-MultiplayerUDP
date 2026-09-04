# Damage-number scope slider

- Date: 2026-09-04
- Objective: replace overlapping damage-number checkboxes with one mutually exclusive four-level source selector.
- Repository: `O:/java/games/CS2D-MultiplayerUDP_Client`
- Branch: `main`
- Pre-change baseline: `40986b851e4933097838e35e11aae276bf10421a`
- Implementation state: committed in the next-task baseline snapshot.
- Preserved user state: the pre-existing runtime preferences in `cs2d_settings.json` remain untouched and excluded from implementation files.

## Files changed

- `src/main/java/cs2d/client/DamageNumberVisibility.java` (+49 / -0): defines `OFF`, `OWN`, `TEAMMATES`, and `ALL`, including source predicates and discrete slider metadata.
- `src/main/java/cs2d/client/DamageNumberModePolicy.java` (+70 / -55): replaces two coupled Boolean maps with a single per-mode visibility map and migrates the previous array schema.
- `src/main/java/cs2d/client/GameClient.java` (+63 / -30): applies source-specific event filtering and replaces two checkboxes with a snapped four-position slider and current-value explanation.
- `src/main/java/cs2d/client/GameSettings.java` (+10 / -14): exposes the unified per-mode visibility API.
- `src/test/java/cs2d/client/DamageNumberModePolicyTest.java` (+50 / -19): verifies all four scopes, future-mode persistence, level clamping, and legacy migration.
- `src/test/java/cs2d/client/GameSettingsTest.java` (+12 / -14): verifies the new nested schema and restoration.
- `.agent/changeHistory/2026-09-04-damage-number-scope-slider.md`: records this change.

## Behavior, schema, and migration

- Esc Settings now shows one `Zombie Damage Number Scope` slider with four exclusive stops: `Off`, `Mine`, `Team`, and `All`.
- `Mine` shows only the local player or controlled bot, `Team` shows only other survivors, and `All` combines both sources.
- `All` does not include zombies damaging survivors; the server's existing Zombie Mode survivor-versus-zombie audience boundary remains unchanged.
- Changing the scope clears existing floating numbers so feedback from the previous scope does not linger.
- Settings now serialize as `damageNumbers.visibilityByMode.<mode> = OFF|OWN|TEAMMATES|ALL`.
- Previous `enabledModes` plus `teammateDamageModes` settings migrate automatically: disabled becomes `OFF`, enabled without teammates becomes `OWN`, and enabled with teammates becomes `ALL`.
- Other game modes still default to `OFF` and can adopt the same four-level policy later without changing the renderer.
- No server, protocol, dependency, damage calculation, or rendering-style change was introduced.

## Validation

- Focused damage-number and protocol suite: passed, 32 tests.
- Focused settings recheck after defensive null handling: passed, 7 tests.
- `mvn test`: passed, 69 tests, 0 failures/errors/skips.
- Impeccable detector: passed with no findings.
- `git diff --check`: passed; Git reported only LF-to-CRLF conversion warnings.

## Manual verification and rollback

- Restart the client, open Esc Settings, and move the slider across all four stops. Confirm it snaps to each named stop and the `Current` explanation matches.
- In Zombie Mode, verify `Mine`, `Team`, and `All` show exactly their named sources while headshots remain red.
- Restart once more to verify the selected scope persists and a previous settings file migrates without manual edits.
- No migration action is required. Roll back the listed implementation files to baseline `40986b851e4933097838e35e11aae276bf10421a` while preserving the separate `cs2d_settings.json` preferences.
