package dev.lyricmod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The "scatter" lyric style: every word floats on its own around the player.
 *
 * When a line starts we remember which way the player was looking, and give
 * each of its words a spot on an arc around that direction: a bit left or
 * right (in reading order), nearer or farther, higher or lower, bigger or
 * smaller, and slightly tilted. Then, every frame, each word:
 *  - pops in with a small bounce when it is sung (easing "outBack"),
 *  - drifts slowly upward,
 *  - orbits slowly around the player together with its line,
 *  - and when the line ends, floats up, shrinks and fades away.
 *
 * Positions are measured from the player's eyes every frame, so the words
 * travel with you when you walk, and you can turn your head to look at them.
 * Everything is computed from the song time, so it freezes when the music pauses.
 */
public final class ScatterAnimation {

	// Most numbers come from the "scatter" part of config.json (see LyricConfig.ScatterSettings).

	/** Degrees of arc per word, and the smallest arc a line uses (the largest is "spreadDegrees"). */
	private static final float ARC_PER_WORD = 13f;
	private static final float MIN_ARC = 20f;
	/** Random wiggle added to each word's angle, in degrees. */
	private static final float ANGLE_JITTER = 4f;
	/** Extra rise while leaving, in blocks. */
	private static final float LEAVE_RISE = 0.6f;
	/** For very long lines, center the orbit on this much time only. */
	private static final long MAX_ORBIT_MS = 8000;

	/** Where and how one word of a line is placed (chosen once per line). */
	private record WordSpot(float angleOffset, float distance, float height, float size, float tilt) {
	}

	/** One line's layout: the direction it was centered on, and its words' spots. */
	private record LineLayout(float centerYaw, List<WordSpot> spots) {
	}

	private static Lyrics shownLyrics;
	private static final Map<Integer, LineLayout> layouts = new HashMap<>();

	private ScatterAnimation() {
	}

	/** Forgets all layouts, e.g. after the settings changed (/lyrics reload). */
	static void reset() {
		shownLyrics = null;
		layouts.clear();
	}

