package dev.aponder.vmrbooklet.client.commands;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.BookletSort;
import dev.aponder.vmrbooklet.client.data.BookletAcquisitionNotes;
import dev.aponder.vmrbooklet.client.data.BookletCategories;
import dev.aponder.vmrbooklet.client.data.BookletLocalProgress;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * {@code /vbooklet missing}: copies a plain newline list of everything you
 * haven't obtained yet to the clipboard - meant for pasting into a notes app
 * or a Discord message when planning a farming/trading trip, not for
 * restoring progress (that's the share/backup codes). {@code /vbooklet
 * missing notes} does the same but appends each item's acquisition note (if
 * it has one), turning the list from "what's left" into an actual to-do list
 * with instructions, not just names. {@code /vbooklet missing bycategory}
 * groups the same list under a header line per {@link BookletCategories.Category}
 * instead of plain ECC order - handier for "what do I still need from the
 * Nether" style planning than one flat list.
 */
public final class BookletMissingExport {

	private BookletMissingExport() {
	}

	public static int run(FabricClientCommandSource source) {
		return run(source, false);
	}

	public static int runWithNotes(FabricClientCommandSource source) {
		return run(source, true);
	}

	private static int run(FabricClientCommandSource source, boolean includeNotes) {
		StringBuilder list = new StringBuilder();
		int count = 0;
		for (BookletEntry entry : BookletData.orderedBy(BookletSort.ECC)) {
			int obtained = BookletLocalProgress.obtained(entry.itemId());
			if (obtained >= entry.required()) {
				continue;
			}
			list.append(entry.displayName().replace('_', ' '))
					.append(" (").append(obtained).append('/').append(entry.required()).append(')');
			if (includeNotes) {
				String note = BookletAcquisitionNotes.forItem(entry.itemId());
				if (note != null) {
					list.append("\n  -> ").append(note);
				}
			}
			list.append('\n');
			count++;
		}

		if (count == 0) {
			source.sendFeedback(Component.translatable("vmrbooklet.cmd.missing.none")
					.withStyle(ChatFormatting.GREEN));
			return 1;
		}

		BookletClipboardExport.copyAndNotify(source, list.toString(),
				Component.translatable(includeNotes ? "vmrbooklet.cmd.missing.copied_notes" : "vmrbooklet.cmd.missing.copied", count));
		return 1;
	}

	public static int runByCategory(FabricClientCommandSource source) {
		Map<BookletCategories.Category, List<BookletEntry>> byCategory = new EnumMap<>(BookletCategories.Category.class);
		int count = 0;
		for (BookletEntry entry : BookletData.orderedBy(BookletSort.ECC)) {
			if (BookletLocalProgress.obtained(entry.itemId()) >= entry.required()) {
				continue;
			}
			byCategory.computeIfAbsent(BookletCategories.categoryOf(entry.item()), k -> new ArrayList<>()).add(entry);
			count++;
		}

		if (count == 0) {
			source.sendFeedback(Component.translatable("vmrbooklet.cmd.missing.none")
					.withStyle(ChatFormatting.GREEN));
			return 1;
		}

		StringBuilder list = new StringBuilder();
		for (BookletCategories.Category category : BookletCategories.Category.values()) {
			List<BookletEntry> entries = byCategory.get(category);
			if (entries == null || entries.isEmpty()) {
				continue;
			}
			list.append("== ").append(category.label()).append(" ==\n");
			for (BookletEntry entry : entries) {
				int obtained = BookletLocalProgress.obtained(entry.itemId());
				list.append(entry.displayName().replace('_', ' '))
						.append(" (").append(obtained).append('/').append(entry.required()).append(")\n");
			}
		}

		BookletClipboardExport.copyAndNotify(source, list.toString(),
				Component.translatable("vmrbooklet.cmd.missing.copied_category", count));
		return 1;
	}
}
