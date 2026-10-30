package dev.aponder.vmrbooklet.client;

import dev.aponder.vmrbooklet.VMRBooklet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Buckets every booklet item into one of Minecraft's own creative-inventory
 * tabs (Building Blocks, Natural Blocks, Redstone, etc.) so the booklet's
 * list can be filtered by category the same rough way the creative menu
 * already groups things - no hand-maintained per-item category list to keep
 * in sync with 1657+ items across every version this mod supports.
 *
 * <p>Built lazily on first use, not at mod init - creative tab contents
 * ({@link CreativeModeTab#getDisplayItems()}) are NOT populated automatically
 * on joining a world. The only vanilla caller of
 * {@code CreativeModeTabs.tryRebuildTabContents} is the creative inventory
 * screen's own constructor, so a survival player who never opens it would
 * otherwise see every item fall back to {@link Category#OTHER} forever -
 * {@link #build()} triggers that same rebuild itself, using the connected
 * player's own enabled features and registry access.
 *
 * <p>Only ever called from the render thread (every known call site -
 * BookletScreen's button/search callbacks and refreshFiltered() - runs
 * there); {@code byItem} is still {@code volatile} to match this codebase's
 * convention for cross-thread-visible static state (see
 * {@link GoogleServiceAccount}'s cachedClientEmail) in case a future caller
 * doesn't honor that.
 */
public final class BookletCategories {

	public enum Category {
		BUILDING_BLOCKS("Building Blocks"),
		COLORED_BLOCKS("Colored Blocks"),
		NATURAL_BLOCKS("Natural Blocks"),
		FUNCTIONAL_BLOCKS("Functional Blocks"),
		REDSTONE("Redstone"),
		TOOLS_AND_UTILITIES("Tools & Utilities"),
		COMBAT("Combat"),
		FOOD_AND_DRINKS("Food & Drinks"),
		INGREDIENTS("Ingredients"),
		SPAWN_EGGS("Spawn Eggs"),
		// Anything not in one of the 10 tabs above - command/debug-only blocks,
		// and anything else the creative menu itself doesn't surface.
		OTHER("Other");

		private final String label;

		Category(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	// Rebuilt per-connection (different servers can enable different
	// experimental features/datapacks, changing which items land in which
	// tab) - cleared on disconnect below, not just left to the next join.
	private static volatile Map<Item, Category> byItem;

	// Guards against retrying the full tab walk on every single categoryOf()
	// call within the same refreshFiltered() pass (up to ~1657 calls) if
	// build() keeps coming back empty - caps retries to roughly once/second
	// instead of once per item.
	private static volatile long lastFailedAttemptMillis = -1;
	private static final long RETRY_INTERVAL_MILLIS = 1000;

	static {
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> byItem = null);
	}

	private BookletCategories() {
	}

	/** @return the item's category, or {@link Category#OTHER} if it's not in any of the mapped creative tabs. */
	public static Category categoryOf(Item item) {
		Map<Item, Category> snapshot = byItem;
		if (snapshot == null) {
			long now = System.currentTimeMillis();
			if (lastFailedAttemptMillis < 0 || now - lastFailedAttemptMillis >= RETRY_INTERVAL_MILLIS) {
				build();
				snapshot = byItem;
			}
		}
		return snapshot == null ? Category.OTHER : snapshot.getOrDefault(item, Category.OTHER);
	}

	private static void build() {
		var player = Minecraft.getInstance().player;
		ClientPacketListener connection = player == null ? null : player.connection;
		if (connection == null) {
			// Nothing to rebuild from (no active connection) and no world's tab
			// contents to trust either - bail out instead of walking tabs that may
			// hold another connection's stale data.
			lastFailedAttemptMillis = System.currentTimeMillis();
			return;
		}

		try {
			// Unlike almost everything else about creative tabs, display-item lists
			// aren't rebuilt on joining a world by vanilla itself - see the class doc.
			// `true` (not false) for displayOperatorCreativeTab: we skip the op-blocks
			// tab entirely in categoryForTabPath() either way, so the value has no
			// effect on our own classification - but tryRebuildTabContents treats a
			// different value than the real Creative screen's own call as a cache
			// miss, forcing a full tab-rebuild every time an operator alternates
			// between that screen and this one. `true` matches what an operator's own
			// Creative screen passes, so the cache stays warm for them too.
			CreativeModeTabs.tryRebuildTabContents(connection.enabledFeatures(), true, connection.registryAccess());

			Map<Item, Category> result = new LinkedHashMap<>();

			for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
				Identifier key = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
				Category category = key == null ? null : categoryForTabPath(key.getPath());
				if (category == null) {
					continue; // hotbar/search/op_blocks/inventory - not a real category
				}

				// First tab (in registry iteration order) wins for an item that shows
				// up in more than one - putIfAbsent, not put.
				for (ItemStack stack : tab.getDisplayItems()) {
					result.putIfAbsent(stack.getItem(), category);
				}
			}

			if (result.isEmpty()) {
				// Tabs genuinely weren't populated yet (shouldn't happen in practice,
				// since the booklet only opens inside a joined world) - don't
				// permanently cache an empty result, but don't hammer a full retry on
				// every item in the current refreshFiltered() pass either.
				lastFailedAttemptMillis = System.currentTimeMillis();
				VMRBooklet.LOGGER.warn("Creative tabs weren't populated yet - category lookup will retry shortly.");
				return;
			}

			byItem = Map.copyOf(result);
			lastFailedAttemptMillis = -1;
			VMRBooklet.LOGGER.info("Classified {} items into creative-tab categories.", byItem.size());
		} catch (Exception e) {
			// A third-party mod's own creative tab can throw from its display-items
			// generator - refreshFiltered() runs from the search box's keystroke
			// handler and from init(), so an uncaught exception here would break
			// GUI input or screen construction well beyond this one feature.
			lastFailedAttemptMillis = System.currentTimeMillis();
			VMRBooklet.LOGGER.warn("Failed to classify items into creative-tab categories; will retry shortly.", e);
		}
	}

	private static Category categoryForTabPath(String path) {
		return switch (path) {
			case "building_blocks" -> Category.BUILDING_BLOCKS;
			case "colored_blocks" -> Category.COLORED_BLOCKS;
			case "natural_blocks" -> Category.NATURAL_BLOCKS;
			case "functional_blocks" -> Category.FUNCTIONAL_BLOCKS;
			case "redstone_blocks" -> Category.REDSTONE;
			case "tools_and_utilities" -> Category.TOOLS_AND_UTILITIES;
			case "combat" -> Category.COMBAT;
			case "food_and_drinks" -> Category.FOOD_AND_DRINKS;
			case "ingredients" -> Category.INGREDIENTS;
			case "spawn_eggs" -> Category.SPAWN_EGGS;
			default -> null;
		};
	}
}
