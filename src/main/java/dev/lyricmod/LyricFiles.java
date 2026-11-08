package dev.lyricmod;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Finds and reads song files, ONLY from the folder {@code config/lyricmod}.
 *
 * A song called "example" is two files next to each other:
 *   config/lyricmod/example.ogg   (the music)
 *   config/lyricmod/example.lrc   (the lyrics)
 *
 * Safety: the song name comes from a command the player types, so we never
 * trust it. A name like "../../secret" could otherwise point OUTSIDE our folder.
 * {@link #songFile} only accepts simple names and double-checks that the final
 * path is really inside our folder.
 */
public final class LyricFiles {

	/** Allowed song names: letters, digits, spaces and _ - . ' ( ) — no slashes. */
	private static final Pattern SAFE_NAME = Pattern.compile("[\\p{L}\\p{N} _.'()\\-]{1,64}");

	/** Refuse giant files so a mistake can't freeze the game or use all memory. */
	private static final long MAX_LRC_BYTES = 1024 * 1024;        // 1 MB
	static final long MAX_OGG_BYTES = 50L * 1024 * 1024;           // 50 MB

	private LyricFiles() {
	}

	/** The folder all songs live in: .minecraft/config/lyricmod */
	public static Path folder() {
		return FabricLoader.getInstance().getConfigDir().resolve(LyricModClient.MOD_ID);
	}

	/**
	 * Creates the folder the first time the mod runs, with a short help file
	 * and an example song, so there is something to try right away.
	 */
	public static void createFolderIfMissing() {
		Path folder = folder();
		if (Files.isDirectory(folder)) {
			return;
		}
		try {
			Files.createDirectories(folder);
			Files.writeString(folder.resolve("README.txt"), """
					Lyric Mod songs folder
					======================
					Put each song here as two files with the same name:
					  mysong.ogg   the music (Ogg Vorbis audio)
					  mysong.lrc   the lyrics with timestamps

					Then in game type:  /lyrics play mysong
					Other commands:     /lyrics list    /lyrics stop

					No .lrc file? Make one by tapping along:
					  1. Put the plain lyrics in mysong.txt (one line per line).
					  2. Type /lyrics record mysong
					  3. Press K each time a line starts; after the last line press K
					     once more. mysong.lrc is saved for you.

					Settings (colors, size, distance, animation) are in config.json in this
					folder. Edit it, save, and type /lyrics reload in game.

					To convert an .mp3 to .ogg you can use a free tool such as Audacity
					(File > Export > Export as OGG).
					""", StandardCharsets.UTF_8);
			copyExample(folder, "example.ogg");
			copyExample(folder, "example.lrc");
		} catch (IOException e) {
			LyricModClient.LOGGER.warn("Could not create {}", folder, e);
		}
	}

	/** Copies a file bundled inside our mod .jar into the songs folder. */
	private static void copyExample(Path folder, String fileName) throws IOException {
		try (InputStream in = LyricFiles.class.getResourceAsStream("/assets/lyricmod/example/" + fileName)) {
			if (in != null) {
				Files.copy(in, folder.resolve(fileName));
			}
		}
	}

	/**
	 * Lists the songs that have BOTH an .ogg and an .lrc file, sorted by name.
	 * Used by /lyrics list and for tab-completion of /lyrics play.
	 */
	public static List<String> listSongs() {
		List<String> names = new ArrayList<>();
		try (Stream<Path> files = Files.list(folder())) {
			files.map(path -> path.getFileName().toString())
					.filter(file -> file.endsWith(".ogg"))
					.map(file -> file.substring(0, file.length() - ".ogg".length()))
					.filter(name -> SAFE_NAME.matcher(name).matches())
					.filter(name -> Files.isRegularFile(folder().resolve(name + ".lrc")))
					.sorted(String.CASE_INSENSITIVE_ORDER)
					.forEach(names::add);
		} catch (IOException e) {
			// Folder missing or unreadable: just report no songs.
		}
		return names;
	}

	/**
	 * Lists the songs that have an .ogg and a .txt file (plain lyrics), which
	 * can be timed with /lyrics record.
	 */
	public static List<String> listRecordable() {
		List<String> names = new ArrayList<>();
		try (Stream<Path> files = Files.list(folder())) {
			files.map(path -> path.getFileName().toString())
					.filter(file -> file.endsWith(".txt") && !file.equals("README.txt"))
					.map(file -> file.substring(0, file.length() - ".txt".length()))
					.filter(name -> SAFE_NAME.matcher(name).matches())
					.filter(name -> Files.isRegularFile(folder().resolve(name + ".ogg")))
					.sorted(String.CASE_INSENSITIVE_ORDER)
					.forEach(names::add);
		} catch (IOException e) {
			// Folder missing or unreadable: just report no songs.
		}
		return names;
	}

	/**
	 * Returns the path of "name.extension" inside our folder, after checking
	 * that it is safe and exists.
	 *
	 * @throws IOException with a player-friendly message if anything is wrong
	 */
	public static Path songFile(String name, String extension) throws IOException {
		if (!SAFE_NAME.matcher(name).matches() || name.contains("..") || name.startsWith(".")) {
			throw new IOException("Invalid song name \"" + name + "\". Use only letters, numbers, spaces, _ and -.");
		}

		Path folder = folder().toAbsolutePath().normalize();
		Path file = folder.resolve(name + extension).normalize();

		// Belt and braces: the file's folder must be exactly our folder.
		if (!folder.equals(file.getParent())) {
			throw new IOException("Songs must be inside " + folder);
		}
		if (!Files.isRegularFile(file)) {
			throw new IOException("Missing file: config/lyricmod/" + name + extension);
		}
		// Also block "shortcuts" (symbolic links) that point somewhere else.
		if (!file.toRealPath().getParent().equals(folder.toRealPath())) {
			throw new IOException("Songs must be real files inside config/lyricmod");
		}
		return file;
	}

	/** Reads and parses name.lrc. */
	public static Lyrics loadLyrics(String name) throws IOException {
		Path file = songFile(name, ".lrc");
		if (Files.size(file) > MAX_LRC_BYTES) {
			throw new IOException(name + ".lrc is too big (max 1 MB)");
		}
		// new String(...) instead of Files.readString: a file that isn't valid UTF-8
		// then shows a few odd characters instead of failing completely.
		String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
		return LrcParser.parse(content, name);
	}
}
