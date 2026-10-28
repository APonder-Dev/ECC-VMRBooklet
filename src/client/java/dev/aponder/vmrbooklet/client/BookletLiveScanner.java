package dev.aponder.vmrbooklet.client;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.client.gui.BookletScreen;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Passively reads the REAL server plugin's booklet container GUI as you browse
 * it: whenever a container screen titled "...Booklet..." is open, each tick we
 * read every visible slot's own item lore ("Amount: N") and fold it into local
 * progress. Just open the real /booklet there and flip through pages - nothing
 * to type, no command needed. Reports what changed via a toast, toggleable in
 * {@link BookletSettings}.
 */
public final class BookletLiveScanner {

	private static final Pattern AMOUNT = Pattern.compile("Amount:\\s*(\\d+)");
	private static final Pattern REMAINING = Pattern.compile("Remaining:\\s*(\\d+)");
	private static final Pattern ACHIEVED = Pattern.compile("Achieved:\\s*(Yes|No)");
	private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId();
	private static final SystemToast.SystemToastId UNKNOWN_TOAST_ID = new SystemToast.SystemToastId();
	private static final int MAX_LINES_LISTED = 4;

	private BookletLiveScanner() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
				return;
			}
			if (!looksLikeBooklet(containerScreen)) {
				return;
			}

			ScreenEvents.afterTick(screen).register(s -> scan((AbstractContainerScreen<?>) s));
		});
	}

	private static boolean looksLikeBooklet(AbstractContainerScreen<?> screen) {
		String title = screen.getTitle().getString();
		return title.toLowerCase(java.util.Locale.ROOT).contains("booklet");
	}

	private static void scan(AbstractContainerScreen<?> screen) {
		AbstractContainerMenu menu = ((MenuAccess<?>) screen).getMenu();
		Map<String, Integer> changes = new HashMap<>();
		List<String> summaryLines = new ArrayList<>();
		boolean foundNewUnknown = false;

		for (Slot slot : menu.slots) {
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) {
				continue;
			}

			ItemLore lore = stack.get(DataComponents.LORE);
			if (lore == null) {
				continue;
			}

			// Require the whole known tooltip shape (Amount + Remaining + Achieved
			// together), not just "Amount:" in isolation - an unrelated item (one of
			// the player's own, a different plugin's tooltip) could otherwise contain
			// that single word/number by coincidence and get misread as real progress.
			boolean hasRemaining = false;
			boolean hasAchieved = false;
			Integer scannedAmount = null;
			Integer scannedRemaining = null;
			for (Component line : lore.lines()) {
				String text = line.getString();
				if (scannedAmount == null) {
					Matcher amountMatcher = AMOUNT.matcher(text);
					if (amountMatcher.find()) {
						scannedAmount = Integer.parseInt(amountMatcher.group(1));
					}
				}
				if (!hasRemaining) {
					Matcher remainingMatcher = REMAINING.matcher(text);
					if (remainingMatcher.find()) {
						hasRemaining = true;
						scannedRemaining = Integer.parseInt(remainingMatcher.group(1));
					}
				}
				if (!hasAchieved && ACHIEVED.matcher(text).find()) {
					hasAchieved = true;
				}
			}

			if (scannedAmount == null || !hasRemaining || !hasAchieved) {
				continue;
			}

			BookletEntry entry = BookletData.byItem(stack.getItem());
			if (entry == null) {
				// Looks exactly like a real booklet item (same lore shape) but matches
				// nothing in our own items.json - logged instead of silently dropped,
				// so a player can page through the real /booklet once and get a report
				// of everything this mod doesn't know about yet, rather than needing to
				// screenshot every page by hand.
				String displayName = stack.getHoverName().getString();
				String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
				int required = scannedAmount + scannedRemaining;
				if (BookletUnknownItemLog.record(displayName, itemId, scannedAmount, required)) {
					foundNewUnknown = true;
				}
				continue;
			}

			int clamped = Math.max(0, Math.min(scannedAmount, entry.required()));
			int before = BookletLocalProgress.obtained(entry.itemId());
			if (clamped != before) {
				changes.put(entry.itemId(), clamped);
				summaryLines.add(describe(entry, before, clamped));
			}
		}

		if (foundNewUnknown && BookletSettings.scanNotifications()) {
			Minecraft.getInstance().gui.toastManager().addToast(new SystemToast(
					UNKNOWN_TOAST_ID,
					Component.literal("VMR Booklet"),
					Component.literal("Found an unrecognized booklet item - run /vbooklet unknown for details")
			));
		}

		if (changes.isEmpty()) {
			return;
		}

		boolean changed = BookletLocalProgress.mergeIfDifferent(changes);
		if (!changed) {
			return;
		}

		if (BookletScreen.current() != null) {
			BookletScreen.current().onProgressUpdated();
		}
		notify(summaryLines);
		BookletAutoPush.scheduleFromRemembered();
	}

	private static String describe(BookletEntry entry, int before, int after) {
		String name = entry.displayName().replace('_', ' ');
		String delta = after > before ? "+" + (after - before) : String.valueOf(after - before);
		String mark = after >= entry.required() && before < entry.required() ? " ✓" : "";
		return delta + " " + name + " (" + after + "/" + entry.required() + ")" + mark;
	}

	private static void notify(List<String> lines) {
		if (!BookletSettings.scanNotifications()) {
			return;
		}

		String body = lines.size() <= MAX_LINES_LISTED
				? String.join(", ", lines)
				: String.join(", ", lines.subList(0, MAX_LINES_LISTED)) + ", +" + (lines.size() - MAX_LINES_LISTED) + " more";

		Minecraft client = Minecraft.getInstance();
		client.gui.toastManager().addToast(new SystemToast(
				TOAST_ID,
				Component.literal("VMR Booklet"),
				Component.literal(body)
		));
	}
}
