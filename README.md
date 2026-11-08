# Lyric Mod (Fabric, Minecraft 26.3)

A client-side Fabric mod that will play a song and show its lyrics floating in the
world around you, synced to the music.

**Current state: step 4.** Lyrics appear in the world in sync with the music, word by word,
scattered around you with animations (or as a simple line, if you prefer).

| Command | What it does |
| --- | --- |
| `/lyrics test` | Shows one floating line 4 blocks in front of you, facing the camera, fading in and out. |
| `/lyrics play <name>` | Plays `config/lyricmod/<name>.ogg` and shows the lyrics from `<name>.lrc` in sync. Press Tab to see the song names. |
| `/lyrics style scatter` | (default) Words float around you: each pops in when sung, tilted, at its own distance/height/size; lines slowly orbit and drift up, then float away. |
| `/lyrics style line` | Each line as a straight row of words in front of you. |
| `/lyrics stop` | Stops the song. |
| `/lyrics list` | Lists the songs in `config/lyricmod`. |
| `/lyrics spotify` | Shows the lyrics of whatever the Spotify app is playing (lyrics from LRCLIB). See below for the one-time setup. |
| `/lyrics record <name>` | Makes `<name>.lrc` from `<name>.ogg` + `<name>.txt` by pressing **K** when each line starts (see below). |

The first time the game starts with the mod, it creates `.minecraft/config/lyricmod/` with a
`README.txt` and an example song, so you can try `/lyrics play example` right away.
The song's volume follows the **Jukebox/Note Blocks** slider in Music & Sounds.
Words appear one by one exactly when they're sung, and the previous line leaves when the next starts.
In the default **scatter** style each line is spread over an arc around where you were looking when it
started (still in reading order, right-to-left for Hebrew); the words travel with you as you walk.

### Spotify mode

The music plays in the Spotify app as usual; the mod asks Spotify about once a second which song is
playing and how far in, downloads synced lyrics for it from [LRCLIB](https://lrclib.net), and shows
them in sync. **This is the only part of the mod that uses the internet** (it talks only to
Spotify and lrclib.net). It needs a one-time setup:

1. Go to <https://developer.spotify.com/dashboard>, log in with your Spotify account and click **Create app**.
   Any name and description are fine. Under **Redirect URIs** add exactly `http://127.0.0.1:18742/callback`,
   and under the APIs tick **Web API**. Save.
2. Open the app's settings and copy its **Client ID**.
3. In Minecraft: `/lyrics spotify setup <Client ID>`, then `/lyrics spotify login`, click the green link
   and allow access in the browser.
4. Play a song in Spotify and type `/lyrics spotify`. `/lyrics stop` stops following Spotify.

The mod only asks for permission to *read* what is playing. Your login is saved in
`config/lyricmod/spotify.json`: don't share that file. `/lyrics spotify logout` forgets it.
Downloaded lyrics are saved in `config/lyricmod/lrclib-cache/` (one `.lrc` per song, named after the
Spotify track ID); if a song has no lyrics on LRCLIB, or the timing is off, you can put or fix the
`.lrc` file there. Because the position comes from Spotify over the internet, sync can be ~0.1–0.3 s
less exact than with local `.ogg` files.

### No .lrc file? Record one in game

1. Put the plain lyrics in `config/lyricmod/mysong.txt`, one lyric line per line
   (blank lines are ignored; you can add `[ar:Artist]` / `[ti:Title]` lines at the top).
2. Type `/lyrics record mysong`. The song starts and the text above your hotbar shows the next line.
3. Press **K** the moment each line starts. The line appears right away, so you see your timing.
   (The key can be changed in *Options → Controls → Miscellaneous → Mark lyric line*.)
4. After the last line, press **K** once more when the singing ends. `mysong.lrc` is saved
   (an existing one is kept as `mysong.lrc.bak`). If the song ends first, the marked lines are saved.
   `/lyrics stop` cancels without saving.

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
| `src/main/java/dev/lyricmod/LyricRecorder.java` | The `/lyrics record` tool: plays the song, records the song time at each **K** press, shows a live preview and writes the `.lrc` file. |
| `src/main/resources/assets/lyricmod/lang/en_us.json` | The English name of the "Mark lyric line" key shown in the Controls menu. |
| `src/main/java/dev/lyricmod/BidiText.java` | Right-to-left support (Hebrew, Arabic): decides the left-to-right drawing order of a line's words, so the first Hebrew word appears on the right. |
| `src/main/java/dev/lyricmod/SpotifyAuth.java` | Spotify login (Authorization Code with PKCE): a temporary local web server on `127.0.0.1:18742` receives the login, tokens are kept in `config/lyricmod/spotify.json` and refreshed automatically. |
| `src/main/java/dev/lyricmod/SpotifyMode.java` | Asks Spotify what is playing about once a second, keeps counting the song time in between, and fetches lyrics when the song changes. |
| `src/main/java/dev/lyricmod/Lrclib.java` | Downloads synced lyrics from lrclib.net (exact match first, then the search result closest in length) and caches them. |
| `src/main/java/dev/lyricmod/ScatterAnimation.java` | The scatter style: chooses each word's spot (angle, distance, height, size, tilt) once per line, then animates pop-in, drift, orbit and leaving every frame. |
| `src/main/java/dev/lyricmod/Easing.java` | Easing curves (`outCubic`, `inCubic`, `inOutSine`, `outBack`) that make motion start and stop smoothly. |
| `src/main/java/dev/lyricmod/LyricStyle.java` | The available styles (`SCATTER`, `LINE`) and the current one. |
| `src/main/java/dev/lyricmod/LyricTimeline.java` | Timing math only: which line is sung at a given time, when it ends, and when each of its words starts. |
| `src/main/java/dev/lyricmod/SyncedLyricsDisplay.java` | Every frame, reads the song's playback time and draws the current line word by word (and the previous line fading out). |
| `src/main/java/dev/lyricmod/LyricRenderer.java` | Hooks into world rendering every frame. Its `drawText` puts one piece of text in 3D: move to its position, rotate to face the camera, scale down, draw with the right transparency. |

## Design notes (for later steps)

- **Client-only:** `environment: client` means the mod never loads on a server. Only the optional Spotify mode uses the internet (api.spotify.com, accounts.spotify.com, lrclib.net).
- **Step 2 (files):** done. Only `config/lyricmod/` is read; names with `/`, `\` or `..` and symbolic links are refused.
- **Step 3 (sync):** done. Everything is computed from `SongPlayer.positionMs()` (the audio device's own playback time), so lyrics stay in sync through lag and pauses.
- **Spotify mode:** done. `LyricRenderer` takes lyrics and time from `SpotifyMode` instead of `SongPlayer`; everything else is shared.
- **Step 4 (animation):** done. The tuning numbers live as constants at the top of `ScatterAnimation`.
- **Step 5 (config):** text color, size, distance, style and the scatter tuning numbers move into a config file.