	/** Called every frame by SyncedLyricsDisplay when the SCATTER style is selected. */
	static void render(LevelRenderContext context, Lyrics lyrics, long positionMs) {
		if (lyrics != shownLyrics) {
			shownLyrics = lyrics;
			layouts.clear();
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (lyrics == null || player == null) {
			return;
		}

		int current = LyricTimeline.lineAt(lyrics, positionMs);
		if (current < 0) {
			return;
		}
		// Draw the current line and the one before it (it may still be leaving).
		for (int line = Math.max(0, current - 1); line <= current; line++) {
			drawLine(context, lyrics, line, positionMs, player);
		}
		// Forget layouts of old lines (keeps memory small in long songs).
		layouts.keySet().removeIf(line -> line < current - 1);
	}

	private static void drawLine(LevelRenderContext context, Lyrics lyrics, int line, long positionMs,
			LocalPlayer player) {
		LyricConfig.ScatterSettings settings = LyricConfig.current.scatter;
		long lineStart = lyrics.lines().get(line).timeMs();
		long lineEnd = LyricTimeline.lineEndMs(lyrics, line);
		if (positionMs > lineEnd + settings.leaveMs) {
			return;
		}
		// Words, direction and drawable text are worked out once per line (see PreparedLine).
		PreparedLine prepared = PreparedLine.get(lyrics, line);
		List<LyricTimeline.TimedWord> words = prepared.words;
		if (words.isEmpty()) {
			return;
		}
		LineLayout layout = layouts.computeIfAbsent(line,
				index -> createLayout(lyrics, index, prepared, player.getYRot()));

		// Leaving: 0 while the line is sung, rising to 1 during "leaveMs" after it ends.
		float leave = Easing.inCubic(SyncedLyricsDisplay.progress(positionMs - lineEnd, settings.leaveMs));
		// Orbit: the whole line turns slowly around the player. We count from the
		// MIDDLE of the line's time, so it starts a bit to one side and passes through
		// the center, instead of drifting off-screen during long lines.
		long middle = lineStart + Math.min(lineEnd - lineStart, MAX_ORBIT_MS) / 2;
		float orbit = settings.orbitDegreesPerSecond * (positionMs - middle) / 1000f;
		// Index of the newest word that has started (the one being sung).
		int newest = -1;
		for (int i = 0; i < words.size(); i++) {
			if (words.get(i).startMs() <= positionMs) {
				newest = i;
			}
		}

		// The player's eyes EXACTLY where the camera shows them in this frame.
		// (getEyePosition() without the "partial tick" only changes 20 times per
		// second, which made the words stutter while walking.)
		float partialTick = context.levelState().cameraRenderState.cameraEntityPartialTicks;
		Vec3 eyes = player.getEyePosition(partialTick);

		for (int i = 0; i < words.size(); i++) {
			long age = positionMs - words.get(i).startMs();
			if (age < 0) {
				continue; // not sung yet
			}
			WordSpot spot = layout.spots().get(i);
			float appear = SyncedLyricsDisplay.progress(age, settings.appearMs);

			float opacity = Easing.outCubic(appear) * (1 - leave);
			if (i != newest) {
				opacity *= settings.sungWordOpacity;
			}
			// Pop: start at 50% size, overshoot a little, settle at 100%; shrink while leaving.
			float pop = settings.popIn ? Easing.lerp(0.5f, 1f, Easing.outBack(appear)) : 1f;
			float scale = spot.size() * pop * (1 - 0.3f * leave);

			float height = spot.height()
					+ settings.driftUpPerSecond * age / 1000f
					+ LEAVE_RISE * leave;
			double angle = Math.toRadians(layout.centerYaw() + spot.angleOffset() + orbit);
			// Minecraft's yaw: 0 = looking toward +Z (south), 90 = toward -X (west).
			Vec3 position = eyes.add(
					-Math.sin(angle) * spot.distance(),
					height,
					Math.cos(angle) * spot.distance());

			// Color: the gradient goes from the first word to the last, in reading order.
			int rgb = LyricConfig.current.wordColor(i, words.size());
			LyricRenderer.drawText(context, position, prepared.drawable[i], 0, opacity, scale, spot.tilt(), rgb);
		}
	}

	/**
	 * Chooses a spot for every word of a line. The random numbers come from a
	 * fixed "seed" per line, so the same song always gets the same layout.
	 */
	private static LineLayout createLayout(Lyrics lyrics, int line, PreparedLine prepared, float playerYaw) {
		LyricConfig.ScatterSettings settings = LyricConfig.current.scatter;
		Random random = new Random(lyrics.title().hashCode() * 31L + line);
		int count = prepared.words.size();
		float arc = Math.min(Math.max(count * ARC_PER_WORD, MIN_ARC), settings.spreadDegrees);

		// Reading order on screen: left to right (Hebrew lines: the first word on the right).
		int[] leftToRight = prepared.leftToRight;
		float[] angleOffsets = new float[count];
		for (int visual = 0; visual < count; visual++) {
			float t = count == 1 ? 0.5f : (float) visual / (count - 1);
			// Increasing yaw turns to the RIGHT, so left-most words get negative offsets.
			angleOffsets[leftToRight[visual]] = Easing.lerp(-arc / 2, arc / 2, t);
		}

		List<WordSpot> spots = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			spots.add(new WordSpot(
					angleOffsets[i] + randomPlusMinus(random) * ANGLE_JITTER,
					settings.distance + randomPlusMinus(random) * settings.distanceVariation,
					settings.height + randomPlusMinus(random) * settings.heightVariation,
					settings.wordSize + randomPlusMinus(random) * settings.sizeVariation,
					randomPlusMinus(random) * settings.tiltDegrees));
		}
		return new LineLayout(playerYaw, spots);
	}

	/** A random number between -1 and +1. */
	private static float randomPlusMinus(Random random) {
		return random.nextFloat() * 2 - 1;
	}
}
