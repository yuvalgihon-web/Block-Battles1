package dev.lyricmod.test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lyricmod.BidiText;
import dev.lyricmod.Easing;
import dev.lyricmod.LrcParser;
import dev.lyricmod.LyricRecorder;
import dev.lyricmod.Lrclib;
import dev.lyricmod.SpotifyAuth;
import dev.lyricmod.SpotifyMode;
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

			command(context, "lyrics style line"); // the step 3 tests below use the LINE style
			testFloatingText(context);
			testFiles();
			testTimeline();
			testPlaySong(context);
			testSyncedLyrics(context);
			testRecorder(context);
			testHebrew(context);
			testSpotifyParts(context);
			testScatter(context);
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
		// Lines WITHOUT word times: words are spread over 60% of the line,
		// longer words get more time (weight = letters + 2).
		Lyrics plain = LrcParser.parse("[00:01.00]one two three four\n[00:05.00]next line\n", "plain");
		check(LyricTimeline.lineAt(plain, 500) == -1, "before first line");
		check(LyricTimeline.lineAt(plain, 1000) == 0, "first line starts at 1s");
		check(LyricTimeline.lineAt(plain, 4999) == 0, "first line until 5s");
		check(LyricTimeline.lineAt(plain, 5000) == 1, "second line at 5s");
		List<LyricTimeline.TimedWord> words = LyricTimeline.words(plain, 0);
		check(words.size() == 4, "4 words");
		// one(3+2) two(3+2) three(5+2) four(4+2) = 23 parts of 2400 ms
		check(words.get(0).startMs() == 1000 && words.get(1).startMs() == 1522
				&& words.get(2).startMs() == 2043 && words.get(3).startMs() == 2774, "weighted words: " + words);
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

	/** /lyrics record: press K at known song times and check the saved .lrc. */
	private void testRecorder(ClientGameTestContext context) {
		try {
			Files.writeString(LyricFiles.folder().resolve("tapped.txt"),
					"[ar:Test Artist]\nFirst line\n\nSecond line\nThird line\n");
			Files.copy(LyricFiles.folder().resolve("example.ogg"), LyricFiles.folder().resolve("tapped.ogg"));
		} catch (IOException e) {
			throw new AssertionError(e);
		}

		command(context, "lyrics record tapped");
		context.waitFor(client -> SongPlayer.isPlaying(), 100);
		check(LyricRecorder.isRecording(), "should be recording");

		long[] pressAt = {1000, 5000, 9000, 12000}; // 3 lines + the end
		for (long time : pressAt) {
			waitForSongTime(context, time);
			context.getInput().pressKey(InputConstants.KEY_K);
			if (time == 5000) {
				context.waitTicks(3);
				context.takeScreenshot("record-after-second-press");
			}
		}
		context.waitTicks(3);
		check(!LyricRecorder.isRecording(), "recording should be finished");
		context.takeScreenshot("record-saved");

		try {
			Lyrics saved = LyricFiles.loadLyrics("tapped");
			check(saved.title().equals("tapped") && saved.artist().equals("Test Artist"), "tags: " + saved);
			check(saved.lines().size() == 4, "3 lines + end marker: " + saved.lines());
			check(saved.lines().get(1).text().equals("Second line"), "line text: " + saved.lines());
			for (int i = 0; i < 4; i++) {
				long diff = saved.lines().get(i).timeMs() - pressAt[i];
				check(diff >= 0 && diff < 250, "line " + i + " time off by " + diff + " ms");
			}
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	/** Right-to-left lyrics: the first Hebrew word must be drawn on the right. */
	private void testHebrew(ClientGameTestContext context) {
		check(BidiText.isRightToLeft("שלום עולם"), "Hebrew is right-to-left");
		check(!BidiText.isRightToLeft("hello"), "English is left-to-right");
		check(java.util.Arrays.equals(BidiText.visualWordOrder(List.of("שלום", "עולם", "יפה"), true),
				new int[] {2, 1, 0}), "pure Hebrew order");
		// English words inside a Hebrew line keep their own left-to-right order.
		check(java.util.Arrays.equals(BidiText.visualWordOrder(List.of("שיר", "Hate", "Me", "יפה"), true),
				new int[] {3, 1, 2, 0}), "mixed order: "
				+ java.util.Arrays.toString(BidiText.visualWordOrder(List.of("שיר", "Hate", "Me", "יפה"), true)));

		try {
			Files.writeString(LyricFiles.folder().resolve("hebrew.lrc"),
					"[ti:שיר בדיקה]\n[00:01.00]אחת שתיים שלוש, ארבע\n[00:05.00]שיר Hate Me יפה\n[00:09.00]\n");
			Files.copy(LyricFiles.folder().resolve("example.ogg"), LyricFiles.folder().resolve("hebrew.ogg"));
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		command(context, "lyrics play hebrew");
		context.waitFor(client -> SongPlayer.isPlaying(), 100);
		waitForSongTime(context, 1700); // first two words
		context.takeScreenshot("hebrew-1.7s-first-words-on-right");
		waitForSongTime(context, 3900);
		context.takeScreenshot("hebrew-3.9s-full-line");
		waitForSongTime(context, 8000);
		context.takeScreenshot("hebrew-8s-mixed-line");
		command(context, "lyrics stop");
	}

	/** Spotify mode, the parts that don't need a real Spotify account. */
	private void testSpotifyParts(ClientGameTestContext context) {
		// 1. Reading Spotify's "currently playing" answer (shape as documented by Spotify).
		SpotifyMode.NowPlaying info = SpotifyMode.parseNowPlaying("""
				{"timestamp":1,"progress_ms":42500,"is_playing":true,"currently_playing_type":"track",
				 "item":{"id":"4u7EnebtmKWzUH433cf5Qv","name":"Bohemian Rhapsody","duration_ms":354320,
				   "artists":[{"name":"Queen"},{"name":"Someone Else"}],
				   "album":{"name":"A Night At The Opera"}}}""");
		check(info != null && info.isPlaying() && info.progressMs() == 42500, "parsed: " + info);
		check(info.track().artist().equals("Queen") && info.track().durationMs() == 354320, "track: " + info.track());
		check(SpotifyMode.parseNowPlaying("""
				{"is_playing":true,"currently_playing_type":"episode","item":null}""") == null, "podcast ignored");

		// 2. Real download from LRCLIB (needs internet), and the cache file.
		Lrclib.Track track = info.track();
		String lrc = Lrclib.findLyrics(track).join();
		check(lrc != null, "LRCLIB should have synced lyrics for Bohemian Rhapsody");
		Lyrics parsed = LrcParser.parse(lrc, "x");
		check(parsed.lines().size() > 20, "LRCLIB lyrics lines: " + parsed.lines().size());
		check(Files.isRegularFile(Lrclib.cacheFile(track.spotifyId())), "lyrics should be cached");
		check(Lrclib.cacheFile("../../evil") == null, "bad track id must not become a file name");

		// 3. The login web server refuses a callback with the wrong "state".
		check(SpotifyAuth.setClientId("0123456789abcdef0123456789abcdef"), "client id accepted");
		check(!SpotifyAuth.setClientId("not a client id"), "bad client id refused");
		java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
		try {
			java.net.URI login = SpotifyAuth.startLogin(result::complete);
			check(login.toString().startsWith("https://accounts.spotify.com/authorize?"), "login url: " + login);
			check(login.toString().contains("code_challenge_method=S256"), "uses PKCE");
			java.net.http.HttpResponse<String> page = java.net.http.HttpClient.newBuilder()
					.proxy(java.net.http.HttpClient.Builder.NO_PROXY).build()
					.send(java.net.http.HttpRequest.newBuilder(
							java.net.URI.create(SpotifyAuth.REDIRECT_URI + "?code=abc&state=wrong")).build(),
							java.net.http.HttpResponse.BodyHandlers.ofString());
			check(page.statusCode() == 200, "callback page answered");
		} catch (Exception e) {
			throw new AssertionError(e);
		}
		String error = result.orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).join();
		check(error != null && error.contains("unexpected answer"), "wrong state refused: " + error);

		// 4. /lyrics spotify without login explains what to do (screenshot).
		SpotifyAuth.logout();
		command(context, "lyrics spotify");
		command(context, "lyrics spotify login");
		context.waitTicks(2);
		context.takeScreenshot("spotify-login-link");
	}

	/** Step 4: easing curves and the SCATTER style. */
	private void testScatter(ClientGameTestContext context) {
		for (float t : new float[] {0f, 1f}) {
			check(Math.abs(Easing.outCubic(t) - t) < 1e-5 && Math.abs(Easing.inCubic(t) - t) < 1e-5
					&& Math.abs(Easing.outBack(t) - t) < 1e-5 && Math.abs(Easing.inOutSine(t) - t) < 1e-5,
					"easings start at 0 and end at 1");
		}
		check(Easing.outBack(0.7f) > 1f, "outBack overshoots");
		check(Easing.outCubic(0.5f) > 0.5f && Easing.inCubic(0.5f) < 0.5f, "out is fast first, in is slow first");
		check(Easing.outCubic(2f) == 1f && Easing.outCubic(-1f) == 0f, "clamped");

		command(context, "lyrics offset 300");
		check(dev.lyricmod.LyricSettings.offsetMs == 300, "offset set");
		command(context, "lyrics offset 0");

		command(context, "lyrics style scatter");
		command(context, "lyrics play example");
		context.waitFor(client -> SongPlayer.isPlaying(), 100);
		waitForSongTime(context, 1300);
		context.takeScreenshot("step4-1.3s-first-word-popping");
		waitForSongTime(context, 4300);
		context.takeScreenshot("step4-4.3s-full-line");
		waitForSongTime(context, 5250);
		context.takeScreenshot("step4-5.25s-old-line-leaving");
		context.runOnClient(client -> client.player.setYRot(40));
		context.waitTicks(2);
		context.takeScreenshot("step4-turned-right");
		context.runOnClient(client -> client.player.setYRot(0));
		// Walk forward for a moment: the words must come along (screenshot while moving).
		context.getInput().holdKey(options -> options.keyUp);
		context.waitTicks(10);
		context.takeScreenshot("step4-walking");
		context.getInput().releaseKey(options -> options.keyUp);
		waitForSongTime(context, 8600);
		context.takeScreenshot("step4-8.6s-second-line");
		command(context, "lyrics stop");

		// A real song's lyrics (from LRCLIB, downloaded in testSpotifyParts) with the example tune.
		try {
			Files.copy(Lrclib.cacheFile("4u7EnebtmKWzUH433cf5Qv"), LyricFiles.folder().resolve("queen.lrc"));
			Files.copy(LyricFiles.folder().resolve("example.ogg"), LyricFiles.folder().resolve("queen.ogg"));
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		command(context, "lyrics play queen");
		context.waitFor(client -> SongPlayer.isPlaying(), 100);
		waitForSongTime(context, 11500);
		context.takeScreenshot("step4-real-lyrics");
		command(context, "lyrics stop");

		command(context, "lyrics play hebrew");
		context.waitFor(client -> SongPlayer.isPlaying(), 100);
		waitForSongTime(context, 7800);
		context.takeScreenshot("step4-hebrew-scatter");
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
