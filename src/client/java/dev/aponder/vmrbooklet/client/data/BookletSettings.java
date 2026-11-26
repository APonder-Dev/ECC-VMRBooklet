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
	private static volatile boolean trackerEnabled = true;
	private static volatile TrackerSide trackerSide = TrackerSide.RIGHT;
	private static volatile boolean trackerHideDone = false;
	private static volatile boolean trackerIcons = false;
	private static final int[] trackerColors = defaultTrackerColors();
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

	/** Whether pinned items are drawn on the in-game side tracker. */
	public static boolean trackerEnabled() {
		return trackerEnabled;
	}

	public static synchronized void setTrackerEnabled(boolean value) {
		trackerEnabled = value;
		save();
	}

	/** Which screen edge the side tracker hugs; it's always vertically centered. */
	public enum TrackerSide {
		RIGHT, LEFT;

		public TrackerSide next() {
			return this == RIGHT ? LEFT : RIGHT;
		}
	}

	public static TrackerSide trackerSide() {
		return trackerSide;
	}

	public static synchronized void setTrackerSide(TrackerSide value) {
		trackerSide = value;
		save();
	}

	/** Whether finished pins drop off the side tracker instead of showing a check. */
	public static boolean trackerHideDone() {
		return trackerHideDone;
	}

	public static synchronized void setTrackerHideDone(boolean value) {
		trackerHideDone = value;
		save();
	}

	/** Whether each side tracker row starts with the item's icon. */
	public static boolean trackerIcons() {
		return trackerIcons;
	}

	public static synchronized void setTrackerIcons(boolean value) {
		trackerIcons = value;
		save();
	}

	/** One editable side tracker color; each is stored as ARGB. */
	public enum TrackerColor {
		BACKGROUND(0x90101015),
		TEXT(0xFFFFFFFF),
		COUNT(0xFFFFD36B),
		DONE(0xFF6BD36B),
		FRIEND(0xFF7FC8FF);

		public final int defaultArgb;

		TrackerColor(int defaultArgb) {
			this.defaultArgb = defaultArgb;
		}

		String jsonKey() {
			return "trackerColor" + name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT);
		}
	}

	/** Every color's default, indexed by {@link TrackerColor#ordinal()} - also the "Hologram" preset. */
	public static int[] defaultTrackerColors() {
		TrackerColor[] values = TrackerColor.values();
		int[] colors = new int[values.length];
		for (TrackerColor color : values) {
			colors[color.ordinal()] = color.defaultArgb;
		}
		return colors;
	}

	public static int trackerColor(TrackerColor color) {
		return trackerColors[color.ordinal()];
	}

	/**
	 * Changes one color in memory only - the color editor calls this on every
	 * keystroke and saves once with {@link #saveTrackerColors()} when it closes.
	 */
	public static synchronized void setTrackerColorUnsaved(TrackerColor color, int argb) {
		trackerColors[color.ordinal()] = argb;
	}

	public static synchronized void saveTrackerColors() {
		save();
	}

	/**
	 * Sets every tracker color at once (a preset or reset) with a single save.
	 * A shorter array (a preset written before a color was added) leaves the
	 * missing colors at their defaults.
	 */
	public static synchronized void setTrackerColors(int[] argb) {
		for (TrackerColor color : TrackerColor.values()) {
			int i = color.ordinal();
			trackerColors[i] = i < argb.length ? argb[i] : color.defaultArgb;
		}
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

	/** "#AARRGGBB" - the form colors are saved and edited in. */
	public static String formatColor(int argb) {
		return String.format(java.util.Locale.ROOT, "#%08X", argb);
	}

	/**
	 * Reads "#RRGGBB" (fully opaque) or "#AARRGGBB", with or without the "#".
	 * @return the ARGB value, or null if it isn't one of those shapes
	 */
	public static Integer parseColor(String text) {
		String hex = text.trim();
		if (hex.startsWith("#")) {
			hex = hex.substring(1);
		}
		if (hex.length() != 6 && hex.length() != 8) {
			return null;
		}
		// Checked by hand: Long.parseLong would also accept a leading "+" or "-".
		for (int i = 0; i < hex.length(); i++) {
			if (Character.digit(hex.charAt(i), 16) < 0) {
				return null;
			}
		}
		long value = Long.parseLong(hex, 16);
		return hex.length() == 6 ? (int) (0xFF000000L | value) : (int) value;
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
			if (obj.has("trackerEnabled")) {
				trackerEnabled = obj.get("trackerEnabled").getAsBoolean();
			}
			if (obj.has("trackerSide")) {
				try {
					trackerSide = TrackerSide.valueOf(obj.get("trackerSide").getAsString());
				} catch (IllegalArgumentException ignored) {
					// Unknown value from a hand edit - keep the default.
				}
			}
			if (obj.has("trackerHideDone")) {
				trackerHideDone = obj.get("trackerHideDone").getAsBoolean();
			}
			if (obj.has("trackerIcons")) {
				trackerIcons = obj.get("trackerIcons").getAsBoolean();
			}
			for (TrackerColor color : TrackerColor.values()) {
				if (obj.has(color.jsonKey())) {
					Integer argb = parseColor(obj.get(color.jsonKey()).getAsString());
					if (argb != null) {
						trackerColors[color.ordinal()] = argb;
					}
				}
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
			obj.addProperty("trackerEnabled", trackerEnabled);
			obj.addProperty("trackerSide", trackerSide.name());
			obj.addProperty("trackerHideDone", trackerHideDone);
			obj.addProperty("trackerIcons", trackerIcons);
			for (TrackerColor color : TrackerColor.values()) {
				obj.addProperty(color.jsonKey(), formatColor(trackerColors[color.ordinal()]));
			}
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
