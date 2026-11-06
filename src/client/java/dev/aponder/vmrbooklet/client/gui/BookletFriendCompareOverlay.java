package dev.aponder.vmrbooklet.client.gui;

import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.client.commands.BookletTradeFinder;
import dev.aponder.vmrbooklet.client.sync.BookletFriends;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The booklet screen's "F" overlay - a live version of {@code /vbooklet
 * trades}' own {@link BookletTradeFinder#findHaveIt()}, so "who already has
 * what I'm missing" is something you can leave open and watch update as you
 * pick things up, rather than only a one-shot clipboard dump.
 */
final class BookletFriendCompareOverlay {

	private static final int ROW_HEIGHT = 20;

	record Row(int y, int height, String itemId) {
	}

	private BookletFriendCompareOverlay() {
	}

	static void draw(GuiGraphicsExtractor context, Font font, int x, int y, int w, int h,
			List<Map.Entry<BookletEntry, List<String>>> entries, BookletUi.ScrollState scroll, List<Row> rows) {
		BookletUi.drawOverlayFrame(context, font, x, y, w, h, Component.translatable("vmrbooklet.overlay.friends.title"));
		rows.clear();

		Component closeHint = Component.translatable("vmrbooklet.overlay.friends.footer_empty");
		if (BookletFriends.names().isEmpty()) {
			BookletUi.drawOverlayMessage(context, font, x, y, w, Component.translatable("vmrbooklet.overlay.friends.none_imported"), ChatFormatting.GRAY);
			BookletUi.drawOverlayFooter(context, font, x, y, w, h, closeHint);
			return;
		}
		if (entries.isEmpty()) {
			BookletUi.drawOverlayMessage(context, font, x, y, w, Component.translatable("vmrbooklet.overlay.friends.nothing"), ChatFormatting.GRAY);
			BookletUi.drawOverlayFooter(context, font, x, y, w, h, closeHint);
			return;
		}

		int listTop = y + BookletUi.OVERLAY_LIST_TOP;
		int listBottom = y + h - BookletUi.OVERLAY_LIST_BOTTOM_INSET;
		scroll.clampTo(entries.size(), (listBottom - listTop) / ROW_HEIGHT);

		context.enableScissor(x + 1, listTop, x + w - 1, listBottom);
		int firstRow = scroll.firstRow();
		for (int i = 0; firstRow + i < entries.size() && listTop + i * ROW_HEIGHT < listBottom; i++) {
			Map.Entry<BookletEntry, List<String>> row = entries.get(firstRow + i);
			BookletEntry entry = row.getKey();
			int rowY = listTop + i * ROW_HEIGHT;

			context.item(new ItemStack(entry.item(), 1), x + 8, rowY + 1);
			int textRoom = w - 28 - 8;
			context.text(font, Component.literal(BookletUi.ellipsize(font, entry.displayName().replace('_', ' '), textRoom)),
					x + 28, rowY + 2, BookletUi.COLOR_TEXT);
			String who = String.join(", ", row.getValue());
			context.text(font, Component.literal(BookletUi.ellipsize(font, who, textRoom)).withStyle(ChatFormatting.GRAY),
					x + 28, rowY + 11, BookletUi.COLOR_TEXT_DIM);

			rows.add(new Row(rowY, ROW_HEIGHT, entry.itemId()));
		}
		context.disableScissor();

		BookletUi.drawOverlayFooter(context, font, x, y, w, h, Component.translatable("vmrbooklet.overlay.friends.footer"));
	}
}
