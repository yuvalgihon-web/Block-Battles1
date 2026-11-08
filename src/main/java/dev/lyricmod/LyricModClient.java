package dev.lyricmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mod's entry point.
 *
 * fabric.mod.json lists this class under "entrypoints" -> "client", so Fabric
 * calls {@link #onInitializeClient()} once while the game is starting up.
 * Our job here is only to "plug in" the other parts of the mod. After that,
 * those classes are called by the game whenever they're needed.
 */
public class LyricModClient implements ClientModInitializer {

	/** Used in log messages and for the config folder name: config/lyricmod */
	public static final String MOD_ID = "lyricmod";

	/** Writes to the game log (the console when you use ./gradlew runClient). */
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		LyricFiles.createFolderIfMissing();
		LyricsCommand.register();
		LyricRenderer.register();

		// 20 times per second: keep the song's volume/pause state up to date.
		ClientTickEvents.END_CLIENT_TICK.register(SongPlayer::tick);

		// Stop the music when leaving a world or server, and when the game closes.
		// (execute = "do it on the game thread", where the audio code must run.)
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(SongPlayer::stop));
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> SongPlayer.stop());

		LOGGER.info("Lyric Mod loaded. Songs folder: {}", LyricFiles.folder());
	}
}
