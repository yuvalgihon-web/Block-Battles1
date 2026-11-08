package dev.lyricmod;

import java.util.ArrayList;
import java.util.List;

/**
 * Answers timing questions about a song's lyrics, like
 * "which line is being sung at 12.3 seconds?" and "when does each word start?".
 *
 * This class only does math: it doesn't draw anything and doesn't know about
 * Minecraft. That makes it easy to test and to reuse later (step 4 animations,
 * and the Spotify mode, which gets its time from Spotify instead of an .ogg).
 */
public final class LyricTimeline {

	/** How long the very last line stays if no line comes after it. */
	private static final long LAST_LINE_MS = 5000;

	/**
	 * Without per-word times, words are spread over this share of the line's
	 * duration, so the whole line is visible a while before the next one.
	 */
	private static final double EVEN_SPREAD_SHARE = 0.6;

	/** Never spread words over more than this many ms (for very long gaps). */
	private static final long MAX_SPREAD_MS = 4000;

	/** One word and the moment it should appear. */
	public record TimedWord(String text, long startMs) {
	}

	private LyricTimeline() {
	}

	/**
	 * Index of the line being sung at {@code positionMs}, or -1 before the first line.
	 * Lines are sorted by time, so it's the LAST line that has already started.
	 */
	public static int lineAt(Lyrics lyrics, long positionMs) {
		List<Lyrics.Line> lines = lyrics.lines();
		int found = -1;
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).timeMs() <= positionMs) {
				found = i;
			} else {
				break; // sorted: every later line starts even later
			}
		}
		return found;
	}

	/** When line {@code index} ends: when the next line starts (or a few seconds after the last one). */
	public static long lineEndMs(Lyrics lyrics, int index) {
		List<Lyrics.Line> lines = lyrics.lines();
		if (index + 1 < lines.size()) {
			return lines.get(index + 1).timeMs();
		}
		return lines.get(index).timeMs() + LAST_LINE_MS;
	}

	/**
	 * The words of line {@code index}, each with its start time.
	 *
	 * If the .lrc file had per-word times we use those. Otherwise we split the
	 * text at spaces and spread the words evenly, which still looks like
	 * "word by word" singing.
	 */
	public static List<TimedWord> words(Lyrics lyrics, int index) {
		Lyrics.Line line = lyrics.lines().get(index);
		List<TimedWord> result = new ArrayList<>();

		if (!line.words().isEmpty()) {
			for (Lyrics.Word word : line.words()) {
				result.add(new TimedWord(word.text(), word.timeMs()));
			}
			return result;
		}

		String[] parts = line.text().isBlank() ? new String[0] : line.text().split(" ");
		long duration = lineEndMs(lyrics, index) - line.timeMs();
		long spread = Math.min((long) (duration * EVEN_SPREAD_SHARE), MAX_SPREAD_MS);
		for (int i = 0; i < parts.length; i++) {
			long start = line.timeMs() + spread * i / parts.length;
			result.add(new TimedWord(parts[i], start));
		}
		return result;
	}
}
