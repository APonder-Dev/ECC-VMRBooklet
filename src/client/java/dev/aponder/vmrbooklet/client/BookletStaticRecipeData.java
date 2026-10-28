package dev.aponder.vmrbooklet.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aponder.vmrbooklet.VMRBooklet;
import net.minecraft.SharedConstants;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A bundled, static copy of vanilla's own crafting recipes (extracted at
 * build time from the game's own {@code data/minecraft/recipe/*.json} files -
 * the same recipe definitions the server loads, not something hand-authored),
 * keyed by output item id.
 *
 * <p>This exists because the live client recipe book ({@link BookletRecipeLookup})
 * only ever contains recipes THIS player has personally unlocked server-side -
 * if you obtained the ingredients some other way (bought from a shop, traded,
 * etc.) the server may never have sent you that recipe's data at all, even
 * though the recipe is perfectly real. This bundled copy is the fallback used
 * when the live lookup comes up empty, so the booklet can still show
 * something rather than nothing.
 *
 * <p>Tag-based ingredients (e.g. "any plank") were resolved down to one
 * representative item at generation time, same simplification the live
 * recipe-book path already makes. Not a byte-for-byte guarantee of what a
 * specific server's datapack does if it has modified vanilla recipes - just
 * vanilla as Mojang ships it.
 *
 * <p>Regenerating this file for a future Minecraft version: it's produced
 * once from the game's own {@code data/minecraft/recipe/*.json} and
 * {@code data/minecraft/tags/item/*.json} files (see the generator script
 * used when this was first built), with a {@code "__meta__": {"minecraftVersion": "..."}}
 * entry stamped on at the end recording which version it was built from.
 * {@link #load()} compares that stamp against the actual running game version
 * and logs a warning (not a hard failure - stale-but-present data still beats
 * nothing) if this file was never regenerated after a version bump, since the
 * recipes/ids it contains could otherwise be silently wrong for the new game.
 */
public final class BookletStaticRecipeData {

	/** @param station "crafting_table", "furnace", "smithing_table" or "stonecutter" - see BookletRecipeLookup.Station. */
	public record Recipe(int width, int height, List<String> ingredientItemIds, int outputCount, String station) {
	}

	private static final Map<String, List<Recipe>> BY_OUTPUT_ITEM_ID = load();

	private BookletStaticRecipeData() {
	}

	public static List<Recipe> forItem(String itemId) {
		return BY_OUTPUT_ITEM_ID.getOrDefault(itemId, List.of());
	}

	/** Every bundled recipe, keyed by output item id - for scanning "what uses this ingredient" lookups. */
	public static Map<String, List<Recipe>> all() {
		return BY_OUTPUT_ITEM_ID;
	}

	private static Map<String, List<Recipe>> load() {
		try (InputStream in = BookletStaticRecipeData.class.getResourceAsStream("/data/vmrbooklet/booklet/static_recipes.json")) {
			if (in == null) {
				VMRBooklet.LOGGER.warn("static_recipes.json is missing from the jar - recipe fallback will be unavailable.");
				return Map.of();
			}

			JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			Map<String, List<Recipe>> result = new HashMap<>();

			if (root.has("__meta__")) {
				checkVersionStamp(root.getAsJsonObject("__meta__"));
			}

			for (Map.Entry<String, JsonElement> outputEntry : root.entrySet()) {
				if (outputEntry.getKey().equals("__meta__")) {
					continue; // metadata, not an item id - its value is an object, not a recipe array
				}

				List<Recipe> recipes = new ArrayList<>();
				for (JsonElement recipeEl : outputEntry.getValue().getAsJsonArray()) {
					JsonObject obj = recipeEl.getAsJsonObject();
					List<String> ingredients = new ArrayList<>();
					for (JsonElement ingredientEl : obj.getAsJsonArray("ingredients")) {
						ingredients.add(ingredientEl.isJsonNull() ? null : ingredientEl.getAsString());
					}
					// NOT List.copyOf() - empty grid cells are legitimately null (e.g. the
					// gap in an iron helmet's "XXX"/"X X" pattern), and List.copyOf()
					// rejects null elements outright. That exception was being thrown on
					// the very first gapped recipe encountered, aborting this whole load
					// and silently leaving every item's fallback data empty.
					// "crafting_table" default covers recipe data generated before the
					// station field existed, so an un-regenerated static_recipes.json
					// (see the version-stamp check above) still loads instead of breaking.
					String station = obj.has("station") ? obj.get("station").getAsString() : "crafting_table";
					recipes.add(new Recipe(obj.get("width").getAsInt(), obj.get("height").getAsInt(),
							Collections.unmodifiableList(ingredients), obj.get("outputCount").getAsInt(), station));
				}
				result.put(outputEntry.getKey(), Collections.unmodifiableList(recipes));
			}

			Map<String, List<Recipe>> loaded = Collections.unmodifiableMap(result);
			VMRBooklet.LOGGER.info("Loaded static recipe fallback data for {} item(s).", loaded.size());
			return loaded;
		} catch (Exception e) {
			VMRBooklet.LOGGER.warn("Failed to load bundled static recipe fallback data.", e);
			return Map.of();
		}
	}

	private static void checkVersionStamp(JsonObject meta) {
		if (!meta.has("minecraftVersion")) {
			return;
		}
		String generatedFor = meta.get("minecraftVersion").getAsString();
		String running = SharedConstants.getCurrentVersion().name();
		if (!generatedFor.equals(running)) {
			VMRBooklet.LOGGER.warn("static_recipes.json was generated for Minecraft {} but this is {} - the bundled "
					+ "recipe fallback may be stale/wrong for this version. Regenerate it.", generatedFor, running);
		}
	}
}
