package dev.lyricmod;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.language.FormattedBidiReorder;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;

/**
 * Draws floating text in the 3D world, once per frame:
 *  - simple {@link FloatingText} lines (the /lyrics test line and song titles);
 *  - the synced lyrics of the playing song, via {@link SyncedLyricsDisplay}.
 *
 * How 3D text works, in short (see {@link #drawText}):
 *  1. Minecraft draws the world relative to the camera, so we first move
 *     ("translate") from the camera to the text's position.
 *  2. We rotate by the camera's rotation so the text always faces us
 *     (this trick is called "billboarding"; name tags work the same way).
 *  3. Font pixels are huge in world units (1 pixel = 1 block!), so we scale
 *     everything down, and flip Y because text is drawn top-to-bottom.
 *  4. We "submit" the text: since Minecraft 1.21.9 we don't draw directly,
 *     we hand Minecraft a description of what to draw and it draws everything
 *     together later in the frame.
 */
public final class LyricRenderer {

	/**
	 * Size of one font pixel in blocks. Name tags use 0.025; the Minecraft
	 * font is 9 pixels tall, so a name tag line is about 0.23 blocks high.
	 * We use a bit less so a whole line fits on screen. (Step 5: from config.)
	 */
	private static final float TEXT_SCALE = 0.02f;

	/** Text color as 0xRRGGBB (white). Alpha is added per frame. (Step 5: from config.) */
	private static final int TEXT_COLOR = 0xFFFFFF;

	/** The simple floating lines currently on screen. */
	private static final List<FloatingText> ACTIVE = new ArrayList<>();

	private LyricRenderer() {
	}

	/** Hooks our {@link #render} method into the game's world rendering. */
	public static void register() {
		// COLLECT_SUBMITS fires once per frame while Minecraft gathers
		// everything it will draw in the world: the right moment to add our text.
		LevelRenderEvents.COLLECT_SUBMITS.register(LyricRenderer::render);
	}

	/** Adds a line to be drawn. Called from the command (always on the game thread). */
	public static void show(FloatingText line) {
		ACTIVE.add(line);
	}

	/**
	 * Current time in milliseconds, for the simple floating lines.
	 * (Synced lyrics use the song's playback time instead.)
	 */
	public static long nowMs() {
		return Util.getMillis();
	}

	private static void render(LevelRenderContext context) {
		// 1. Synced lyrics of the playing song (nothing happens if no song plays).
		//    The song comes either from Spotify or from a local .ogg file.
		if (SpotifyMode.isActive()) {
			SyncedLyricsDisplay.render(context, SpotifyMode.currentLyrics(), SpotifyMode.positionMs());
		} else {
			SyncedLyricsDisplay.render(context, SongPlayer.currentLyrics(), SongPlayer.positionMs());
		}

		// 2. Simple floating lines.
		if (ACTIVE.isEmpty()) {
			return;
		}
		long now = nowMs();
		ACTIVE.removeIf(line -> line.isFinished(now)); // forget faded-out lines
		for (FloatingText line : ACTIVE) {
			drawText(context, line.position(), line.text(), 0, line.opacity(now),
					BidiText.isRightToLeft(line.text()));
		}
	}

	/** Width of a piece of text, in font pixels. */
	static float textWidth(String text) {
		return Minecraft.getInstance().font.width(text);
	}

	/**
	 * Draws one piece of text in the world, facing the camera.
	 *
	 * @param position where the text's anchor point is in the world
	 * @param text     what to write
	 * @param xOffset  how far (in font pixels) the text's CENTER is moved sideways
	 *                 from the anchor; used to place the words of a line next to each other
	 * @param opacity  0 = invisible, 1 = fully visible
	 * @param rightToLeft true if the text belongs to a right-to-left line (Hebrew, Arabic),
	 *                 so punctuation like "," ends up on the correct side
	 */
	static void drawText(LevelRenderContext context, Vec3 position, String text, float xOffset, float opacity,
			boolean rightToLeft) {
		drawText(context, position, text, xOffset, opacity, rightToLeft, 1f, 0f);
	}

	/**
	 * Same as above, plus:
	 *
	 * @param scale       size multiplier (1 = normal floating text)
	 * @param tiltDegrees rotation around the line of sight (positive = tilted counter-clockwise)
	 */
	static void drawText(LevelRenderContext context, Vec3 position, String text, float xOffset, float opacity,
			boolean rightToLeft, float scale, float tiltDegrees) {
		// Very low alpha values can be treated as "fully opaque" by the
		// text renderer (an old Minecraft quirk), so skip nearly-invisible
		// frames instead of having the text flash at full brightness.
		int alpha = Math.round(opacity * 255);
		if (alpha < 5) {
			return;
		}
		// Color as 0xAARRGGBB: put alpha in the top 8 bits.
		int argb = (alpha << 24) | TEXT_COLOR;

		Font font = Minecraft.getInstance().font;
		CameraRenderState camera = context.levelState().cameraRenderState;
		Vec3 cameraPos = camera.pos;

		// A PoseStack keeps track of moves/rotations/scales ("poses").
		// push/pop saves and restores it, so our changes don't leak
		// into the rest of the game's rendering.
		PoseStack poses = context.poseStack();
		poses.pushPose();

		// 1. Move from the camera to the text position.
		poses.translate(position.x - cameraPos.x, position.y - cameraPos.y, position.z - cameraPos.z);

		// 2. Face the camera, then tilt around the line of sight (Z axis).
		poses.rotate(camera.orientation);
		if (tiltDegrees != 0) {
			poses.rotateDegrees(Axis.ZP, tiltDegrees);
		}

		// 3. Shrink font pixels down to world size; negative Y flips the
		//    text right side up.
		float size = TEXT_SCALE * scale;
		poses.scale(size, -size, size);

		// 4. Submit the text, centered on xOffset: shift left by half its width
		//    and up by half its height (both in font pixels).
		// "Visual order" = the characters in the order they appear on screen
		// (for Hebrew, right-to-left). Minecraft's FormattedBidiReorder does that.
		FormattedCharSequence chars = FormattedBidiReorder.reorder(Component.literal(text), rightToLeft);
		float x = xOffset - font.width(chars) / 2f;
		float y = -font.lineHeight / 2f;
		context.submitNodeCollector().submitText(
				poses,
				x, y,
				chars,
				true,                          // drop shadow, easier to read
				Font.DisplayMode.NORMAL,       // hidden behind blocks, like real objects
				LightCoordsUtil.FULL_BRIGHT,   // glow even at night or in caves
				argb,                          // color with alpha for the fade
				0,                             // background color (0 = none)
				0                              // outline color (0 = none)
		);

		poses.popPose();
	}
}
