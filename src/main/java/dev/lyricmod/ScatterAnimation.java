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

	/** Empty space between two words, in degrees (seen from the player). */
	private static final float WORD_GAP_DEGREES = 3f;
	/** Very long lines shrink until all their rows fit in about this many degrees, top to bottom. */
	private static final float MAX_LINE_HEIGHT_DEGREES = 30f;
	/** Random sideways wiggle of a word, in degrees (smaller than the gap, so words never touch). */
	private static final float ANGLE_JITTER = 0.6f;
	/** Extra rise while leaving, in blocks. */
	private static final float LEAVE_RISE = 0.6f;
	/** For very long lines, center the orbit on this much time only. */
	private static final long MAX_ORBIT_MS = 8000;

	/**
	 * Where and how one word of a line is placed (chosen once per line).
	 *
	 * @param angleOffset   degrees left (-) or right (+) of the line's center direction
	 * @param angularWidth  how wide the word looks from the player, in degrees
	 * @param elevation     degrees above (+) or below (-) eye level, as seen from the player
	 * @param angularHeight how tall the word looks from the player, in degrees
	 * @param row           0 = top row (long lines are wrapped into several rows)
	 * @param height        blocks above eye level (= what "elevation" means at this word's distance)
	 */
	public record WordSpot(float angleOffset, float angularWidth, float elevation, float angularHeight, int row,
			float distance, float height, float size, float tilt) {
	}

	/** One line's layout: the direction it was centered on, and its words' spots. */
	public record LineLayout(float centerYaw, List<WordSpot> spots) {
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

			// The drift is counted from the LINE's start, so all its words rise
			// together and never drift into each other.
			float height = spot.height()
					+ settings.driftUpPerSecond * Math.max(0, positionMs - lineStart) / 1000f
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
	 *
	 * To avoid overlapping words, we measure how wide each word will look from
	 * the player (in degrees) and put words side by side with a small gap.
	 * If a line doesn't fit in "spreadDegrees", it is wrapped into more rows,
	 * like text wrapping in a document: first row on top.
	 */
	public static LineLayout createLayout(Lyrics lyrics, int line, PreparedLine prepared, float playerYaw) {
		LyricConfig.ScatterSettings settings = LyricConfig.current.scatter;
		Random random = new Random(lyrics.title().hashCode() * 31L + line);
		int count = prepared.words.size();

		// Everything is measured in ANGLES as seen from the player's eyes, because
		// that's what the screen shows: a near word looks bigger and higher than a far
		// word at the same height, so measuring in blocks would let rows cross.

		// 1. Random size, distance and tilt for each word.
		float[] size = new float[count];
		float[] distance = new float[count];
		float[] tilt = new float[count];
		for (int i = 0; i < count; i++) {
			size[i] = settings.wordSize + randomPlusMinus(random) * settings.sizeVariation;
			distance[i] = settings.distance + randomPlusMinus(random) * settings.distanceVariation;
			tilt[i] = randomPlusMinus(random) * settings.tiltDegrees;
		}

		// 2. Measure the words and wrap them into rows that fit in "spreadDegrees".
		//    If a very long line needs too many rows to fit on screen, make all its
		//    words a bit smaller and try again.
		float[] angularWidth = new float[count];
		float[] angularHeight = new float[count];
		float tallest = 0; // the tallest word, in degrees
		List<List<Integer>> rows = List.of();
		for (int attempt = 0; attempt < 8; attempt++) {
			tallest = measure(prepared, size, distance, tilt, angularWidth, angularHeight);
			rows = wrapIntoRows(angularWidth, settings.spreadDegrees);
			if (rows.size() * tallest * 1.2f <= MAX_LINE_HEIGHT_DEGREES) {
				break;
			}
			for (int i = 0; i < count; i++) {
				size[i] *= 0.85f;
			}
		}

		// 3. Up and down: with one row, words may scatter freely ("heightVariation").
		//    With more rows they wiggle less, and rows are far enough apart not to touch.
		float centerElevation = (float) Math.toDegrees(Math.atan2(settings.height, settings.distance));
		float freeJitter = (float) Math.toDegrees(Math.atan2(settings.heightVariation, settings.distance));
		float elevationJitter = rows.size() == 1 ? freeJitter : Math.min(freeJitter, tallest * 0.2f);
		float rowGap = tallest * 1.1f + 2 * elevationJitter;

		// 4. Place each row's words from left to right, centered on the line's direction.
		float[] angleOffset = new float[count];
		float[] elevation = new float[count];
		float[] height = new float[count];
		int[] rowOf = new int[count];
		for (int r = 0; r < rows.size(); r++) {
			List<Integer> words = rows.get(r);
			// Reading order on screen, for this row only (Hebrew: first word on the right).
			List<String> texts = words.stream().map(prepared.texts::get).toList();
			int[] leftToRight = BidiText.visualWordOrder(texts, prepared.rightToLeft);

			float total = -WORD_GAP_DEGREES;
			for (int i : words) {
				total += angularWidth[i] + WORD_GAP_DEGREES;
			}
			float rowElevation = centerElevation + ((rows.size() - 1) / 2f - r) * rowGap; // row 0 on top
			// Increasing yaw turns to the RIGHT, so we go from -total/2 (left) to +total/2 (right).
			float left = -total / 2;
			for (int visual : leftToRight) {
				int i = words.get(visual);
				angleOffset[i] = left + angularWidth[i] / 2 + randomPlusMinus(random) * ANGLE_JITTER;
				elevation[i] = rowElevation + randomPlusMinus(random) * elevationJitter;
				// Turn the angle back into blocks above eye level, at this word's distance.
				height[i] = (float) (Math.tan(Math.toRadians(elevation[i])) * distance[i]);
				rowOf[i] = r;
				left += angularWidth[i] + WORD_GAP_DEGREES;
			}
		}

		List<WordSpot> spots = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			spots.add(new WordSpot(angleOffset[i], angularWidth[i], elevation[i], angularHeight[i], rowOf[i],
					distance[i], height[i], size[i], tilt[i]));
		}
		return new LineLayout(playerYaw, spots);
	}

	/**
	 * Works out how wide and tall each word looks from the player, in degrees.
	 * Returns the tallest.
	 */
	private static float measure(PreparedLine prepared, float[] size, float[] distance, float[] tilt,
			float[] angularWidth, float[] angularHeight) {
		float blocksPerPixel = LyricRenderer.blocksPerFontPixel();
		float tallest = 0;
		for (int i = 0; i < size.length; i++) {
			float widthBlocks = prepared.widths[i] * blocksPerPixel * size[i];
			float heightBlocks = LyricRenderer.LINE_HEIGHT_PIXELS * blocksPerPixel * size[i];
			// A tilted word takes more room: its box turned a little.
			double t = Math.toRadians(Math.abs(tilt[i]));
			float tiltedWidth = (float) (widthBlocks * Math.cos(t) + heightBlocks * Math.sin(t));
			float tiltedHeight = (float) (heightBlocks * Math.cos(t) + widthBlocks * Math.sin(t));
			angularWidth[i] = degreesSeen(tiltedWidth, distance[i]);
			angularHeight[i] = degreesSeen(tiltedHeight, distance[i]);
			tallest = Math.max(tallest, angularHeight[i]);
		}
		return tallest;
	}

	/** Puts words (in reading order) into rows no wider than {@code maxDegrees}, like text wrapping. */
	private static List<List<Integer>> wrapIntoRows(float[] angularWidth, float maxDegrees) {
		List<List<Integer>> rows = new ArrayList<>();
		List<Integer> row = new ArrayList<>();
		float rowWidth = 0;
		for (int i = 0; i < angularWidth.length; i++) {
			if (!row.isEmpty() && rowWidth + WORD_GAP_DEGREES + angularWidth[i] > maxDegrees) {
				rows.add(row);
				row = new ArrayList<>();
				rowWidth = 0;
			}
			rowWidth += (row.isEmpty() ? 0 : WORD_GAP_DEGREES) + angularWidth[i];
			row.add(i);
		}
		rows.add(row);
		return rows;
	}

	/** How many degrees something {@code size} blocks big looks, seen from {@code distance} blocks away. */
	private static float degreesSeen(float size, float distance) {
		return (float) Math.toDegrees(2 * Math.atan(size / 2 / distance));
	}

	/** A random number between -1 and +1. */
	private static float randomPlusMinus(Random random) {
		return random.nextFloat() * 2 - 1;
	}
}
