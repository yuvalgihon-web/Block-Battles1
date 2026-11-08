package dev.lyricmod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Registers the client-side command {@code /lyrics} and its sub-commands:
 *
 *   /lyrics test          show one floating test line (step 1)
 *   /lyrics play <name>   play config/lyricmod/<name>.ogg with <name>.lrc
 *   /lyrics stop          stop the current song
 *   /lyrics list          list the songs in config/lyricmod
 *   /lyrics record <name> make <name>.lrc by pressing a key at each line (see LyricRecorder)
 *   /lyrics spotify       show the lyrics of what Spotify is playing (see SpotifyMode)
 *   /lyrics spotify setup <client id> | login | logout
 *
 * "Client-side" means the command runs only inside your own game. It is never
 * sent to the server, so it works in singleplayer and on any server, even one
 * that doesn't have this mod.
 *
 * Commands use Mojang's "Brigadier" library: you build a tree of words.
 *   literal("lyrics")
 *     .then(literal("test"))
 *     .then(literal("play").then(argument("name", ...)))
 */
public final class LyricsCommand {

	/** How far in front of the player's eyes floating text appears, in blocks. */
	private static final double TEXT_DISTANCE = 4.0;

	private LyricsCommand() {
		// Only static methods; nobody should create an instance.
	}

