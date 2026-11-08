package dev.lyricmod;

/**
 * The player's choices for how lyrics are shown.
 * (Step 5 will load and save these in a config file.)
 */
public final class LyricSettings {

	/** How lyrics are shown: /lyrics style <scatter|line>. */
	public static LyricStyle style = LyricStyle.SCATTER;

	/**
	 * Timing correction in milliseconds: /lyrics offset <ms>.
	 * Positive = lyrics appear EARLIER (use it if they come late), negative = later.
	 * Useful with Bluetooth headphones or when Spotify's reported time lags behind.
	 */
	public static long offsetMs = 0;

	private LyricSettings() {
	}
}
