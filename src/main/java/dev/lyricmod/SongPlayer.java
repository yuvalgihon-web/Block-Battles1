package dev.lyricmod;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCStdlib;

/**
 * Plays one .ogg song at a time and tells us how far into the song we are.
 *
 * Why not use Minecraft's normal sound system? It can only play sounds that
 * come from resource packs. Our songs are loose files in config/lyricmod, so we
 * talk to OpenAL directly. OpenAL is the audio library Minecraft itself uses
 * (it comes with the game through LWJGL), so we reuse the audio device Minecraft
 * already opened.
 *
 * The most important method for later steps is {@link #positionMs()}: the exact
 * playback time of the song. Step 3 will use it to show each lyric at the right
 * moment, which stays in sync even if the game lags.
 *
 * Threading: all OpenAL calls happen on the game's main ("render") thread.
 * Only the slow part, decoding the .ogg file, runs on a background thread so
 * the game doesn't freeze for a second when a song starts.
 */
public final class SongPlayer {

	/** The decoded audio: raw sound samples plus their format. */
	private record DecodedAudio(ShortBuffer samples, int channels, int sampleRate) {
	}

	private static int source;          // OpenAL "speaker" playing our song (0 = none)
	private static int buffer;          // OpenAL memory holding the song's samples (0 = none)
	private static Lyrics lyrics;       // lyrics of the song that is playing
	private static boolean pausedByGame;
	private static int requestId;       // increases with every play/stop, to ignore outdated loads

	private SongPlayer() {
	}

	/**
	 * Starts loading and then playing a song. Returns right away; the music
	 * starts a moment later, once the file is decoded.
	 *
	 * @param oggFile   the .ogg file (already checked by LyricFiles)
	 * @param newLyrics the parsed lyrics for this song
	 * @param onStarted runs on the game thread when the music actually starts
	 * @param onError   runs on the game thread if the file can't be played
	 */
	public static void play(Path oggFile, Lyrics newLyrics, Runnable onStarted, java.util.function.Consumer<String> onError) {
		stop();
		int myRequest = ++requestId;
		Minecraft client = Minecraft.getInstance();

		CompletableFuture
				.supplyAsync(() -> decode(oggFile))   // background thread
				.whenComplete((audio, error) -> client.execute(() -> { // back on the game thread
					if (myRequest != requestId) {
						// The player typed /lyrics stop or played another song meanwhile.
						if (audio != null) {
							LibCStdlib.free(audio.samples());
						}
						return;
					}
					if (error != null) {
						Throwable cause = error.getCause() != null ? error.getCause() : error;
						onError.accept("Could not read " + oggFile.getFileName() + ": " + cause.getMessage());
						return;
					}
					String problem = start(audio);
					if (problem != null) {
						onError.accept(problem);
						return;
					}
					lyrics = newLyrics;
					onStarted.run();
				}));
	}

	/**
	 * Reads the .ogg file and decodes it into raw samples.
	 * stb_vorbis is a small Ogg decoder that Minecraft also ships with.
	 */
	private static DecodedAudio decode(Path oggFile) {
		ByteBuffer fileData = null;
		try {
			long size = Files.size(oggFile);
			if (size > LyricFiles.MAX_OGG_BYTES) {
				throw new IOException("file is too big (max 50 MB)");
			}
			// stb_vorbis needs the file in "native" memory (outside Java's heap).
			byte[] bytes = Files.readAllBytes(oggFile);
			fileData = MemoryUtil.memAlloc(bytes.length);
			fileData.put(bytes).flip();

			try (MemoryStack stack = MemoryStack.stackPush()) {
				IntBuffer channels = stack.mallocInt(1);
				IntBuffer sampleRate = stack.mallocInt(1);
				ShortBuffer samples = STBVorbis.stb_vorbis_decode_memory(fileData, channels, sampleRate);
				if (samples == null) {
					throw new IOException("not a valid Ogg Vorbis file");
				}
				return new DecodedAudio(samples, channels.get(0), sampleRate.get(0));
			}
		} catch (IOException e) {
			throw new RuntimeException(e.getMessage(), e);
		} finally {
			if (fileData != null) {
				MemoryUtil.memFree(fileData);
			}
		}
	}

