package dev.lyricmod.test;

import java.io.IOException;

import dev.lyricmod.LyricFiles;
import dev.lyricmod.Lyrics;
import dev.lyricmod.SongPlayer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * An automated test that plays the game for us: creates a world, types our
 * commands, checks the results and takes screenshots. Look at the pictures in
 * build/run/clientGameTest/screenshots afterwards.
 *
 * Run it with: ./gradlew runClientGameTest
 */
public class LyricTestCommandGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			world.getServer().runCommand("time set noon");
			context.runOnClient(client -> {
				client.player.setYRot(0);
				client.player.setXRot(-10);
			});
			context.waitTicks(5);

			testFloatingText(context);
			testFiles();
			testPlaySong(context);
		}
	}

	/** Step 1: /lyrics test fades a line in and out. (20 ticks = 1 second) */
	private void testFloatingText(ClientGameTestContext context) {
		command(context, "lyrics test");
		context.waitTicks(4);
		context.takeScreenshot("step1-0.2s-fading-in");
		context.waitTicks(26);
		context.takeScreenshot("step1-1.5s-visible");
		context.runOnClient(client -> client.player.setYRot(35));
		context.waitTicks(2);
		context.takeScreenshot("step1-turned-camera");
		context.runOnClient(client -> client.player.setYRot(0));
		context.waitTicks(100);
		context.takeScreenshot("step1-6.6s-gone");
	}

	/** Step 2: file safety checks and .lrc parsing of the example song. */
	private void testFiles() {
		// The example song was copied into config/lyricmod on first start.
		check(LyricFiles.listSongs().contains("example"), "example song should be listed");

		// Names that try to escape the folder must be refused.
		for (String bad : new String[] {"../secret", "..", "a/b", "a\\b", ".hidden", ""}) {
			try {
				LyricFiles.songFile(bad, ".ogg");
				throw new AssertionError("unsafe name was accepted: " + bad);
			} catch (IOException expected) {
				// good
			}
		}

		try {
			Lyrics lyrics = LyricFiles.loadLyrics("example");
			check(lyrics.title().equals("Twinkle, Twinkle, Little Star"), "title: " + lyrics.title());
			check(lyrics.artist().equals("Traditional"), "artist: " + lyrics.artist());
			check(lyrics.lines().size() == 7, "line count: " + lyrics.lines().size());
			Lyrics.Line first = lyrics.lines().get(0);
			check(first.timeMs() == 1000, "first line time: " + first.timeMs());
			check(first.text().equals("Twinkle, twinkle, little star"), "first line text: " + first.text());
			check(first.words().size() == 4, "first line words: " + first.words());
			check(first.words().get(2).timeMs() == 3000, "word 'little' time: " + first.words().get(2));
		} catch (IOException e) {
			throw new AssertionError("could not load example lyrics", e);
		}
	}

	/** Step 2: /lyrics play starts the music and the playback time moves forward. */
	private void testPlaySong(ClientGameTestContext context) {
		command(context, "lyrics list");
		command(context, "lyrics play example");
		context.waitFor(client -> SongPlayer.isPlaying(), 100);

		context.waitTicks(40); // about 2 seconds
		long position = context.computeOnClient(client -> SongPlayer.positionMs());
		check(position > 1200 && position < 3500, "song position after ~2s: " + position + " ms");
		context.takeScreenshot("step2-playing");

		command(context, "lyrics stop");
		check(!context.computeOnClient(client -> SongPlayer.isPlaying()), "song should be stopped");

		// Unsafe name typed as a command: must show an error, not crash.
		command(context, "lyrics play ../secret");
		context.waitTicks(2);
		context.takeScreenshot("step2-chat");
	}

	/** Sends a command exactly like a player typing it in chat. */
	private static void command(ClientGameTestContext context, String command) {
		context.runOnClient(client -> client.player.connection.sendCommand(command));
		context.waitTick();
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("Check failed: " + message);
		}
	}
}
