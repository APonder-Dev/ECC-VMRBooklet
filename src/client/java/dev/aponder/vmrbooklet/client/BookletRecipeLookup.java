package dev.aponder.vmrbooklet.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds a crafting-table recipe (shaped or shapeless) that produces a given
 * item. Prefers the client's own live recipe book data (the same
 * recipe-display system the vanilla recipe book UI reads from) since it
 * reflects whatever this exact server/player state actually is; falls back to
 * {@link BookletStaticRecipeData}'s bundled vanilla recipes when the live
 * lookup comes up empty, since the live data only ever covers recipes THIS
 * player has personally unlocked server-side - a perfectly real recipe can
 * otherwise show as "none" just because of that.
 */
public final class BookletRecipeLookup {

	/** Where a recipe is made - shown as an icon/label on its panel, and (for the fallback data) how it was tagged at generation time. */
	public enum Station {
		CRAFTING_TABLE("Crafting", Items.CRAFTING_TABLE, 0xFFD9A441),
		FURNACE("Smelting", Items.FURNACE, 0xFFE0693D),
		SMITHING_TABLE("Smithing", Items.SMITHING_TABLE, 0xFF9A6FD6),
		STONECUTTER("Stonecutting", Items.STONECUTTER, 0xFF4FA7D9);

		private final String label;
		private final Item icon;
		private final int color;

		Station(String label, Item icon, int color) {
			this.label = label;
			this.icon = icon;
			this.color = color;
		}

		public String label() {
			return label;
		}

		public Item icon() {
			return icon;
		}

		/** A per-station accent used to color that recipe panel's border, so each type reads at a glance instead of all looking the same flat gray. */
		public int color() {
			return color;
		}
	}

	/** @param ingredients one entry per grid cell, row-major, width * height long (shapeless packs left-to-right/top-to-bottom into as few rows as needed). */
	public record Recipe(int width, int height, List<ItemStack> ingredients, ItemStack output, Station station) {
	}

	private BookletRecipeLookup() {
	}

	/**
	 * @param itemId the item's registry id (e.g. "minecraft:iron_helmet"), used
	 * only for the bundled fallback lookup - the live lookup matches by the
	 * {@link Item} instance itself.
	 * @return every known crafting-table recipe (shaped or shapeless) that
	 * produces this item - some items have more than one legitimate recipe
	 * (e.g. a dye obtainable from several different plants), so this doesn't
	 * stop at the first match. Empty if truly none are known either live or in
	 * the bundled fallback data.
	 */
	public static List<Recipe> findAll(Item item, String itemId) {
		List<Recipe> live = findLive(item);
		return live.isEmpty() ? fromStaticFallback(itemId) : live;
	}

