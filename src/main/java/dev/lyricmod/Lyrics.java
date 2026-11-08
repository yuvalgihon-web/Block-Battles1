package dev.lyricmod;

import java.util.List;

/**
 * Everything we know about a song's lyrics after reading its .lrc file.
 *
 * This is plain data, with no logic. {@link LrcParser} creates it, and the
 * renderer (step 3) will read it to decide what to show and when.
 *
 * @param title  song title from the [ti:...] tag, or the file name if missing
 * @param artist artist from the [ar:...] tag, or "" if missing
 * @param lines  every lyric line, sorted by time
 */
public record Lyrics(String title, String artist, List<Line> lines) {

	/**
	 * One line of lyrics.
	 *
	 * @param timeMs when the line starts, in milliseconds from the start of the song
	 * @param text   the whole line as plain text ("" for a pause between verses)
	 * @param words  per-word timings, only if the file has them (enhanced LRC);
	 *               otherwise an empty list and step 3 will space words evenly
	 */
	public record Line(long timeMs, String text, List<Word> words) {
	}

	/**
	 * One word with its own start time (from enhanced LRC: {@code <00:12.50>word}).
	 */
	public record Word(long timeMs, String text) {
	}
}
