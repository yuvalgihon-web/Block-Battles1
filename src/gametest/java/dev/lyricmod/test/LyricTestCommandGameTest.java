package dev.lyricmod.test;

import java.io.IOException;

import java.util.List;

import dev.lyricmod.LrcParser;
import dev.lyricmod.LyricFiles;
import dev.lyricmod.LyricTimeline;
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
			testTimeline();
			testPlaySong(context);
			testSyncedLyrics(context);
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

	/** Step 3: the timing math, without the game. */
	private void testTimeline() {
		// Lines WITHOUT word times: words are spread over 60% of the line.
		Lyrics plain = LrcParser.parse("[00:01.00]one two three four\n[00:05.00]next line\n", "plain");
		check(LyricTimeline.lineAt(plain, 500) == -1, "before first line");
		check(LyricTimeline.lineAt(plain, 1000) == 0, "first line starts at 1s");
		check(LyricTimeline.lineAt(plain, 4999) == 0, "first line until 5s");
		check(LyricTimeline.lineAt(plain, 5000) == 1, "second line at 5s");
		List<LyricTimeline.TimedWord> words = LyricTimeline.words(plain, 0);
		check(words.size() == 4, "4 words");
		check(words.get(0).startMs() == 1000 && words.get(1).startMs() == 1600
				&& words.get(3).startMs() == 2800, "evenly spread words: " + words);
		check(LyricTimeline.lineEndMs(plain, 1) == 10000, "last line lasts 5s");

		// Lines WITH word times use them as they are.
		try {
			Lyrics example = LyricFiles.loadLyrics("example");
			List<LyricTimeline.TimedWord> timed = LyricTimeline.words(example, 1);
			check(timed.get(0).text().equals("How") && timed.get(0).startMs() == 5000, "How @5s: " + timed);
			check(timed.get(2).text().equals("wonder") && timed.get(2).startMs() == 6000, "wonder @6s: " + timed);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	/** Step 3: screenshots at exact moments of the song. */
	private void testSyncedLyrics(ClientGameTestContext context) {
		command(context, "lyrics play example");
		context.waitFor(client -> SongPlayer.isPlaying(), 100);

		// 3.3s: "Twinkle, twinkle, little" visible, "star" (at 4.0s) not yet.
		waitForSongTime(context, 3300);
		context.takeScreenshot("step3-3.3s-twinkle-twinkle-little");

		// 5.6s: new line "How I" while the old line fades out.
		waitForSongTime(context, 5600);
		context.takeScreenshot("step3-5.6s-how-i");

		// 8.4s: the whole second line.
		waitForSongTime(context, 8400);
		context.takeScreenshot("step3-8.4s-full-line");

		command(context, "lyrics stop");
	}

	private static void waitForSongTime(ClientGameTestContext context, long ms) {
		context.waitFor(client -> SongPlayer.positionMs() >= ms, 400);
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
