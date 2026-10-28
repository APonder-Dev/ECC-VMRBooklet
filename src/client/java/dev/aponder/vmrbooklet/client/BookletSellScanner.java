package dev.aponder.vmrbooklet.client;

import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.client.gui.BookletScreen;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the real server's own "/sell" feedback chat line and folds the sold
 * amount into local progress - additively (a sell tells you how many you just
 * sold, not an absolute total, unlike /booklet's own "Amount: N" readout) and
 * clamped to each item's required count.
 *
 * <p>Only counts while {@link BookletServerTracker#isInVmr()} says we're on
 * the VMR backend server - /sell is presumably usable on every backend on
 * this network, but VMR-booklet progress should only track VMR activity.
 */
public final class BookletSellScanner {

	// "Sold for $9 (12 diorite at $0 each)."
	private static final Pattern SELL_LINE = Pattern.compile(
			"Sold for \\$[\\d,.]+\\s*\\(([\\d,]+) ([a-zA-Z_ ]+?) at \\$[\\d,.]+ each\\)\\.");
	private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId();

	private BookletSellScanner() {
	}

	public static void register() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay) {
				return;
			}
			onMessage(message.getString());
		});
	}

	private static void onMessage(String text) {
		if (!BookletServerTracker.isInVmr()) {
			return;
		}

		Matcher matcher = SELL_LINE.matcher(text);
		if (!matcher.find()) {
			return;
		}

		int sold;
		try {
			sold = Integer.parseInt(matcher.group(1).replace(",", ""));
		} catch (NumberFormatException e) {
			return;
		}

		// The plugin prints plain English names ("oak log"), not item IDs - the
		// booklet dataset indexes display names with underscores, same as every
		// other lowercase-name lookup in this mod (see BookletData.byItemId).
		String itemName = matcher.group(2).trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
		BookletEntry entry = BookletData.byItemId(itemName);
		if (entry == null) {
			return; // unrecognized/renamed item on the plugin's side - nothing to track
		}

		int before = BookletLocalProgress.obtained(entry.itemId());
		int after = Math.min(entry.required(), before + sold);
		if (after == before) {
			return;
		}

		Map<String, Integer> change = new HashMap<>();
		change.put(entry.itemId(), after);
		boolean changed = BookletLocalProgress.mergeIfDifferent(change);
		if (!changed) {
			return;
		}

		if (BookletScreen.current() != null) {
			BookletScreen.current().onProgressUpdated();
		}
		notify(entry, before, after, sold);
		BookletAutoPush.scheduleFromRemembered();
	}

	private static void notify(BookletEntry entry, int before, int after, int sold) {
		if (!BookletSettings.scanNotifications()) {
			return;
		}

		String name = entry.displayName().replace('_', ' ');
		String mark = after >= entry.required() && before < entry.required() ? " ✓" : "";
		String body = "+" + (after - before) + " " + name + " (" + after + "/" + entry.required() + ")" + mark
				+ (sold > after - before ? " - capped, already near complete" : "");

		Minecraft client = Minecraft.getInstance();
		client.gui.toastManager().addToast(new SystemToast(
				TOAST_ID,
				Component.literal("VMR Booklet"),
				Component.literal(body)
		));
	}
}
