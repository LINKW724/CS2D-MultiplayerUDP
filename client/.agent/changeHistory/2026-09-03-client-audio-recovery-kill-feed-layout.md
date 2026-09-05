# Client audio recovery and stable kill-feed layout

- Date: 2026-09-03
- Objective: prevent high-population PCM audio from becoming silent and keep the top-right HUD stable when the kill-feed limit is below four.
- Repository: `O:/java/games/CS2D-MultiplayerUDP_Client`
- Branch: `main`
- Pre-change baseline: `ae8f3c9fc7b09166bf8482aa056507455a766293`
- Implementation state: changes remain in the working tree and are not committed.
- Preserved user state: the pre-existing `cs2d_settings.json` follow-camera zoom change remains untouched and is excluded from this implementation.

## Files changed

### `src/main/java/cs2d/client/PcmAudioMixer.java` (+115 / -18)

- Replaces the unbounded play-request queue with a fixed 512-request real-time queue.
- Limits concurrent mixed voices to 128 by default, configurable with `cs2d.audio.maxVoices` and never below 32.
- Virtualizes the quietest voice first; equal-volume contention replaces the voice that has already played furthest, keeping current shots synchronized with the picture.
- Discards requests older than 200 ms instead of replaying stale sounds after an output interruption.
- Clears active voices, pending requests, and buffered output after a failed or 150 ms late device submission.
- Adds background recovery for an unexpectedly stopped mixer without blocking JavaFX, network, or state threads.
- Adds virtualized-voice, stale-request, timeline-reset, and mixer-restart diagnostics.

### `src/main/java/cs2d/client/PcmOutputWorkerMain.java` (+9 / -1)

- Treats partial or zero-byte device writes and a closed/stopped `SourceDataLine` as output failure.
- Reopens the device immediately instead of acknowledging a silently unusable output line.

### `src/main/java/cs2d/client/GameClient.java` (+30 / -6)

- Extends `[AUDIO-PCM]` logging with the new overload and recovery counters.
- Keeps a four-row minimum kill-feed layout reservation so the camera button, ping, round, and timer HUD do not jump upward for limits 0–3.
- Trims visible feed rows immediately when the slider changes rather than waiting for another server kill-feed update.
- Avoids constructing a temporary row when the configured limit is zero while preserving local kill statistics processing.

### `src/main/java/cs2d/client/GameSettings.java` (+12 / -2)

- Defines the supported kill-feed range as 0–10.
- Adds a clamped setter and applies it when loading settings, preventing invalid persisted values from reaching HUD removal logic.

### `src/test/java/cs2d/client/PcmAudioMixerTest.java` (+38 / -0)

- Verifies loud-current-over-quiet-stale voice selection, quiet-voice rejection, and equal-volume oldest-voice replacement.

### `src/test/java/cs2d/client/IsolatedPcmOutputTransportTest.java` (+7 / -0)

- Verifies worker recovery decisions for partial writes and stopped device lines.

### `src/test/java/cs2d/client/GameSettingsTest.java` (+27 / -0)

- Verifies lower and upper kill-feed limits loaded from JSON are clamped safely.

## User-visible behavior and compatibility

- Audio remains 48 kHz, 16-bit, stereo with the existing samples, volume calculation, and 256-frame mixing precision.
- Under extreme simultaneous sound load, inaudible/quieter or older overlapping voices are virtualized so current nearby sounds continue instead of the entire mixer falling behind.
- The audio helper process and mixer recover automatically; obsolete PCM is not replayed after recovery.
- Kill-feed limits 0–3 remain supported, but no longer reposition the rest of the top-right HUD above its original four-entry baseline.
- No game protocol, server behavior, render frequency, FOV, dependency, or persistent schema migration changed.

## Validation

- `mvn -Dtest=PcmAudioMixerTest,IsolatedPcmOutputTransportTest,GameSettingsTest test`: passed, 13 tests, 0 failures, 0 errors.
- `mvn test`: passed, 53 tests, 0 failures, 0 errors, 0 skipped.
- `mvn -DskipTests package`: passed, including Windows `jpackage` and assembly output; Maven printed existing JavaFX parent-project and artifact-replacement warnings.
- `git diff --check`: passed; Git only reported LF-to-CRLF conversion warnings.

## Manual verification and rollback

- Fully exit and restart the client so a new mixer and audio helper process are created.
- Test a high-population battle without resizing the window. `[AUDIO-PCM]` should keep `running=true`; overload may increase `声部虚拟化`, while `排队` should remain bounded and current shots should stay audible.
- Temporarily set the ESC kill-feed slider to 0, 1, 2, and 3. Existing entries should trim immediately and the camera/Ping/time block should remain at a stable vertical position.
- If the audio device actually stops, `时间线重置`, `设备恢复`, or `混音重启` may increase; sound should resume without replaying old shots.
- Roll back code to `ae8f3c9fc7b09166bf8482aa056507455a766293`; no migration is required. Preserve the user's separate `cs2d_settings.json` change.
