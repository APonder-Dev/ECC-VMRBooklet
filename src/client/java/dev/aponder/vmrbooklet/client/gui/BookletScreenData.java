package dev.aponder.vmrbooklet.client.gui;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.client.commands.BookletNeededTree;
import dev.aponder.vmrbooklet.client.commands.BookletTradeFinder;
import dev.aponder.vmrbooklet.client.data.BookletActivityLog;
import dev.aponder.vmrbooklet.client.data.BookletGoals;
import dev.aponder.vmrbooklet.client.data.BookletLocalProgress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Everything {@link BookletScreen} and its overlays derive from the whole
 * booklet (counts, streak, stats, the needed tree, the friend comparison),
 * computed at most once per second instead of every frame. {@link #invalidate}
 * drops it all immediately whenever progress, friends or the active tab
 * change; the one-second age limit covers anything that slips past that.
 */
final class BookletScreenData {

	private static final long MAX_AGE_MILLIS = 1000L;

	record GoalProgress(int done, int total) {
	}

	private record Slot(Object key, Object value) {
	}

	private final Map<String, Slot> slots = new HashMap<>();
	private long computedAtMillis;

	void invalidate() {
		slots.clear();
	}

	int completedCount(String viewingPlayer) {
		return memo("completed", viewingPlayer, () -> BookletScreenStats.completedCount(viewingPlayer));
	}

	int streakDays() {
		return memo("streak", null, BookletScreenStats::computeStreakDays);
	}

	/** Always your own progress - a goal list is personal, whichever tab is active. */
	GoalProgress goalProgress() {
		return memo("goals", null, () -> {
			Set<String> goals = BookletGoals.all();
			int done = 0;
			for (String itemId : goals) {
				BookletEntry entry = BookletData.byItemId(itemId);
				int required = entry == null ? 1 : entry.required();
				if (BookletLocalProgress.obtained(itemId) >= required) {
					done++;
				}
			}
			return new GoalProgress(done, goals.size());
		});
	}

	List<BookletScreenStats.LeaderboardRow> leaderboard() {
		return memo("leaderboard", null, BookletScreenStats::computeLeaderboard);
	}

	List<BookletScreenStats.ReleaseStat> releaseStats(String viewingPlayer) {
		return memo("releases", viewingPlayer, () -> BookletScreenStats.computeReleaseStats(viewingPlayer));
	}

	BookletScreenStats.Pace pace() {
		return memo("pace", null, BookletScreenStats::computePace);
	}

	List<BookletScreenStats.DayStat> dailyStats() {
		return memo("daily", null, BookletScreenStats::computeDailyStats);
	}

	List<BookletActivityLog.Entry> recent() {
		return memo("recent", null, BookletActivityLog::recent);
	}

	/** Null when the item is already satisfied or has nothing to walk. */
	BookletNeededTree.Node neededTree(BookletEntry root) {
		if (root == null) {
			return null;
		}
		return memo("needed", root.itemId(), () -> BookletNeededTree.buildTree(root.itemId()));
	}

	List<Map.Entry<BookletEntry, List<String>>> friendHaveIt() {
		return memo("friends", null, () -> new ArrayList<>(BookletTradeFinder.findHaveIt().entrySet()));
	}

	@SuppressWarnings("unchecked")
	private <T> T memo(String name, Object key, Supplier<T> compute) {
		long now = System.currentTimeMillis();
		if (now - computedAtMillis > MAX_AGE_MILLIS) {
			slots.clear();
			computedAtMillis = now;
		}
		Slot slot = slots.get(name);
		if (slot == null || !Objects.equals(slot.key(), key)) {
			slot = new Slot(key, compute.get());
			slots.put(name, slot);
		}
		return (T) slot.value();
	}
}
