package dev.aponder.vmrbooklet.client.sync;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.VMRBooklet;
import dev.aponder.vmrbooklet.client.commands.BookletClipboardExport;
import dev.aponder.vmrbooklet.client.data.BookletLocalProgress;
import dev.aponder.vmrbooklet.client.gui.BookletScreen;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

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

	/**
	 * Codes pack progress by position in the bundled list (4 bits per item), so
	 * they mean nothing - or the wrong items - while a custom item list is active.
	 */
	private static boolean blockedByCustomList(FabricClientCommandSource source) {
		if (!BookletData.isCustom()) {
			return false;
		}
		source.sendError(Component.translatable("vmrbooklet.share.custom_list"));
		return true;
	}

	public static int generateCode(FabricClientCommandSource source) {
		if (blockedByCustomList(source)) {
			return 0;
		}
		String name = source.getPlayer().getName().getString();
		String code = BookletShareCode.encode(BookletShareCode.Type.SHARE, name, BookletLocalProgress.all());
		uploadThenShare(source, code, Component.translatable("vmrbooklet.share.label_share"), "/import");
		return 1;
	}

	/**
	 * {@code /vbooklet backup}: same codec as a friend share code, but for your
	 * OWN progress - a way to move {@code progress.json} between computers or
	 * keep an offline copy, with no Google Sheet involved at all.
	 */
	public static int generateBackup(FabricClientCommandSource source) {
		if (blockedByCustomList(source)) {
			return 0;
		}
		String name = source.getPlayer().getName().getString();
		String code = BookletShareCode.encode(BookletShareCode.Type.BACKUP, name, BookletLocalProgress.all());
		uploadThenShare(source, code, Component.translatable("vmrbooklet.share.label_backup"), "/vbooklet restore");
		return 1;
	}

	private static void uploadThenShare(FabricClientCommandSource source, String code, Component label, String importCommand) {
		var client = source.getClient();
		source.sendFeedback(Component.translatable("vmrbooklet.share.uploading", label).withStyle(ChatFormatting.GRAY));

		Thread.ofVirtual().start(() -> {
			try {
				String id = BookletPasteService.upload(code);
				client.execute(() -> BookletClipboardExport.copyAndNotify(source, id,
						Component.translatable("vmrbooklet.share.copied", label, id, importCommand + " " + id)));
			} catch (Exception e) {
				VMRBooklet.LOGGER.warn("mclo.gs upload failed, falling back to the full code", e);
				client.execute(() -> BookletClipboardExport.copyAndNotify(source, code,
						Component.translatable("vmrbooklet.share.copied_full", label, importCommand)));
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
		source.sendFeedback(Component.translatable("vmrbooklet.share.fetching").withStyle(ChatFormatting.GRAY));

		Thread.ofVirtual().start(() -> {
			try {
				String fetched = BookletPasteService.fetch(idOrUrl);
				client.execute(() -> applyDecoded(source, fetched, expected));
			} catch (Exception e) {
				client.execute(() -> source.sendError(Component.translatable("vmrbooklet.share.fetch_failed", String.valueOf(e.getMessage()))));
			}
		});
		return 1;
	}

	public static int importCode(FabricClientCommandSource source, String code) {
		if (blockedByCustomList(source)) {
			return 0;
		}
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
			source.sendFeedback(Component.translatable("vmrbooklet.share.imported", label)
					.withStyle(ChatFormatting.GREEN));
			return 1;
		} catch (Exception e) {
			source.sendError(Component.translatable("vmrbooklet.share.import_failed", String.valueOf(e.getMessage())));
			return 0;
		}
	}

	public static int restoreBackup(FabricClientCommandSource source, String code) {
		if (blockedByCustomList(source)) {
			return 0;
		}
		try {
			BookletShareCode.Decoded decoded = BookletShareCode.decodeExpecting(code, BookletShareCode.Type.BACKUP);
			BookletLocalProgress.replaceAll(clampToRequired(decoded.progress()));
			if (BookletScreen.current() != null) {
				BookletScreen.current().onProgressUpdated();
			}
			source.sendFeedback(Component.translatable("vmrbooklet.share.restored", decoded.progress().size())
					.withStyle(ChatFormatting.GREEN));
			return 1;
		} catch (Exception e) {
			source.sendError(Component.translatable("vmrbooklet.share.restore_failed", String.valueOf(e.getMessage())));
			return 0;
		}
	}

	/** {@code /import <id-or-code-or-sheet-link>} - preserves the old "paste a sheet link here too" alias. */
	public static int importFromArgument(FabricClientCommandSource source, String text) {
		if (blockedByCustomList(source)) {
			return 0;
		}
		int result = resolveAndApply(source, text, BookletShareCode.Type.SHARE);
		if (result >= 0) {
			return result;
		}
		return BookletUpload.run(source, text);
	}

	public static int restoreFromArgument(FabricClientCommandSource source, String text) {
		if (blockedByCustomList(source)) {
			return 0;
		}
		int result = resolveAndApply(source, text, BookletShareCode.Type.BACKUP);
		if (result >= 0) {
			return result;
		}
		source.sendError(Component.translatable("vmrbooklet.share.not_backup"));
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
		if (blockedByCustomList(source)) {
			return 0;
		}
		String text = readClipboardOrNull();
		if (text == null) {
			source.sendError(Component.translatable("vmrbooklet.share.clipboard_empty_import"));
			return 0;
		}
		int result = resolveAndApply(source, text, BookletShareCode.Type.SHARE);
		if (result >= 0) {
			return result;
		}
		source.sendError(Component.translatable("vmrbooklet.share.clipboard_not_code"));
		return 0;
	}

	public static int restoreFromClipboard(FabricClientCommandSource source) {
		if (blockedByCustomList(source)) {
			return 0;
		}
		String text = readClipboardOrNull();
		if (text == null) {
			source.sendError(Component.translatable("vmrbooklet.share.clipboard_empty_restore"));
			return 0;
		}
		int result = resolveAndApply(source, text, BookletShareCode.Type.BACKUP);
		if (result >= 0) {
			return result;
		}
		source.sendError(Component.translatable("vmrbooklet.share.clipboard_not_backup"));
		return 0;
	}
}
