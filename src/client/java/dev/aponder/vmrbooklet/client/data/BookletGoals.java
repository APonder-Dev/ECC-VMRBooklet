package dev.aponder.vmrbooklet.client.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aponder.vmrbooklet.VMRBooklet;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Several small, self-picked lists of items to focus on right now - separate
 * from {@link BookletFavorites} (a general bookmark list) in that the header
 * shows a dedicated "goal" progress bar for whichever list is active, so you
 * can track a specific batch (e.g. "Nether trip" vs. "Village trading") one
 * at a time without them all being mixed into one list.
 *
 * <p>Started out as a single unnamed list (a flat {@code Set<String>}
 * persisted as a flat JSON array). {@link #load()} migrates a file still in
 * that shape into a single list named {@link #DEFAULT_LIST} on first read, so
 * nobody's existing goals silently vanish just because this grew named lists.
 */
public final class BookletGoals {

	public static final String DEFAULT_LIST = "default";

	private static final Path FILE = FabricLoader.getInstance().getConfigDir()
			.resolve("vmrbooklet").resolve("goals.json");

	// Every public accessor is synchronized: item tooltips (which check isGoal)
	// can be built off the render thread, e.g. for the creative search index.
	private static final Map<String, LinkedHashSet<String>> LISTS = new LinkedHashMap<>();
	private static String activeList = DEFAULT_LIST;
	private static boolean savingDisabled = false;

	static {
		load();
	}

	private BookletGoals() {
	}

	private static LinkedHashSet<String> activeSet() {
		return LISTS.computeIfAbsent(activeList, k -> new LinkedHashSet<>());
	}

	public static synchronized boolean isGoal(String itemId) {
		return activeSet().contains(itemId);
	}

	public static synchronized Set<String> all() {
		return Set.copyOf(activeSet());
	}

	public static synchronized boolean isEmpty() {
		return activeSet().isEmpty();
	}

	public static synchronized String activeListName() {
		return activeList;
	}

	/** Every named goal list, in the order each was first created ({@link #DEFAULT_LIST} always first). */
	public static synchronized List<String> listNames() {
		List<String> names = new ArrayList<>(LISTS.keySet());
		names.remove(DEFAULT_LIST);
		names.add(0, DEFAULT_LIST);
		return List.copyOf(names);
	}

	/**
	 * Switches to this named list, creating it (empty) first if it doesn't exist
	 * yet. Names are case-insensitive: "Nether" and "nether" are the same list.
	 * @return true if it was just created.
	 */
	public static synchronized boolean switchOrCreate(String name) {
		String existing = findKey(name);
		boolean created = existing == null;
		String key = created ? name.trim() : existing;
		LISTS.computeIfAbsent(key, k -> new LinkedHashSet<>());
		activeList = key;
		save();
		return created;
	}

	public enum DeleteResult { DELETED, NOT_FOUND, IS_DEFAULT }

	/**
	 * Deletes a named list. The default list can only be cleared, never deleted,
	 * so there is always at least one list; deleting the active list switches
	 * back to the default one.
	 */
	public static synchronized DeleteResult delete(String name) {
		String key = findKey(name);
		if (key == null) {
			return DeleteResult.NOT_FOUND;
		}
		if (key.equals(DEFAULT_LIST)) {
			return DeleteResult.IS_DEFAULT;
		}
		LISTS.remove(key);
		if (activeList.equals(key)) {
			activeList = DEFAULT_LIST;
		}
		save();
		return DeleteResult.DELETED;
	}

	/** @return the stored spelling of a list matching this name case-insensitively, or null. */
	public static synchronized String findKey(String name) {
		String trimmed = name.trim();
		for (String key : LISTS.keySet()) {
			if (key.equalsIgnoreCase(trimmed)) {
				return key;
			}
		}
		return null;
	}

	/** @return true if it's now a goal, false if this removed it - always applies to the active list. */
	public static synchronized boolean toggle(String itemId) {
		LinkedHashSet<String> set = activeSet();
		boolean nowGoal;
		if (set.remove(itemId)) {
			nowGoal = false;
		} else {
			set.add(itemId);
			nowGoal = true;
		}
		save();
		return nowGoal;
	}

	public static synchronized void clear() {
		LinkedHashSet<String> set = activeSet();
		if (!set.isEmpty()) {
			set.clear();
			save();
		}
	}

	private static void load() {
		LISTS.put(DEFAULT_LIST, new LinkedHashSet<>());
		if (!Files.exists(FILE)) {
			return;
		}
		// Parsed into a scratch map first and only committed once the whole file
		// read cleanly - a half-read file must never become the in-memory state,
		// because the next save would then overwrite the lists that didn't load.
		Map<String, LinkedHashSet<String>> parsed = new LinkedHashMap<>();
		parsed.put(DEFAULT_LIST, new LinkedHashSet<>());
		String parsedActive = DEFAULT_LIST;
		try (Reader reader = Files.newBufferedReader(FILE)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (root.isJsonArray()) {
				// Pre-named-lists format: a flat array of item ids, all in one
				// (unnamed) list - becomes the default list.
				for (JsonElement element : root.getAsJsonArray()) {
					parsed.get(DEFAULT_LIST).add(element.getAsString());
				}
			} else {
				JsonObject obj = root.getAsJsonObject();
				if (obj.has("lists")) {
					for (Map.Entry<String, JsonElement> listEntry : obj.getAsJsonObject("lists").entrySet()) {
						LinkedHashSet<String> set = new LinkedHashSet<>();
						for (JsonElement element : listEntry.getValue().getAsJsonArray()) {
							set.add(element.getAsString());
						}
						// Older builds treated "Default" and "default" as separate lists -
						// merge any case-only duplicates into whichever spelling came first.
						String key = keyIgnoringCase(parsed, listEntry.getKey().trim());
						parsed.computeIfAbsent(key, k -> new LinkedHashSet<>()).addAll(set);
					}
				}
				if (obj.has("activeList")) {
					String key = keyIgnoringCase(parsed, obj.get("activeList").getAsString().trim());
					if (parsed.containsKey(key)) {
						parsedActive = key;
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			backUpUnreadableFile(e);
			return;
		}
		LISTS.clear();
		LISTS.putAll(parsed);
		activeList = parsedActive;
	}

	private static String keyIgnoringCase(Map<String, ?> map, String name) {
		for (String key : map.keySet()) {
			if (key.equalsIgnoreCase(name)) {
				return key;
			}
		}
		return name;
	}

	private static void backUpUnreadableFile(Exception cause) {
		Path backup = FILE.resolveSibling(FILE.getFileName() + ".bak");
		try {
			Files.copy(FILE, backup, StandardCopyOption.REPLACE_EXISTING);
			VMRBooklet.LOGGER.warn("Couldn't read booklet goals - kept a copy at {} and started empty.", backup, cause);
		} catch (IOException copyFailure) {
			// No safe copy exists, so never overwrite the original this session.
			savingDisabled = true;
			VMRBooklet.LOGGER.warn("Couldn't read booklet goals or back them up - started empty, and won't save over the file this session.", cause);
		}
	}

	private static void save() {
		if (savingDisabled) {
			return;
		}
		try {
			Files.createDirectories(FILE.getParent());
			JsonObject root = new JsonObject();
			root.addProperty("activeList", activeList);
			JsonObject listsObj = new JsonObject();
			for (Map.Entry<String, LinkedHashSet<String>> entry : LISTS.entrySet()) {
				JsonArray array = new JsonArray();
				for (String itemId : entry.getValue()) {
					array.add(itemId);
				}
				listsObj.add(entry.getKey(), array);
			}
			root.add("lists", listsObj);
			BookletConfigFiles.writeJson(FILE, root);
		} catch (IOException e) {
			VMRBooklet.LOGGER.warn("Failed to save booklet goals.", e);
		}
	}
}
