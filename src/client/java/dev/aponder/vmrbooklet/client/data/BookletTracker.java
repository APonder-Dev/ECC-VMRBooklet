package dev.aponder.vmrbooklet.client.data;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Items pinned to the on-screen side tracker, each tied to whose booklet it
 * follows - your own, or an imported friend's. The same item can be pinned
 * once per source (your Elytra and a friend's Elytra are two pins). Saved as
 * {@code "itemId|source"} strings, with an empty source meaning you. Only
 * touched from the render/main thread, same as {@link BookletFavorites}.
 */
public final class BookletTracker {

	/** One pinned row. {@code friend} is null for your own booklet. */
	public record Pin(String itemId, String friend) {
		/** The saved form, also a stable per-pin key for anything that tracks pins by identity. */
		public String key() {
			return itemId + "|" + (friend == null ? "" : friend);
		}

		static Pin parse(String key) {
			int split = key.indexOf('|');
			if (split < 0) {
				return new Pin(key, null);
			}
			String friend = key.substring(split + 1);
			return new Pin(key.substring(0, split), friend.isEmpty() ? null : friend);
		}
	}

	private static final Path FILE = FabricLoader.getInstance().getConfigDir()
			.resolve("vmrbooklet").resolve("tracker.json");

	private static final Set<String> PINS = new LinkedHashSet<>(BookletJsonStringSet.load(FILE, "booklet tracker"));

	private BookletTracker() {
	}

	public static boolean isPinned(String itemId, String friend) {
		return PINS.contains(new Pin(itemId, friend).key());
	}

	/** Pins or unpins and saves. @return true if the item is now pinned. */
	public static boolean toggle(String itemId, String friend) {
		String key = new Pin(itemId, friend).key();
		boolean pinned = PINS.add(key);
		if (!pinned) {
			PINS.remove(key);
		}
		BookletJsonStringSet.save(FILE, PINS, "booklet tracker");
		return pinned;
	}

	/** Drops every pin that follows {@code friend} - used when that friend is removed. */
	public static void removeFriend(String friend) {
		if (PINS.removeIf(key -> friend.equals(Pin.parse(key).friend()))) {
			BookletJsonStringSet.save(FILE, PINS, "booklet tracker");
		}
	}

	public static void clear() {
		PINS.clear();
		BookletJsonStringSet.save(FILE, PINS, "booklet tracker");
	}

	/** Every pin, in the order it was added. */
	public static List<Pin> all() {
		List<Pin> pins = new ArrayList<>(PINS.size());
		for (String key : PINS) {
			pins.add(Pin.parse(key));
		}
		return pins;
	}

	public static boolean isEmpty() {
		return PINS.isEmpty();
	}
}
