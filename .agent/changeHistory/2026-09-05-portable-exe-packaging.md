# Portable EXE packaging

- Date: 2026-09-05
- Repository: `C:/Users/小麦/Documents/Codex/2026-09-03/ai/CS2D-MultiplayerUDP`
- Branch: `main`
- Pre-change baseline: `61c8a7d8eb1a705f589be466b4bab4606a5eab79`
- Objective: replace the client installer and batch-file distributions with click-to-run, no-install client and server application images.
- State preservation: the already-published `main` and `v3.1.3` tag are not changed during preview packaging.

## Per-file changes

- `client/pom.xml` (+8/-5): changes jpackage output from an EXE installer to the `CS2D Client` portable `APP_IMAGE` launcher and includes all runtime modules found by dependency analysis.
- `packaging/build-portable.ps1` (89 added lines): builds both Maven projects, creates client and server application images with bundled runtimes, copies required runtime data, archives both images, and writes SHA-256 checksums.
- `README.md` (+12/-3): documents the no-install build and launch workflow.
- `packaging/client/run-client.bat` (6 removed lines): removed because the native client launcher replaces it.
- `packaging/server/run-server.bat` (6 removed lines): removed because the native server launcher replaces it.
- `.agent/changeHistory/2026-09-05-portable-exe-packaging.md` (32 added lines): this record.

## Validation

- Initial full build ran the client and server test suites successfully.
- `jdeps` identified missing client runtime modules; `java.management`, `java.sql`, and `jdk.jfr` are now included explicitly.
- The packaging script completed after the runtime correction and produced both ZIPs plus SHA-256 checksums.
- Archive inspection confirmed each native EXE, bundled JVM, configuration files, and server maps/assets are present.
- Hidden smoke launch kept both `CS2D Client.exe` and `CS2D Server.exe` alive beyond five seconds; both test processes were then stopped.
- `git diff --check` passed with only existing LF/CRLF conversion warnings.

## Deployment and rollback

- Preview output is ignored under `release/preview-v3.1.3/` and is shown locally before any GitHub Release upload.
- The obsolete installer and previous temporary archives were removed from local release staging after the portable packages passed smoke tests; they can be regenerated from the previous commit if needed.
- Roll back against `61c8a7d8eb1a705f589be466b4bab4606a5eab79` to restore installer and batch-launcher packaging.
