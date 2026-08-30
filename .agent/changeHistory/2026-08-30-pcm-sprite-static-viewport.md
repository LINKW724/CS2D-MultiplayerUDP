# PCM Mixer, Sprite Cache, and Static Viewport

- Date: 2026-08-30
- Objective: remove JavaFX audio thread churn and repeated Marlin rasterization without lowering sound frequency, visual quality, FOV precision, or render target.
- Repository: `O:/java/games/CS2D-MultiplayerUDP_Client`
- Branch: `main`
- Baseline commit: `e60890b873b7821d0fa1b21d10eb057e38d92879`
- Implementation status: changes remain in the working tree for live performance verification.

## Files

- `src/main/java/cs2d/client/PcmAudioMixer.java` — 246 added, 0 deleted.
  - Adds one fixed 48kHz, 16-bit, stereo PCM output thread.
  - Decodes WAV resources once, mixes concurrent voices with saturation, exposes runtime metrics, and closes the audio device cleanly.
- `src/main/java/cs2d/client/GameClient.java` — 269 added, 47 deleted.
  - Routes high-frequency WAV sounds through the PCM mixer while preserving MP3/unsupported-format fallback.
  - Caches player body/direction/weapon-line graphics and dropped-weapon graphics as 2x-resolution sprites.
  - Adds a 3072x3072 one-pixel-per-world-pixel sliding obstacle viewport, using the original tiles only for infrequent cache rebuilds.
  - Adds `[AUDIO-PCM]`, `[SPRITE-CACHE]`, and `[STATIC-VIEWPORT]` diagnostics.
- `src/test/java/cs2d/client/PcmAudioMixerTest.java` — 72 added, 0 deleted.
  - Covers PCM endian fidelity, concurrent mixing/saturation, and conversion of all 289 bundled WAV files.
- `src/test/java/cs2d/client/RenderFrameSchedulerTest.java` — 23 added, 0 deleted.
  - Covers viewport origin clamping and rebuild-margin behavior at map boundaries.
- `.agent/changeHistory/2026-08-30-pcm-sprite-static-viewport.md` — 50 added, 0 deleted.
- `cs2d_settings.json` — pre-existing user runtime change; preserved and deliberately excluded.

## Behavior and compatibility

- Removed: one JavaFX native media playback thread per high-frequency WAV playback.
- Added: unlimited normal gameplay voice overlap on one persistent PCM mixer thread; volume and distance calculations remain unchanged.
- Removed: per-frame Marlin rasterization of player circles, direction dots, weapon lines, and dropped-weapon labels.
- Added: transparent 2x sprite textures drawn at the exact original world dimensions and rotation.
- Removed: 6–12 independently composited obstacle tile nodes during normal camera tracking.
- Added: one full-resolution sliding viewport texture; overview and tile fallback paths remain available.
- No dependency, protocol, server, settings schema, FOV, simulation-rate, render-rate, or gameplay changes.

## Validation

- `mvn -Dtest=PcmAudioMixerTest test` — passed, including all 289 WAV resources.
- `mvn test` — passed, 29 tests, 0 failures, 0 errors.
- `git diff --check` — passed; only the repository's existing LF-to-CRLF advisory was printed.

## Manual verification and rollback

- Run the same 50-player small/large-map JFR scenario.
- Expect `[AUDIO-PCM]` requested and mixed counts to match closely, queue near zero, and JavaFX fallback zero for gunfire.
- Expect `[SPRITE-CACHE]` new textures only during warm-up, then zero; expect `[STATIC-VIEWPORT]` rebuilds to be zero in most two-second windows.
- Compare FPS, 1% Low, Prism CPU, Marlin samples, `ThreadStart`, and sound output before committing.
- If the PCM device cannot open, the client logs a clear message and safely falls back to existing JavaFX playback.
- Restore or compare against baseline `e60890b` if manual visual/audio verification finds a regression.