	/** Hands the decoded samples to OpenAL and presses "play". Returns an error message or null. */
	private static String start(DecodedAudio audio) {
		try {
			if (ALC10.alcGetCurrentContext() == 0) {
				return "Minecraft's sound system isn't running (check your audio device).";
			}
			int format;
			if (audio.channels() == 1) {
				format = AL10.AL_FORMAT_MONO16;
			} else if (audio.channels() == 2) {
				format = AL10.AL_FORMAT_STEREO16;
			} else {
				return "Only mono or stereo .ogg files are supported.";
			}

			AL10.alGetError(); // clear any old error
			buffer = AL10.alGenBuffers();
			AL10.alBufferData(buffer, format, audio.samples(), audio.sampleRate());
			source = AL10.alGenSources();
			AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
			// "Relative at 0,0,0" = the sound plays at your head, not somewhere in the world.
			AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
			AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, 0f);
			AL10.alSourcef(source, AL10.AL_GAIN, volume());
			AL10.alSourcePlay(source);

			int error = AL10.alGetError();
			if (error != AL10.AL_NO_ERROR) {
				stop();
				return "OpenAL error " + error + " while starting the song.";
			}
			return null;
		} finally {
			// OpenAL made its own copy of the samples, so we can free ours now.
			LibCStdlib.free(audio.samples());
		}
	}

	/** Stops the song (if any) and frees its memory. Safe to call any time. */
	public static void stop() {
		requestId++;
		if (source != 0) {
			AL10.alSourceStop(source);
			AL10.alDeleteSources(source);
			source = 0;
		}
		if (buffer != 0) {
			AL10.alDeleteBuffers(buffer);
			buffer = 0;
		}
		lyrics = null;
		pausedByGame = false;
	}

	/**
	 * Replaces the lyrics of the playing song. Used by the recorder to show
	 * the lines marked so far while you record.
	 */
	public static void setLyrics(Lyrics newLyrics) {
		if (source != 0) {
			lyrics = newLyrics;
		}
	}

	/** True while a song is loaded (playing or paused). */
	public static boolean isPlaying() {
		return source != 0;
	}

	/** Lyrics of the current song, or null if nothing is playing. */
	public static Lyrics currentLyrics() {
		return lyrics;
	}

	/**
	 * How far into the song we are, in milliseconds (0 if nothing is playing).
	 * This comes straight from the audio device, so it is exactly what you hear.
	 */
	public static long positionMs() {
		if (source == 0) {
			return 0;
		}
		return (long) (AL10.alGetSourcef(source, AL11.AL_SEC_OFFSET) * 1000);
	}

	/**
	 * Called every game tick (20 times per second):
	 * follows the volume slider, pauses with the game, and cleans up at the end.
	 */
	public static void tick(Minecraft client) {
		if (source == 0) {
			return;
		}
		AL10.alSourcef(source, AL10.AL_GAIN, volume());

		int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
		boolean gamePaused = client.isPaused();

		if (gamePaused && state == AL10.AL_PLAYING) {
			// Singleplayer pause menu: pause the music too, so lyrics stay in sync.
			AL10.alSourcePause(source);
			pausedByGame = true;
		} else if (!gamePaused && pausedByGame) {
			AL10.alSourcePlay(source);
			pausedByGame = false;
		} else if (state == AL10.AL_STOPPED) {
			stop(); // The song reached its end.
		}
	}

	/**
	 * The song's volume: the "Jukebox/Note Blocks" slider (times "Master")
	 * from Minecraft's Music & Sounds options, so players control it as usual.
	 */
	private static float volume() {
		return Minecraft.getInstance().options.getFinalSoundSourceVolume(SoundSource.RECORDS);
	}
}