	public static void register() {
		// Fabric calls this lambda whenever the client builds its command list.
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
				dispatcher.register(
						ClientCommands.literal("lyrics")
								.then(ClientCommands.literal("test")
										.executes(context -> runTest(context.getSource())))
								.then(ClientCommands.literal("play")
										// greedyString = "the rest of the line", so names may contain spaces.
										.then(ClientCommands.argument("name", StringArgumentType.greedyString())
												// Pressing Tab suggests the songs found in the folder.
												.suggests((context, builder) ->
														SharedSuggestionProvider.suggest(LyricFiles.listSongs(), builder))
												.executes(context -> runPlay(context.getSource(),
														StringArgumentType.getString(context, "name")))))
								.then(ClientCommands.literal("record")
										.then(ClientCommands.argument("name", StringArgumentType.greedyString())
												.suggests((context, builder) ->
														SharedSuggestionProvider.suggest(LyricFiles.listRecordable(), builder))
												.executes(context -> runRecord(context.getSource(),
														StringArgumentType.getString(context, "name")))))
								.then(ClientCommands.literal("spotify")
										.executes(context -> runSpotify(context.getSource()))
										.then(ClientCommands.literal("setup")
												.then(ClientCommands.argument("clientId", StringArgumentType.word())
														.executes(context -> runSpotifySetup(context.getSource(),
																StringArgumentType.getString(context, "clientId")))))
										.then(ClientCommands.literal("login")
												.executes(context -> runSpotifyLogin(context.getSource())))
										.then(ClientCommands.literal("logout")
												.executes(context -> runSpotifyLogout(context.getSource()))))
								.then(ClientCommands.literal("stop")
										.executes(context -> runStop(context.getSource())))
								.then(ClientCommands.literal("list")
										.executes(context -> runList(context.getSource())))
				)
		);
	}

	/** /lyrics test — shows one floating line. Returns 1 to mean "success". */
	private static int runTest(FabricClientCommandSource source) {
		showInFrontOfPlayer(source.getPlayer(), "♪ Hello from Lyric Mod ♪", 0);
		source.sendFeedback(Component.literal("Showing a test lyric line."));
		return 1;
	}

	/** /lyrics play <name> — loads the lyrics, then starts the music. */
	private static int runPlay(FabricClientCommandSource source, String rawName) {
		String name = rawName.strip();
		Lyrics lyrics;
		Path oggFile;
		try {
			lyrics = LyricFiles.loadLyrics(name);
			oggFile = LyricFiles.songFile(name, ".ogg");
		} catch (IOException e) {
			source.sendError(Component.literal(e.getMessage()));
			return 0; // 0 = the command failed
		}

		if (lyrics.lines().isEmpty()) {
			source.sendError(Component.literal(name + ".lrc has no timed lyric lines like [00:12.34]text"));
			return 0;
		}

		LyricRecorder.cancel(); // playing a song stops any recording
		SpotifyMode.stop();     // ...and following Spotify
		source.sendFeedback(Component.literal("Loading " + name + "..."));
		SongPlayer.play(oggFile, lyrics,
				() -> {
					// The music has started.
					String byArtist = lyrics.artist().isEmpty() ? "" : " — " + lyrics.artist();
					source.sendFeedback(Component.literal("♪ Now playing: " + lyrics.title() + byArtist
							+ " (" + lyrics.lines().size() + " lyric lines)"));
					// Float the song title in front of us, a bit above where the lyrics appear.
					showInFrontOfPlayer(source.getPlayer(), "♪ " + lyrics.title() + byArtist + " ♪", 1.8);
				},
				error -> source.sendError(Component.literal(error)));
		return 1;
	}

	/** /lyrics record <name> */
	private static int runRecord(FabricClientCommandSource source, String rawName) {
		try {
			LyricRecorder.start(rawName.strip());
		} catch (IOException e) {
			source.sendError(Component.literal(e.getMessage()));
			return 0;
		}
		source.sendFeedback(Component.literal("Loading " + rawName.strip() + "..."));
		return 1;
	}

	/** /lyrics spotify — start following Spotify. */
	private static int runSpotify(FabricClientCommandSource source) {
		if (!SpotifyAuth.hasClientId()) {
			source.sendError(Component.literal("First connect Spotify once:"));
			source.sendFeedback(Component.literal(
					"1. Open developer.spotify.com/dashboard, log in, and click \"Create app\"."));
			source.sendFeedback(Component.literal(
					"2. Add this Redirect URI: " + SpotifyAuth.REDIRECT_URI + " and tick \"Web API\"."));
			source.sendFeedback(Component.literal(
					"3. Copy the app's Client ID and type: /lyrics spotify setup <Client ID>"));
			return 0;
		}
		if (!SpotifyAuth.isLoggedIn()) {
			source.sendError(Component.literal("Not logged in to Spotify yet. Type: /lyrics spotify login"));
			return 0;
		}
		SpotifyMode.start();
		source.sendFeedback(Component.literal(
				"Following Spotify. Play a song in the Spotify app! (/lyrics stop to stop)"));
		return 1;
	}

	/** /lyrics spotify setup <client id> */
	private static int runSpotifySetup(FabricClientCommandSource source, String clientId) {
		if (!SpotifyAuth.setClientId(clientId.strip())) {
			source.sendError(Component.literal("That doesn't look like a Client ID (it has 32 letters and digits)."));
			return 0;
		}
		source.sendFeedback(Component.literal("Client ID saved. Now type: /lyrics spotify login"));
		return 1;
	}

	/** /lyrics spotify login — shows a link that opens Spotify's login page. */
	private static int runSpotifyLogin(FabricClientCommandSource source) {
		if (!SpotifyAuth.hasClientId()) {
			return runSpotify(source); // prints the setup steps
		}
		java.net.URI loginPage;
		try {
			loginPage = SpotifyAuth.startLogin(error -> source.getClient().execute(() -> {
				if (error == null) {
					source.sendFeedback(Component.literal("Connected to Spotify! Type /lyrics spotify to start."));
				} else {
					source.sendError(Component.literal(error));
				}
			}));
		} catch (IOException e) {
			source.sendError(Component.literal("Could not start the login (is port 18742 in use?): " + e.getMessage()));
			return 0;
		}
		// A clickable link: Minecraft asks "open this link?" and then opens your browser.
		source.sendFeedback(Component.literal("▶ Click here to log in to Spotify").withStyle(style -> style
				.withColor(net.minecraft.ChatFormatting.GREEN)
				.withUnderlined(true)
				.withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(loginPage))));
		source.sendFeedback(Component.literal("(Allow access in the browser, then come back here.)"));
		return 1;
	}

	/** /lyrics spotify logout */
	private static int runSpotifyLogout(FabricClientCommandSource source) {
		SpotifyMode.stop();
		SpotifyAuth.logout();
		source.sendFeedback(Component.literal("Logged out of Spotify."));
		return 1;
	}

	/** /lyrics stop */
	private static int runStop(FabricClientCommandSource source) {
		if (SpotifyMode.isActive()) {
			SpotifyMode.stop();
			source.sendFeedback(Component.literal("Stopped following Spotify."));
			return 1;
		}
		if (LyricRecorder.isRecording()) {
			LyricRecorder.cancel();
			SongPlayer.stop();
			source.sendFeedback(Component.literal("Recording cancelled, nothing saved."));
			return 1;
		}
		if (!SongPlayer.isPlaying()) {
			source.sendFeedback(Component.literal("Nothing is playing."));
			return 1;
		}
		SongPlayer.stop();
		source.sendFeedback(Component.literal("Stopped."));
		return 1;
	}

	/** /lyrics list */
	private static int runList(FabricClientCommandSource source) {
		List<String> songs = LyricFiles.listSongs();
		if (songs.isEmpty()) {
			source.sendFeedback(Component.literal("No songs found. Put name.ogg + name.lrc in " + LyricFiles.folder()));
		} else {
			source.sendFeedback(Component.literal("Songs: " + String.join(", ", songs)));
		}
		return 1;
	}

	/**
	 * Puts a floating line of text a few blocks in front of the player's eyes.
	 *
	 * @param raise how many blocks higher than straight ahead to put it
	 */
	private static void showInFrontOfPlayer(LocalPlayer player, String text, double raise) {
		// getLookAngle gives a direction of length 1; scaling it by 4 gives a
		// point 4 blocks along that direction.
		Vec3 position = player.getEyePosition().add(player.getLookAngle().scale(TEXT_DISTANCE)).add(0, raise, 0);
		LyricRenderer.show(new FloatingText(text, position, LyricRenderer.nowMs()));
	}
}
