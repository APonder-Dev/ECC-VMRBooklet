package dev.aponder.vmrbooklet.client.gui;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.BookletSort;
import dev.aponder.vmrbooklet.client.data.BookletLocalProgress;
import dev.aponder.vmrbooklet.client.data.BookletSettings;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * Toasts for "you just finished an item" and whole-booklet completion
 * milestones (25/50/75/100%), fired from {@link BookletLocalProgress}
 * whenever a progress change pushes something over its required amount -
 * regardless of whether that came from /upload, the live scanner, /sell, a
 * manual edit, or a restore, since they all funnel through the same place.
 */
public final class BookletCelebrations {

	private static final SystemToast.SystemToastId ACHIEVEMENT_TOAST_ID = new SystemToast.SystemToastId();
	private static final SystemToast.SystemToastId MILESTONE_TOAST_ID = new SystemToast.SystemToastId();
	private static final SystemToast.SystemToastId SESSION_TOAST_ID = new SystemToast.SystemToastId();
	// Session count at the last summary toast, so closing the booklet twice (or
	// closing it, then leaving the server) without finishing anything new in
	// between doesn't repeat the same toast.
	private static int lastSummarizedCount = 0;
	private static final int[] MILESTONES = {25, 50, 75, 100};

	private BookletCelebrations() {
	}

	public static void notifyAchievements(List<String> justAchievedItemIds) {
		if (justAchievedItemIds.isEmpty() || !BookletSettings.scanNotifications()) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		Component body;
		if (justAchievedItemIds.size() == 1) {
			BookletEntry entry = BookletData.byItemId(justAchievedItemIds.get(0));
			String name = entry != null ? entry.displayName().replace('_', ' ') : justAchievedItemIds.get(0);
			body = Component.translatable("vmrbooklet.toast.completed_one", name);
		} else {
			body = Component.translatable("vmrbooklet.toast.completed_many", justAchievedItemIds.size());
		}
		client.gui.toastManager().addToast(new SystemToast(
				ACHIEVEMENT_TOAST_ID, Component.translatable("vmrbooklet.title"), body));
	}

	/**
	 * "+12 items this session, 5-day streak" - shown when the booklet closes and
	 * when you leave a server, only if something new was completed since the
	 * last one. Render thread only.
	 */
	public static void notifySessionSummary() {
		int count = BookletLocalProgress.sessionObtainedCount();
		if (!BookletSettings.sessionSummary() || count == 0 || count == lastSummarizedCount) {
			return;
		}
		lastSummarizedCount = count;
		int streak = BookletScreenStats.computeStreakDays();
		Component body = streak > 1
				? Component.translatable("vmrbooklet.toast.session_streak", count, streak)
				: Component.translatable("vmrbooklet.toast.session", count);
		Minecraft.getInstance().gui.toastManager().addToast(new SystemToast(
				SESSION_TOAST_ID, Component.translatable("vmrbooklet.toast.session_title"), body));
	}

	/**
	 * Separately from per-item toasts: checks whether this progress change just
	 * pushed overall completion across a 25/50/75/100% line, and toasts (once
	 * ever, via {@link BookletSettings#markMilestoneSeen}) the first one it
	 * crosses. {@code before} is the full progress snapshot from right before
	 * this change, same as what {@link BookletLocalProgress} already tracks for
	 * its own per-item before/after comparison.
	 */
	public static void notifyMilestones(Map<String, Integer> before) {
		if (!BookletSettings.scanNotifications()) {
			return;
		}

		List<BookletEntry> all = BookletData.orderedBy(BookletSort.ECC);
		int total = all.size();
		if (total == 0) {
			return;
		}

		int beforeCount = 0;
		int afterCount = 0;
		for (BookletEntry entry : all) {
			if (before.getOrDefault(entry.itemId(), 0) >= entry.required()) {
				beforeCount++;
			}
			if (BookletLocalProgress.obtained(entry.itemId()) >= entry.required()) {
				afterCount++;
			}
		}
		float beforeFraction = beforeCount / (float) total;
		float afterFraction = afterCount / (float) total;

		for (int milestone : MILESTONES) {
			float threshold = milestone / 100f;
			if (beforeFraction < threshold && afterFraction >= threshold && !BookletSettings.hasSeenMilestone(milestone)) {
				BookletSettings.markMilestoneSeen(milestone);
				Component body = milestone == 100
						? Component.translatable("vmrbooklet.toast.milestone_complete")
						: Component.translatable("vmrbooklet.toast.milestone", milestone);
				Minecraft.getInstance().gui.toastManager().addToast(new SystemToast(
						MILESTONE_TOAST_ID, Component.translatable("vmrbooklet.title"), body));
			}
		}
	}
}
