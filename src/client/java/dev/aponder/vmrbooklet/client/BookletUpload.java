package dev.aponder.vmrbooklet.client;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.VMRBooklet;
import dev.aponder.vmrbooklet.client.gui.BookletScreen;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /upload <google-sheet-link>}: fetches that sheet's "Obtained" tab as
 * CSV and SETS the local progress directly from it (not additive) - entirely
 * client-side, no server involved.
 */
public final class BookletUpload {

	private static final Pattern ID_PATTERN = Pattern.compile("/d/([a-zA-Z0-9-_]+)");
	private static final Pattern GID_PATTERN = Pattern.compile("[?&#]gid=(\\d+)");

	private BookletUpload() {
	}

	/** {@code /upload <link>}: remembers and uses that link, editing whatever was stored before. */
	public static int run(FabricClientCommandSource source, String link) {
		Matcher idMatcher = ID_PATTERN.matcher(link);
		if (!idMatcher.find()) {
			source.sendError(Component.literal("That doesn't look like a Google Sheets link."));
			return 0;
		}

		BookletSettings.setLastSheetLink(link);
		return runWithLink(source, link);
	}

	/** {@code /upload} with no argument: re-syncs from whatever link was last remembered. */
	public static int runRemembered(FabricClientCommandSource source) {
		String link = BookletSettings.lastSheetLink();
		if (link == null || link.isBlank()) {
			source.sendError(Component.literal("No sheet linked yet - run /upload <google-sheet-link> first."));
			return 0;
		}
		return runWithLink(source, link);
	}

	private static int runWithLink(FabricClientCommandSource source, String link) {
		Matcher idMatcher = ID_PATTERN.matcher(link);
		if (!idMatcher.find()) {
			source.sendError(Component.literal("The remembered link doesn't look like a Google Sheets link."));
			return 0;
		}

		String sheetId = idMatcher.group(1);
		Matcher gidMatcher = GID_PATTERN.matcher(link);
		String gid = gidMatcher.find() ? gidMatcher.group(1) : null;

		String csvUrl = "https://docs.google.com/spreadsheets/d/" + sheetId + "/export?format=csv"
				+ (gid != null ? "&gid=" + gid : "");

		source.sendFeedback(Component.literal("Fetching sheet...").withStyle(ChatFormatting.GRAY));
		var client = source.getClient();

		Thread.ofVirtual().start(() -> {
			try {
				String csv = fetch(csvUrl);

				// apply() writes to BookletLocalProgress, which (unlike every other
				// progress-mutation path - /sell, the live scanner, manual +/-) was
				// being called straight from this background virtual thread. That
				// reaches achievement/milestone toasts through to
				// Minecraft.getInstance().gui.toastManager() off the render thread -
				// client.execute() keeps it on the thread the toast manager expects.
				client.execute(() -> {
					Result result = apply(csv);
					if (BookletScreen.current() != null) {
						BookletScreen.current().onProgressUpdated();
					}
					source.sendFeedback(Component.literal(
							"Updated " + result.updated + " item(s), " + result.unmatched + " unmatched. "
									+ "(Run /vbooklet undo to revert this if that doesn't look right.)")
							.withStyle(ChatFormatting.GREEN));

					// client.execute() only queues apply() for the next tick, so pushBack
					// (which reads local progress back out) has to wait for it too, not
					// just for fetch() - otherwise it could push the pre-apply progress
					// back to the sheet. Its own network I/O still needs a fresh thread,
					// not the render thread apply() just ran on.
					Thread.ofVirtual().start(() -> pushBack(source, sheetId, gid));
				});
			} catch (Exception e) {
				VMRBooklet.LOGGER.warn("Failed to import booklet sheet", e);
				client.execute(() -> source.sendError(Component.literal("Failed to import: " + e.getMessage())));
			}
		});

		return 1;
	}

	/**
	 * After a pull, pushes local progress back into the same sheet IF this player
	 * has their own write-back credential set up ({@link GoogleServiceAccount}).
	 * Silently does nothing otherwise - write-back is opt-in per person.
	 */
	private static void pushBack(FabricClientCommandSource source, String sheetId, String gid) {
		var client = source.getClient();
		try {
			int pushed = BookletSheetWriter.push(sheetId, gid);
			if (pushed < 0) {
				return; // not configured for this user - stay silent, read-only is the default
			}

			int count = pushed;
			client.execute(() -> source.sendFeedback(count == 0
					? Component.literal("Sheet already matches your local progress.").withStyle(ChatFormatting.GRAY)
					: Component.literal("Wrote " + count + " value(s) back to your sheet.").withStyle(ChatFormatting.GREEN)));
		} catch (Exception e) {
			VMRBooklet.LOGGER.warn("Failed to write booklet progress back to sheet", e);
			client.execute(() -> source.sendError(Component.literal("Failed to write back to sheet: " + e.getMessage())));
		}
	}

