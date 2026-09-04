# Unified adjustable fog darkness

- Date: 2026-09-04
- Objective: use one fog darkness across Follow, Full, and Free camera views and expose it in the Esc settings panel.
- Repository: `O:/java/games/CS2D-MultiplayerUDP_Client`
- Branch: `main`
- Pre-change baseline: `f320d3d61c759b23aabdd5d0e2c36b13c3f5eb86`
- Implementation state: remains uncommitted in the working tree.
- Preserved user state: the pre-existing `cs2d_settings.json` preference change remains untouched and is not part of this implementation.

## Files changed

- `src/main/java/cs2d/client/GameClient.java` (+49 / -3): replaces camera-mode-specific fog colors with one settings-driven color, adds the Esc-panel darkness slider and value label, and caches the resolved color for the render hot path.
- `src/main/java/cs2d/client/GameSettings.java` (+27 / -0): adds bounded fog-darkness state, defaults, property access, and JSON persistence.
- `src/test/java/cs2d/client/GameSettingsTest.java` (+37 / -0): covers bounds, invalid numeric input, backward compatibility, loading, and saving.
- `.agent/changeHistory/2026-09-04-unified-adjustable-fog-darkness.md` (+36 / -0): records this change.

## Behavior and architecture

- Follow, Full, and Free camera views now resolve the same fog color and opacity; switching views no longer changes darkness.
- Esc Settings includes a `Fog Darkness` slider from 0% to 100%, with 5% keyboard increments and a live percentage readout.
- Slider changes update the active fog mask immediately. Dragging saves once on release, while clicks and keyboard changes save immediately.
- New and legacy configurations use a 70% default when `fogDarkness` is absent.
- Saved settings add the backward-compatible JSON field `fogDarkness`; no protocol, server, dependency, or asset changes were made.

## Validation

- `mvn test`: passed, 61 tests, 0 failures/errors/skips.
- `node C:/Users/小麦/.codex/skills/impeccable/scripts/detect.mjs --json src/main/java/cs2d/client/GameClient.java`: passed with no findings.
- `git diff --check`: passed; Git reported only existing LF-to-CRLF conversion warnings.

## Manual verification and rollback

- Restart the client, join a match, open Esc Settings, move `Fog Darkness`, then cycle Follow, Full, and Free views. The percentage and visual darkness should remain identical across modes and persist after another restart.
- No migration is required; old settings files receive the default until the client next saves settings.
- To roll back implementation behavior, restore the three source/test files to baseline `f320d3d61c759b23aabdd5d0e2c36b13c3f5eb86` while preserving the separate `cs2d_settings.json` preference change.
