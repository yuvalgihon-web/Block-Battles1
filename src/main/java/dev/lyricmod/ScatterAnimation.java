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

	// ----- Layout: where words are placed (step 5 will make some of these configurable) -----

	/** Degrees of arc per word, and the smallest/largest arc a line uses. */
	private static final float ARC_PER_WORD = 13f;
	private static final float MIN_ARC = 20f;
	private static final float MAX_ARC = 70f;
	/** Random wiggle added to each word's angle, in degrees. */
	private static final float ANGLE_JITTER = 4f;
	/** Distance from the eyes, in blocks. */
	private static final float MIN_DISTANCE = 4.5f;
	private static final float MAX_DISTANCE = 6.5f;
	/** Height above the eyes, in blocks. */
	private static final float MIN_HEIGHT = -0.2f;
	private static final float MAX_HEIGHT = 1.6f;
	/** Word size, as a multiple of normal floating text. */
	private static final float MIN_SIZE = 1.3f;
	private static final float MAX_SIZE = 2.3f;
	/** Tilt, in degrees to either side. */
	private static final float MAX_TILT = 12f;

	// ----- Motion -----

	/** How long a word takes to appear (fade + pop). */
	private static final long APPEAR_MS = 400;
	/** How long a line takes to leave after it ends. */
	private static final long LEAVE_MS = 600;
	/** Slow upward drift while visible, blocks per second. */
	private static final float DRIFT_UP_PER_SECOND = 0.08f;
	/** Extra rise while leaving, in blocks. */
	private static final float LEAVE_RISE = 0.6f;
	/** Orbit speed of a whole line around the player, degrees per second. */
	private static final float ORBIT_DEGREES_PER_SECOND = 3f;
	/** For very long lines, center the orbit on this much time only. */
	private static final long MAX_ORBIT_MS = 8000;
	/** Words already sung are a little dimmer than the newest one. */
	private static final float SUNG_WORD_OPACITY = 0.75f;

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
		List<LyricTimeline.TimedWord> words = LyricTimeline.words(lyrics, line);
		long lineStart = lyrics.lines().get(line).timeMs();
		long lineEnd = LyricTimeline.lineEndMs(lyrics, line);
		if (words.isEmpty() || positionMs > lineEnd + LEAVE_MS) {
			return;
		}

		List<String> texts = words.stream().map(LyricTimeline.TimedWord::text).toList();
		boolean rightToLeft = BidiText.isRightToLeft(String.join(" ", texts));
		LineLayout layout = layouts.computeIfAbsent(line,
				index -> createLayout(lyrics, index, texts, rightToLeft, player.getYRot()));

		// Leaving: 0 while the line is sung, rising to 1 during LEAVE_MS after it ends.
		float leave = Easing.inCubic((float) (positionMs - lineEnd) / LEAVE_MS);
		// Orbit: the whole line turns slowly around the player. We count from the
		// MIDDLE of the line's time, so it starts a bit to one side and passes through
		// the center, instead of drifting off-screen during long lines.
		long middle = lineStart + Math.min(lineEnd - lineStart, MAX_ORBIT_MS) / 2;
		float orbit = ORBIT_DEGREES_PER_SECOND * (positionMs - middle) / 1000f;
		// Index of the newest word that has started (the one being sung).
		int newest = -1;
		for (int i = 0; i < words.size(); i++) {
			if (words.get(i).startMs() <= positionMs) {
				newest = i;
			}
		}

		Vec3 eyes = player.getEyePosition();
		for (int i = 0; i < words.size(); i++) {
			long age = positionMs - words.get(i).startMs();
			if (age < 0) {
				continue; // not sung yet
			}
			WordSpot spot = layout.spots().get(i);
			float appear = (float) age / APPEAR_MS;

			float opacity = Easing.outCubic(appear) * (1 - leave);
			if (i != newest) {
				opacity *= SUNG_WORD_OPACITY;
			}
			// Pop: start at 50% size, overshoot a little, settle at 100%; shrink while leaving.
			float scale = spot.size() * Easing.lerp(0.5f, 1f, Easing.outBack(appear)) * (1 - 0.3f * leave);

			float height = spot.height()
					+ DRIFT_UP_PER_SECOND * age / 1000f
					+ LEAVE_RISE * leave;
			double angle = Math.toRadians(layout.centerYaw() + spot.angleOffset() + orbit);
			// Minecraft's yaw: 0 = looking toward +Z (south), 90 = toward -X (west).
			Vec3 position = eyes.add(
					-Math.sin(angle) * spot.distance(),
					height,
					Math.cos(angle) * spot.distance());

			LyricRenderer.drawText(context, position, texts.get(i), 0, opacity, rightToLeft,
					scale, spot.tilt());
		}
	}

	/**
	 * Chooses a spot for every word of a line. The random numbers come from a
	 * fixed "seed" per line, so the same song always gets the same layout.
	 */
	private static LineLayout createLayout(Lyrics lyrics, int line, List<String> texts, boolean rightToLeft,
			float playerYaw) {
		Random random = new Random(lyrics.title().hashCode() * 31L + line);
		int count = texts.size();
		float arc = Math.clamp(count * ARC_PER_WORD, MIN_ARC, MAX_ARC);

		// Reading order on screen: left to right (Hebrew lines: the first word on the right).
		int[] leftToRight = BidiText.visualWordOrder(texts, rightToLeft);
		float[] angleOffsets = new float[count];
		for (int visual = 0; visual < count; visual++) {
			float t = count == 1 ? 0.5f : (float) visual / (count - 1);
			// Increasing yaw turns to the RIGHT, so left-most words get negative offsets.
			angleOffsets[leftToRight[visual]] = Easing.lerp(-arc / 2, arc / 2, t);
		}

		List<WordSpot> spots = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			spots.add(new WordSpot(
					angleOffsets[i] + (random.nextFloat() * 2 - 1) * ANGLE_JITTER,
					Easing.lerp(MIN_DISTANCE, MAX_DISTANCE, random.nextFloat()),
					Easing.lerp(MIN_HEIGHT, MAX_HEIGHT, random.nextFloat()),
					Easing.lerp(MIN_SIZE, MAX_SIZE, random.nextFloat()),
					(random.nextFloat() * 2 - 1) * MAX_TILT));
		}
		return new LineLayout(playerYaw, spots);
	}
}
