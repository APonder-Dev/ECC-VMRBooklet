package dev.aponder.vmrbooklet.client.data;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

/**
 * A personal shortlist of pinned items, entirely independent of progress -
 * just a flag you can toggle on anything to build your own "focus on these
 * next" list, separate from what's actually achieved/in-progress/undiscovered.
 * Only ever touched from the render/main thread (GUI clicks), so no
 * synchronization beyond what a plain {@link LinkedHashSet} already gives a
 * single-threaded caller.
 */
public final class BookletFavorites {

	private static final Path FILE = FabricLoader.getInstance().getConfigDir()
			.resolve("vmrbooklet").resolve("favorites.json");

	private static final Set<String> FAVORITES = new LinkedHashSet<>(BookletJsonStringSet.load(FILE, "booklet favorites"));

	private BookletFavorites() {
	}

	public static boolean isFavorite(String itemId) {
		return FAVORITES.contains(itemId);
	}

	/** Flips the favorite flag for this item and saves. */
	public static void toggle(String itemId) {
		if (!FAVORITES.remove(itemId)) {
			FAVORITES.add(itemId);
		}
		BookletJsonStringSet.save(FILE, FAVORITES, "booklet favorites");
	}
}
