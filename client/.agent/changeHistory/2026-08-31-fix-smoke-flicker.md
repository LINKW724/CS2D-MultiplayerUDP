# Fix smoke grenade flicker

- Timestamp: 2026-08-31 21:36 Asia/Shanghai
- Baseline commit: `73c9b448c156d1bccc5db0c1f7ba1b97488feabf`
- Baseline message: `perf: optimize large-map fog network and AI`
- Scope: client smoke-state publication and protocol regression coverage.

## Summary

Replaced the shared mutable smoke map's clear-then-put update with an immutable snapshot built off-thread and published through one volatile assignment. Render and FOV readers now observe either the complete previous smoke cloud or the complete next cloud, never the transient empty or partially rebuilt state that caused whole-cloud flashing at high render rates.

## File changes

- `src/main/java/cs2d/client/GameClient.java` — added 21 lines, removed 14. Added atomic immutable smoke snapshots for full updates, small updates, disconnect, and reset paths.
- `src/test/java/cs2d/client/GameClientProtocolTest.java` — added 19 lines. Verifies complete smoke publication, stable IDs, empty snapshots, and immutability.

## Validation

- `mvn test` — passed: 41 tests, 0 failures, 0 errors.
- `git diff --check` — passed; Git only reported the repository's existing LF-to-CRLF checkout warning.

## Notes

- Smoke density, lifetime, opacity, particle count, FOV interaction, and update frequency were not reduced.
- The user-owned runtime file `cs2d_settings.json` was not modified by this work and remains excluded from the implementation diff.
- Implementation changes intentionally remain uncommitted after the baseline snapshot for gameplay review.
