package dev.aponder.vmrbooklet.client;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.VMRBooklet;
import dev.aponder.vmrbooklet.client.gui.BookletScreen;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code /export booklet}: packs your own progress into a {@link BookletShareCode},
 * uploads it to mclo.gs ({@link BookletPasteService}) and copies the short id
 * back to you - a friend runs {@code /import <id>} to pull it back down and
 * add it as a read-only tab via {@link BookletFriends}. No account, no server
 * of our own; if mclo.gs can't be reached, falls back to copying the full
 * (longer) code instead so the feature still works, just less conveniently.
 */
public final class BookletShareImport {

	private BookletShareImport() {
	}

	public static int generateCode(FabricClientCommandSource source) {
		String name = source.getPlayer().getName().getString();
		String code = BookletShareCode.encode(BookletShareCode.Type.SHARE, name, BookletLocalProgress.all());
		uploadThenShare(source, code, "share code", "/import");
		return 1;
	}

	/**
	 * {@code /vbooklet backup}: same codec as a friend share code, but for your
	 * OWN progress - a way to move {@code progress.json} between computers or
	 * keep an offline copy, with no Google Sheet involved at all.
	 */
	public static int generateBackup(FabricClientCommandSource source) {
		String name = source.getPlayer().getName().getString();
		String code = BookletShareCode.encode(BookletShareCode.Type.BACKUP, name, BookletLocalProgress.all());
		uploadThenShare(source, code, "backup code", "/vbooklet restore");
		return 1;
	}

	private static void uploadThenShare(FabricClientCommandSource source, String code, String label, String importCommand) {
		var client = source.getClient();
		source.sendFeedback(Component.literal("Uploading your " + label + "...").withStyle(ChatFormatting.GRAY));

		Thread.ofVirtual().start(() -> {
			try {
				String id = BookletPasteService.upload(code);
				client.execute(() -> copyToClipboard(source, id,
						"Copied your " + label + " (" + id + ") to the clipboard! Send that short code to a friend - "
								+ "they run " + importCommand + " " + id + "."));
			} catch (Exception e) {
				VMRBooklet.LOGGER.warn("mclo.gs upload failed, falling back to the full code", e);
				client.execute(() -> copyToClipboard(source, code,
						"Couldn't reach mclo.gs, so here's the full " + label + " instead (copied to clipboard). "
								+ "Send it to a friend, then they run " + importCommand + " with no arguments after copying it themselves."));
			}
		});
	}

