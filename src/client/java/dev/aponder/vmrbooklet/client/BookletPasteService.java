package dev.aponder.vmrbooklet.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Posts/fetches plain text via mclo.gs's public paste API (no account, no API
 * key) - used so a share/backup code, which can run several hundred
 * characters and is too long to paste into Minecraft's own chat box, can
 * instead be handed around as a short id. mclo.gs pastes expire after about
 * 90 days.
 *
 * <p>This is the one part of the share/backup flow that leaves the local
 * machine and this player's own friends: the code (just item counts, nothing
 * sensitive) sits on mclo.gs, readable by anyone who has or guesses the id,
 * for as long as it's kept. Everything else in this mod (Sheets sync aside)
 * stays fully local.
 */
public final class BookletPasteService {

	private static final String UPLOAD_URL = "https://api.mclo.gs/1/log";
	private static final String RAW_URL = "https://api.mclo.gs/1/raw/";
	// A bare id ("ltJvQt5") or a full mclo.gs link, with or without scheme.
	private static final Pattern ID_OR_URL = Pattern.compile("^(?:https?://mclo\\.gs/)?([A-Za-z0-9]{4,16})/?$");

	private BookletPasteService() {
	}

	public static boolean looksLikePasteId(String text) {
		return text != null && ID_OR_URL.matcher(text.trim()).matches();
	}

	/** @return the short id mclo.gs assigned, to hand to someone else. */
	public static String upload(String content) throws Exception {
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		String form = "content=" + URLEncoder.encode(content, StandardCharsets.UTF_8);
		HttpRequest request = HttpRequest.newBuilder(URI.create(UPLOAD_URL))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(form))
				.timeout(Duration.ofSeconds(15))
				.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		JsonObject body;
		try {
			body = JsonParser.parseString(response.body()).getAsJsonObject();
		} catch (Exception e) {
			throw new IllegalStateException("mclo.gs sent back something unexpected (HTTP " + response.statusCode() + ").", e);
		}

		if (response.statusCode() != 200 || !body.has("success") || !body.get("success").getAsBoolean()) {
			String error = body.has("error") ? body.get("error").getAsString() : ("HTTP " + response.statusCode());
			throw new IllegalStateException("mclo.gs rejected the upload: " + error);
		}
		return body.get("id").getAsString();
	}

	/** @param idOrUrl a bare id ("ltJvQt5") or a full "https://mclo.gs/..." link. */
	public static String fetch(String idOrUrl) throws Exception {
		Matcher matcher = ID_OR_URL.matcher(idOrUrl.trim());
		if (!matcher.matches()) {
			throw new IllegalArgumentException("that doesn't look like an mclo.gs id or link");
		}
		String id = matcher.group(1);

		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		HttpRequest request = HttpRequest.newBuilder(URI.create(RAW_URL + id))
				.GET()
				.timeout(Duration.ofSeconds(15))
				.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		if (response.statusCode() == 404) {
			throw new IllegalStateException("that code wasn't found - it may have expired (mclo.gs keeps pastes "
					+ "for about 90 days) or the id was mistyped");
		}
		if (response.statusCode() != 200) {
			throw new IllegalStateException("mclo.gs error: HTTP " + response.statusCode());
		}
		return response.body();
	}
}
