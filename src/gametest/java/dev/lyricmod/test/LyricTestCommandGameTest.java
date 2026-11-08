package dev.lyricmod.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * An automated test that plays the game for us:
 * creates a world, types /lyrics test, and takes screenshots while the
 * line fades in, stays, and fades out. Look at the pictures in
 * build/run/clientGameTest/screenshots afterwards.
 */
public class LyricTestCommandGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();

			// Daytime and a fixed view direction so screenshots are comparable.
			world.getServer().runCommand("time set noon");
			context.runOnClient(client -> {
				client.player.setYRot(0);
				client.player.setXRot(-10);
			});
			context.waitTicks(5);

			// Send the command exactly like a player typing it in chat.
			context.runOnClient(client -> client.player.connection.sendCommand("lyrics test"));

			// 20 ticks = 1 second.
			context.waitTicks(4);
			context.takeScreenshot("lyrics-0.2s-fading-in");
			context.waitTicks(6);
			context.takeScreenshot("lyrics-0.5s-fading-in");
			context.waitTicks(20);
			context.takeScreenshot("lyrics-1.5s-visible");

			// Turn the camera: the text should keep facing us.
			context.runOnClient(client -> client.player.setYRot(35));
			context.waitTicks(2);
			context.takeScreenshot("lyrics-turned-camera");

			context.waitTicks(100);
			context.takeScreenshot("lyrics-6.6s-gone");
		}
	}
}
