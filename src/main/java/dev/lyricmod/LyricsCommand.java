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
								.then(ClientCommands.literal("stop")
										.executes(context -> runStop(context.getSource())))
								.then(ClientCommands.literal("list")
										.executes(context -> runList(context.getSource())))
				)
		);
	}

	/** /lyrics test — shows one floating line. Returns 1 to mean "success". */
	private static int runTest(FabricClientCommandSource source) {
		showInFrontOfPlayer(source.getPlayer(), "♪ Hello from Lyric Mod ♪");
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

		source.sendFeedback(Component.literal("Loading " + name + "..."));
		SongPlayer.play(oggFile, lyrics,
				() -> {
					// The music has started.
					String byArtist = lyrics.artist().isEmpty() ? "" : " — " + lyrics.artist();
					source.sendFeedback(Component.literal("♪ Now playing: " + lyrics.title() + byArtist
							+ " (" + lyrics.lines().size() + " lyric lines)"));
					// Until step 3 shows the real lyrics, float the song title in front of us.
					showInFrontOfPlayer(source.getPlayer(), "♪ " + lyrics.title() + byArtist + " ♪");
				},
				error -> source.sendError(Component.literal(error)));
		return 1;
	}

	/** /lyrics stop */
	private static int runStop(FabricClientCommandSource source) {
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

	/** Puts a floating line of text a few blocks in front of the player's eyes. */
	private static void showInFrontOfPlayer(LocalPlayer player, String text) {
		// getLookAngle gives a direction of length 1; scaling it by 4 gives a
		// point 4 blocks along that direction.
		Vec3 position = player.getEyePosition().add(player.getLookAngle().scale(TEXT_DISTANCE));
		LyricRenderer.show(new FloatingText(text, position, LyricRenderer.nowMs()));
	}
}
