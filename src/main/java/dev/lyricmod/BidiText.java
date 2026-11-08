package dev.lyricmod;

import java.util.List;

import com.ibm.icu.text.Bidi;

/**
 * Helpers for right-to-left languages like Hebrew and Arabic ("bidi" = bidirectional text).
 *
 * Minecraft already writes the letters of a Hebrew word in the right order.
 * But we draw a lyric line one WORD at a time, placing the words ourselves,
 * so we must also put the words in the right order: in a Hebrew line the
 * first word is on the RIGHT. Lines that mix Hebrew and English work too
 * (an English name inside a Hebrew line stays left-to-right).
 *
 * We use ICU, the same text library Minecraft uses for this.
 */
public final class BidiText {

	private BidiText() {
	}

	/** True if the text "reads" right-to-left, judged by its first strong letter. */
	public static boolean isRightToLeft(String text) {
		return Bidi.getBaseDirection(text) == Bidi.RTL;
	}

	/**
	 * Works out the order to place words from LEFT to RIGHT on screen.
	 *
	 * @return visual order: result[0] is the index of the word to draw leftmost,
	 *         result[1] the next one to its right, and so on
	 */
	public static int[] visualWordOrder(List<String> words, boolean rightToLeft) {
		int[] order = new int[words.size()];
		if (words.isEmpty()) {
			return order;
		}

		// Run the bidi algorithm on the whole line, then ask what "level"
		// (direction) each word got: even = left-to-right, odd = right-to-left.
		String line = String.join(" ", words);
		Bidi bidi = new Bidi(line, rightToLeft ? Bidi.RTL : Bidi.LTR);
		byte[] levels = new byte[words.size()];
		int position = 0;
		for (int i = 0; i < words.size(); i++) {
			levels[i] = bidi.getLevelAt(position);
			position += words.get(i).length() + 1; // +1 for the space
		}

		// ICU turns the levels into the left-to-right drawing order.
		return Bidi.reorderVisual(levels);
	}
}
