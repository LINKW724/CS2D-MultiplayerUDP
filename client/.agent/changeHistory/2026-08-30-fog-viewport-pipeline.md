# Fog and Viewport Rendering Pipeline

- Date: 2026-08-30
- Objective: improve 50-player large-map frame pacing without reducing the 165 Hz target, 1696 FOV rays, FOV precision, or visual quality.
- Repository: `O:/java/games/CS2D-MultiplayerUDP_Client`
- Branch: `main`
- Baseline commit: `bf2ef217df8fa056780183dbdf67730b8a087a9b`
- Implementation status: changes remain uncommitted for live visual/performance verification.

## Files

- `src/main/java/cs2d/client/GameClient.java` — 458 added, 132 deleted.
  - Splits world, persistent fog, and HUD drawing into ordered layers so fog geometry can be cached without covering the HUD.
  - Rasterizes fog only for a newly published immutable FOV snapshot, then applies an exact camera affine transform on intervening display frames.
  - Reuses a primitive screen-coordinate buffer and adds coverage protection for camera movement and zoom.
  - Precomputes static-obstacle bounding circles, uses squared-distance rejection, and replaces the common angle-normalization loop with a single-step fast path plus an exceptional fallback.
  - Replaces synchronous 3072x3072 Canvas snapshots with adaptive full-resolution viewport buffers, background pixel composition, front/back `PixelBuffer` images, request coalescing, and atomic FX-thread publication.
  - Shares each baked tile's premultiplied ARGB storage between its display image and background compositor instead of retaining duplicate long-lived pixel copies.
  - Removes the 20 resident tile `ImageView` nodes; unsupported or not-yet-ready viewport states draw the original visible tile images through the existing Canvas fallback.
  - Adds `[FOG-CACHE]` reuse metrics and background time, replacement count, and dimensions to `[STATIC-VIEWPORT]` diagnostics.
- `src/test/java/cs2d/client/GameClientProtocolTest.java` — 27 added, 0 deleted.
  - Covers fast angle normalization, including abnormal multi-turn input.
  - Proves cached fog affine transforms keep world coordinates aligned with direct camera conversion.
- `src/test/java/cs2d/client/RenderFrameSchedulerTest.java` — 8 added, 0 deleted.
  - Covers adaptive, quantized, one-world-pixel-per-texture-pixel viewport sizing and map-edge clamping.
- `.agent/changeHistory/2026-08-30-fog-viewport-pipeline.md` — 50 added, 0 deleted; this record.
- `cs2d_settings.json` — pre-existing user runtime change; preserved and deliberately excluded.

## Behavior and compatibility

- FOV ray count remains `1696`; ray directions, segment intersection, simplification tolerance, and final polygon geometry are unchanged.
- Render target remains 165 Hz and JavaFX full-speed pulse remains enabled.
- Static obstacle pixels are copied exactly in premultiplied ARGB form; adaptive viewport sizing changes allocation dimensions only, not map resolution or obstacle precision.
- While a new viewport is composed, the previous front buffer remains visible when it still covers the camera. Teleports or uncovered regions use the original tile Canvas fallback until publication, preventing missing obstacles.
- Full-map overview behavior and static-map-disabled fallback remain available.
- No protocol, server, dependency, configuration schema, sound, gameplay, or network-rate changes.

## Validation

- `mvn -DskipTests compile` — passed.
- `mvn test` — passed before final review: 32 tests, 0 failures, 0 errors.
- `git diff --check` — passed; only the repository's LF-to-CRLF advisory was printed.

## Manual verification and rollback

- Run the same 50-player small-map and `4392x3840 / 744 obstacle` large-map JFR scenario.
- Verify fog edges, zoom, rapid turning, death/spectator transitions, flashbang overlay, crosshair/HUD ordering, and obstacle continuity during fast camera movement.
- Expect `[FOG-CACHE]` transform-only frames to be nonzero and `[STATIC-VIEWPORT]` to report an adaptive buffer near the visible area plus padding rather than a permanent 3072x3072 allocation.
- Compare FPS, 1% Low, Prism/Marlin samples, `Parent.updateBounds`, viewport rebuild windows, heap, and GC against the previous large-map baseline of 85.8 FPS and 28.1 FPS 1% Low.
- Restore or compare against baseline `bf2ef21` if live verification finds a visual or performance regression.
