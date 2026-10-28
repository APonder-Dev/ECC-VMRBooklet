package dev.aponder.vmrbooklet.client;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.BookletSort;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;

/**
 * Toasts for "you just finished an item" and whole-booklet completion
 * milestones (25/50/75/100%), fired from {@link BookletLocalProgress}
 * whenever a progress change pushes something over its required amount -
 * regardless of whether that came from /upload, the live scanner, /sell, a
 * manual edit, or a restore, since they all funnel through the same place.
 */
final class BookletCelebrations {

	private static final SystemToast.SystemToastId ACHIEVEMENT_TOAST_ID = new SystemToast.SystemToastId();
	private static final SystemToast.SystemToastId MILESTONE_TOAST_ID = new SystemToast.SystemToastId();
	private static final int[] MILESTONES = {25, 50, 75, 100};

	private BookletCelebrations() {
	}

	static void notifyAchievements(List<String> justAchievedItemIds) {
		if (justAchievedItemIds.isEmpty() || !BookletSettings.scanNotifications()) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		String body;
		if (justAchievedItemIds.size() == 1) {
			BookletEntry entry = BookletData.byItemId(justAchievedItemIds.get(0));
			String name = entry != null ? entry.displayName().replace('_', ' ') : justAchievedItemIds.get(0);
			body = "✓ Completed: " + name;
		} else {
			body = "✓ Completed " + justAchievedItemIds.size() + " items!";
		}
		client.gui.toastManager().addToast(new SystemToast(
				ACHIEVEMENT_TOAST_ID, Component.literal("VMR Booklet"), Component.literal(body)));
	}

	/**
	 * Separately from per-item toasts: checks whether this progress change just
	 * pushed overall completion across a 25/50/75/100% line, and toasts (once
	 * ever, via {@link BookletSettings#markMilestoneSeen}) the first one it
	 * crosses. {@code before} is the full progress snapshot from right before
	 * this change, same as what {@link BookletLocalProgress} already tracks for
	 * its own per-item before/after comparison.
	 */
	static void notifyMilestones(Map<String, Integer> before) {
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
				String body = milestone == 100 ? "★ Booklet 100% complete! ★" : "🎉 Booklet " + milestone + "% complete!";
				Minecraft.getInstance().gui.toastManager().addToast(new SystemToast(
						MILESTONE_TOAST_ID, Component.literal("VMR Booklet"), Component.literal(body)));
			}
		}
	}
}
