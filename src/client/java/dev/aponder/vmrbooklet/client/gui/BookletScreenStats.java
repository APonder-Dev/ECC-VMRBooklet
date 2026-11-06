package dev.aponder.vmrbooklet.client.gui;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.BookletSort;
import dev.aponder.vmrbooklet.client.data.BookletActivityLog;
import dev.aponder.vmrbooklet.client.data.BookletLocalProgress;
import dev.aponder.vmrbooklet.client.sync.BookletFriends;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pure data computation behind the booklet screen's header and %/T overlays -
 * grouping and sorting with no rendering or screen state of its own. Results
 * are cached per screen by {@link BookletScreenData}. The streak and
 * completed-count helpers are public for the progress card and toasts.
 */
public final class BookletScreenStats {

	record ReleaseStat(String release, int completed, int total) {
	}

	record LeaderboardRow(String label, float fraction, boolean isYou) {
	}

	record DayStat(LocalDate date, int count) {
	}

	/**
	 * Your recent completion rate. {@code daysToFinish} is 0 when the booklet is
	 * already complete and -1 when there's no recent activity to extrapolate from.
	 */
	record Pace(double perDay, int remaining, int daysToFinish) {
	}

	static final int PACE_WINDOW_DAYS = 7;

	private BookletScreenStats() {
	}

	/** Groups every item by its {@code release} field and tallies completion for the given player (null = you). */
	static List<ReleaseStat> computeReleaseStats(String viewingPlayer) {
		// TreeMap, not HashMap - release strings are "NNN - Name" (e.g. "031 -
		// 1.8"), so sorting the keys lexically already sorts them chronologically,
		// with no need to parse/compare anything ourselves.
		TreeMap<String, int[]> byRelease = new TreeMap<>();
		for (BookletEntry entry : BookletData.orderedBy(BookletSort.ECC)) {
			int[] counts = byRelease.computeIfAbsent(entry.release(), k -> new int[2]);
			counts[1]++;
			if (obtainedFor(entry, viewingPlayer) >= entry.required()) {
				counts[0]++;
			}
		}

		List<ReleaseStat> stats = new ArrayList<>(byRelease.size());
		for (var entry : byRelease.entrySet()) {
			stats.add(new ReleaseStat(entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
		}
		return stats;
	}

	/** You first, then every imported friend, sorted by completion % descending. */
	static List<LeaderboardRow> computeLeaderboard() {
		List<LeaderboardRow> rows = new ArrayList<>();
		rows.add(new LeaderboardRow("You", completionFraction(null), true));
		for (String name : BookletFriends.names()) {
			rows.add(new LeaderboardRow(name, completionFraction(name), false));
		}
		rows.sort((a, b) -> Float.compare(b.fraction(), a.fraction()));
		return rows;
	}

	static float completionFraction(String player) {
		int total = BookletData.size();
		return total == 0 ? 0f : completedCount(player) / (float) total;
	}

	/** How many items the given player (null = you) has fully completed. */
	public static int completedCount(String player) {
		int done = 0;
		for (BookletEntry entry : BookletData.orderedBy(BookletSort.ECC)) {
			if (obtainedFor(entry, player) >= entry.required()) {
				done++;
			}
		}
		return done;
	}

	private static int obtainedFor(BookletEntry entry, String player) {
		if (player == null) {
			return BookletLocalProgress.obtained(entry.itemId());
		}
		return BookletFriends.progressOf(player).getOrDefault(entry.itemId(), 0);
	}

	/** Groups the whole retained activity log by local calendar day, newest first. */
	static List<DayStat> computeDailyStats() {
		Map<LocalDate, Integer> byDay = new TreeMap<>(Comparator.reverseOrder());
		for (BookletActivityLog.Entry entry : BookletActivityLog.recent()) {
			LocalDate day = Instant.ofEpochMilli(entry.timestampMillis())
					.atZone(ZoneId.systemDefault()).toLocalDate();
			byDay.merge(day, 1, Integer::sum);
		}
		List<DayStat> stats = new ArrayList<>(byDay.size());
		for (var dayEntry : byDay.entrySet()) {
			stats.add(new DayStat(dayEntry.getKey(), dayEntry.getValue()));
		}
		return stats;
	}

	/** Items completed over the last {@link #PACE_WINDOW_DAYS} days, extrapolated over what's left. */
	static Pace computePace() {
		int remaining = Math.max(0, BookletData.size() - completedCount(null));
		long cutoff = System.currentTimeMillis() - PACE_WINDOW_DAYS * 24L * 60 * 60 * 1000;
		int recentCount = 0;
		for (BookletActivityLog.Entry entry : BookletActivityLog.recent()) {
			if (entry.timestampMillis() >= cutoff) {
				recentCount++;
			}
		}
		return pace(recentCount, remaining);
	}

	static Pace pace(int completedInWindow, int remaining) {
		double perDay = completedInWindow / (double) PACE_WINDOW_DAYS;
		int days;
		if (remaining == 0) {
			days = 0;
		} else if (perDay <= 0) {
			days = -1;
		} else {
			days = (int) Math.ceil(remaining / perDay);
		}
		return new Pace(perDay, remaining, days);
	}

	/**
	 * How many consecutive calendar days (ending today, or yesterday if
	 * nothing's been completed yet today) have at least one completion -
	 * entirely derived from {@link BookletActivityLog}'s own retained history,
	 * no separate persisted counter needed. 0 once a day is skipped.
	 */
	public static int computeStreakDays() {
		Set<LocalDate> days = new HashSet<>();
		for (BookletActivityLog.Entry entry : BookletActivityLog.recent()) {
			days.add(Instant.ofEpochMilli(entry.timestampMillis()).atZone(ZoneId.systemDefault()).toLocalDate());
		}
		if (days.isEmpty()) {
			return 0;
		}

		LocalDate cursor = LocalDate.now();
		if (!days.contains(cursor)) {
			// Nothing completed yet today doesn't break a streak that's still
			// "active" as of yesterday - only an actually-skipped day does.
			cursor = cursor.minusDays(1);
		}

		int streak = 0;
		while (days.contains(cursor)) {
			streak++;
			cursor = cursor.minusDays(1);
		}
		return streak;
	}
}
