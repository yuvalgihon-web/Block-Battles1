# Lyric Mod (Fabric, Minecraft 26.3)

A client-side Fabric mod that will play a song and show its lyrics floating in the
world around you, synced to the music.

**Current state: step 3.** Lyrics appear in the world in sync with the music, word by word.

| Command | What it does |
| --- | --- |
| `/lyrics test` | Shows one floating line 4 blocks in front of you, facing the camera, fading in and out. |
| `/lyrics play <name>` | Plays `config/lyricmod/<name>.ogg` and shows the lyrics from `<name>.lrc` in sync. Press Tab to see the song names. |
| `/lyrics stop` | Stops the song. |
| `/lyrics list` | Lists the songs in `config/lyricmod`. |

The first time the game starts with the mod, it creates `.minecraft/config/lyricmod/` with a
`README.txt` and an example song, so you can try `/lyrics play example` right away.
The song's volume follows the **Jukebox/Note Blocks** slider in Music & Sounds.
Each lyric line appears in front of you when it starts and stays at that spot; its words fade in
one by one as they're sung, and the previous line fades out.

### Adding your own songs

Put two files with the same name in `config/lyricmod/`:

- `mysong.ogg`: the music (Ogg Vorbis). Convert an mp3 with e.g. Audacity: *File → Export → Export as OGG*.
- `mysong.lrc`: the lyrics with timestamps. Many songs have ready-made .lrc files online.

```
[ti:Song title]
[ar:Artist]
[00:12.50]First line of the song
[00:17.20]Second line
```

Word-by-word timing ("enhanced LRC") is used when present. Without it, the words of a line are
spread evenly over the first 60% of the line:

```
[00:12.50]<00:12.50>First <00:13.10>line <00:13.40>of <00:13.80>the <00:14.00>song
```

## Running it

You need **Java 25** (`java -version`). Then:

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
| `gradle.properties` | All version numbers (Minecraft, Fabric Loader, Fabric API, Loom) in one place. |
| `build.gradle` | The build script: applies Loom, declares dependencies, sets Java 25, fills the version into `fabric.mod.json`. |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/` | The Gradle Wrapper: downloads the right Gradle version automatically, so you don't install Gradle yourself. |
| `src/main/resources/fabric.mod.json` | The mod's "ID card" read by Fabric Loader: id, name, `"environment": "client"`, the entry point class, and dependencies. |
| `src/main/java/dev/lyricmod/LyricModClient.java` | Entry point. Fabric calls it once at startup; it registers the command and the renderer. |
| `src/main/java/dev/lyricmod/LyricsCommand.java` | Defines the client-side `/lyrics` commands (`test`, `play`, `stop`, `list`). |
| `src/main/java/dev/lyricmod/FloatingText.java` | Plain data for one line (text, position, start time) plus the fade-in/hold/fade-out opacity math. |
| `src/main/java/dev/lyricmod/LyricFiles.java` | Finds songs in `config/lyricmod` and reads them. Checks every name so nothing outside that folder can be read. Creates the folder and example song on first start. |
| `src/main/java/dev/lyricmod/LrcParser.java` | Turns the text of an `.lrc` file into a `Lyrics` object: title, artist, timed lines and (if present) timed words. |
| `src/main/java/dev/lyricmod/Lyrics.java` | Plain data: the song's title, artist and lyric lines/words with their times. |
| `src/main/java/dev/lyricmod/SongPlayer.java` | Plays the `.ogg` through OpenAL (Minecraft's audio library), follows the volume slider, pauses with the game, and reports the exact playback time. |
| `src/main/resources/assets/lyricmod/example/` | The example song ("Twinkle, Twinkle, Little Star", public domain) copied to `config/lyricmod` on first start. |
| `src/gametest/` | An automated test that starts Minecraft, runs the commands and takes screenshots (`./gradlew runClientGameTest`). Not part of the mod `.jar`. |
| `src/main/java/dev/lyricmod/LyricTimeline.java` | Timing math only: which line is sung at a given time, when it ends, and when each of its words starts. |
| `src/main/java/dev/lyricmod/SyncedLyricsDisplay.java` | Every frame, reads the song's playback time and draws the current line word by word (and the previous line fading out). |
| `src/main/java/dev/lyricmod/LyricRenderer.java` | Hooks into world rendering every frame. Its `drawText` puts one piece of text in 3D: move to its position, rotate to face the camera, scale down, draw with the right transparency. |

## Design notes (for later steps)

- **Client-only, offline:** no networking code at all. `environment: client` means the mod never loads on a server.
- **Step 2 (files):** done. Only `config/lyricmod/` is read; names with `/`, `\` or `..` and symbolic links are refused.
- **Step 3 (sync):** done. Everything is computed from `SongPlayer.positionMs()` (the audio device's own playback time), so lyrics stay in sync through lag and pauses.
- **Spotify mode (planned, after step 3):** Spotify plays the music; the mod reads the song and position from Spotify and downloads synced lyrics from LRCLIB. Only `LyricTimeline`/`SyncedLyricsDisplay` need a different time source.
- **Step 4 (animation):** `SyncedLyricsDisplay` will place words around the player (scatter, drift, orbit, tilt, scale) with easing curves instead of one straight line.
- **Step 5 (config):** the constants at the top of `LyricRenderer` (`TEXT_SCALE`, `TEXT_COLOR`) and `LyricsCommand` (`TEST_DISTANCE`) become config values.
