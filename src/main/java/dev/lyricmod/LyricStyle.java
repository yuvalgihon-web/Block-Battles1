package dev.lyricmod;

/**
 * How lyrics are shown. Switch with /lyrics style <name>.
 * (Step 5 will remember the choice in a config file.)
 */
public enum LyricStyle {
	/** Each line as a straight row of words in front of you (step 3). */
	LINE,
	/** Words scattered around you, tilted, drifting and orbiting (step 4, like the TikTok video). */
	SCATTER;

	/** The style currently in use. */
	public static LyricStyle current = SCATTER;
}
