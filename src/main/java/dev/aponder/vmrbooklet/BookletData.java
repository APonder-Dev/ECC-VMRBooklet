package dev.aponder.vmrbooklet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

/**
 * The booklet's item list. Normally the bundled list (generated from the
 * "Master Order" sheet of the VMR Booklet 26.3 Template) read straight off the
 * classpath; if {@code config/vmrbooklet/items.json} exists it replaces the
 * bundled list entirely, so the mod can track any "collect these items"
 * challenge, not just the one it was built for.
 */
public final class BookletData {

	private static final String RESOURCE_PATH = "/data/" + VMRBooklet.MOD_ID + "/booklet/items.json";
	private static final String DEFAULT_RELEASE = "Custom";

	/** What the last {@link #load()} actually used. {@code error} is set when a custom file existed but couldn't be used. */
	public record LoadResult(boolean custom, int loaded, int skipped, String error) {
	}

	private static volatile List<BookletEntry> byEcc = List.of();
	private static volatile List<BookletEntry> byCreative = List.of();
	private static volatile Map<String, BookletEntry> byItemId = Map.of();
	private static volatile Map<Item, BookletEntry> byItem = Map.of();
	private static volatile boolean customActive = false;

	private BookletData() {
	}

	/** Where a custom item list is read from, if present. */
	public static Path customFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(VMRBooklet.MOD_ID).resolve("items.json");
	}

	/** (Re)loads the item list: the custom file when it exists and parses, otherwise the bundled list. */
	public static synchronized LoadResult load() {
		Path custom = customFile();
		String customError = null;
		if (Files.exists(custom)) {
			try (Reader reader = Files.newBufferedReader(custom, StandardCharsets.UTF_8)) {
				Parsed parsed = parse(JsonParser.parseReader(reader).getAsJsonArray(), true);
				if (parsed.entries().isEmpty()) {
					customError = "no entries matched a registered item";
				} else {
					commit(parsed.entries());
					customActive = true;
					VMRBooklet.LOGGER.info("VMR Booklet loaded {} items from custom list {} ({} skipped as unknown).",
							parsed.entries().size(), custom, parsed.skipped());
					return new LoadResult(true, parsed.entries().size(), parsed.skipped(), null);
				}
			} catch (IOException | RuntimeException e) {
				customError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			}
			VMRBooklet.LOGGER.error("Couldn't use custom booklet list {} ({}) - falling back to the bundled list.", custom, customError);
		}

		Parsed parsed;
		try (InputStream stream = BookletData.class.getResourceAsStream(RESOURCE_PATH)) {
			if (stream == null) {
				throw new IllegalStateException("Missing bundled resource " + RESOURCE_PATH);
			}
			try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				parsed = parse(JsonParser.parseReader(reader).getAsJsonArray(), false);
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to load VMR Booklet item data", e);
		}
		commit(parsed.entries());
		customActive = false;
		VMRBooklet.LOGGER.info("VMR Booklet loaded {} items from the bundled list ({} skipped as unknown on this version).",
				parsed.entries().size(), parsed.skipped());
		return new LoadResult(false, parsed.entries().size(), parsed.skipped(), customError);
	}

	private record Parsed(List<BookletEntry> entries, int skipped) {
	}

	/**
	 * @param lenient custom lists may leave out everything but {@code id}: the
	 *                name comes from the id, both orders default to file order,
	 *                {@code required} to 1 and {@code release} to "Custom"
	 */
	private static Parsed parse(JsonArray array, boolean lenient) {
		List<BookletEntry> entries = new ArrayList<>();
		int skipped = 0;
		int index = 0;
		for (JsonElement element : array) {
			index++;
			JsonObject obj = element.getAsJsonObject();
			String idString = obj.get("id").getAsString();
			Identifier identifier = Identifier.parse(idString);
			Item item = BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);

			if (item == null) {
				skipped++;
				VMRBooklet.LOGGER.warn("Booklet entry '{}' does not match any registered item on this Minecraft version; skipping.", idString);
				continue;
			}

			if (lenient) {
				entries.add(new BookletEntry(
						item,
						idString,
						obj.has("name") ? obj.get("name").getAsString() : nameFromId(identifier),
						obj.has("ecc_order") ? obj.get("ecc_order").getAsInt() : index,
						obj.has("creative_order") ? obj.get("creative_order").getAsInt() : index,
						obj.has("required") ? Math.max(1, obj.get("required").getAsInt()) : 1,
						obj.has("release") ? obj.get("release").getAsString() : DEFAULT_RELEASE
				));
			} else {
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
		}
		return new Parsed(entries, skipped);
	}

	/** "minecraft:turtle_scute" -> "Turtle_Scute", matching the bundled list's naming. */
	private static String nameFromId(Identifier identifier) {
		StringBuilder name = new StringBuilder();
		for (String part : identifier.getPath().split("_")) {
			if (part.isEmpty()) continue;
			if (!name.isEmpty()) name.append('_');
			name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
		}
		return name.toString();
	}

	private static void commit(List<BookletEntry> entries) {
		List<BookletEntry> eccOrdered = List.copyOf(entries.stream().sorted(Comparator.comparingInt(BookletEntry::eccOrder)).toList());
		List<BookletEntry> creativeOrdered = List.copyOf(entries.stream().sorted(Comparator.comparingInt(BookletEntry::creativeOrder)).toList());

		Map<String, BookletEntry> idMap = new HashMap<>();
		Map<Item, BookletEntry> itemMap = new HashMap<>();
		for (BookletEntry entry : entries) {
			idMap.put(entry.itemId(), entry);

			String lowerName = entry.displayName().toLowerCase(Locale.ROOT);
			// Last-wins, same as any other HashMap.put - but a collision here (two
			// entries sharing a display name once lowercased) would otherwise make
			// byItemId/byQuery silently resolve to the wrong one with no sign
			// anything went wrong. Worth a warning even though none exist in the
			// bundled data as of this check, since a future sheet update could add one.
			BookletEntry existingByName = idMap.get(lowerName);
			if (existingByName != null && existingByName != entry) {
				VMRBooklet.LOGGER.warn("Two booklet entries share the display name '{}' ('{}' and '{}') - name-based lookups will resolve to whichever loaded last.",
						entry.displayName(), existingByName.itemId(), entry.itemId());
			}
			idMap.put(lowerName, entry);

			itemMap.put(entry.item(), entry);
		}
		// Legacy sheet names for items that were renamed on this Minecraft version
		// (copper chains pushed plain "chain" to "iron_chain"; "golden_" replaced "gold_").
		aliasTo(idMap, "chain", "minecraft:iron_chain");
		aliasTo(idMap, "gold_spear", "minecraft:golden_spear");

		// Lookup maps first, ordered lists last: anything iterating the lists can
		// then always resolve what it finds through the maps.
		byItemId = Map.copyOf(idMap);
		byItem = Map.copyOf(itemMap);
		byEcc = eccOrdered;
		byCreative = creativeOrdered;
	}

	/** Whether a custom {@code items.json} is the active list (share/backup codes only work with the bundled one). */
	public static boolean isCustom() {
		return customActive;
	}

	public static List<BookletEntry> orderedBy(BookletSort sort) {
		return sort == BookletSort.ECC ? byEcc : byCreative;
	}

	/** Looks up by either "minecraft:stone" or the sheet's display name ("Stone", case-insensitive). */
	public static BookletEntry byItemId(String key) {
		Map<String, BookletEntry> map = byItemId;
		BookletEntry direct = map.get(key);
		if (direct != null) {
			return direct;
		}
		return map.get(key.toLowerCase(Locale.ROOT));
	}

	/**
	 * Same lookup as {@link #byItemId}, but also accepts free-typed command
	 * input with spaces instead of underscores ("ender eye" as well as
	 * "ender_eye" or "minecraft:ender_eye").
	 */
	public static BookletEntry byQuery(String query) {
		String trimmed = query.trim();
		BookletEntry direct = byItemId(trimmed);
		if (direct != null) {
			return direct;
		}
		return byItemId(trimmed.toLowerCase(Locale.ROOT).replace(' ', '_'));
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
