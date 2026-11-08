package dev.lyricmod;

import java.util.List;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Shows the lyrics of the playing song in the world, in sync with the music.
 * This class draws the LINE style itself and hands the SCATTER style to
 * {@link ScatterAnimation}.
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

	// Distance, height and fade times come from the "line" part of config.json
	// (see LyricConfig.LineSettings).

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
		if (LyricConfig.current.styleValue == LyricStyle.SCATTER) {
			ScatterAnimation.render(context, lyrics, positionMs);
			shownLyrics = null; // so the LINE style starts fresh if you switch back
			return;
		}
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

		LyricConfig.LineSettings settings = LyricConfig.current.line;

		// The previous line fades out during the first moments of the new line.
		if (previousLine >= 0 && currentLine >= 0) {
			long sinceChange = positionMs - lyrics.lines().get(currentLine).timeMs();
			float fade = 1f - progress(sinceChange, settings.lineFadeOutMs);
			if (fade > 0 && previousAnchor != null) {
				drawLine(context, lyrics, previousLine, previousAnchor, positionMs, fade);
			} else {
				previousLine = -1;
			}
		}

		if (currentLine >= 0 && currentAnchor != null) {
			// The current line also fades out if it's the last one and has ended.
			long untilEnd = LyricTimeline.lineEndMs(lyrics, currentLine) - positionMs;
			float fade = Easing.clamp01(progress(untilEnd, settings.lineFadeOutMs));
			drawLine(context, lyrics, currentLine, currentAnchor, positionMs, fade);
		}
	}

	/** Draws one line word by word. {@code lineOpacity} fades the whole line (1 = fully visible). */
	private static void drawLine(LevelRenderContext context, Lyrics lyrics, int index, Vec3 anchor,
			long positionMs, float lineOpacity) {
		// Words, direction and drawable text are worked out once per line (see PreparedLine).
		PreparedLine prepared = PreparedLine.get(lyrics, index);
		List<LyricTimeline.TimedWord> words = prepared.words;
		if (words.isEmpty() || lineOpacity <= 0) {
			return; // an empty line is a pause in the singing
		}

		// Measure the whole line first so we can center it. Words that haven't
		// been sung yet still take up their space, so the line doesn't jump around.
		float spaceWidth = LyricRenderer.textWidth(LyricRenderer.prepare(" ", false)); // in the lyric font
		float totalWidth = -spaceWidth;
		for (float width : prepared.widths) {
			totalWidth += width + spaceWidth;
		}

		// Right-to-left lines (Hebrew, Arabic): leftToRight puts the first word on the RIGHT.
		float x = -totalWidth / 2f;
		for (int wordIndex : prepared.leftToRight) {
			float wordWidth = prepared.widths[wordIndex];
			long sinceStart = positionMs - words.get(wordIndex).startMs();
			if (sinceStart >= 0) {
				float wordOpacity = Math.min(1f, progress(sinceStart, LyricConfig.current.line.wordFadeInMs));
				// Color: the gradient goes from the first word to the last, in reading order.
				int rgb = LyricConfig.current.wordColor(wordIndex, words.size());
				// drawText centers on x, so pass the middle of the word.
				LyricRenderer.drawText(context, anchor, prepared.drawable[wordIndex], x + wordWidth / 2f,
						wordOpacity * lineOpacity, 1f, 0f, rgb);
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
		LyricConfig.LineSettings settings = LyricConfig.current.line;
		return player.getEyePosition().add(flat.normalize().scale(settings.distance)).add(0, settings.height, 0);
	}

	/** How far along an animation of {@code durationMs} is after {@code elapsedMs} (1 = done). */
	static float progress(long elapsedMs, int durationMs) {
		return durationMs <= 0 ? 1f : (float) elapsedMs / durationMs;
	}
}