	private static List<Recipe> findLive(Item item) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.level == null) {
			return List.of();
		}

		List<Recipe> found = new ArrayList<>();
		ContextMap context = SlotDisplayContext.fromLevel(client.level);
		for (RecipeCollection collection : client.player.getRecipeBook().getCollections()) {
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				Recipe recipe = fromDisplay(entry.display(), item, context);
				if (recipe != null) {
					found.add(recipe);
				}
			}
		}
		return found;
	}

	private static List<Recipe> fromStaticFallback(String itemId) {
		List<BookletStaticRecipeData.Recipe> staticRecipes = BookletStaticRecipeData.forItem(itemId);
		if (staticRecipes.isEmpty()) {
			return List.of();
		}

		// Items.AIR-guarded the same way findUsedIn() guards its own registry
		// lookups - not reachable today (every id here came from this exact game
		// jar/version, either via BookletData or at static_recipes.json generation
		// time), but a version bump reusing a stale static_recipes.json without
		// regenerating it could otherwise silently show an AIR icon instead of
		// failing loudly.
		Item outputItem = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
		if (outputItem == Items.AIR) {
			return List.of();
		}

		List<Recipe> result = new ArrayList<>(staticRecipes.size());
		for (BookletStaticRecipeData.Recipe staticRecipe : staticRecipes) {
			List<ItemStack> ingredients = new ArrayList<>(staticRecipe.ingredientItemIds().size());
			for (String ingredientId : staticRecipe.ingredientItemIds()) {
				if (ingredientId == null) {
					ingredients.add(ItemStack.EMPTY);
				} else {
					Item ingredientItem = BuiltInRegistries.ITEM.getValue(Identifier.parse(ingredientId));
					ingredients.add(ingredientItem == Items.AIR ? ItemStack.EMPTY : new ItemStack(ingredientItem));
				}
			}
			result.add(new Recipe(staticRecipe.width(), staticRecipe.height(), ingredients,
					new ItemStack(outputItem, staticRecipe.outputCount()), stationFromString(staticRecipe.station())));
		}
		return result;
	}

	/**
	 * @return every distinct item (one entry each, no duplicates even if several
	 * recipes use this ingredient) that this item is a known ingredient of -
	 * combining both live and bundled data, since the point here is
	 * discoverability across many different outputs, not just whatever's
	 * personally unlocked.
	 */
	public static List<ItemStack> findUsedIn(Item item, String itemId) {
		Set<Item> seen = new LinkedHashSet<>();
		List<ItemStack> usages = new ArrayList<>();

		Minecraft client = Minecraft.getInstance();
		if (client.player != null && client.level != null) {
			ContextMap context = SlotDisplayContext.fromLevel(client.level);
			for (RecipeCollection collection : client.player.getRecipeBook().getCollections()) {
				for (RecipeDisplayEntry entry : collection.getRecipes()) {
					addUsageIfMatches(entry.display(), item, context, seen, usages);
				}
			}
		}

		for (Map.Entry<String, List<BookletStaticRecipeData.Recipe>> outputEntry : BookletStaticRecipeData.all().entrySet()) {
			if (outputEntry.getKey().equals(itemId)) {
				continue; // an item "using" itself isn't a useful result even if the data somehow implied it
			}
			for (BookletStaticRecipeData.Recipe recipe : outputEntry.getValue()) {
				if (!recipe.ingredientItemIds().contains(itemId)) {
					continue;
				}
				Item outputItem = BuiltInRegistries.ITEM.getValue(Identifier.parse(outputEntry.getKey()));
				if (outputItem != Items.AIR && seen.add(outputItem)) {
					usages.add(new ItemStack(outputItem, recipe.outputCount()));
				}
			}
		}

		return usages;
	}

	private static void addUsageIfMatches(RecipeDisplay display, Item item, ContextMap context, Set<Item> seen, List<ItemStack> usages) {
		List<SlotDisplay> ingredients = ingredientSlotsOf(display);
		if (ingredients == null) {
			return;
		}

		boolean uses = false;
		for (SlotDisplay slot : ingredients) {
			if (slotContainsItem(slot, item, context)) {
				uses = true;
				break;
			}
		}
		if (!uses) {
			return;
		}

		ItemStack output = display.result().resolveForFirstStack(context);
		if (!output.isEmpty() && seen.add(output.getItem())) {
			usages.add(output);
		}
	}

	private static List<SlotDisplay> ingredientSlotsOf(RecipeDisplay display) {
		if (display instanceof ShapedCraftingRecipeDisplay shaped) {
			return shaped.ingredients();
		}
		if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
			return shapeless.ingredients();
		}
		if (display instanceof FurnaceRecipeDisplay furnace) {
			return List.of(furnace.ingredient());
		}
		if (display instanceof StonecutterRecipeDisplay stonecutter) {
			return List.of(stonecutter.input());
		}
		if (display instanceof SmithingRecipeDisplay smithing) {
			return List.of(smithing.template(), smithing.base(), smithing.addition());
		}
		return null;
	}

	private static boolean slotContainsItem(SlotDisplay slot, Item item, ContextMap context) {
		// Every possible item this slot could resolve to (e.g. a whole tag), not
		// just the first representative one - this is a membership check, not an
		// icon to draw, so it needs to be thorough rather than simplified.
		for (ItemStack stack : slot.resolveForStacks(context)) {
			if (stack.getItem() == item) {
				return true;
			}
		}
		return false;
	}

	private static Station stationFromString(String station) {
		return switch (station) {
			case "furnace" -> Station.FURNACE;
			case "smithing_table" -> Station.SMITHING_TABLE;
			case "stonecutter" -> Station.STONECUTTER;
			default -> Station.CRAFTING_TABLE;
		};
	}

	private static Recipe fromDisplay(RecipeDisplay display, Item item, ContextMap context) {
		if (display instanceof ShapedCraftingRecipeDisplay shaped) {
			if (!producesItem(shaped.result(), item, context)) {
				return null;
			}
			return new Recipe(shaped.width(), shaped.height(), resolveEach(shaped.ingredients(), context),
					shaped.result().resolveForFirstStack(context), Station.CRAFTING_TABLE);
		}
		if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
			if (!producesItem(shapeless.result(), item, context)) {
				return null;
			}
			List<ItemStack> ingredients = resolveEach(shapeless.ingredients(), context);
			int width = Math.min(3, Math.max(1, ingredients.size()));
			int height = Math.max(1, (ingredients.size() + 2) / 3);
			return new Recipe(width, height, ingredients, shapeless.result().resolveForFirstStack(context), Station.CRAFTING_TABLE);
		}
		if (display instanceof FurnaceRecipeDisplay furnace) {
			if (!producesItem(furnace.result(), item, context)) {
				return null;
			}
			List<ItemStack> ingredients = List.of(furnace.ingredient().resolveForFirstStack(context));
			return new Recipe(1, 1, ingredients, furnace.result().resolveForFirstStack(context), Station.FURNACE);
		}
		if (display instanceof StonecutterRecipeDisplay stonecutter) {
			if (!producesItem(stonecutter.result(), item, context)) {
				return null;
			}
			List<ItemStack> ingredients = List.of(stonecutter.input().resolveForFirstStack(context));
			return new Recipe(1, 1, ingredients, stonecutter.result().resolveForFirstStack(context), Station.STONECUTTER);
		}
		if (display instanceof SmithingRecipeDisplay smithing) {
			if (!producesItem(smithing.result(), item, context)) {
				return null;
			}
			List<ItemStack> ingredients = List.of(
					smithing.template().resolveForFirstStack(context),
					smithing.base().resolveForFirstStack(context),
					smithing.addition().resolveForFirstStack(context));
			return new Recipe(3, 1, ingredients, smithing.result().resolveForFirstStack(context), Station.SMITHING_TABLE);
		}
		return null;
	}

	private static boolean producesItem(SlotDisplay result, Item item, ContextMap context) {
		for (ItemStack stack : result.resolveForStacks(context)) {
			if (stack.getItem() == item) {
				return true;
			}
		}
		return false;
	}

	private static List<ItemStack> resolveEach(List<SlotDisplay> slots, ContextMap context) {
		List<ItemStack> stacks = new ArrayList<>(slots.size());
		for (SlotDisplay slot : slots) {
			// A slot can represent several interchangeable items (a tag, "any fuel",
			// etc.) - showing just the first is the same simplification the vanilla
			// recipe book's ghost-slot preview makes.
			stacks.add(slot.resolveForFirstStack(context));
		}
		return stacks;
	}
}
