package dev.lyricmod;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Downloads synced lyrics from LRCLIB (https://lrclib.net), a free, open
 * database of .lrc lyrics that anyone can contribute to.
 *
 * Downloaded lyrics are saved in config/lyricmod/lrclib-cache/<spotify id>.lrc,
 * so each song is only downloaded once. If LRCLIB's timing for a song is off,
 * you can edit that file (or replace it with your own) and the mod will use it.
 */
public final class Lrclib {

	private static final String API = "https://lrclib.net/api/";

	/** LRCLIB asks apps to say who they are. */
	private static final String USER_AGENT = "LyricMod/0.1 (https://github.com/yuvalgihon-web/Block-Battles1)";

	/** Spotify track IDs are 22 letters/digits; checked before using one as a file name. */
	private static final Pattern TRACK_ID = Pattern.compile("[0-9A-Za-z]{22}");

	/** Accept search results whose length differs from Spotify's by at most this. */
	private static final double MAX_DURATION_DIFF_SECONDS = 5;

	/** What we know about a song from Spotify. */
	public record Track(String spotifyId, String title, String artist, String album, long durationMs) {
	}

	private Lrclib() {
	}

	/**
	 * Finds synced lyrics for a track: from the cache, or else from LRCLIB.
	 * Completes with null if no synced lyrics exist for this song.
	 */
	public static CompletableFuture<String> findLyrics(Track track) {
		Path cacheFile = cacheFile(track.spotifyId());
		if (cacheFile != null && Files.isRegularFile(cacheFile)) {
			try {
				return CompletableFuture.completedFuture(Files.readString(cacheFile, StandardCharsets.UTF_8));
			} catch (IOException e) {
				// unreadable cache file: just download again
			}
		}

		// 1. Exact lookup by title + artist + album + length.
		String exact = API + "get"
				+ "?track_name=" + SpotifyAuth.encode(track.title())
				+ "&artist_name=" + SpotifyAuth.encode(track.artist())
				+ "&album_name=" + SpotifyAuth.encode(track.album())
				+ "&duration=" + Math.round(track.durationMs() / 1000.0);

		return get(exact)
				.thenCompose(body -> {
					String lrc = body == null ? null : syncedLyrics(JsonParser.parseString(body));
					if (lrc != null) {
						return CompletableFuture.completedFuture(lrc);
					}
					// 2. Search by title + artist, and pick the result closest in length.
					String search = API + "search"
							+ "?track_name=" + SpotifyAuth.encode(track.title())
							+ "&artist_name=" + SpotifyAuth.encode(track.artist());
					return get(search).thenApply(results -> results == null ? null
							: bestSearchResult(JsonParser.parseString(results), track.durationMs() / 1000.0));
				})
				.thenApply(lrc -> {
					if (lrc != null && cacheFile != null) {
						try {
							Files.createDirectories(cacheFile.getParent());
							Files.writeString(cacheFile, lrc, StandardCharsets.UTF_8);
						} catch (IOException e) {
							LyricModClient.LOGGER.warn("Could not cache lyrics", e);
						}
					}
					return lrc;
				});
	}

	/** The cache file for a track, or null if the ID looks wrong. */
	public static Path cacheFile(String spotifyId) {
		if (spotifyId == null || !TRACK_ID.matcher(spotifyId).matches()) {
			return null;
		}
		return LyricFiles.folder().resolve("lrclib-cache").resolve(spotifyId + ".lrc");
	}

	/** GETs a LRCLIB address. Completes with the body, or null for "not found". */
	private static CompletableFuture<String> get(String url) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", USER_AGENT)
				.timeout(Duration.ofSeconds(15))
				.GET()
				.build();
		return SpotifyAuth.HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			if (response.statusCode() == 404) {
				return null;
			}
			if (response.statusCode() != 200) {
				throw new RuntimeException("LRCLIB answered " + response.statusCode());
			}
			return response.body();
		});
	}

	/** The "syncedLyrics" text of one LRCLIB result, or null. */
	private static String syncedLyrics(JsonElement element) {
		if (!element.isJsonObject()) {
			return null;
		}
		JsonElement synced = element.getAsJsonObject().get("syncedLyrics");
		return synced == null || synced.isJsonNull() || synced.getAsString().isBlank() ? null : synced.getAsString();
	}

	/** From a list of search results, the synced one whose length is closest to the song's. */
	static String bestSearchResult(JsonElement results, double durationSeconds) {
		if (!results.isJsonArray()) {
			return null;
		}
		String best = null;
		double bestDiff = MAX_DURATION_DIFF_SECONDS;
		JsonArray array = results.getAsJsonArray();
		for (JsonElement result : array) {
			String lrc = syncedLyrics(result);
			JsonObject object = result.getAsJsonObject();
			if (lrc == null || !object.has("duration") || object.get("duration").isJsonNull()) {
				continue;
			}
			double diff = Math.abs(object.get("duration").getAsDouble() - durationSeconds);
			if (diff <= bestDiff) {
				best = lrc;
				bestDiff = diff;
			}
		}
		return best;
	}
}
