# Lyric Mod (Fabric, Minecraft 1.21.1)

A client-side Fabric mod that will play a song and show its lyrics floating in the
world around you, synced to the music.

**Current state: step 1.** `/lyrics test` shows one line of text 4 blocks in front of you,
always facing the camera, fading in over 1 second (then fading out after a few seconds).

## Running it

You need **Java 21** (`java -version`). Then:

```bash
./gradlew runClient
```

The first run downloads Minecraft and Fabric, so it takes a few minutes. Create or open a
world and type `/lyrics test`.

To build a `.jar` for your normal Minecraft install: `./gradlew build`, then copy
`build/libs/lyricmod-0.1.0.jar` into your `mods` folder (together with Fabric API).

## What each file does

| File | Purpose |
| --- | --- |
| `settings.gradle` | Tells Gradle where to find the Fabric Loom plugin and names the project. |
| `gradle.properties` | All version numbers (Minecraft, Yarn mappings, Fabric Loader, Fabric API, Loom) in one place. |
| `build.gradle` | The build script: applies Loom, declares dependencies, sets Java 21, fills the version into `fabric.mod.json`. |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/` | The Gradle Wrapper: downloads the right Gradle version automatically, so you don't install Gradle yourself. |
| `src/main/resources/fabric.mod.json` | The mod's "ID card" read by Fabric Loader: id, name, `"environment": "client"`, the entry point class, and dependencies. |
| `src/main/java/dev/lyricmod/LyricModClient.java` | Entry point. Fabric calls it once at startup; it registers the command and the renderer. |
| `src/main/java/dev/lyricmod/LyricsCommand.java` | Defines the client-side `/lyrics test` command. Computes a point in front of the player and hands a line to the renderer. |
| `src/main/java/dev/lyricmod/FloatingText.java` | Plain data for one line (text, position, start time) plus the fade-in/hold/fade-out opacity math. |
| `src/main/java/dev/lyricmod/LyricRenderer.java` | Every frame, draws each active line in 3D: move to its position, rotate to face the camera, scale down, draw with the right transparency. |

## Design notes (for later steps)

- **Client-only, offline:** no networking code at all. `environment: client` means the mod never loads on a server.
- **Step 2 (files):** a loader will read only from `config/lyricmod/` (via `FabricLoader.getInstance().getConfigDir().resolve("lyricmod")`), and reject names containing `/`, `\` or `..`.
- **Step 3 (sync):** `LyricRenderer.nowMs()` is the single clock. It will be replaced with the song's playback position.
- **Step 4 (animation):** `FloatingText.opacity()` will grow into an animation that also returns position offset and scale, using easing curves.
- **Step 5 (config):** the constants at the top of `LyricRenderer` (`TEXT_SCALE`, `TEXT_COLOR`) and `LyricsCommand` (`TEST_DISTANCE`) become config values.
