package dev.lyricmod;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Util;
import net.minecraft.util.math.Vec3d;

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
 *  4. We ask the font renderer to draw the string at that spot.
 */
public final class LyricRenderer {

	/**
	 * Size of one font pixel in blocks. 0.025 is what name tags use; the
	 * Minecraft font is 9 pixels tall, so a line is about 0.23 blocks high at 0.025.
	 * We use a bit less so a whole line fits on screen. (Step 5: from config.)
	 */
	private static final float TEXT_SCALE = 0.02f;

	/** Text color as 0xRRGGBB (white). Alpha is added per frame. (Step 5: from config.) */
	private static final int TEXT_COLOR = 0xFFFFFF;

	/** "Full brightness" light value, so the text glows even at night or in caves. */
	private static final int FULL_BRIGHT = LightmapTextureManager.MAX_LIGHT_COORDINATE;

	/** The lines currently on screen. */
	private static final List<FloatingText> ACTIVE = new ArrayList<>();

	private LyricRenderer() {
	}

	/** Hooks our {@link #render} method into the game's world rendering. */
	public static void register() {
		// AFTER_ENTITIES fires each frame after mobs/players are drawn, which is
		// a good moment to draw our own see-through-ish things into the world.
		WorldRenderEvents.AFTER_ENTITIES.register(LyricRenderer::render);
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
		return Util.getMeasuringTimeMs();
	}

	private static void render(WorldRenderContext context) {
		if (ACTIVE.isEmpty()) {
			return; // Nothing to draw: do no work at all.
		}

		long now = nowMs();

		// Forget lines that have completely faded out.
		ACTIVE.removeIf(line -> line.isFinished(now));

		MinecraftClient client = MinecraftClient.getInstance();
		TextRenderer font = client.textRenderer;
		Camera camera = context.camera();
		Vec3d cameraPos = camera.getPos();

		// A "vertex consumer provider" collects the shapes we want to draw and
		// sends them to the graphics card when we call draw() at the end.
		VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();

		// A MatrixStack keeps track of moves/rotations/scales. At this point
		// in the frame the camera's rotation is already applied by the game,
		// so we start from a fresh (identity) stack in camera-relative space,
		// the same way Minecraft draws entities.
		MatrixStack matrices = new MatrixStack();

		for (FloatingText line : ACTIVE) {
			float opacity = line.opacity(now);

			// The font renderer treats an alpha below 4 (out of 255) as
			// "fully opaque" (an old Minecraft quirk), so skip nearly-invisible
			// frames instead of having the text flash at full brightness.
			int alpha = Math.round(opacity * 255);
			if (alpha < 5) {
				continue;
			}

			// Color as 0xAARRGGBB: put alpha in the top 8 bits.
			int argb = (alpha << 24) | TEXT_COLOR;

			matrices.push(); // Save the stack so our changes don't leak to the next line.

			// 1. Move from the camera to the text position.
			Vec3d p = line.position();
			matrices.translate(p.x - cameraPos.x, p.y - cameraPos.y, p.z - cameraPos.z);

			// 2. Face the camera.
			matrices.multiply(camera.getRotation());

			// 3. Shrink font pixels down to world size; negative Y flips the
			//    text right side up. (If you ever see the text mirrored,
			//    make the first number negative, as in older MC versions.)
			matrices.scale(TEXT_SCALE, -TEXT_SCALE, TEXT_SCALE);

			// 4. Draw, centered: shift left by half the text's width (in pixels).
			float x = -font.getWidth(line.text()) / 2f;
			font.draw(
					line.text(),
					x, 0,                               // position (pixels) after our transforms
					argb,                               // color with alpha for the fade
					true,                               // drop shadow, easier to read
					matrices.peek().getPositionMatrix(),
					buffers,
					TextRenderer.TextLayerType.NORMAL,  // hidden behind blocks, like real objects
					0,                                  // background color (0 = none)
					FULL_BRIGHT
			);

			matrices.pop(); // Undo this line's transforms.
		}

		// Actually send everything to the GPU.
		buffers.draw();
	}
}
