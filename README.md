# CS2D Multiplayer UDP

A Java 17 multiplayer top-down shooter using UDP networking. This repository contains both authoritative server code and the JavaFX client, including team modes, zombie mode, bots, tactical coordination, map tools, and combat feedback.

## Repository layout

- `server/` — authoritative game server, AI, maps, map editor, and tests.
- `client/` — JavaFX desktop client, rendering, audio, settings, and tests.
- `packaging/` — Windows launchers included in release archives.

Both projects retain their original Git histories in this combined repository.

## Requirements

- JDK 17
- Maven 3.9 or newer
- Windows is recommended for the packaged JavaFX client.

## Build and test

Run each project from its own directory:

```powershell
cd server
mvn test
mvn package

cd ..\client
mvn test
mvn package
```

The server produces `server/target/cs2d-server.jar`. The client build prepares its application JAR and runtime dependencies under `client/target/jpackage-deps/`; on a configured Windows machine it can also create an installer through `jpackage`.

## Run from source

Server:

```powershell
cd server
java -jar target/cs2d-server.jar
```

Client:

```powershell
cd client
mvn javafx:run
```

Release archives contain `run-server.bat` and `run-client.bat` for convenience. Configuration files are ordinary local JSON files; do not commit private tokens or machine-specific credentials.

