package dev.aponder.vmrbooklet.client.commands;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.client.data.BookletNotes;
import java.util.Arrays;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * {@code /vbooklet note <item> [text | here | clear]}. The item and the rest
 * share one free-text argument (item names have spaces), so the longest
 * leading run of words that names a booklet item is the item, and whatever
 * follows is the note.
 */
public final class BookletNoteCommand {

	private BookletNoteCommand() {
	}

	public static int run(FabricClientCommandSource source, String args) {
		String[] words = args.trim().split("\\s+");
		BookletEntry entry = null;
		int itemWords = 0;
		for (int n = words.length; n >= 1 && entry == null; n--) {
			entry = BookletData.byQuery(String.join(" ", Arrays.copyOfRange(words, 0, n)));
			itemWords = n;
		}
		if (entry == null) {
			source.sendError(Component.translatable("vmrbooklet.cmd.no_match", args.trim()));
			return 0;
		}

		String name = entry.displayName().replace('_', ' ');
		String rest = String.join(" ", Arrays.copyOfRange(words, itemWords, words.length)).trim();

		if (rest.isEmpty()) {
			return show(source, entry, name);
		}
		if (rest.equalsIgnoreCase("clear")) {
			boolean had = BookletNotes.clear(entry.itemId());
			source.sendFeedback(Component.translatable(had ? "vmrbooklet.cmd.note.cleared" : "vmrbooklet.cmd.note.none", name)
					.withStyle(had ? ChatFormatting.GREEN : ChatFormatting.GRAY));
			return 1;
		}
		if (rest.equalsIgnoreCase("here")) {
			BlockPos pos = source.getPlayer().blockPosition();
			String dimension = source.getLevel().dimension().identifier().toString();
			BookletNotes.setWaypoint(entry.itemId(), new BookletNotes.Waypoint(pos.getX(), pos.getY(), pos.getZ(), dimension));
			source.sendFeedback(Component.translatable("vmrbooklet.cmd.note.waypoint_saved", name,
					formatWaypoint(BookletNotes.get(entry.itemId()).waypoint())).withStyle(ChatFormatting.GREEN));
			return 1;
		}
		BookletNotes.setText(entry.itemId(), rest);
		source.sendFeedback(Component.translatable("vmrbooklet.cmd.note.saved", name).withStyle(ChatFormatting.GREEN));
		return 1;
	}

	private static int show(FabricClientCommandSource source, BookletEntry entry, String name) {
		BookletNotes.Note note = BookletNotes.get(entry.itemId());
		if (note == null) {
			source.sendFeedback(Component.translatable("vmrbooklet.cmd.note.none", name).withStyle(ChatFormatting.GRAY));
			return 1;
		}
		if (!note.text().isEmpty()) {
			source.sendFeedback(Component.translatable("vmrbooklet.cmd.note.show_text", name, note.text()).withStyle(ChatFormatting.GOLD));
		}
		if (note.waypoint() != null) {
			source.sendFeedback(Component.translatable("vmrbooklet.cmd.note.show_waypoint", name, formatWaypoint(note.waypoint()))
					.withStyle(ChatFormatting.GOLD));
		}
		return 1;
	}

	/** "120, 64, -340 (overworld)" - the vanilla namespace is dropped for brevity. */
	public static String formatWaypoint(BookletNotes.Waypoint waypoint) {
		String dimension = waypoint.dimension().startsWith("minecraft:") ? waypoint.dimension().substring("minecraft:".length()) : waypoint.dimension();
		return waypoint.x() + ", " + waypoint.y() + ", " + waypoint.z() + (dimension.isEmpty() ? "" : " (" + dimension + ")");
	}
}
