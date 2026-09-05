# Kill-feed Marlin safepoint stall

## Request

Eliminate the remaining client freeze that recurs every several seconds without reducing render frequency, FOV precision, ray count, or visual quality.

## Baseline

- Pre-change snapshot: `3a2b858a3b735ee2d56d700586cbcd415a41ece7`
- User-owned `cs2d_settings.json` changes were preserved and excluded from this change.

## Evidence and root cause

- The server log remained stable at 60 Hz and did not coincide with the multi-second client stalls.
- The live JFR showed recurring 3.3–4.7 second safepoint-entry waits while the actual G1 collection took only a few milliseconds.
- Immediately before a representative stall, `QuantumRenderer-0` was in `NGRegion.renderBackgroundRectanglesDirectly()` and `com.sun.marlin.Renderer._endRendering()`.
- The five nested parent `NGRegion` frames match the in-game kill-feed hierarchy: kill-feed row, kill-feed list, top-right HUD, HUD overlay, game container, and root.
- Kill-feed rows contain static text and SVG icons but were being rerasterized while their opacity animated.
- The fade-out callback appended expired rows to a method-local list after that method invocation had already returned, so the rows were never removed by that list.

## File changes

### `src/main/java/cs2d/client/GameClient.java`

- Added JavaFX `CacheHint` usage.
- Enabled per-row bitmap caching for immutable kill-feed rows so their rounded background, text, and SVG icons are rasterized once and reused during opacity animation.
- Replaced the lost method-local fade-out removal list with direct FX-thread removal from `killFeedVBox` when the animation completes.
- Preserved all kill-feed colors, fonts, icons, timing, opacity animation, maximum entry count, and gameplay behavior.

## Validation

- `mvn test`
- Result: 48 tests passed, 0 failures, 0 errors, 0 skipped.
- `git diff --check` completed without whitespace errors (only the repository's existing Windows line-ending notice).

## Runtime verification requested

Run a 30–60 second high-kill-count match. The expected result is that kill-feed animations remain visually unchanged and the previous 3–4.7 second periodic safepoint freezes no longer occur. A new JFR should show no repeated multi-second `SafepointBegin` waits in `QuantumRenderer-0`.
