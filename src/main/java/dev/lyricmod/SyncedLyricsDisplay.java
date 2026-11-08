package dev.lyricmod;

import java.util.List;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Shows the lyrics of the playing song in the world, in sync with the music.
 *
 * Every frame we ask "what time is it in the song?" and draw what belongs to
 * that moment:
 *  - the current line, placed in front of the player when it starts;
 *  - its words appearing one by one, each fading in at its own time;
 *  - the previous line fading out for a moment after the next one starts.
 *
 * Because everything is computed from the song's playback time (not from a
 * timer of our own), the lyrics stay in sync even if the game lags, and they
 * freeze when the song is paused.
 */
public final class SyncedLyricsDisplay {

	/** How far in front of the player a new line appears, in blocks. */
	private static final double DISTANCE = 4.0;

	/** How high above eye level a new line appears, in blocks. */
	private static final double HEIGHT = 0.8;

	/** How long one word takes to fade in. */
	private static final long WORD_FADE_IN_MS = 250;

	/** How long a finished line takes to fade out. */
	private static final long LINE_FADE_OUT_MS = 400;

	/** Lyrics we are showing, so we notice when a new song starts. */
	private static Lyrics shownLyrics;

	/** Index of the line currently shown, and where in the world it is. */
	private static int currentLine = -1;
	private static Vec3 currentAnchor;

	/** The line before it, still fading out. */
	private static int previousLine = -1;
	private static Vec3 previousAnchor;

	private SyncedLyricsDisplay() {
	}

	/** Called by LyricRenderer every frame. */
	static void render(LevelRenderContext context, Lyrics lyrics, long positionMs) {
		if (lyrics == null) {
			shownLyrics = null;
			return;
		}
		if (lyrics != shownLyrics) {
			// A new song: forget everything about the previous one.
			shownLyrics = lyrics;
			currentLine = -1;
			previousLine = -1;
		}

		int line = LyricTimeline.lineAt(lyrics, positionMs);
		if (line != currentLine) {
			// A new line started: the old one starts fading out,
			// and the new one gets a spot in front of the player.
			previousLine = currentLine;
			previousAnchor = currentAnchor;
			currentLine = line;
			currentAnchor = spotInFrontOfPlayer();
		}

		// The previous line fades out during the first moments of the new line.
		if (previousLine >= 0 && currentLine >= 0) {
			long sinceChange = positionMs - lyrics.lines().get(currentLine).timeMs();
			float fade = 1f - (float) sinceChange / LINE_FADE_OUT_MS;
			if (fade > 0 && previousAnchor != null) {
				drawLine(context, lyrics, previousLine, previousAnchor, positionMs, fade);
			} else {
				previousLine = -1;
			}
		}

		if (currentLine >= 0 && currentAnchor != null) {
			// The current line also fades out if it's the last one and has ended.
			long untilEnd = LyricTimeline.lineEndMs(lyrics, currentLine) - positionMs;
			float fade = Math.clamp((float) untilEnd / LINE_FADE_OUT_MS, 0f, 1f);
			drawLine(context, lyrics, currentLine, currentAnchor, positionMs, fade);
		}
	}

	/** Draws one line word by word. {@code lineOpacity} fades the whole line (1 = fully visible). */
	private static void drawLine(LevelRenderContext context, Lyrics lyrics, int index, Vec3 anchor,
			long positionMs, float lineOpacity) {
		List<LyricTimeline.TimedWord> words = LyricTimeline.words(lyrics, index);
		if (words.isEmpty() || lineOpacity <= 0) {
			return; // an empty line is a pause in the singing
		}

		// Right-to-left lines (Hebrew, Arabic): the first word goes on the RIGHT.
		// BidiText tells us in which order to place the words from left to right.
		List<String> texts = words.stream().map(LyricTimeline.TimedWord::text).toList();
		boolean rightToLeft = BidiText.isRightToLeft(String.join(" ", texts));
		int[] leftToRight = BidiText.visualWordOrder(texts, rightToLeft);

		// Measure the whole line first so we can center it. Words that haven't
		// been sung yet still take up their space, so the line doesn't jump around.
		float spaceWidth = LyricRenderer.textWidth(" ");
		float totalWidth = -spaceWidth;
		for (LyricTimeline.TimedWord word : words) {
			totalWidth += LyricRenderer.textWidth(word.text()) + spaceWidth;
		}

		float x = -totalWidth / 2f;
		for (int wordIndex : leftToRight) {
			LyricTimeline.TimedWord word = words.get(wordIndex);
			float wordWidth = LyricRenderer.textWidth(word.text());
			long sinceStart = positionMs - word.startMs();
			if (sinceStart >= 0) {
				float wordOpacity = Math.min(1f, (float) sinceStart / WORD_FADE_IN_MS);
				// drawText centers on x, so pass the middle of the word.
				LyricRenderer.drawText(context, anchor, word.text(), x + wordWidth / 2f,
						wordOpacity * lineOpacity, rightToLeft);
			}
			x += wordWidth + spaceWidth;
		}
	}

	/** A point a few blocks in front of the player, at about eye height. */
	private static Vec3 spotInFrontOfPlayer() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return null;
		}
		// Use only the horizontal part of the look direction, so the line
		// appears at eye level even if you're looking at your feet.
		Vec3 look = player.getLookAngle();
		Vec3 flat = new Vec3(look.x, 0, look.z);
		if (flat.lengthSqr() < 1.0e-4) {
			flat = new Vec3(0, 0, 1); // looking straight up or down
		}
		return player.getEyePosition().add(flat.normalize().scale(DISTANCE)).add(0, HEIGHT, 0);
	}
}
