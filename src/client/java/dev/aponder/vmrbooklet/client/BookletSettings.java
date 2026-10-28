package dev.aponder.vmrbooklet.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aponder.vmrbooklet.VMRBooklet;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small persisted client settings - just the scan-notification toggle for now. */
public final class BookletSettings {

	private static final Path FILE = FabricLoader.getInstance().getConfigDir()
			.resolve("vmrbooklet").resolve("settings.json");

	// volatile: markSyncedNow()/setLastSheetLink() are called from background
	// threads (/upload's virtual thread, BookletAutoPush's executor) while the
	// render thread reads these every frame - without volatile there's no
	// happens-before edge, so the render thread could keep seeing stale values.
	private static volatile boolean scanNotifications = true;
	private static volatile long lastSyncedMillis = 0L;
	private static volatile String lastSheetLink = "";
	private static volatile boolean seenOnboarding = false;
	// Which overall-completion milestones (25/50/75/100) have already fired a
	// celebration toast - persisted so reaching 50% once doesn't re-toast it on
	// every future login just because progress is still >= 50%.
	private static final java.util.Set<Integer> seenMilestones = java.util.concurrent.ConcurrentHashMap.newKeySet();

	static {
		load();
	}

	private BookletSettings() {
	}

	public static boolean scanNotifications() {
		return scanNotifications;
	}

	public static synchronized void setScanNotifications(boolean value) {
		scanNotifications = value;
		save();
	}

	public static long lastSyncedMillis() {
		return lastSyncedMillis;
	}

	public static synchronized void markSyncedNow() {
		lastSyncedMillis = System.currentTimeMillis();
		save();
	}

	public static String lastSheetLink() {
		return lastSheetLink;
	}

	public static synchronized void setLastSheetLink(String link) {
		lastSheetLink = link;
		save();
	}

	public static boolean hasSeenOnboarding() {
		return seenOnboarding;
	}

	public static synchronized void markOnboardingSeen() {
		if (!seenOnboarding) {
			seenOnboarding = true;
			save();
		}
	}

	public static boolean hasSeenMilestone(int percent) {
		return seenMilestones.contains(percent);
	}

	public static synchronized void markMilestoneSeen(int percent) {
		if (seenMilestones.add(percent)) {
			save();
		}
	}

	private static void load() {
		if (!Files.exists(FILE)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(FILE)) {
			JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
			if (obj.has("scanNotifications")) {
				scanNotifications = obj.get("scanNotifications").getAsBoolean();
			}
			if (obj.has("lastSyncedMillis")) {
				lastSyncedMillis = obj.get("lastSyncedMillis").getAsLong();
			}
			if (obj.has("lastSheetLink")) {
				lastSheetLink = obj.get("lastSheetLink").getAsString();
			}
			if (obj.has("seenOnboarding")) {
				seenOnboarding = obj.get("seenOnboarding").getAsBoolean();
			}
			if (obj.has("seenMilestones")) {
				for (com.google.gson.JsonElement el : obj.getAsJsonArray("seenMilestones")) {
					seenMilestones.add(el.getAsInt());
				}
			}
		} catch (IOException | RuntimeException e) {
			VMRBooklet.LOGGER.warn("Failed to load booklet settings, using defaults.", e);
		}
	}

	private static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			JsonObject obj = new JsonObject();
			obj.addProperty("scanNotifications", scanNotifications);
			obj.addProperty("lastSyncedMillis", lastSyncedMillis);
			obj.addProperty("lastSheetLink", lastSheetLink);
			obj.addProperty("seenOnboarding", seenOnboarding);
			com.google.gson.JsonArray milestonesArray = new com.google.gson.JsonArray();
			for (int milestone : seenMilestones) {
				milestonesArray.add(milestone);
			}
			obj.add("seenMilestones", milestonesArray);
			try (Writer writer = Files.newBufferedWriter(FILE)) {
				new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(obj, writer);
			}
		} catch (IOException e) {
			VMRBooklet.LOGGER.warn("Failed to save booklet settings.", e);
		}
	}
}
