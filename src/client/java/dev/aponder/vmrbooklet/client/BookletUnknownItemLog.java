package dev.aponder.vmrbooklet.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aponder.vmrbooklet.VMRBooklet;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Logs every item {@link BookletLiveScanner} sees in the real plugin's
 * {@code /booklet} GUI that LOOKS like a real booklet entry (has the
 * Amount/Remaining/Achieved lore) but doesn't match anything in our own
 * {@code items.json} dataset - the fast alternative to manually screenshotting
 * every page: just page through the real booklet once with this mod
 * installed, then run {@code /vbooklet unknown} and send the resulting file.
 *
 * <p>Keyed by the item's exact displayed name (not its underlying registry
 * item id) - several distinct booklet entries can share one real Minecraft
 * item type (e.g. every treasure-map variant is a {@code minecraft:filled_map}
 * with a different custom name), so the display name is the only thing that
 * actually distinguishes them.
 */
public final class BookletUnknownItemLog {

	public record Entry(String displayName, String itemId, int amount, int required) {
	}

	private static final Path FILE = FabricLoader.getInstance().getConfigDir()
			.resolve("vmrbooklet").resolve("unrecognized_items.json");

	private static final Map<String, Entry> SEEN = load();

	private BookletUnknownItemLog() {
	}

	/** @return true if this was a newly seen name (not just an updated amount for one already logged). */
	public static synchronized boolean record(String displayName, String itemId, int amount, int required) {
		boolean isNew = !SEEN.containsKey(displayName);
		SEEN.put(displayName, new Entry(displayName, itemId, amount, required));
		save();
		return isNew;
	}

	public static synchronized int count() {
		return SEEN.size();
	}

	public static Path file() {
		return FILE;
	}

	private static Map<String, Entry> load() {
		Map<String, Entry> result = new LinkedHashMap<>();
		if (!Files.exists(FILE)) {
			return result;
		}
		try (Reader reader = Files.newBufferedReader(FILE)) {
			JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();
			for (var element : array) {
				JsonObject obj = element.getAsJsonObject();
				String name = obj.get("displayName").getAsString();
				result.put(name, new Entry(name, obj.get("itemId").getAsString(),
						obj.get("amount").getAsInt(), obj.get("required").getAsInt()));
			}
		} catch (IOException | RuntimeException e) {
			VMRBooklet.LOGGER.warn("Failed to load unrecognized-item log, starting empty.", e);
		}
		return result;
	}

	private static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			JsonArray array = new JsonArray();
			for (Entry entry : SEEN.values()) {
				JsonObject obj = new JsonObject();
				obj.addProperty("displayName", entry.displayName());
				obj.addProperty("itemId", entry.itemId());
				obj.addProperty("amount", entry.amount());
				obj.addProperty("required", entry.required());
				array.add(obj);
			}
			try (Writer writer = Files.newBufferedWriter(FILE)) {
				new GsonBuilder().setPrettyPrinting().create().toJson(array, writer);
			}
		} catch (IOException e) {
			VMRBooklet.LOGGER.warn("Failed to save unrecognized-item log.", e);
		}
	}
}
