package dev.lyricmod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the text of a .lrc lyrics file and turns it into a {@link Lyrics} object.
 *
 * An .lrc file is plain text. Each lyric line starts with a time in square brackets:
 *
 *   [ti:Song title]
 *   [ar:Artist]
 *   [00:12.50]First line of the song
 *   [00:17.20]Second line
 *
 * "Enhanced" .lrc files can also time each word with angle brackets:
 *
 *   [00:12.50]<00:12.50>First <00:13.10>line <00:13.40>of ...
 *
 * We support both. Lines we don't understand are skipped instead of causing errors,
 * because .lrc files from the internet are often a bit messy.
 */
public final class LrcParser {

	/** A time like 01:23.45 or 1:23 or 01:23.456 (minutes:seconds.fraction). */
	private static final String TIME = "(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?";

	/** One [....] tag at the start of a line. Group 1 is what is inside. */
	private static final Pattern TAG = Pattern.compile("^\\[([^\\]]*)\\]");

	/** A whole time tag, used to check what is inside [...]. */
	private static final Pattern TIME_ONLY = Pattern.compile("^" + TIME + "$");

	/** A word time like <00:12.50> inside the lyric text. */
	private static final Pattern WORD_TIME = Pattern.compile("<" + TIME + ">");

	private LrcParser() {
	}

	/**
	 * Parses the full text of an .lrc file.
	 *
	 * @param content      the file's text
	 * @param fallbackName used as the title if the file has no [ti:] tag
	 */
	public static Lyrics parse(String content, String fallbackName) {
		String title = fallbackName;
		String artist = "";
		long offsetMs = 0;
		List<Lyrics.Line> lines = new ArrayList<>();

		// Some editors put an invisible "byte order mark" at the very start of the file.
		if (content.startsWith("﻿")) {
			content = content.substring(1);
		}

		for (String rawLine : content.split("\\R")) { // \R = any kind of line break
			String rest = rawLine.strip();
			List<Long> times = new ArrayList<>();

			// Read all [..] tags at the start. A line may have several times,
			// e.g. "[00:10.00][01:30.00]Chorus line" (the same text twice).
			Matcher tag = TAG.matcher(rest);
			while (tag.find()) {
				String inside = tag.group(1).strip();
				Matcher time = TIME_ONLY.matcher(inside);
				if (time.matches()) {
					times.add(toMillis(time));
				} else {
					// A "metadata" tag like [ti:Title] or [offset:+200].
					int colon = inside.indexOf(':');
					if (colon > 0) {
						String key = inside.substring(0, colon).strip().toLowerCase();
						String value = inside.substring(colon + 1).strip();
						switch (key) {
							case "ti" -> title = value.isEmpty() ? title : value;
							case "ar" -> artist = value;
							case "offset" -> offsetMs = parseOffset(value);
							default -> { } // [al:], [by:], [length:] ... not needed
						}
					}
				}
				rest = rest.substring(tag.end());
				tag = TAG.matcher(rest);
			}

			if (times.isEmpty()) {
				continue; // Not a lyric line (metadata, comment, or empty).
			}

			// Split out per-word times, if the line has any.
			List<Lyrics.Word> words = parseWords(rest);
			String text = WORD_TIME.matcher(rest).replaceAll("").strip().replaceAll("\\s+", " ");

			for (long time : times) {
				lines.add(new Lyrics.Line(time, text, shiftWords(words, time - times.get(0))));
			}
		}

		// The [offset:] tag means "show lyrics this many ms EARLIER" (if positive).
		if (offsetMs != 0) {
			List<Lyrics.Line> shifted = new ArrayList<>();
			for (Lyrics.Line line : lines) {
				shifted.add(new Lyrics.Line(Math.max(0, line.timeMs() - offsetMs), line.text(),
						shiftWords(line.words(), -offsetMs)));
			}
			lines = shifted;
		}

		lines.sort(Comparator.comparingLong(Lyrics.Line::timeMs));
		return new Lyrics(title, artist, List.copyOf(lines));
	}

	/** Turns "<00:01.00>Twinkle, <00:02.00>twinkle" into two timed words. */
	private static List<Lyrics.Word> parseWords(String text) {
		List<Lyrics.Word> words = new ArrayList<>();
		Matcher m = WORD_TIME.matcher(text);
		long previousTime = -1;
		int previousEnd = -1;
		while (m.find()) {
			if (previousTime >= 0) {
				addWord(words, previousTime, text.substring(previousEnd, m.start()));
			}
			previousTime = toMillis(m);
			previousEnd = m.end();
		}
		if (previousTime >= 0) {
			addWord(words, previousTime, text.substring(previousEnd));
		}
		return words;
	}

	private static void addWord(List<Lyrics.Word> words, long timeMs, String text) {
		String clean = text.strip();
		if (!clean.isEmpty()) {
			words.add(new Lyrics.Word(timeMs, clean));
		}
	}

	/** Moves every word by deltaMs (used for repeated lines and the offset tag). */
	private static List<Lyrics.Word> shiftWords(List<Lyrics.Word> words, long deltaMs) {
		List<Lyrics.Word> result = new ArrayList<>(words.size());
		for (Lyrics.Word w : words) {
			result.add(new Lyrics.Word(Math.max(0, w.timeMs() + deltaMs), w.text()));
		}
		return List.copyOf(result);
	}

	/** Converts a matched TIME (minutes, seconds, fraction) to milliseconds. */
	private static long toMillis(Matcher m) {
		long minutes = Long.parseLong(m.group(1));
		long seconds = Long.parseLong(m.group(2));
		long millis = 0;
		String fraction = m.group(3);
		if (fraction != null) {
			// ".5" = 500 ms, ".50" = 500 ms, ".500" = 500 ms: pad to 3 digits.
			millis = Long.parseLong((fraction + "00").substring(0, 3));
		}
		return minutes * 60_000 + seconds * 1000 + millis;
	}

	private static long parseOffset(String value) {
		try {
			return Long.parseLong(value.replace("+", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