	/**
	 * Clamps every decoded value to [0, that item's required()], same as
	 * {@code BookletUpload.apply()} already does for sheet imports - a decoded
	 * share/backup code (especially a hand-crafted or legacy v1 one, which only
	 * checked {@code value > 0}) could otherwise claim more of an item than it
	 * takes to achieve it, skewing the GUI's "x/required" display and the
	 * percentage/milestone math built on top of it. Unknown item ids are kept
	 * as-is (nothing to clamp against) rather than dropped.
	 */
	private static Map<String, Integer> clampToRequired(Map<String, Integer> progress) {
		Map<String, Integer> clamped = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : progress.entrySet()) {
			BookletEntry bookletEntry = BookletData.byItemId(entry.getKey());
			int value = entry.getValue();
			if (bookletEntry != null) {
				value = bookletEntry.clamp(value);
			}
			clamped.put(entry.getKey(), value);
		}
		return clamped;
	}

	private static void copyToClipboard(FabricClientCommandSource source, String text, String message) {
		// Minecraft's own clipboard (GLFW-backed, via KeyboardHandler) - not
		// java.awt.Toolkit, which throws in this game's JVM (AWT runs headless).
		Minecraft.getInstance().keyboardHandler.setClipboard(text);
		source.sendFeedback(Component.literal(message).withStyle(ChatFormatting.GREEN));
	}

	/**
	 * Codes (and now ids) are easy to mistype/mis-paste; this one spot decides
	 * what a piece of text actually is and routes it accordingly, used by every
	 * entry point below (clipboard, typed argument, /upload's legacy alias).
	 */
	private static int resolveAndApply(FabricClientCommandSource source, String text, BookletShareCode.Type expected) {
		if (BookletShareCode.looksLikeCode(text)) {
			return applyDecoded(source, text, expected);
		}
		if (BookletPasteService.looksLikePasteId(text)) {
			return fetchThenApply(source, text, expected);
		}
		return -1; // not recognized as either - caller decides what to do next
	}

	private static int applyDecoded(FabricClientCommandSource source, String code, BookletShareCode.Type expected) {
		return expected == BookletShareCode.Type.SHARE ? importCode(source, code) : restoreBackup(source, code);
	}

	private static int fetchThenApply(FabricClientCommandSource source, String idOrUrl, BookletShareCode.Type expected) {
		var client = source.getClient();
		source.sendFeedback(Component.literal("Fetching that code from mclo.gs...").withStyle(ChatFormatting.GRAY));

		Thread.ofVirtual().start(() -> {
			try {
				String fetched = BookletPasteService.fetch(idOrUrl);
				client.execute(() -> applyDecoded(source, fetched, expected));
			} catch (Exception e) {
				client.execute(() -> source.sendError(Component.literal("Failed to fetch that code: " + e.getMessage())));
			}
		});
		return 1;
	}

	public static int importCode(FabricClientCommandSource source, String code) {
		try {
			BookletShareCode.Decoded decoded = BookletShareCode.decodeExpecting(code, BookletShareCode.Type.SHARE);
			String label = decoded.exporterName().isBlank() ? "Friend" : decoded.exporterName();
			BookletFriends.put(label, clampToRequired(decoded.progress()));
			// Every other progress-mutating path (BookletUpload, the live scanner,
			// /sell, /vbooklet undo) notifies the open screen so its filtered list
			// re-runs against the new data - this one was missing it, so a friend
			// tab re-imported while that tab was already open could leave an
			// Achieved/In-progress filter showing stale list membership.
			if (BookletScreen.current() != null) {
				BookletScreen.current().onProgressUpdated();
			}
			source.sendFeedback(Component.literal(
					"Imported " + label + "'s progress - switch to it with the booklet's Player button.")
					.withStyle(ChatFormatting.GREEN));
			return 1;
		} catch (Exception e) {
			source.sendError(Component.literal("Failed to import that code: " + e.getMessage()));
			return 0;
		}
	}

	public static int restoreBackup(FabricClientCommandSource source, String code) {
		try {
			BookletShareCode.Decoded decoded = BookletShareCode.decodeExpecting(code, BookletShareCode.Type.BACKUP);
			BookletLocalProgress.replaceAll(clampToRequired(decoded.progress()));
			if (BookletScreen.current() != null) {
				BookletScreen.current().onProgressUpdated();
			}
			source.sendFeedback(Component.literal(
					"Restored " + decoded.progress().size() + " tracked item(s) from that backup. "
							+ "(Run /vbooklet undo to revert if that's not what you meant to restore.)")
					.withStyle(ChatFormatting.GREEN));
			return 1;
		} catch (Exception e) {
			source.sendError(Component.literal("Failed to restore that backup code: " + e.getMessage()));
			return 0;
		}
	}

	/** {@code /import <id-or-code-or-sheet-link>} - preserves the old "paste a sheet link here too" alias. */
	public static int importFromArgument(FabricClientCommandSource source, String text) {
		int result = resolveAndApply(source, text, BookletShareCode.Type.SHARE);
		if (result >= 0) {
			return result;
		}
		return BookletUpload.run(source, text);
	}

	public static int restoreFromArgument(FabricClientCommandSource source, String text) {
		int result = resolveAndApply(source, text, BookletShareCode.Type.BACKUP);
		if (result >= 0) {
			return result;
		}
		source.sendError(Component.literal("That doesn't look like a backup code or an mclo.gs id/link."));
		return 0;
	}

	/**
	 * Codes are long enough that pasting one into Minecraft's chat box can hit
	 * its own input length limit before it even reaches the command. Reading the
	 * clipboard directly (what {@code /import}/{@code /vbooklet restore} do with
	 * no argument) skips the chat box entirely, so that limit never applies -
	 * and now it also accepts a short mclo.gs id copied the same way.
	 */
	private static String readClipboardOrNull() {
		String text = Minecraft.getInstance().keyboardHandler.getClipboard();
		return text != null && !text.isBlank() ? text : null;
	}

	public static int importFromClipboard(FabricClientCommandSource source) {
		String text = readClipboardOrNull();
		if (text == null) {
			source.sendError(Component.literal(
					"Your clipboard doesn't have anything in it - copy a friend's code first (they run /export booklet), "
							+ "or run /import <code> directly."));
			return 0;
		}
		int result = resolveAndApply(source, text, BookletShareCode.Type.SHARE);
		if (result >= 0) {
			return result;
		}
		source.sendError(Component.literal("Your clipboard doesn't contain a booklet code or mclo.gs id."));
		return 0;
	}

	public static int restoreFromClipboard(FabricClientCommandSource source) {
		String text = readClipboardOrNull();
		if (text == null) {
			source.sendError(Component.literal(
					"Your clipboard doesn't have anything in it - copy a backup code first (/vbooklet backup), "
							+ "or run /vbooklet restore <code> directly."));
			return 0;
		}
		int result = resolveAndApply(source, text, BookletShareCode.Type.BACKUP);
		if (result >= 0) {
			return result;
		}
		source.sendError(Component.literal("Your clipboard doesn't contain a backup code or mclo.gs id."));
		return 0;
	}
}
