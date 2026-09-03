# Reliable audio event delivery

- Date: 2026-09-03
- Objective: deduplicate replayed transient events, start isolated PCM with the real Java runtime, and guarantee immediate JavaFX fallback when PCM is unavailable.
- Repository: `O:/java/games/CS2D-MultiplayerUDP_Client`
- Branch: `main`
- Pre-change baseline: `54bc043` (`chore: snapshot before reliable audio delivery`)
- Implementation state: changes remain in the working tree and are not committed.
- Preserved user state: `cs2d_settings.json` remains modified and was excluded from the baseline and this implementation.

## Files changed

- `src/main/java/cs2d/client/EventDeduplicator.java` (+42 / -0): adds a bounded 4096-id recent-event window with session reset support.
- `src/main/java/cs2d/client/JavaRuntimeLocator.java` (+23 / -0): resolves and validates the Java executable under `java.home`, including jpackage private runtimes.
- `src/main/java/cs2d/client/PcmWorkerProcessFactory.java` (+36 / -0): builds and launches the PCM worker independently of the native application launcher and exposes startup errors.
- `src/main/java/cs2d/client/IsolatedPcmOutputTransport.java` (+3 / -8): replaces `ProcessHandle.current().command()` worker startup with the runtime locator and process factory.
- `src/main/java/cs2d/client/PcmAudioMixer.java` (+15 / -7): replaces ambiguous boolean playback with explicit results and reports backend unavailability before accepting a request.
- `src/main/java/cs2d/client/GameClient.java` (+39 / -4): deduplicates replayed sound, private sound, damage, flash, and footstep events; falls back immediately when PCM is unavailable or overloaded; and reports received versus duplicate audio events.
- `src/test/java/cs2d/client/EventDeduplicatorTest.java` (+26 / -0): covers duplicate rejection, bounded eviction, and session reset.
- `src/test/java/cs2d/client/IsolatedPcmOutputTransportTest.java` (+13 / -0): verifies the worker command begins with the `java.home` runtime instead of the packaged game launcher.
- `src/test/java/cs2d/client/PcmAudioMixerTest.java` (+9 / -0): verifies a stopped mixer reports backend unavailability so the caller can use JavaFX.

## Behavior and compatibility

- Repeated server events carrying the same `eventId` are processed once; events from older servers without an id remain accepted.
- PCM no longer returns false success while stopped. Current sound is sent to JavaFX while background PCM recovery proceeds.
- The worker JVM is launched with the bundled/system Java runtime rather than recursively invoking the game `.exe`.
- Child-process stderr is inherited so startup failures are visible instead of discarded.
- Diagnostics now distinguish network audio received, `eventId` duplicates, source-level repetition, PCM requests, and JavaFX fallback.
- No persistent settings, dependencies, audio formats, sample rates, or volume rules changed.

## Validation

- `mvn -q -Dtest=EventDeduplicatorTest,PcmAudioMixerTest,IsolatedPcmOutputTransportTest,GameClientProtocolTest test`: passed.
- `mvn -q test`: passed, 57 tests, 0 failures, 0 errors, 0 skipped.
- `mvn -q -DskipTests package`: passed, including Windows jpackage output.
- `git diff --check`: passed; Git printed only existing LF-to-CRLF advisories.

## Restart and rollback

- Fully exit and restart the client so the PCM mixer and isolated worker are recreated.
- No settings or data migration is required.
- Roll back implementation changes to baseline `54bc043` while preserving the separate `cs2d_settings.json` modification.
- Manual verification should confirm `[AUDIO-EVENT] 网络收到` rises during combat, duplicate counts rise during the replay window, PCM requests rise when the worker is healthy, and JavaFX fallback rises if the worker is unavailable.
