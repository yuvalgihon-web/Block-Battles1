package dev.lyricmod;

/**
 * How lyrics are shown. Switch with /lyrics style <name>;
 * the current choice is in {@link LyricConfig#styleValue}.
 */
public enum LyricStyle {
	/** Each line as a straight row of words in front of you (step 3). */
	LINE,
	/** Words scattered around you, tilted, drifting and orbiting (step 4, like the TikTok video). */
	SCATTER
}
