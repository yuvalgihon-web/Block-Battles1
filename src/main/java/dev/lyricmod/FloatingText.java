package dev.lyricmod;

import net.minecraft.world.phys.Vec3;

/**
 * One line of text placed somewhere in the world.
 *
 * This class only holds data ("what, where, since when"); it does not draw
 * anything itself. {@link LyricRenderer} reads it every frame and draws it.
 *
 * A Java "record" is a short way to write a class whose fields never change:
 * Java generates the constructor and the getters (text(), position(), ...).
 *
 * Design note for later steps: in step 3 a line's start time will come from
 * the .lrc timestamp (in song time) instead of the system clock, and in
 * step 4 the position will be computed by an animation instead of fixed.
 *
 * @param text        the words to show
 * @param position    the point in the world (x, y, z) where the text's center sits
 * @param startTimeMs when the line appeared, in milliseconds (see LyricRenderer#nowMs)
 */
public record FloatingText(String text, Vec3 position, long startTimeMs) {

	/** How long the fade-in takes. */
	public static final long FADE_IN_MS = 1000;

	/** How long the line stays fully visible after fading in. */
	public static final long HOLD_MS = 4000;

	/** How long the fade-out takes before the line disappears. */
	public static final long FADE_OUT_MS = 1000;

	/** Total lifetime of the line, after which it is removed. */
	public static final long LIFETIME_MS = FADE_IN_MS + HOLD_MS + FADE_OUT_MS;

	/**
	 * Returns how visible the line is at time {@code nowMs}:
	 * 0.0 = invisible, 1.0 = fully visible.
	 *
	 *   opacity
	 *   1.0 |     ____________
	 *       |    /            \
	 *   0.0 |___/              \___
	 *          0s  1s        5s  6s   (time since start)
	 */
	public float opacity(long nowMs) {
		long age = nowMs - startTimeMs;

		if (age < 0 || age >= LIFETIME_MS) {
			return 0f;
		}
		if (age < FADE_IN_MS) {
			// Rising from 0 to 1 during the first second.
			return (float) age / FADE_IN_MS;
		}
		long fadeOutStart = FADE_IN_MS + HOLD_MS;
		if (age >= fadeOutStart) {
			// Falling from 1 to 0 during the last second.
			return 1f - (float) (age - fadeOutStart) / FADE_OUT_MS;
		}
		return 1f;
	}

	/** True once the line has fully faded out and can be thrown away. */
	public boolean isFinished(long nowMs) {
		return nowMs - startTimeMs >= LIFETIME_MS;
	}
}
