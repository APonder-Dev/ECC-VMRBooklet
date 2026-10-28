package dev.aponder.vmrbooklet.client;

import dev.aponder.vmrbooklet.VMRBooklet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Debounced write-back: whenever the live scanner changes local progress, this
 * waits a few seconds for things to settle (so paging through many pages of the
 * real booklet doesn't fire a Sheets write per page) before pushing everything
 * collected in one batch. Entirely opt-in - a no-op unless the player has their
 * own {@link GoogleServiceAccount} credential configured.
 */
public final class BookletAutoPush {

	private static final Pattern ID_PATTERN = Pattern.compile("/d/([a-zA-Z0-9-_]+)");
	private static final Pattern GID_PATTERN = Pattern.compile("[?&#]gid=(\\d+)");
	private static final long DEBOUNCE_SECONDS = 5;
	private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId();

	private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread thread = new Thread(r, "vmrbooklet-autopush");
		thread.setDaemon(true);
		return thread;
	});

	private static ScheduledFuture<?> pending;

	private BookletAutoPush() {
	}

	/** Called by the live scanner after a change. No-op if write-back isn't set up for this player. */
	public static synchronized void scheduleFromRemembered() {
		if (!GoogleServiceAccount.isConfigured()) {
			return;
		}

		String link = BookletSettings.lastSheetLink();
		if (link == null || link.isBlank()) {
			return;
		}

		Matcher idMatcher = ID_PATTERN.matcher(link);
		if (!idMatcher.find()) {
			return;
		}
		String sheetId = idMatcher.group(1);
		Matcher gidMatcher = GID_PATTERN.matcher(link);
		String gid = gidMatcher.find() ? gidMatcher.group(1) : null;

		if (pending != null) {
			pending.cancel(false);
		}
		pending = EXECUTOR.schedule(() -> push(sheetId, gid), DEBOUNCE_SECONDS, TimeUnit.SECONDS);
	}

	private static void push(String sheetId, String gid) {
		try {
			int pushed = BookletSheetWriter.push(sheetId, gid);
			if (pushed > 0) {
				notify("Wrote " + pushed + " value(s) back to your sheet.");
			}
		} catch (Exception e) {
			VMRBooklet.LOGGER.warn("Auto push-back to sheet failed", e);
			notify("Failed to auto-sync to your sheet: " + e.getMessage());
		}
	}

	private static void notify(String message) {
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> client.gui.toastManager().addToast(new SystemToast(
				TOAST_ID,
				Component.literal("VMR Booklet"),
				Component.literal(message)
		)));
	}
}
