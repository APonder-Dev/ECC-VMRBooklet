package dev.aponder.vmrbooklet.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;

/**
 * Loads a user's OWN Google service-account key and exchanges it for a
 * short-lived Sheets API access token via the standard JWT-bearer OAuth2 flow
 * (no browser, no interactive login). This key is never bundled with the mod -
 * each person who wants write-back creates their own in Google Cloud Console
 * and shares only their own sheet with it. See the mod's docs for setup steps.
 */
public final class GoogleServiceAccount {

	private static final Path FILE = FabricLoader.getInstance().getConfigDir()
			.resolve("vmrbooklet").resolve("service-account.json");
	private static final String SCOPE = "https://www.googleapis.com/auth/spreadsheets";

	private static String cachedToken;
	private static long cachedTokenExpiryEpochSeconds;
	// volatile: written inside synchronized getAccessToken() (called from
	// /upload's virtual thread) and read/written from the unsynchronized
	// clientEmailOrNull() (called from BookletAutoPush's own executor thread) -
	// without volatile there's no happens-before edge between those two threads.
	private static volatile String cachedClientEmail;

	private GoogleServiceAccount() {
	}

	public static boolean isConfigured() {
		return Files.exists(FILE);
	}

	/** @return the service account's own email (for "share your sheet with..." messages), or null if unreadable/not configured. */
	public static String clientEmailOrNull() {
		if (cachedClientEmail != null) {
			return cachedClientEmail;
		}
		try {
			JsonObject key = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
			if (key.has("client_email")) {
				cachedClientEmail = key.get("client_email").getAsString();
			}
		} catch (Exception ignored) {
			// Fine - this is only used to enrich an error message, never load-bearing.
		}
		return cachedClientEmail;
	}

	public static synchronized String getAccessToken() throws Exception {
		long now = System.currentTimeMillis() / 1000;
		if (cachedToken != null && now < cachedTokenExpiryEpochSeconds - 60) {
			return cachedToken;
		}

		JsonObject key;
		try {
			key = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
		} catch (Exception e) {
			throw new IllegalStateException("your service-account.json isn't valid JSON - re-download the key file from "
					+ "Google Cloud Console and replace it.", e);
		}
		if (!key.has("client_email")) {
			throw new IllegalStateException("your service-account.json is missing \"client_email\" - make sure you "
					+ "downloaded the whole JSON key file, not a partial copy.");
		}
		if (!key.has("private_key")) {
			throw new IllegalStateException("your service-account.json is missing \"private_key\" - make sure you "
					+ "downloaded the whole JSON key file, not a partial copy.");
		}

		String clientEmail = key.get("client_email").getAsString();
		cachedClientEmail = clientEmail;
		String tokenUri = key.has("token_uri") ? key.get("token_uri").getAsString() : "https://oauth2.googleapis.com/token";
		PrivateKey privateKey;
		try {
			privateKey = parsePrivateKey(key.get("private_key").getAsString());
		} catch (Exception e) {
			throw new IllegalStateException("couldn't read the \"private_key\" in your service-account.json - it may be "
					+ "corrupted or truncated. Re-download the key file from Google Cloud Console.", e);
		}

		long iat = now;
		long exp = now + 3600;
		String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
		String claims = base64Url("{\"iss\":\"" + clientEmail + "\",\"scope\":\"" + SCOPE + "\",\"aud\":\""
				+ tokenUri + "\",\"iat\":" + iat + ",\"exp\":" + exp + "}");
		String signingInput = header + "." + claims;

		Signature signature = Signature.getInstance("SHA256withRSA");
		signature.initSign(privateKey);
		signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
		String signed = Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
		String jwt = signingInput + "." + signed;

		String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
				+ "&assertion=" + URLEncoder.encode(jwt, StandardCharsets.UTF_8);

		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUri))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(form))
				.timeout(Duration.ofSeconds(15))
				.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		if (response.statusCode() != 200) {
			throw new IOException("Google token request failed: HTTP " + response.statusCode() + " " + response.body());
		}

		JsonObject tokenResponse = JsonParser.parseString(response.body()).getAsJsonObject();
		cachedToken = tokenResponse.get("access_token").getAsString();
		int expiresIn = tokenResponse.has("expires_in") ? tokenResponse.get("expires_in").getAsInt() : 3600;
		cachedTokenExpiryEpochSeconds = now + expiresIn;
		return cachedToken;
	}

	private static String base64Url(String json) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	private static PrivateKey parsePrivateKey(String pem) throws Exception {
		String cleaned = pem
				.replace("-----BEGIN PRIVATE KEY-----", "")
				.replace("-----END PRIVATE KEY-----", "")
				.replace("\n", "")
				.replace("\r", "")
				.trim();
		byte[] der = Base64.getDecoder().decode(cleaned);
		return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
	}
}
