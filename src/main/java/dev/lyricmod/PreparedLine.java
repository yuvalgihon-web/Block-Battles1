package dev.lyricmod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.util.FormattedCharSequence;

/**
 * Everything about one lyric line that never changes while it's on screen:
 * its words and their start times, the reading direction, the left-to-right
 * order of the words, and each word already converted to "drawable" text
 * with its width.
 *
 * Working these out costs a little time (especially for right-to-left text).
 * Doing it 60+ times per second for every word would be wasteful, so we do it
 * ONCE per line and keep the result here. This is called caching.
 */
public final class PreparedLine {

	public final List<LyricTimeline.TimedWord> words;
	public final List<String> texts;
	public final boolean rightToLeft;
	/** leftToRight[0] = index of the word drawn leftmost, and so on. */
	public final int[] leftToRight;
	/** Each word ready to draw, and its width in font pixels. */
	public final FormattedCharSequence[] drawable;
	public final float[] widths;

	private PreparedLine(Lyrics lyrics, int index) {
		words = LyricTimeline.words(lyrics, index);
		texts = words.stream().map(LyricTimeline.TimedWord::text).toList();
		rightToLeft = BidiText.isRightToLeft(String.join(" ", texts));
		leftToRight = BidiText.visualWordOrder(texts, rightToLeft);
		drawable = new FormattedCharSequence[texts.size()];
		widths = new float[texts.size()];
		for (int i = 0; i < texts.size(); i++) {
			drawable[i] = LyricRenderer.prepare(texts.get(i), rightToLeft);
			widths[i] = LyricRenderer.textWidth(drawable[i]);
		}
	}

	// ----- The cache: prepared lines of the song currently shown -----

	private static Lyrics cachedLyrics;
	private static final Map<Integer, PreparedLine> CACHE = new HashMap<>();

	/** Returns line {@code index} of {@code lyrics}, preparing it the first time. */
	public static PreparedLine get(Lyrics lyrics, int index) {
		if (lyrics != cachedLyrics) {
			// A different song (or the recorder added a line): start a fresh cache.
			cachedLyrics = lyrics;
			CACHE.clear();
		}
		return CACHE.computeIfAbsent(index, i -> new PreparedLine(lyrics, i));
	}
}
