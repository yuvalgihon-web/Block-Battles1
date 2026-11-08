package dev.lyricmod;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/**
 * "Spotify mode": the music plays in the Spotify app, and the mod shows the
 * lyrics of whatever Spotify is playing, in sync.
 *
 * About once a second we ask Spotify "what is playing, and how far in?".
 * Between those answers we keep counting forward ourselves, so the lyrics
 * move smoothly. When the song changes, we fetch its lyrics from LRCLIB.
 *
 * The rest of the mod doesn't care where the time comes from:
 * SyncedLyricsDisplay just gets {@link #currentLyrics()} and {@link #positionMs()}.
 */
public final class SpotifyMode {

	private static final String NOW_PLAYING_URL = "https://api.spotify.com/v1/me/player/currently-playing";

	/** How often to ask Spotify, while music plays / while it doesn't. */
	private static final long POLL_PLAYING_MS = 1000;
	private static final long POLL_IDLE_MS = 3000;

	/**
	 * Small backwards corrections from Spotify are ignored (lyrics would
	 * flicker back); only real jumps (you seeking in the song) are followed.
	 */
	private static final long IGNORE_BACKWARDS_MS = 400;

	private static boolean active;
	private static boolean requestInFlight;
	private static boolean warnedAboutConnection;
	private static long nextPollAt;

	/** The current track, its lyrics (null while loading / if none). */
	private static Lrclib.Track track;
	private static Lyrics lyrics;

	/** Playback position: Spotify said {@code basePositionMs} at our time {@code baseTime}. */
	private static boolean playing;
	private static long basePositionMs;
	private static long baseTime;

	private SpotifyMode() {
	}

	public static boolean isActive() {
		return active;
	}

	/** Starts following Spotify. */
	public static void start() {
		SongPlayer.stop();
		LyricRecorder.cancel();
		active = true;
		warnedAboutConnection = false;
		track = null;
		lyrics = null;
		playing = false;
		nextPollAt = 0;
	}

	/** Stops following Spotify. */
	public static void stop() {
		active = false;
		track = null;
		lyrics = null;
	}

	/** Lyrics of the song Spotify is playing, or null. */
	public static Lyrics currentLyrics() {
		return active ? lyrics : null;
	}

	/** Estimated position in the Spotify song right now, in milliseconds. */
	public static long positionMs() {
		if (!playing) {
			return basePositionMs;
		}
		return basePositionMs + (Util.getMillis() - baseTime);
	}

	/** Called every game tick: asks Spotify again when it's time. */
	public static void tick(Minecraft client) {
		if (!active || requestInFlight || Util.getMillis() < nextPollAt) {
			return;
		}
		requestInFlight = true;
		long sentAt = Util.getMillis();

		SpotifyAuth.accessToken()
				.thenCompose(token -> SpotifyAuth.HTTP.sendAsync(
						HttpRequest.newBuilder(URI.create(NOW_PLAYING_URL))
								.header("Authorization", "Bearer " + token)
								.timeout(Duration.ofSeconds(10))
								.GET().build(),
						HttpResponse.BodyHandlers.ofString()))
				.whenComplete((response, error) -> client.execute(() -> {
					requestInFlight = false;
					if (!active) {
						return;
					}
					if (error != null) {
						if (!SpotifyAuth.isLoggedIn()) {
							fail(client, "Not logged in to Spotify. Use /lyrics spotify login");
							return;
						}
						// Probably a short internet problem: tell the player once, keep trying.
						if (!warnedAboutConnection) {
							warnedAboutConnection = true;
							message(client, "Can't reach Spotify (" + SpotifyAuth.rootMessage(error) + "), retrying...");
						}
						nextPollAt = Util.getMillis() + 5000;
						return;
					}
					warnedAboutConnection = false;
					handleResponse(client, response, sentAt);
				}));
	}

