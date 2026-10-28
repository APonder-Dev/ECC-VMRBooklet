package dev.aponder.vmrbooklet.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.BookletSort;
import dev.aponder.vmrbooklet.VMRBooklet;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Purely local, client-side booklet progress. There is no server component to
 * this mod at all - this is the only source of truth, populated entirely by
 * {@code /upload <google-sheet-link>} and persisted to disk so it survives
 * restarts and follows you between servers (including the real public one).
 *
 * <p>Backed by a {@link ConcurrentHashMap}: the render thread reads this every
 * frame while a background thread ({@code /upload}'s virtual thread, or
 * {@link BookletAutoPush}'s executor) can be reading it mid-write for a
 * multi-second network round trip, so plain {@code HashMap} would risk
 * corrupting iteration state under concurrent access.
 */
public final class BookletLocalProgress {

	private static final Path FILE = FabricLoader.getInstance().getConfigDir()
			.resolve("vmrbooklet").resolve("progress.json");

	private static final Map<String, Integer> progress = new ConcurrentHashMap<>();

	// One-level undo for /upload, which fully replaces progress - a stray wrong
	// link or fat-fingered URL would otherwise wipe real scanned progress with
	// no way back. Session-only (not persisted): a safety net for "oops", not a
	// full history.
	private static volatile Map<String, Integer> undoSnapshot;

	static {
		load();
	}

	private BookletLocalProgress() {
	}

	public static int obtained(String itemId) {
		return progress.getOrDefault(itemId, 0);
	}

	public static Map<String, Integer> all() {
		return Map.copyOf(progress);
	}

	/** Replaces the whole progress map (what {@code /upload} does) and saves it. */
	public static synchronized void replaceAll(Map<String, Integer> newProgress) {
		Map<String, Integer> before = Map.copyOf(progress);
		undoSnapshot = before;
		progress.clear();
		progress.putAll(newProgress);
		save();
		detectAndNotifyAchievements(before);
	}

	public static boolean canUndo() {
		return undoSnapshot != null;
	}

	/** Restores whatever progress looked like right before the last {@link #replaceAll}. One level only. */
	public static synchronized boolean undo() {
		if (undoSnapshot == null) {
			return false;
		}
		Map<String, Integer> restore = undoSnapshot;
		undoSnapshot = null;
		progress.clear();
		progress.putAll(restore);
		save();
		return true;
	}

	/**
	 * Merges in scanned values (what {@link BookletLiveScanner} does), only
	 * touching entries that actually changed. Saves once if anything did.
	 *
	 * @return whether anything changed.
	 */
	public static synchronized boolean mergeIfDifferent(Map<String, Integer> scanned) {
		Map<String, Integer> before = Map.copyOf(progress);
		boolean changed = false;
		for (Map.Entry<String, Integer> entry : scanned.entrySet()) {
			Integer previous = progress.put(entry.getKey(), entry.getValue());
			if (previous == null || !previous.equals(entry.getValue())) {
				changed = true;
			}
		}
		if (changed) {
			// The undo snapshot is a "revert my last /upload or /restore" button, not
			// a general history - once the live scanner has folded in real progress on
			// top of that, reverting would silently throw those real gains away too.
			// Safer to just give up the one-level undo than to risk that.
			undoSnapshot = null;
			save();
			detectAndNotifyAchievements(before);
		}
		return changed;
	}

	/**
	 * Compares every item's progress before/after a change and logs+celebrates
	 * ({@link BookletActivityLog}, {@link BookletCelebrations}) any that just
	 * crossed from "not achieved" to "achieved" - called from every path that
	 * changes progress (upload, live scan, /sell, manual edit, restore), so
	 * none of those call sites need their own copy of this detection.
	 */
	private static void detectAndNotifyAchievements(Map<String, Integer> before) {
		List<String> justAchieved = new ArrayList<>();
		for (BookletEntry entry : BookletData.orderedBy(BookletSort.ECC)) {
			int previousAmount = before.getOrDefault(entry.itemId(), 0);
			int currentAmount = progress.getOrDefault(entry.itemId(), 0);
			if (previousAmount < entry.required() && currentAmount >= entry.required()) {
				justAchieved.add(entry.itemId());
			}
		}
		// Milestones are checked on every call (not just when something was
		// individually achieved this call) - not strictly necessary today since
		// an item only crosses "achieved" here, but keeps this correct even if a
		// future change makes required() itself able to shrink.
		BookletCelebrations.notifyMilestones(before);
		if (justAchieved.isEmpty()) {
			return;
		}
		sessionObtainedCount += justAchieved.size();
		for (String itemId : justAchieved) {
			BookletActivityLog.recordAchieved(itemId);
		}
		BookletCelebrations.notifyAchievements(justAchieved);
	}

	// Session-only (not persisted): how many items this client has completed
	// since the game was launched, shown in the booklet's header as a quick
	// "what have I gotten done today" counter separate from the lifetime total.
	private static volatile int sessionObtainedCount = 0;

	public static int sessionObtainedCount() {
		return sessionObtainedCount;
	}

	private static void load() {
		if (!Files.exists(FILE)) {
			return;
		}

		try (Reader reader = Files.newBufferedReader(FILE)) {
			JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
			for (Map.Entry<String, com.google.gson.JsonElement> entry : obj.entrySet()) {
				progress.put(entry.getKey(), entry.getValue().getAsInt());
			}
		} catch (IOException | RuntimeException e) {
			VMRBooklet.LOGGER.warn("Failed to load local booklet progress, starting empty.", e);
		}
	}

	private static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			JsonObject obj = new JsonObject();
			for (Map.Entry<String, Integer> entry : progress.entrySet()) {
				obj.addProperty(entry.getKey(), entry.getValue());
			}
			try (Writer writer = Files.newBufferedWriter(FILE)) {
				new GsonBuilder().setPrettyPrinting().create().toJson(obj, writer);
			}
		} catch (IOException e) {
			VMRBooklet.LOGGER.warn("Failed to save local booklet progress.", e);
		}
	}
}
