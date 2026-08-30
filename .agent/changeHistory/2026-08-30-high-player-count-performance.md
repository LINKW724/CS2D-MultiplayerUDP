# High-player-count client performance repair

- Date: 2026-08-30
- Objective: remove large-map 50-player UDP chunk work from the JavaFX frame thread and stop overlapping MP3 media players from creating native thread storms.
- Repository: `O:\java\games\CS2D-MultiplayerUDP_Client`
- Branch: `main`
- Baseline commit: `3a4a3cd8ad8ebc778fd48fe736be21fd581abf60`
- Implementation status: changes remain in the worktree; no implementation commit was requested.
- Preserved user change: `cs2d_settings.json` was already modified before this task and was not edited by this implementation.

## Files changed

- `src/main/java/cs2d/client/GameClient.java` — 135 additions, 69 deletions. Enables the resident static-map layer by default, performs UDP chunk validation/reassembly/decompression on the receive thread, queues only complete messages for the 165 Hz FX frame, serializes overlapping remote MP3 world voices while leaving WAV gunfire untouched, and adds chunk/audio diagnostics.
- `src/test/java/cs2d/client/MediaSoundGateTest.java` — new file, 19 lines. Verifies native-media overlap prevention and cooldown behavior.
- `.agent/changeHistory/2026-08-30-high-player-count-performance.md` — new documentation record.

## Behavior changes

- Client rendering remains 165 Hz and FOV remains 1696 rays with unchanged precision.
- Static-map resident tiles are now the default; use `-Dcs2d.staticMapLayer=false` only for diagnostic fallback.
- UDP chunk JSON parsing, checksum validation, assembly, Base64 decoding, GZIP decompression, and full-message reconstruction no longer run in the JavaFX frame callback.
- Remote MP3/radio-style world sounds cannot overlap MediaPlayer instances; WAV weapon sounds and private/UI MP3 sounds retain their existing playback path.
- New log rows report chunk datagrams/completed messages/background cost and played/suppressed media-backed world sounds.
- Protocol schema and saved-settings format are unchanged.

## Validation

- `mvn test` — passed: 23 tests, 0 failures, 0 errors.
- `git diff --check` — passed; only the repository's existing LF-to-CRLF checkout warning was reported.
- Existing `cs2d_settings.json` worktree modification remains present and untouched.

## Rollback and manual verification

- Roll back implementation files to baseline commit `3a4a3cd8ad8ebc778fd48fe736be21fd581abf60` if required; preserve the user's settings file separately.
- Restart the client before testing.
- Re-run the same 50-100 player large-map scenario and compare `[REAL]`, `[PACE]`, `[NET-CHUNK]`, `[AUDIO-MEDIA]`, GC, and a new JFR thread-start count.
