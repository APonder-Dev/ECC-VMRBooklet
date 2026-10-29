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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A short, local log of items you've recently finished (local progress
 * reaching its required amount) - newest first, capped at
 * {@link #MAX_ENTRIES}. Populated by {@link BookletLocalProgress}, which is
 * the one place every progress-changing path (upload, live scan, /sell,
 * manual edit, restore) already funnels through, so this never needs its own
 * copy of "did this just finish" detection.
 */
public final class BookletActivityLog {

	public record Entry(String itemId, long timestampMillis) {
	}

	private static final int MAX_ENTRIES = 50;
	private static final Path FILE = FabricLoader.getInstance().getConfigDir()
			.resolve("vmrbooklet").resolve("activity.json");

	// Same reasoning as BookletFriends' map: detectAndNotifyAchievements (and
	// so recordAchieved) can run from a background thread (e.g. /upload), while
	// recent() is read every frame the "R" overlay is open - a plain ArrayList
	// risks ConcurrentModificationException or a torn read across that race.
	private static final List<Entry> ENTRIES = Collections.synchronizedList(load());

	private BookletActivityLog() {
	}

	/** Newest first. */
	public static List<Entry> recent() {
		synchronized (ENTRIES) {
			return List.copyOf(ENTRIES);
		}
	}

	public static void recordAchieved(String itemId) {
		synchronized (ENTRIES) {
			ENTRIES.removeIf(e -> e.itemId().equals(itemId));
			ENTRIES.add(0, new Entry(itemId, System.currentTimeMillis()));
			while (ENTRIES.size() > MAX_ENTRIES) {
				ENTRIES.remove(ENTRIES.size() - 1);
			}
		}
		save();
	}

	private static List<Entry> load() {
		List<Entry> result = new ArrayList<>();
		if (!Files.exists(FILE)) {
			return result;
		}
		try (Reader reader = Files.newBufferedReader(FILE)) {
			JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();
			for (var element : array) {
				JsonObject obj = element.getAsJsonObject();
				result.add(new Entry(obj.get("itemId").getAsString(), obj.get("time").getAsLong()));
			}
		} catch (IOException | RuntimeException e) {
			VMRBooklet.LOGGER.warn("Failed to load booklet activity log, starting empty.", e);
		}
		return result;
	}

	private static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			JsonArray array = new JsonArray();
			for (Entry entry : recent()) {
				JsonObject obj = new JsonObject();
				obj.addProperty("itemId", entry.itemId());
				obj.addProperty("time", entry.timestampMillis());
				array.add(obj);
			}
			try (Writer writer = Files.newBufferedWriter(FILE)) {
				new GsonBuilder().setPrettyPrinting().create().toJson(array, writer);
			}
		} catch (IOException e) {
			VMRBooklet.LOGGER.warn("Failed to save booklet activity log.", e);
		}
	}
}
