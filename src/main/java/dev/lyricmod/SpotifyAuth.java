package dev.lyricmod;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.util.Util;

/**
 * Logs in to Spotify and keeps a valid "access token" (a temporary password
 * that lets us ask Spotify what is playing).
 *
 * How the login works (Spotify calls it "Authorization Code with PKCE"):
 *  1. You create a free app at developer.spotify.com and give us its Client ID.
 *  2. /lyrics spotify login shows a link. It opens Spotify's login page in your
 *     browser, where you allow "Lyric Mod" to see what you're listening to.
 *  3. Spotify sends your browser back to http://127.0.0.1:18742/callback, a
 *     tiny web server this class runs on YOUR computer for a few minutes.
 *  4. We trade the code from that address for tokens and save the long-lived
 *     "refresh token" in config/lyricmod/spotify.json, so you only log in once.
 *
 * We only ask for permission to READ what is playing; the mod can't change
 * your music, playlists or account.
 */
public final class SpotifyAuth {

	/** The exact address you must add as "Redirect URI" in your Spotify app. */
	public static final String REDIRECT_URI = "http://127.0.0.1:18742/callback";
	private static final int CALLBACK_PORT = 18742;

	/** What we ask permission for: only reading the current song. */
	private static final String SCOPES = "user-read-currently-playing user-read-playback-state";

	private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";

	/** Spotify Client IDs are 32 letters/digits. */
	private static final Pattern CLIENT_ID = Pattern.compile("[0-9a-zA-Z]{32}");

	static final HttpClient HTTP = HttpClient.newHttpClient();

	private static String clientId;
	private static String refreshToken;
	private static String accessToken;
	private static long accessTokenExpiresAt; // Util.getMillis() time

	/** The running login web server, if a login is in progress. */
	private static HttpServer loginServer;

	private SpotifyAuth() {
	}

	/** The file where the Client ID and refresh token are stored. Don't share it! */
	private static Path settingsFile() {
		return LyricFiles.folder().resolve("spotify.json");
	}

	/** Reads spotify.json (if it exists). Called once at startup. */
	public static void load() {
		try {
			Path file = settingsFile();
			if (Files.isRegularFile(file)) {
				JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
				clientId = json.has("clientId") ? json.get("clientId").getAsString() : null;
				refreshToken = json.has("refreshToken") ? json.get("refreshToken").getAsString() : null;
			}
		} catch (Exception e) {
			LyricModClient.LOGGER.warn("Could not read spotify.json", e);
		}
	}

	private static synchronized void save() {
		JsonObject json = new JsonObject();
		if (clientId != null) {
			json.addProperty("clientId", clientId);
		}
		if (refreshToken != null) {
			json.addProperty("refreshToken", refreshToken);
		}
		try {
			Files.createDirectories(LyricFiles.folder());
			Files.writeString(settingsFile(), json.toString());
		} catch (IOException e) {
			LyricModClient.LOGGER.warn("Could not save spotify.json", e);
		}
	}

	public static boolean hasClientId() {
		return clientId != null;
	}

	public static boolean isLoggedIn() {
		return refreshToken != null;
	}

	/** Saves the Client ID from /lyrics spotify setup. Returns false if it doesn't look like one. */
	public static boolean setClientId(String id) {
		if (!CLIENT_ID.matcher(id).matches()) {
			return false;
		}
		if (!id.equals(clientId)) {
			clientId = id;
			refreshToken = null; // a login belongs to one app
			accessToken = null;
			save();
		}
		return true;
	}

	/**
	 * Stops a login that is still waiting for the browser. Called when the game
	 * closes: the little web server would otherwise keep the game from exiting.
	 */
	public static void cancelLogin() {
		stopLoginServer();
	}

	/** Forgets the login (keeps the Client ID). */
	public static void logout() {
		stopLoginServer();
		refreshToken = null;
		accessToken = null;
		save();
	}

