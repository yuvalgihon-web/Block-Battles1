package dev.lyricmod;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Registers the client-side command {@code /lyrics}.
 *
 * "Client-side" means the command runs only inside your own game. It is never
 * sent to the server, so it works in singleplayer and on any server, even one
 * that doesn't have this mod.
 *
 * Commands use Mojang's "Brigadier" library: you build a tree of words.
 *   /lyrics            -> literal("lyrics")
 *   /lyrics test       ->   .then(literal("test"))
 * Step 2 will add another branch here:
 *   /lyrics play <name> -> .then(literal("play").then(argument("name", ...)))
 */
public final class LyricsCommand {

	/** How far in front of the player's eyes the test line appears, in blocks. */
	private static final double TEST_DISTANCE = 4.0;

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
				)
		);
	}

	/** Runs when the player types /lyrics test. Returns 1 to mean "success". */
	private static int runTest(FabricClientCommandSource source) {
		LocalPlayer player = source.getPlayer();

		// Where are the player's eyes, and which way are they looking?
		// getLookAngle gives a direction of length 1; scaling it by 4 gives a
		// point 4 blocks along that direction.
		Vec3 eyes = player.getEyePosition();
		Vec3 lookDirection = player.getLookAngle();
		Vec3 position = eyes.add(lookDirection.scale(TEST_DISTANCE));

		// Hand the line to the renderer, which starts drawing it next frame.
		LyricRenderer.show(new FloatingText(
				"♪ Hello from Lyric Mod ♪",
				position,
				LyricRenderer.nowMs()
		));

		source.sendFeedback(Component.literal("Showing a test lyric line."));
		return 1;
	}
}
