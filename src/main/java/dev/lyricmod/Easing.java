package dev.lyricmod;

/**
 * Easing curves: ways to turn a straight "0 → 1" progress into a nicer motion.
 *
 * Without easing, an animation moves at a constant speed and starts/stops
 * abruptly, which looks robotic. With easing it can start fast and slow down
 * at the end (like something sliding to a stop), or overshoot a little and
 * settle (like a bounce).
 *
 * Every function takes t from 0 (start) to 1 (end) and returns 0 at t=0 and
 * 1 at t=1. See https://easings.net for pictures of each curve.
 */
public final class Easing {

	private Easing() {
	}

	/** Keeps t between 0 and 1, so animations never go past their start or end. */
	public static float clamp01(float t) {
		return Math.clamp(t, 0f, 1f);
	}

	/** Fast at first, then slows down gently. Good for things appearing. */
	public static float outCubic(float t) {
		float inverse = 1 - clamp01(t);
		return 1 - inverse * inverse * inverse;
	}

	/** Slow at first, then speeds up. Good for things leaving. */
	public static float inCubic(float t) {
		float c = clamp01(t);
		return c * c * c;
	}

	/** Slow start, fast middle, slow end. Good for back-and-forth motion. */
	public static float inOutSine(float t) {
		return (float) (-(Math.cos(Math.PI * clamp01(t)) - 1) / 2);
	}

	/** Goes a little PAST 1 and then settles back: a small "pop" or bounce. */
	public static float outBack(float t) {
		float c = clamp01(t) - 1;
		final float overshoot = 1.70158f;
		return 1 + (overshoot + 1) * c * c * c + overshoot * c * c;
	}

	/** Mixes from a to b: lerp(a, b, 0) = a, lerp(a, b, 1) = b. */
	public static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}
}
