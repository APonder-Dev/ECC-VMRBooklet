package dev.aponder.vmrbooklet.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import dev.aponder.vmrbooklet.VMRBooklet;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

	private static final Set<String> FAVORITES = load();

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
		save();
	}

	private static Set<String> load() {
		Set<String> result = new LinkedHashSet<>();
		if (!Files.exists(FILE)) {
			return result;
		}
		try (Reader reader = Files.newBufferedReader(FILE)) {
			JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();
			for (var element : array) {
				result.add(element.getAsString());
			}
		} catch (IOException | RuntimeException e) {
			VMRBooklet.LOGGER.warn("Failed to load booklet favorites, starting empty.", e);
		}
		return result;
	}

	private static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			JsonArray array = new JsonArray();
			for (String itemId : List.copyOf(FAVORITES)) {
				array.add(itemId);
			}
			try (Writer writer = Files.newBufferedWriter(FILE)) {
				new GsonBuilder().setPrettyPrinting().create().toJson(array, writer);
			}
		} catch (IOException e) {
			VMRBooklet.LOGGER.warn("Failed to save booklet favorites.", e);
		}
	}
}
