package dev.lyricmod;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;

/**
 * Draws every active {@link FloatingText} in the 3D world, once per frame.
 *
 * How 3D text works, in short:
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

	/** The lines currently on screen. */
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
	 * Current time in milliseconds. A single helper so every class uses the
	 * same clock. In step 3 we'll switch to the song's playback position.
	 */
	public static long nowMs() {
		return Util.getMillis();
	}

	private static void render(LevelRenderContext context) {
		if (ACTIVE.isEmpty()) {
			return; // Nothing to draw: do no work at all.
		}

		long now = nowMs();

		// Forget lines that have completely faded out.
		ACTIVE.removeIf(line -> line.isFinished(now));

		Font font = Minecraft.getInstance().font;
		CameraRenderState camera = context.levelState().cameraRenderState;
		Vec3 cameraPos = camera.pos;

		// A PoseStack keeps track of moves/rotations/scales ("poses").
		// push/pop saves and restores it, so one line's changes don't leak
		// into the next line or into the rest of the game's rendering.
		PoseStack poses = context.poseStack();

		for (FloatingText line : ACTIVE) {
			float opacity = line.opacity(now);

			// Very low alpha values can be treated as "fully opaque" by the
			// text renderer (an old Minecraft quirk), so skip nearly-invisible
			// frames instead of having the text flash at full brightness.
			int alpha = Math.round(opacity * 255);
			if (alpha < 5) {
				continue;
			}

			// Color as 0xAARRGGBB: put alpha in the top 8 bits.
			int argb = (alpha << 24) | TEXT_COLOR;

			poses.pushPose();

			// 1. Move from the camera to the text position.
			Vec3 p = line.position();
			poses.translate(p.x - cameraPos.x, p.y - cameraPos.y, p.z - cameraPos.z);

			// 2. Face the camera.
			poses.rotate(camera.orientation);

			// 3. Shrink font pixels down to world size; negative Y flips the
			//    text right side up.
			poses.scale(TEXT_SCALE, -TEXT_SCALE, TEXT_SCALE);

			// 4. Submit the text, centered: shift left by half its width and
			//    up by half its height (both in font pixels).
			FormattedCharSequence text = Component.literal(line.text()).getVisualOrderText();
			float x = -font.width(text) / 2f;
			float y = -font.lineHeight / 2f;
			context.submitNodeCollector().submitText(
					poses,
					x, y,
					text,
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
}
