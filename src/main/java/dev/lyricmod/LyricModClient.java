package dev.lyricmod;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mod's entry point.
 *
 * fabric.mod.json lists this class under "entrypoints" -> "client", so Fabric
 * calls {@link #onInitializeClient()} once while the game is starting up.
 * Our job here is only to "plug in" the other parts of the mod: we register
 * the /lyrics command and the world renderer. After that, those classes are
 * called by the game whenever they're needed.
 */
public class LyricModClient implements ClientModInitializer {

	/** Used in log messages and (later) for the config folder name: config/lyricmod */
	public static final String MOD_ID = "lyricmod";

	/** Writes to the game log (the console when you use ./gradlew runClient). */
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		LyricsCommand.register();
		LyricRenderer.register();
		LOGGER.info("Lyric Mod loaded. Try /lyrics test in a world!");
	}
}
