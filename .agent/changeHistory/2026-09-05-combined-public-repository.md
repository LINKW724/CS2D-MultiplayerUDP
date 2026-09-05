# Combined public repository

- Date: 2026-09-05
- Repository: `C:/Users/小麦/Documents/Codex/2026-09-03/ai/CS2D-MultiplayerUDP`
- Branch: `main`
- Pre-change server baseline: `96395aa11459499f3c36637e4fc78f7fda03dfad`
- Pre-change client baseline: `66671031d27c85519647c394059d0272e6d54e6d`
- Combined baseline before repository-level files: `4d99b28`
- Objective: publish one repository containing both projects, retain both histories, and provide release-ready launchers.

## Baseline and preservation

- Created a separate integration checkout; the two original O-drive repositories remain intact.
- Moved the server tree under `server/` in a dedicated history-preserving commit.
- Merged the complete client history under `client/` without squashing.
- Excluded the client's uncommitted personal `cs2d_settings.json` changes from the merge.

## Per-file changes

- `README.md` (52 added lines): documents the combined layout, prerequisites, build, test, run, and release behavior.
- `.gitignore` (9 added lines): ignores build products, release staging, editor metadata, logs, and crash dumps throughout the monorepo.
- `packaging/client/run-client.bat` (6 added lines): starts the portable client from its bundled `lib` directory.
- `packaging/server/run-server.bat` (6 added lines): starts the packaged server from its archive directory.
- `.agent/changeHistory/2026-09-05-combined-public-repository.md` (41 added lines): records this integration and publication workflow.

## Validation

- `mvn -q test` in `server/`: passed.
- `mvn -q test` in `client/`: passed.
- `mvn -q -DskipTests package` in both projects: passed.
- Server JAR manifest contains `Main-Class: cs2d.ServerMain`.
- Release archive listing confirms server maps/assets and all portable client runtime dependencies are present.
- No current tracked file is 10 MB or larger; no historical Git blob is 50 MB or larger.
- Current-tree credential-pattern scan found no committed secret value; the server RL token remains a runtime-only parameter.
- Public remote verification is performed after this local record is committed.

## Deployment and rollback

- Publication target: `LINKW724/CS2D-MultiplayerUDP`, public visibility, `main` default branch.
- Release target: combined version `v3.1.3` with server ZIP, portable client ZIP, and Windows client installer.
- The original repositories are the rollback source; the combined checkout can be removed without affecting them.