	/**
	 * Starts a login: opens the little local web server and returns the
	 * Spotify address the player must open in their browser.
	 *
	 * @param onDone called (on a background thread) with null on success,
	 *               or an error message
	 */
	public static synchronized URI startLogin(java.util.function.Consumer<String> onDone) throws IOException {
		stopLoginServer();

		// PKCE: a random secret ("verifier") that only we know, and its hash
		// ("challenge") that we send first. Spotify later checks they match.
		String verifier = randomString(64);
		String challenge = base64Url(sha256(verifier));
		String state = randomString(16); // protects against fake callbacks

		HttpServer server = HttpServer.create(
				new InetSocketAddress(InetAddress.getLoopbackAddress(), CALLBACK_PORT), 0);
		server.createContext("/callback", exchange -> {
			Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
			String error = null;
			if (!state.equals(query.get("state"))) {
				error = "Login failed: unexpected answer (please try again).";
			} else if (query.containsKey("error")) {
				error = "Login cancelled: " + query.get("error");
			}

			String page = error == null
					? "<h2>Lyric Mod is connected to Spotify.</h2><p>You can close this tab and go back to Minecraft.</p>"
					: "<h2>" + error + "</h2>";
			byte[] body = ("<html><body style='font-family:sans-serif'>" + page + "</body></html>")
					.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
			stopLoginServer();

			if (error != null) {
				onDone.accept(error);
				return;
			}
			// Trade the one-time code for tokens.
			requestTokens(Map.of(
					"grant_type", "authorization_code",
					"code", query.getOrDefault("code", ""),
					"redirect_uri", REDIRECT_URI,
					"client_id", clientId,
					"code_verifier", verifier))
					.whenComplete((ok, e) -> onDone.accept(e == null ? null : "Login failed: " + rootMessage(e)));
		});
		server.setExecutor(Executors.newSingleThreadExecutor(task -> {
			Thread thread = new Thread(task, "Lyric Mod Spotify login");
			thread.setDaemon(true); // never keeps the game from closing
			return thread;
		}));
		server.start();
		loginServer = server;

		// Give up after 5 minutes so the port isn't kept open forever.
		CompletableFuture.delayedExecutor(5, TimeUnit.MINUTES).execute(() -> {
			synchronized (SpotifyAuth.class) {
				if (loginServer == server) {
					stopLoginServer();
				}
			}
		});

		return URI.create("https://accounts.spotify.com/authorize"
				+ "?response_type=code"
				+ "&client_id=" + clientId
				+ "&scope=" + encode(SCOPES)
				+ "&redirect_uri=" + encode(REDIRECT_URI)
				+ "&code_challenge_method=S256"
				+ "&code_challenge=" + challenge
				+ "&state=" + state);
	}

	private static synchronized void stopLoginServer() {
		if (loginServer != null) {
			loginServer.stop(0);
			loginServer = null;
		}
	}

	/**
	 * Gets a valid access token, refreshing it when it has (almost) expired.
	 * Fails if not logged in.
	 */
	public static CompletableFuture<String> accessToken() {
		if (refreshToken == null) {
			return CompletableFuture.failedFuture(new IOException("not logged in"));
		}
		if (accessToken != null && Util.getMillis() < accessTokenExpiresAt - 60_000) {
			return CompletableFuture.completedFuture(accessToken);
		}
		return requestTokens(Map.of(
				"grant_type", "refresh_token",
				"refresh_token", refreshToken,
				"client_id", clientId))
				.thenApply(ignored -> accessToken);
	}

	/** Forces a new access token on the next request (e.g. after Spotify answered 401). */
	public static void invalidateAccessToken() {
		accessToken = null;
	}

	/** POSTs to Spotify's token address and stores the tokens from the answer. */
	private static CompletableFuture<Void> requestTokens(Map<String, String> form) {
		StringBuilder body = new StringBuilder();
		form.forEach((key, value) -> {
			if (!body.isEmpty()) {
				body.append('&');
			}
			body.append(encode(key)).append('=').append(encode(value));
		});
		HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();

		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
			if (response.statusCode() != 200) {
				if (response.statusCode() == 400 && form.get("grant_type").equals("refresh_token")) {
					// The saved login is no longer valid (e.g. access was removed).
					logout();
				}
				throw new RuntimeException("Spotify answered " + response.statusCode() + ": " + response.body());
			}
			JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
			synchronized (SpotifyAuth.class) {
				accessToken = json.get("access_token").getAsString();
				accessTokenExpiresAt = Util.getMillis() + json.get("expires_in").getAsLong() * 1000;
				if (json.has("refresh_token")) {
					refreshToken = json.get("refresh_token").getAsString();
					save();
				}
			}
		});
	}

	// ----- small helpers -----

	private static String randomString(int length) {
		String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
		SecureRandom random = new SecureRandom();
		StringBuilder result = new StringBuilder(length);
		for (int i = 0; i < length; i++) {
			result.append(chars.charAt(random.nextInt(chars.length())));
		}
		return result.toString();
	}

	private static byte[] sha256(String text) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.US_ASCII));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e); // every Java has SHA-256
		}
	}

	private static String base64Url(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String encode(String text) {
		return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static Map<String, String> parseQuery(String rawQuery) {
		Map<String, String> result = new HashMap<>();
		if (rawQuery == null) {
			return result;
		}
		for (String pair : rawQuery.split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0) {
				result.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
						URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
			}
		}
		return result;
	}

	static String rootMessage(Throwable e) {
		while (e.getCause() != null) {
			e = e.getCause();
		}
		return e.getMessage();
	}
}
