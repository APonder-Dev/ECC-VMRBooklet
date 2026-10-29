package dev.aponder.vmrbooklet.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.VMRBooklet;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Pushes local progress back into the player's OWN sheet via the Sheets API,
 * authenticating as their own service account (see {@link GoogleServiceAccount}).
 * Only touches cells whose value actually differs, in the same "Obtained"
 * column {@link BookletUpload} reads from.
 */
public final class BookletSheetWriter {

	private BookletSheetWriter() {
	}

	/** @return how many cells were updated, or -1 if write-back isn't configured for this user. */
	public static int push(String sheetId, String gid) throws Exception {
		if (!GoogleServiceAccount.isConfigured()) {
			return -1;
		}

		String token = GoogleServiceAccount.getAccessToken();
		try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {

			String tabTitle = resolveTabTitle(client, token, sheetId, gid);

			// A1:Z2000, not A1:B2000 - BookletUpload's CSV path scans all columns
			// positionally for "Item"/"Obtained", so a sheet with either header past
			// column B would otherwise throw a false "couldn't find columns" here.
			String range = "'" + tabTitle.replace("'", "''") + "'!A1:Z2000";
			String readUrl = "https://sheets.googleapis.com/v4/spreadsheets/" + sheetId + "/values/"
					+ URLEncoder.encode(range, StandardCharsets.UTF_8);
			JsonObject readBody = getJson(client, token, readUrl);
			JsonArray rows = readBody.has("values") ? readBody.getAsJsonArray("values") : new JsonArray();

			int headerRow = -1, itemCol = -1, obtainedCol = -1;
			for (int i = 0; i < rows.size(); i++) {
				JsonArray row = rows.get(i).getAsJsonArray();
				for (int c = 0; c < row.size(); c++) {
					String cell = row.get(c).getAsString().trim();
					if (cell.equalsIgnoreCase("item")) {
						itemCol = c;
						headerRow = i;
					}
					if (cell.equalsIgnoreCase("obtained")) {
						obtainedCol = c;
					}
				}
				if (headerRow == i && obtainedCol >= 0) {
					break;
				}
			}
			if (headerRow < 0 || itemCol < 0 || obtainedCol < 0) {
				throw new IllegalStateException("couldn't find \"Item\"/\"Obtained\" columns to write back to");
			}

			List<JsonObject> updates = new ArrayList<>();
			for (int i = headerRow + 1; i < rows.size(); i++) {
				JsonArray row = rows.get(i).getAsJsonArray();
				if (itemCol >= row.size()) {
					continue;
				}

				String itemName = row.get(itemCol).getAsString().trim();
				if (itemName.isEmpty()) {
					continue;
				}

				BookletEntry entry = BookletData.byItemId(itemName);
				if (entry == null) {
					continue;
				}

				int localAmount = BookletLocalProgress.obtained(entry.itemId());
				String sheetAmountText = obtainedCol < row.size() ? row.get(obtainedCol).getAsString().trim() : "";
				int sheetAmount = sheetAmountText.isEmpty() ? 0 : tryParseInt(sheetAmountText);

				if (localAmount != sheetAmount) {
					String cellRange = "'" + tabTitle.replace("'", "''") + "'!" + columnLetter(obtainedCol) + (i + 1);
					JsonObject update = new JsonObject();
					update.addProperty("range", cellRange);
					JsonArray valueRow = new JsonArray();
					JsonArray valueInner = new JsonArray();
					valueInner.add(localAmount);
					valueRow.add(valueInner);
					update.add("values", valueRow);
					updates.add(update);
				}
			}

			if (updates.isEmpty()) {
				return 0;
			}

			JsonObject batchBody = new JsonObject();
			batchBody.addProperty("valueInputOption", "RAW");
			JsonArray data = new JsonArray();
			updates.forEach(data::add);
			batchBody.add("data", data);

			String writeUrl = "https://sheets.googleapis.com/v4/spreadsheets/" + sheetId + "/values:batchUpdate";
			HttpRequest writeRequest = HttpRequest.newBuilder(URI.create(writeUrl))
					.header("Authorization", "Bearer " + token)
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(batchBody.toString()))
					.timeout(Duration.ofSeconds(20))
					.build();
			HttpResponse<String> writeResponse = client.send(writeRequest, HttpResponse.BodyHandlers.ofString());
			if (writeResponse.statusCode() == 403) {
				VMRBooklet.LOGGER.warn("Sheets write-back got 403: {}", writeResponse.body());
				throw new IllegalStateException(forbiddenMessage());
			}
			if (writeResponse.statusCode() != 200) {
				throw new IllegalStateException("Sheets write failed: HTTP " + writeResponse.statusCode() + " " + writeResponse.body());
			}

			return updates.size();
		}
	}

	private static String resolveTabTitle(HttpClient client, String token, String sheetId, String gid) throws Exception {
		String metaUrl = "https://sheets.googleapis.com/v4/spreadsheets/" + sheetId + "?fields=sheets.properties";
		JsonObject meta = getJson(client, token, metaUrl);
		JsonArray sheets = meta.getAsJsonArray("sheets");
		for (int i = 0; i < sheets.size(); i++) {
			JsonObject props = sheets.get(i).getAsJsonObject().getAsJsonObject("properties");
			String sheetGid = String.valueOf(props.get("sheetId").getAsLong());
			if (gid == null ? i == 0 : sheetGid.equals(gid)) {
				return props.get("title").getAsString();
			}
		}
		throw new IllegalStateException("couldn't find that tab in the spreadsheet (check the link's gid)");
	}

	private static JsonObject getJson(HttpClient client, String token, String url) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("Authorization", "Bearer " + token)
				.GET()
				.timeout(Duration.ofSeconds(15))
				.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 403) {
			VMRBooklet.LOGGER.warn("Sheets API got 403: {}", response.body());
			throw new IllegalStateException(forbiddenMessage());
		}
		if (response.statusCode() != 200) {
			throw new IllegalStateException("Sheets API error: HTTP " + response.statusCode() + " " + response.body());
		}
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}

	private static String forbiddenMessage() {
		String email = GoogleServiceAccount.clientEmailOrNull();
		return "Google rejected that request (403) - make sure your sheet is shared with "
				+ (email != null ? "\"" + email + "\"" : "your service account's email (see service-account.json)")
				+ " as an Editor, not just \"Anyone with the link\".";
	}

	private static int tryParseInt(String s) {
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static String columnLetter(int index) {
		StringBuilder sb = new StringBuilder();
		int n = index;
		do {
			sb.insert(0, (char) ('A' + n % 26));
			n = n / 26 - 1;
		} while (n >= 0);
		return sb.toString();
	}
}