	private static String fetch(String url) throws IOException, InterruptedException {
		// Google's CSV export always 307-redirects to a signed googleusercontent.com
		// URL, and expects the cookies from that first response to be carried along -
		// without both of these, the redirect target comes back empty or rejected.
		try (HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.cookieHandler(new java.net.CookieManager())
				.build()) {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(15)).build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

			String body = response.body();

			// A private/inaccessible sheet comes back as a Google HTML error page (not
			// CSV), sometimes with a 200 status despite not actually being the data -
			// so check content, not just status code.
			if (response.statusCode() != 200 || body.stripLeading().startsWith("<!DOCTYPE") || body.contains("<html")) {
				throw new IOException("that sheet isn't shared as \"Anyone with the link\" (viewer) - check its Share settings");
			}

			return body;
		}
	}

	private record Result(int updated, int unmatched) {
	}

	private static Result apply(String csv) {
		List<String[]> rows = parseCsv(csv);

		int headerRow = -1;
		int itemCol = -1;
		int obtainedCol = -1;

		for (int i = 0; i < rows.size(); i++) {
			String[] row = rows.get(i);
			for (int c = 0; c < row.length; c++) {
				if (row[c].trim().equalsIgnoreCase("item")) {
					itemCol = c;
					headerRow = i;
				}
				if (row[c].trim().equalsIgnoreCase("obtained")) {
					obtainedCol = c;
					headerRow = i;
				}
			}
			// Breaks once BOTH headers are found, even if they're on two different
			// rows (not just when both land on the same row) - otherwise a data row
			// further down whose cell happens to literally read "item"/"obtained"
			// (a stray note, or an item genuinely named that) would silently
			// override the already-correct column indices, since this loop would
			// never have stopped scanning in the first place.
			if (itemCol >= 0 && obtainedCol >= 0) {
				break;
			}
		}

		if (itemCol >= 0 && obtainedCol < 0) {
			throw new IllegalStateException("found an \"Item\" column but no \"Obtained\" one - this link points at the "
					+ "wrong tab. Open the sheet's \"Obtained\" tab specifically, then copy that URL (it should contain "
					+ "\"gid=...\") and run /upload with that link.");
		}

		if (headerRow < 0 || itemCol < 0 || obtainedCol < 0) {
			throw new IllegalStateException("Couldn't find \"Item\"/\"Obtained\" columns in that sheet.");
		}

		Map<String, Integer> newProgress = new HashMap<>(BookletLocalProgress.all());
		int updated = 0;
		int unmatched = 0;

		for (int i = headerRow + 1; i < rows.size(); i++) {
			String[] row = rows.get(i);
			if (itemCol >= row.length || obtainedCol >= row.length) {
				continue;
			}

			String itemName = row[itemCol].trim();
			String obtainedText = row[obtainedCol].trim();
			if (itemName.isEmpty() || obtainedText.isEmpty()) {
				continue;
			}

			int amount;
			try {
				amount = Integer.parseInt(obtainedText);
			} catch (NumberFormatException e) {
				continue;
			}

			BookletEntry entry = BookletData.byItemId(itemName);
			if (entry == null) {
				unmatched++;
				continue;
			}

			newProgress.put(entry.itemId(), entry.clamp(amount));
			updated++;
		}

		BookletLocalProgress.replaceAll(newProgress);
		BookletSettings.markSyncedNow();
		return new Result(updated, unmatched);
	}

	/** Minimal CSV parser handling quoted fields (Google Sheets export style). */
	private static List<String[]> parseCsv(String csv) {
		List<String[]> rows = new ArrayList<>();
		List<String> row = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		boolean inQuotes = false;

		for (int i = 0; i < csv.length(); i++) {
			char c = csv.charAt(i);

			if (inQuotes) {
				if (c == '"') {
					if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
						field.append('"');
						i++;
					} else {
						inQuotes = false;
					}
				} else {
					field.append(c);
				}
			} else {
				switch (c) {
					case '"' -> inQuotes = true;
					case ',' -> {
						row.add(field.toString());
						field.setLength(0);
					}
					case '\n' -> {
						row.add(field.toString());
						field.setLength(0);
						rows.add(row.toArray(new String[0]));
						row = new ArrayList<>();
					}
					case '\r' -> {
					}
					default -> field.append(c);
				}
			}
		}

		if (!field.isEmpty() || !row.isEmpty()) {
			row.add(field.toString());
			rows.add(row.toArray(new String[0]));
		}

		return rows;
	}
}
