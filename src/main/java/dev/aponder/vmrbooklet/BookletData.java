package dev.aponder.vmrbooklet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the static VMR Booklet item list (generated from the "Master Order" sheet
 * of the VMR Booklet 26.3 Template) from the mod jar. This is the same on client
 * and dedicated server, so it is read once directly off the classpath rather than
 * through the resource-pack/data-pack pipeline.
 */
public final class BookletData {

	private static final String RESOURCE_PATH = "/data/" + VMRBooklet.MOD_ID + "/booklet/items.json";

	private static List<BookletEntry> byEcc = List.of();
	private static List<BookletEntry> byCreative = List.of();
	private static Map<String, BookletEntry> byItemId = Map.of();
	private static Map<Item, BookletEntry> byItem = Map.of();

	private BookletData() {
	}

	public static void load() {
		List<BookletEntry> entries = new ArrayList<>();
		int skipped = 0;

		try (InputStream stream = BookletData.class.getResourceAsStream(RESOURCE_PATH)) {
			if (stream == null) {
				throw new IllegalStateException("Missing bundled resource " + RESOURCE_PATH);
			}

			JsonArray array;
			try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				array = JsonParser.parseReader(reader).getAsJsonArray();
			}

			for (JsonElement element : array) {
				JsonObject obj = element.getAsJsonObject();
				String idString = obj.get("id").getAsString();
				Identifier identifier = Identifier.parse(idString);
				Item item = BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);

				if (item == null) {
					skipped++;
					VMRBooklet.LOGGER.warn("Booklet entry '{}' does not match any registered item on this Minecraft version; skipping.", idString);
					continue;
				}

				entries.add(new BookletEntry(
						item,
						idString,
						obj.get("name").getAsString(),
						obj.get("ecc_order").getAsInt(),
						obj.get("creative_order").getAsInt(),
						obj.get("required").getAsInt(),
						obj.get("release").getAsString()
				));
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to load VMR Booklet item data", e);
		}

		byEcc = List.copyOf(entries.stream().sorted(Comparator.comparingInt(BookletEntry::eccOrder)).toList());
		byCreative = List.copyOf(entries.stream().sorted(Comparator.comparingInt(BookletEntry::creativeOrder)).toList());

		Map<String, BookletEntry> idMap = new HashMap<>();
		Map<Item, BookletEntry> itemMap = new HashMap<>();
		for (BookletEntry entry : entries) {
			idMap.put(entry.itemId(), entry);
			idMap.put(entry.displayName().toLowerCase(java.util.Locale.ROOT), entry);
			itemMap.put(entry.item(), entry);
		}
		// Legacy sheet names for items that were renamed on this Minecraft version
		// (copper chains pushed plain "chain" to "iron_chain"; "golden_" replaced "gold_").
		aliasTo(idMap, "chain", "minecraft:iron_chain");
		aliasTo(idMap, "gold_spear", "minecraft:golden_spear");

		byItemId = Map.copyOf(idMap);
		byItem = Map.copyOf(itemMap);

		VMRBooklet.LOGGER.info("VMR Booklet loaded {} items ({} skipped as unknown on this version).", entries.size(), skipped);
	}

	public static List<BookletEntry> orderedBy(BookletSort sort) {
		return sort == BookletSort.ECC ? byEcc : byCreative;
	}

	/** Looks up by either "minecraft:stone" or the sheet's display name ("Stone", case-insensitive). */
	public static BookletEntry byItemId(String key) {
		BookletEntry direct = byItemId.get(key);
		if (direct != null) {
			return direct;
		}
		return byItemId.get(key.toLowerCase(java.util.Locale.ROOT));
	}

	public static BookletEntry byItem(Item item) {
		return byItem.get(item);
	}

	public static int size() {
		return byEcc.size();
	}

	private static void aliasTo(Map<String, BookletEntry> idMap, String legacyLowercaseName, String currentItemId) {
		BookletEntry entry = idMap.get(currentItemId);
		if (entry != null) {
			idMap.put(legacyLowercaseName, entry);
		}
	}
}