	/** Reacts to Spotify's answer. Runs on the game thread. */
	private static void handleResponse(Minecraft client, HttpResponse<String> response, long sentAt) {
		long now = Util.getMillis();
		int status = response.statusCode();

		if (status == 401) {
			// Access token expired early: get a new one and try again soon.
			SpotifyAuth.invalidateAccessToken();
			nextPollAt = now + 500;
			return;
		}
		if (status == 429) {
			// "Too many requests": wait as long as Spotify asks.
			long waitSeconds = response.headers().firstValueAsLong("Retry-After").orElse(5);
			nextPollAt = now + waitSeconds * 1000;
			return;
		}
		if (status == 204 || status == 200 && response.body().isBlank()) {
			// Nothing is playing.
			playing = false;
			nextPollAt = now + POLL_IDLE_MS;
			return;
		}
		if (status != 200) {
			fail(client, "Spotify answered " + status + ". Stopped following Spotify.");
			return;
		}

		NowPlaying info = parseNowPlaying(response.body());
		if (info == null) {
			// Something else is playing (e.g. a podcast or an ad).
			playing = false;
			nextPollAt = now + POLL_IDLE_MS;
			return;
		}

		// Spotify measured progress somewhere during our request; assume the middle.
		long measuredAt = (sentAt + now) / 2;
		long predicted = positionMs();
		boolean sameTrack = track != null && track.spotifyId().equals(info.track().spotifyId());
		long reported = info.progressMs() + (info.isPlaying() ? now - measuredAt : 0);

		if (sameTrack && playing && info.isPlaying()
				&& reported < predicted && predicted - reported < IGNORE_BACKWARDS_MS) {
			// Tiny backwards correction: keep counting smoothly instead.
		} else {
			basePositionMs = reported;
			baseTime = now;
		}
		playing = info.isPlaying();
		nextPollAt = now + (playing ? POLL_PLAYING_MS : POLL_IDLE_MS);

		if (!sameTrack) {
			changeTrack(client, info.track());
		}
	}

	/** A new song started in Spotify: show its title and fetch its lyrics. */
	private static void changeTrack(Minecraft client, Lrclib.Track newTrack) {
		track = newTrack;
		lyrics = null;
		message(client, "♪ Spotify: " + newTrack.title() + " — " + newTrack.artist());

		Lrclib.findLyrics(newTrack).whenComplete((lrc, error) -> client.execute(() -> {
			if (track != newTrack || !active) {
				return; // the song changed again meanwhile
			}
			if (error != null) {
				message(client, "Could not get lyrics from LRCLIB: " + SpotifyAuth.rootMessage(error));
			} else if (lrc == null) {
				message(client, "No synced lyrics on LRCLIB for this song. You can add your own: "
						+ "config/lyricmod/lrclib-cache/" + newTrack.spotifyId() + ".lrc");
			} else {
				lyrics = LrcParser.parse(lrc, newTrack.title());
			}
		}));
	}

	/** What we need from Spotify's "currently playing" answer. */
	public record NowPlaying(Lrclib.Track track, long progressMs, boolean isPlaying) {
	}

	/** Reads Spotify's JSON answer. Returns null if it isn't a music track. */
	public static NowPlaying parseNowPlaying(String body) {
		JsonObject json = JsonParser.parseString(body).getAsJsonObject();
		JsonElement item = json.get("item");
		if (item == null || item.isJsonNull() || !"track".equals(stringOrNull(json, "currently_playing_type"))) {
			return null;
		}
		JsonObject song = item.getAsJsonObject();

		List<String> artists = new ArrayList<>();
		if (song.has("artists")) {
			for (JsonElement artist : song.getAsJsonArray("artists")) {
				artists.add(artist.getAsJsonObject().get("name").getAsString());
			}
		}
		String album = song.has("album") ? stringOrNull(song.getAsJsonObject("album"), "name") : null;

		String title = stringOrNull(song, "name");
		String id = stringOrNull(song, "id");
		if (id == null) {
			id = "local:" + title; // songs from your own computer in Spotify have no ID
		}
		Lrclib.Track track = new Lrclib.Track(
				id,
				title == null ? "" : title,
				artists.isEmpty() ? "" : artists.get(0), // LRCLIB matches best on the main artist
				album == null ? "" : album,
				song.get("duration_ms").getAsLong());
		long progress = json.has("progress_ms") && !json.get("progress_ms").isJsonNull()
				? json.get("progress_ms").getAsLong() : 0;
		boolean isPlaying = json.has("is_playing") && json.get("is_playing").getAsBoolean();
		return new NowPlaying(track, progress, isPlaying);
	}

	private static String stringOrNull(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value == null || value.isJsonNull() ? null : value.getAsString();
	}

	private static void fail(Minecraft client, String text) {
		stop();
		message(client, text);
	}

	private static void message(Minecraft client, String text) {
		if (client.player != null) {
			client.player.sendSystemMessage(Component.literal(text));
		}
	}
}
