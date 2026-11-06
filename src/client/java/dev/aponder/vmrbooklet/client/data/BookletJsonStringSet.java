package dev.aponder.vmrbooklet.client.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import dev.aponder.vmrbooklet.VMRBooklet;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared load/save for the handful of config files that are nothing more
 * than a flat JSON array of item ids - {@link BookletFavorites} and {@link
 * BookletGoals} both used to carry their own copy of this exact read/write
 * logic, differing only in the file path and the log message.
 */
final class BookletJsonStringSet {

	private BookletJsonStringSet() {
	}

	static Set<String> load(Path file, String whatForLogging) {
		Set<String> result = new LinkedHashSet<>();
		if (!Files.exists(file)) {
			return result;
		}
		try (Reader reader = Files.newBufferedReader(file)) {
			JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();
			for (var element : array) {
				result.add(element.getAsString());
			}
		} catch (IOException | RuntimeException e) {
			VMRBooklet.LOGGER.warn("Failed to load " + whatForLogging + ", starting empty.", e);
		}
		return result;
	}

	static void save(Path file, Set<String> values, String whatForLogging) {
		try {
			Files.createDirectories(file.getParent());
			JsonArray array = new JsonArray();
			for (String itemId : List.copyOf(values)) {
				array.add(itemId);
			}
			BookletConfigFiles.writeJson(file, array);
		} catch (IOException e) {
			VMRBooklet.LOGGER.warn("Failed to save " + whatForLogging + ".", e);
		}
	}
}
