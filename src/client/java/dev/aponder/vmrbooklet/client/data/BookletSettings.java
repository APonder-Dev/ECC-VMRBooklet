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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;

/** Small persisted client settings: notifications, header lines, HUD/tooltip/highlight options, sync bookkeeping and one-time flags. */
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
	private static volatile boolean showStreakLine = true;
	private static volatile boolean showGoalLine = true;
	private static volatile boolean tooltipLine = true;
	private static volatile boolean highlightNeeded = false;
	private static volatile boolean hudEnabled = false;
	private static volatile HudCorner hudCorner = HudCorner.TOP_LEFT;
	private static volatile boolean colorblind = false;
	private static volatile boolean sessionSummary = true;
	// Which overall-completion milestones (25/50/75/100) have already fired a
	// celebration toast - persisted so reaching 50% once doesn't re-toast it on
	// every future login just because progress is still >= 50%.
	private static final Set<Integer> seenMilestones = ConcurrentHashMap.newKeySet();

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

	/** Whether the booklet header shows the "N day streak" part. */
	public static boolean showStreakLine() {
		return showStreakLine;
	}

	public static synchronized void setShowStreakLine(boolean value) {
		showStreakLine = value;
		save();
	}

	/** Whether the booklet header shows the active goal list's progress. */
	public static boolean showGoalLine() {
		return showGoalLine;
	}

	public static synchronized void setShowGoalLine(boolean value) {
		showGoalLine = value;
		save();
	}

	/** Where the on-screen goal tracker sits. */
	public enum HudCorner {
		TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT;

		public HudCorner next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	/** Whether every item tooltip gets a "Booklet: 3/5" line. */
	public static boolean tooltipLine() {
		return tooltipLine;
	}

	public static synchronized void setTooltipLine(boolean value) {
		tooltipLine = value;
		save();
	}

	/** Whether container slots holding a still-needed item get a tinted highlight. */
	public static boolean highlightNeeded() {
		return highlightNeeded;
	}

	public static synchronized void setHighlightNeeded(boolean value) {
		highlightNeeded = value;
		save();
	}

	/** Whether the active goal list is drawn on the in-game HUD. */
	public static boolean hudEnabled() {
		return hudEnabled;
	}

	public static synchronized void setHudEnabled(boolean value) {
		hudEnabled = value;
		save();
	}

	public static HudCorner hudCorner() {
		return hudCorner;
	}

	public static synchronized void setHudCorner(HudCorner value) {
		hudCorner = value;
		save();
	}

	/** Blue/orange/gray instead of green/yellow/gray, plus check/tilde marks on list rows. */
	public static boolean colorblind() {
		return colorblind;
	}

	public static synchronized void setColorblind(boolean value) {
		colorblind = value;
		save();
	}

	/** Whether closing the booklet or leaving a server shows a "+N items this session" toast. */
	public static boolean sessionSummary() {
		return sessionSummary;
	}

	public static synchronized void setSessionSummary(boolean value) {
		sessionSummary = value;
		save();
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
			if (obj.has("showStreakLine")) {
				showStreakLine = obj.get("showStreakLine").getAsBoolean();
			}
			if (obj.has("showGoalLine")) {
				showGoalLine = obj.get("showGoalLine").getAsBoolean();
			}
			if (obj.has("tooltipLine")) {
				tooltipLine = obj.get("tooltipLine").getAsBoolean();
			}
			if (obj.has("highlightNeeded")) {
				highlightNeeded = obj.get("highlightNeeded").getAsBoolean();
			}
			if (obj.has("hudEnabled")) {
				hudEnabled = obj.get("hudEnabled").getAsBoolean();
			}
			if (obj.has("hudCorner")) {
				try {
					hudCorner = HudCorner.valueOf(obj.get("hudCorner").getAsString());
				} catch (IllegalArgumentException ignored) {
					// Unknown value from a hand edit - keep the default.
				}
			}
			if (obj.has("colorblind")) {
				colorblind = obj.get("colorblind").getAsBoolean();
			}
			if (obj.has("sessionSummary")) {
				sessionSummary = obj.get("sessionSummary").getAsBoolean();
			}
			if (obj.has("seenMilestones")) {
				for (JsonElement el : obj.getAsJsonArray("seenMilestones")) {
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
			obj.addProperty("showStreakLine", showStreakLine);
			obj.addProperty("showGoalLine", showGoalLine);
			obj.addProperty("tooltipLine", tooltipLine);
			obj.addProperty("highlightNeeded", highlightNeeded);
			obj.addProperty("hudEnabled", hudEnabled);
			obj.addProperty("hudCorner", hudCorner.name());
			obj.addProperty("colorblind", colorblind);
			obj.addProperty("sessionSummary", sessionSummary);
			JsonArray milestonesArray = new JsonArray();
			for (int milestone : seenMilestones) {
				milestonesArray.add(milestone);
			}
			obj.add("seenMilestones", milestonesArray);
			BookletConfigFiles.writeJson(FILE, obj);
		} catch (IOException e) {
			VMRBooklet.LOGGER.warn("Failed to save booklet settings.", e);
		}
	}
}
