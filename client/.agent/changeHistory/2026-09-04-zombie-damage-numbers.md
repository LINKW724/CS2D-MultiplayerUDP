# Zombie-mode damage numbers

- Date: 2026-09-04
- Objective: add extensible per-mode damage feedback with Canvas-rendered zombie-mode floating numbers and an Esc setting.
- Repository: `O:/java/games/CS2D-MultiplayerUDP_Client`
- Branch: `main`
- Pre-change baseline: `3660a49febeda28fdcd9af90b04f4eda0aa44247`
- Implementation state: remains uncommitted in the working tree.
- Preserved user state: the pre-existing runtime preferences in `cs2d_settings.json` remain untouched and excluded from implementation files.

## Files changed

- `src/main/java/cs2d/client/GameClient.java` (+83 / -0): ingests deduplicated outgoing damage events, recognizes a controlled bot, resolves authoritative or legacy fallback positions, draws after fog and before the crosshair, adds the Esc control, and clears effects on lifecycle boundaries.
- `src/main/java/cs2d/client/GameSettings.java` (+17 / -0): persists the nested, extensible `damageNumbers.enabledModes` configuration.
- `src/main/java/cs2d/client/DamageNumberEvent.java` (+23 / -0): defines validated immutable damage feedback input.
- `src/main/java/cs2d/client/DamageNumberModePolicy.java` (+72 / -0): owns per-mode enablement; zombie mode defaults on and future mode names are preserved.
- `src/main/java/cs2d/client/DamageNumberSystem.java` (+163 / -0): provides bounded same-tick merging, lifecycle, screen culling, upward motion, outline, and Canvas rendering.
- `src/test/java/cs2d/client/GameClientProtocolTest.java` (+8 / -0): verifies local-player and controlled-bot attacker recognition.
- `src/test/java/cs2d/client/GameSettingsTest.java` (+25 / -0): verifies nested mode configuration persistence and restoration.
- `src/test/java/cs2d/client/DamageNumberModePolicyTest.java` (+34 / -0): verifies zombie-only defaults, explicit disabling, and future-mode round trips.
- `src/test/java/cs2d/client/DamageNumberSystemTest.java` (+36 / -0): verifies exact white/red colors, same-tick merging, separation by headshot type, capacity, and expiry.
- `.agent/changeHistory/2026-09-04-zombie-damage-numbers.md` (+46 / -0): records this change.

## Behavior and architecture

- Esc Settings now contains `Combat Feedback` with `Zombie Mode Damage Numbers`, enabled by default.
- Only positive outgoing damage from the local player or currently controlled bot creates feedback; teammate output and incoming damage do not.
- Normal damage is white, headshot damage is red, both use a dark outline, fixed screen-space size, subtle lane staggering, 720 ms upward motion, and fade-out.
- Same-target hits of the same type within 40 ms merge; headshots never merge into body hits. Active effects are capped at 64 and offscreen effects are not drawn.
- Rendering uses the existing HUD Canvas after fog and before the crosshair, avoiding per-hit JavaFX nodes and preserving consistent sizing in Follow, Full, and Free views.
- Repeated UDP snapshots stay behind the existing transient-event deduplicator. New-server hit coordinates survive target removal; old-server payloads fall back to a currently available target position.
- The JSON schema adds `damageNumbers: { enabledModes: [...] }`; other modes remain disabled but can be enabled later without changing the renderer.

## Validation

- Focused client suite: passed, 8 tests.
- `mvn test`: passed, 67 tests, 0 failures/errors/skips.
- `mvn -Dtest=DamageNumberSystemTest test`: passed after exact color assertions, 2 tests.
- Impeccable detector on `GameClient.java` and `DamageNumberSystem.java`: passed with no findings.
- `git diff --check`: passed; Git reported only LF-to-CRLF conversion warnings.

## Manual verification and rollback

- Restart both applications. In Zombie Mode, confirm white body-hit numbers, red headshot numbers, readable machine-gun stacking, final-hit feedback after zombie removal, and immediate clearing when the Esc option is disabled.
- Cycle Follow, Full, and Free views to confirm positions track the camera while text size stays constant; restart the client to confirm persistence.
- No migration is required. Roll back implementation files to baseline `3660a49febeda28fdcd9af90b04f4eda0aa44247` while preserving the separate `cs2d_settings.json` preferences.
