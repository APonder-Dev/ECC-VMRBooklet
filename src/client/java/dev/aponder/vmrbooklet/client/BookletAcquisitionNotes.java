package dev.aponder.vmrbooklet.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aponder.vmrbooklet.VMRBooklet;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A short, hand-written "how do I actually get this" note for items that have
 * no crafting/smelting/stonecutting/smithing recipe at all (ores you mine, mob
 * drops, structure/trade-only items, naturally-placed blocks, etc.) - covers
 * the gap left once {@link BookletRecipeLookup} and {@link BookletStaticRecipeData}
 * both come up empty for an item, so the detail panel isn't just silently blank
 * for roughly a third of the booklet's items.
 *
 * <p>Not every no-recipe item necessarily has an entry yet - {@link #forItem}
 * just returns null if there's nothing on file, and the detail panel simply
 * shows nothing in that case, same as before this existed.
 */
public final class BookletAcquisitionNotes {

	private static final Map<String, String> BY_ITEM_ID = load();

	private BookletAcquisitionNotes() {
	}

	public static String forItem(String itemId) {
		return BY_ITEM_ID.get(itemId);
	}

	private static Map<String, String> load() {
		try (InputStream in = BookletAcquisitionNotes.class.getResourceAsStream("/data/vmrbooklet/booklet/acquisition_notes.json")) {
			if (in == null) {
				VMRBooklet.LOGGER.warn("acquisition_notes.json is missing from the jar - no-recipe-item notes will be unavailable.");
				return Map.of();
			}

			JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			Map<String, String> result = new java.util.HashMap<>();
			for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
				result.put(entry.getKey(), entry.getValue().getAsString());
			}

			Map<String, String> loaded = Map.copyOf(result);
			VMRBooklet.LOGGER.info("Loaded {} acquisition note(s) for no-recipe items.", loaded.size());
			return loaded;
		} catch (Exception e) {
			VMRBooklet.LOGGER.warn("Failed to load bundled acquisition notes.", e);
			return Map.of();
		}
	}
}
