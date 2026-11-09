package dev.lyricmod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * All settings, stored in config/lyricmod/config.json.
 *
 * The file is created with the default values the first time the game starts.
 * You can edit it with any text editor and then type /lyrics reload in game.
 * The /lyrics style and /lyrics offset commands also save their changes here.
 *
 * How it works: Gson (a JSON library that comes with Minecraft) turns the
 * file's text into a LyricConfig object and back. Every field below becomes
 * one setting in the file, with the value written here as its default.
 * After loading, {@link #validate} fixes values that are out of range, so a
 * typo can't make the text invisible or huge.
 */
public final class LyricConfig {

	// ===== Settings (these names are the names in config.json) =====

	/** Which version of the mod wrote this file; used to update old default values. */
	public int configVersion = 0;

	/** "scatter" (words float around you) or "line" (a straight row in front of you). */
	public String style = "scatter";

	/** Timing correction in ms: positive = lyrics earlier, negative = later. */
	public int offsetMs = 0;

	/** Text color, as #RRGGBB. */
	public String color = "#FFFFFF";

	/** If set, each line fades from {@link #color} (first word) to this color (last word). "" = off. */
	public String gradientColor = "#D7B8FF";

	/** "smooth" (the mod's bold italic font) or "minecraft" (the normal blocky font). */
	public String font = "smooth";

	/** true = lyrics show through blocks (like an overlay); false = blocks in front hide them. */
	public boolean alwaysVisible = true;

	/** Dark shadow behind the letters, makes them easier to read. */
	public boolean shadow = true;

	/** Size multiplier for all lyric text (1 = normal). */
	public float textSize = 1.0f;

	/** Settings for the "line" style. */
	public LineSettings line = new LineSettings();

	/** Settings for the "scatter" style. */
	public ScatterSettings scatter = new ScatterSettings();

	public static final class LineSettings {
		/** Distance in front of you, in blocks. */
		public float distance = 4.0f;
		/** Height above your eyes, in blocks. */
		public float height = 0.8f;
		/** How long one word takes to fade in, in ms. */
		public int wordFadeInMs = 250;
		/** How long a finished line takes to fade out, in ms. */
		public int lineFadeOutMs = 400;
	}

	public static final class ScatterSettings {
		/** Average distance of words from you, and how much it varies (+/-), in blocks. */
		public float distance = 4.5f;
		public float distanceVariation = 0.8f;
		/** Average height above your eyes, and how much it varies (+/-), in blocks. */
		public float height = 0.7f;
		public float heightVariation = 0.9f;
		/** Average word size, and how much it varies (+/-). */
		public float wordSize = 1.5f;
		public float sizeVariation = 0.5f;
		/** Widest angle (left to right) one row of words may cover, in degrees; longer lines wrap into rows. */
		public float spreadDegrees = 70f;
		/** Largest tilt of a word to either side, in degrees (0 = no tilt). */
		public float tiltDegrees = 12f;
		/** How fast lines circle around you, in degrees per second (0 = off, negative = other way). */
		public float orbitDegreesPerSecond = 3f;
		/** How fast words float upward, in blocks per second (0 = off). */
		public float driftUpPerSecond = 0.08f;
		/** Words grow in with a small bounce when they appear. */
		public boolean popIn = true;
		/** How long a word takes to appear, and a finished line to leave, in ms. */
		public int appearMs = 400;
		public int leaveMs = 600;
		/** Brightness of words already sung, compared to the newest word (0 to 1). */
		public float sungWordOpacity = 0.75f;
	}

	// ===== Values worked out from the settings (not saved in the file) =====

	/** {@link #style}, {@link #color} and {@link #gradientColor} as numbers/enum. */
	public transient LyricStyle styleValue = LyricStyle.SCATTER;
	public transient boolean smoothFont = true;
	public transient int colorRgb = 0xFFFFFF;
	public transient int gradientRgb = -1; // -1 = no gradient

	// ===== Loading and saving =====

	/** The version written into new files (increase it when default values change). */
	private static final int CURRENT_VERSION = 2;

	/** The settings in use right now. */
	public static LyricConfig current = new LyricConfig();

	/** Set when config.json couldn't be read; then we never overwrite it (to keep your edits). */
	private static boolean fileIsBroken;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** True if config.json couldn't be read the last time we tried. */
	public static boolean fileIsBroken() {
		return fileIsBroken;
	}

	public static Path file() {
		return LyricFiles.folder().resolve("config.json");
	}

	/**
	 * Loads config.json (creating it with defaults if it doesn't exist).
	 *
	 * @return messages for the player: problems found, or empty if all was fine
	 */
	public static List<String> load() {
		List<String> messages = new ArrayList<>();
		Path file = file();
		if (!Files.exists(file)) {
			current = new LyricConfig();
			current.configVersion = CURRENT_VERSION;
			current.validate(messages);
			fileIsBroken = false;
			save();
			return messages;
		}
		try {
			String text = Files.readString(file, StandardCharsets.UTF_8);
			LyricConfig loaded = GSON.fromJson(text, LyricConfig.class);
			if (loaded == null) {
				throw new JsonParseException("the file is empty");
			}
			loaded.upgradeOldDefaults();
			loaded.validate(messages);
			current = loaded;
			fileIsBroken = false;
			save(); // writes back fixed values and any settings that were missing
		} catch (IOException | JsonParseException | IllegalStateException e) {
			fileIsBroken = true;
			messages.add("config.json has an error, keeping the previous settings: " + SpotifyAuth.rootMessage(e));
		}
		return messages;
	}

	/**
	 * Saves the current settings to config.json.
	 *
	 * @return false if not saved (the file has an error you should fix first)
	 */
	public static boolean save() {
		if (fileIsBroken) {
			return false; // don't overwrite a file the player is in the middle of editing
		}
		try {
			Files.createDirectories(LyricFiles.folder());
			Files.writeString(file(), GSON.toJson(current), StandardCharsets.UTF_8);
			return true;
		} catch (IOException e) {
			LyricModClient.LOGGER.warn("Could not save config.json", e);
			return false;
		}
	}

	/**
	 * Files written by older versions contain the OLD default values. If the
	 * player never changed a value, we switch it to the new default; values the
	 * player changed are kept.
	 */
	private void upgradeOldDefaults() {
		if (configVersion < 2 && scatter != null) {
			// Version 2: words closer (they were hidden by nearby blocks) and a bit smaller.
			if (scatter.distance == 5.5f && scatter.distanceVariation == 1.0f) {
				scatter.distance = 4.5f;
				scatter.distanceVariation = 0.8f;
			}
			if (scatter.wordSize == 1.8f) {
				scatter.wordSize = 1.5f;
			}
		}
		configVersion = CURRENT_VERSION;
	}

	/** Puts every value in a safe range and works out the "transient" values. */
	void validate(List<String> messages) {
		if (line == null) {
			line = new LineSettings();
		}
		if (scatter == null) {
			scatter = new ScatterSettings();
		}

		try {
			styleValue = LyricStyle.valueOf(String.valueOf(style).toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			messages.add("style \"" + style + "\" is unknown, using \"scatter\"");
			styleValue = LyricStyle.SCATTER;
		}
		style = styleValue.name().toLowerCase(Locale.ROOT);

		if ("smooth".equalsIgnoreCase(font) || "minecraft".equalsIgnoreCase(font)) {
			font = font.toLowerCase(Locale.ROOT);
		} else {
			messages.add("font \"" + font + "\" is unknown (use \"smooth\" or \"minecraft\"), using \"smooth\"");
			font = "smooth";
		}
		smoothFont = font.equals("smooth");

		Integer rgb = parseColor(color);
		if (rgb == null) {
			messages.add("color \"" + color + "\" is not like #RRGGBB, using white");
			rgb = 0xFFFFFF;
			color = "#FFFFFF";
		}
		colorRgb = rgb;
		if (gradientColor == null || gradientColor.isBlank()) {
			gradientColor = "";
			gradientRgb = -1;
		} else {
			Integer gradient = parseColor(gradientColor);
			if (gradient == null) {
				messages.add("gradientColor \"" + gradientColor + "\" is not like #RRGGBB, gradient turned off");
				gradientColor = "";
				gradientRgb = -1;
			} else {
				gradientRgb = gradient;
			}
		}

		offsetMs = clamp(messages, "offsetMs", offsetMs, -5000, 5000);
		textSize = clamp(messages, "textSize", textSize, 0.2f, 5f);

		line.distance = clamp(messages, "line.distance", line.distance, 1f, 32f);
		line.height = clamp(messages, "line.height", line.height, -5f, 10f);
		line.wordFadeInMs = clamp(messages, "line.wordFadeInMs", line.wordFadeInMs, 0, 5000);
		line.lineFadeOutMs = clamp(messages, "line.lineFadeOutMs", line.lineFadeOutMs, 0, 5000);

		ScatterSettings s = scatter;
		s.distance = clamp(messages, "scatter.distance", s.distance, 1.5f, 32f);
		s.distanceVariation = clamp(messages, "scatter.distanceVariation", s.distanceVariation, 0f, s.distance - 1f);
		s.height = clamp(messages, "scatter.height", s.height, -5f, 10f);
		s.heightVariation = clamp(messages, "scatter.heightVariation", s.heightVariation, 0f, 10f);
		s.wordSize = clamp(messages, "scatter.wordSize", s.wordSize, 0.2f, 10f);
		s.sizeVariation = clamp(messages, "scatter.sizeVariation", s.sizeVariation, 0f, s.wordSize - 0.1f);
		s.spreadDegrees = clamp(messages, "scatter.spreadDegrees", s.spreadDegrees, 0f, 340f);
		s.tiltDegrees = clamp(messages, "scatter.tiltDegrees", s.tiltDegrees, 0f, 90f);
		s.orbitDegreesPerSecond = clamp(messages, "scatter.orbitDegreesPerSecond", s.orbitDegreesPerSecond, -90f, 90f);
		s.driftUpPerSecond = clamp(messages, "scatter.driftUpPerSecond", s.driftUpPerSecond, -2f, 2f);
		s.appearMs = clamp(messages, "scatter.appearMs", s.appearMs, 0, 5000);
		s.leaveMs = clamp(messages, "scatter.leaveMs", s.leaveMs, 0, 5000);
		s.sungWordOpacity = clamp(messages, "scatter.sungWordOpacity", s.sungWordOpacity, 0f, 1f);
	}

	/**
	 * The color of word number {@code index} (in reading order) of a line with
	 * {@code count} words: {@link #color} for the first word, fading to
	 * {@link #gradientColor} for the last one.
	 */
	public int wordColor(int index, int count) {
		if (gradientRgb < 0 || count <= 1) {
			return colorRgb;
		}
		float t = (float) index / (count - 1);
		int red = Math.round(Easing.lerp(colorRgb >> 16 & 0xFF, gradientRgb >> 16 & 0xFF, t));
		int green = Math.round(Easing.lerp(colorRgb >> 8 & 0xFF, gradientRgb >> 8 & 0xFF, t));
		int blue = Math.round(Easing.lerp(colorRgb & 0xFF, gradientRgb & 0xFF, t));
		return red << 16 | green << 8 | blue;
	}

	// ----- helpers -----

	/** "#C8A2FF" or "C8A2FF" -> 0xC8A2FF; null if it isn't a color. */
	static Integer parseColor(String text) {
		if (text == null) {
			return null;
		}
		String hex = text.strip().startsWith("#") ? text.strip().substring(1) : text.strip();
		if (!hex.matches("[0-9a-fA-F]{6}")) {
			return null;
		}
		return Integer.parseInt(hex, 16);
	}

	private static float clamp(List<String> messages, String name, float value, float min, float max) {
		if (Float.isNaN(value) || value < min || value > max) {
			float fixed = Float.isNaN(value) ? min : Math.clamp(value, min, max);
			messages.add(name + " = " + value + " is out of range (" + min + " to " + max + "), using " + fixed);
			return fixed;
		}
		return value;
	}

	private static int clamp(List<String> messages, String name, int value, int min, int max) {
		if (value < min || value > max) {
			int fixed = Math.clamp(value, min, max);
			messages.add(name + " = " + value + " is out of range (" + min + " to " + max + "), using " + fixed);
			return fixed;
		}
		return value;
	}
}
