package dev.aponder.vmrbooklet.client.gui;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.client.data.BookletActivityLog;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The help/"?", stats/"%", recent/"R" and timeline/"T" overlays {@link
 * BookletScreen} can show over the list+detail area. Each draw call takes its
 * data precomputed (see {@link BookletScreenData}) and a {@link
 * BookletUi.ScrollState} it clamps to whatever it actually laid out.
 */
final class BookletOverlays {

	record RecentRowRect(int y, int height, String itemId) {
	}

	private BookletOverlays() {
	}

	/** Translation keys for the help overlay, one line each, in display order. */
	static final String[] HELP_LINE_KEYS = {
			"vmrbooklet.help.open",
			"vmrbooklet.help.upload_link",
			"vmrbooklet.help.upload",
			"vmrbooklet.help.export",
			"vmrbooklet.help.undo",
			"vmrbooklet.help.backup",
			"vmrbooklet.help.restore",
			"vmrbooklet.help.export_booklet",
			"vmrbooklet.help.import",
			"vmrbooklet.help.export_csv",
			"vmrbooklet.help.unknown",
			"vmrbooklet.help.missing",
			"vmrbooklet.help.missing_bycategory",
			"vmrbooklet.help.missing_notes",
			"vmrbooklet.help.trades",
			"vmrbooklet.help.need",
			"vmrbooklet.help.goal_item",
			"vmrbooklet.help.goal",
			"vmrbooklet.help.goal_lists",
			"vmrbooklet.help.goal_delete",
			"vmrbooklet.help.ready",
			"vmrbooklet.help.note",
			"vmrbooklet.help.card",
			"vmrbooklet.help.reload",
			"vmrbooklet.help.keys",
			"vmrbooklet.help.search_syntax",
			"vmrbooklet.help.hover_lookup",
			"vmrbooklet.help.favorite",
			"vmrbooklet.help.show_filter",
			"vmrbooklet.help.category_filter",
			"vmrbooklet.help.button_stats",
			"vmrbooklet.help.button_recent",
			"vmrbooklet.help.button_timeline",
			"vmrbooklet.help.button_needed",
			"vmrbooklet.help.button_friends",
			"vmrbooklet.help.arrows",
			"vmrbooklet.help.paging",
			"vmrbooklet.help.letter_rail",
			"vmrbooklet.help.ingredient_filter",
			"vmrbooklet.help.back",
			"vmrbooklet.help.milestones",
			"vmrbooklet.help.live_scan",
	};

	private static final int HELP_LINE_HEIGHT = 10;

	static void drawHelpOverlay(GuiGraphicsExtractor context, Font font, int x, int y, int w, int h, BookletUi.ScrollState scroll) {
		BookletUi.drawOverlayFrame(context, font, x, y, w, h, Component.translatable("vmrbooklet.overlay.help.title"));

		int textTop = y + 24;
		int textBottom = y + h - BookletUi.OVERLAY_LIST_BOTTOM_INSET;
		scroll.clampTo(HELP_LINE_KEYS.length, (textBottom - textTop) / HELP_LINE_HEIGHT);

		context.enableScissor(x + 1, textTop, x + w - 1, textBottom);
		int lineY = textTop;
		for (int i = scroll.firstRow(); i < HELP_LINE_KEYS.length; i++) {
			if (lineY + HELP_LINE_HEIGHT > textBottom) break;
			context.text(font, Component.translatable(HELP_LINE_KEYS[i]).withStyle(ChatFormatting.GRAY), x + 8, lineY, BookletUi.COLOR_TEXT);
			lineY += HELP_LINE_HEIGHT;
		}
		context.disableScissor();

		BookletUi.drawOverlayFooter(context, font, x, y, w, h, Component.translatable("vmrbooklet.overlay.help.footer"));
	}

	private static final int STATS_ROW_HEIGHT = 13;

	static void drawStatsOverlay(GuiGraphicsExtractor context, Font font, int x, int y, int w, int h,
			List<BookletScreenStats.LeaderboardRow> leaderboard, List<BookletScreenStats.ReleaseStat> stats,
			BookletScreenStats.Pace pace, BookletUi.ScrollState scroll) {
		BookletUi.drawOverlayFrame(context, font, x, y, w, h, Component.translatable("vmrbooklet.overlay.stats.leaderboard"));

		int rowY = y + 20;
		for (BookletScreenStats.LeaderboardRow row : leaderboard) {
			String pctText = Math.round(row.fraction() * 100) + "%";
			int pctWidth = font.width(pctText);
			Component label = row.isYou()
					? Component.translatable("vmrbooklet.tab.you").withStyle(ChatFormatting.BOLD)
					: Component.literal(row.label());
			context.text(font, label, x + 8, rowY, row.isYou() ? BookletUi.COLOR_ACCENT : BookletUi.COLOR_TEXT);
			int barRight = x + w - 8 - pctWidth - 6;
			int barLeft = x + w / 2;
			int barColor = row.fraction() >= 1f ? BookletUi.barDone() : BookletUi.barPartial();
			BookletUi.drawBar(context, barLeft, rowY + 1, Math.max(10, barRight - barLeft), 7, row.fraction(), barColor);
			context.text(font, Component.literal(pctText), x + w - 8 - pctWidth, rowY, BookletUi.COLOR_TEXT_DIM);
			rowY += STATS_ROW_HEIGHT;
		}

		rowY += 2;
		String paceText = BookletUi.ellipsize(font, paceLine(pace).getString(), w - 16);
		context.text(font, Component.literal(paceText).withStyle(ChatFormatting.ITALIC), x + 8, rowY, BookletUi.COLOR_TEXT_DIM);
		rowY += STATS_ROW_HEIGHT;

		rowY += 2;
		context.fill(x + 8, rowY, x + w - 8, rowY + 1, BookletUi.COLOR_BORDER);
		rowY += 6;

		context.text(font, Component.translatable("vmrbooklet.overlay.stats.by_release").withStyle(ChatFormatting.BOLD),
				x + 8, rowY, BookletUi.COLOR_ACCENT);
		int listTop = rowY + 12;
		int listBottom = y + h - BookletUi.OVERLAY_LIST_BOTTOM_INSET;
		scroll.clampTo(stats.size(), (listBottom - listTop) / STATS_ROW_HEIGHT);

		context.enableScissor(x + 1, listTop, x + w - 1, listBottom);
		int firstRow = scroll.firstRow();
		for (int i = 0; firstRow + i < stats.size() && listTop + i * STATS_ROW_HEIGHT < listBottom; i++) {
			BookletScreenStats.ReleaseStat stat = stats.get(firstRow + i);
			int rowAt = listTop + i * STATS_ROW_HEIGHT;

			context.text(font, Component.literal(stat.release()), x + 8, rowAt, BookletUi.COLOR_TEXT);

			String countText = stat.completed() + "/" + stat.total();
			int countWidth = font.width(countText);
			int barRight = x + w - 8 - countWidth - 6;
			int barLeft = x + w / 2;
			float fraction = stat.total() == 0 ? 0f : stat.completed() / (float) stat.total();
			BookletUi.drawBar(context, barLeft, rowAt + 1, Math.max(10, barRight - barLeft), 7, fraction,
					BookletUi.progressColor(stat.completed(), stat.total()));
			context.text(font, Component.literal(countText), x + w - 8 - countWidth, rowAt, BookletUi.COLOR_TEXT_DIM);
		}
		context.disableScissor();

		BookletUi.drawOverlayFooter(context, font, x, y, w, h, Component.translatable("vmrbooklet.overlay.stats.footer"));
	}

	static Component paceLine(BookletScreenStats.Pace pace) {
		if (pace.daysToFinish() == 0) {
			return Component.translatable("vmrbooklet.overlay.stats.pace_done");
		}
		if (pace.daysToFinish() < 0) {
			return Component.translatable("vmrbooklet.overlay.stats.pace_none", BookletScreenStats.PACE_WINDOW_DAYS);
		}
		return Component.translatable("vmrbooklet.overlay.stats.pace", BookletScreenStats.PACE_WINDOW_DAYS,
				String.format(Locale.ROOT, "%.1f", pace.perDay()), pace.daysToFinish());
	}

	private static final int RECENT_ROW_HEIGHT = 20;

	/** {@code outRecentRowRects} is cleared and repopulated by this call, for the caller's own click hit-testing. */
	static void drawRecentOverlay(GuiGraphicsExtractor context, Font font, int x, int y, int w, int h,
			List<BookletActivityLog.Entry> recent, BookletUi.ScrollState scroll, List<RecentRowRect> outRecentRowRects) {
		BookletUi.drawOverlayFrame(context, font, x, y, w, h, Component.translatable("vmrbooklet.overlay.recent.title"));

		outRecentRowRects.clear();
		if (recent.isEmpty()) {
			BookletUi.drawOverlayMessage(context, font, x, y, w, Component.translatable("vmrbooklet.overlay.nothing_completed"), ChatFormatting.GRAY);
			BookletUi.drawOverlayFooter(context, font, x, y, w, h, Component.translatable("vmrbooklet.overlay.recent.footer_empty"));
			return;
		}

		int listTop = y + BookletUi.OVERLAY_LIST_TOP;
		int listBottom = y + h - BookletUi.OVERLAY_LIST_BOTTOM_INSET;
		scroll.clampTo(recent.size(), (listBottom - listTop) / RECENT_ROW_HEIGHT);

		context.enableScissor(x + 1, listTop, x + w - 1, listBottom);
		int firstRow = scroll.firstRow();
		for (int i = 0; firstRow + i < recent.size() && listTop + i * RECENT_ROW_HEIGHT < listBottom; i++) {
			BookletActivityLog.Entry entry = recent.get(firstRow + i);
			int rowY = listTop + i * RECENT_ROW_HEIGHT;
			BookletEntry bookletEntry = BookletData.byItemId(entry.itemId());
			String name = bookletEntry != null ? bookletEntry.displayName().replace('_', ' ') : entry.itemId();

			if (bookletEntry != null) {
				context.item(new ItemStack(bookletEntry.item(), 1), x + 8, rowY + 1);
			}
			String ago = BookletUi.formatAgo(entry.timestampMillis()).getString();
			int agoWidth = font.width(ago);
			context.text(font, Component.literal(BookletUi.ellipsize(font, name, w - 28 - agoWidth - 14)), x + 28, rowY + 2, BookletUi.COLOR_TEXT);
			context.text(font, Component.literal(ago).withStyle(ChatFormatting.GRAY), x + w - 8 - agoWidth, rowY + 2, BookletUi.COLOR_TEXT_DIM);

			outRecentRowRects.add(new RecentRowRect(rowY, RECENT_ROW_HEIGHT, entry.itemId()));
		}
		context.disableScissor();

		BookletUi.drawOverlayFooter(context, font, x, y, w, h, Component.translatable("vmrbooklet.overlay.recent.footer"));
	}

	private static final int TIMELINE_ROW_HEIGHT = 13;

	static void drawTimelineOverlay(GuiGraphicsExtractor context, Font font, int x, int y, int w, int h,
			List<BookletScreenStats.DayStat> stats, BookletUi.ScrollState scroll) {
		BookletUi.drawOverlayFrame(context, font, x, y, w, h, Component.translatable("vmrbooklet.overlay.timeline.title"));

		if (stats.isEmpty()) {
			BookletUi.drawOverlayMessage(context, font, x, y, w, Component.translatable("vmrbooklet.overlay.nothing_completed"), ChatFormatting.GRAY);
			BookletUi.drawOverlayFooter(context, font, x, y, w, h, Component.translatable("vmrbooklet.overlay.timeline.footer_empty"));
			return;
		}

		int maxCount = 1;
		for (BookletScreenStats.DayStat stat : stats) {
			maxCount = Math.max(maxCount, stat.count());
		}

		int listTop = y + BookletUi.OVERLAY_LIST_TOP;
		int listBottom = y + h - BookletUi.OVERLAY_LIST_BOTTOM_INSET;
		scroll.clampTo(stats.size(), (listBottom - listTop) / TIMELINE_ROW_HEIGHT);

		context.enableScissor(x + 1, listTop, x + w - 1, listBottom);
		int firstRow = scroll.firstRow();
		for (int i = 0; firstRow + i < stats.size() && listTop + i * TIMELINE_ROW_HEIGHT < listBottom; i++) {
			BookletScreenStats.DayStat stat = stats.get(firstRow + i);
			int rowAt = listTop + i * TIMELINE_ROW_HEIGHT;

			context.text(font, Component.literal(stat.date().toString()), x + 8, rowAt, BookletUi.COLOR_TEXT);

			String countText = Component.translatable(stat.count() == 1 ? "vmrbooklet.overlay.timeline.item" : "vmrbooklet.overlay.timeline.items",
					stat.count()).getString();
			int countWidth = font.width(countText);
			int barRight = x + w - 8 - countWidth - 6;
			int barLeft = x + w / 2;
			float fraction = stat.count() / (float) maxCount;
			BookletUi.drawBar(context, barLeft, rowAt + 1, Math.max(10, barRight - barLeft), 7, fraction, BookletUi.barPartial());
			context.text(font, Component.literal(countText), x + w - 8 - countWidth, rowAt, BookletUi.COLOR_TEXT_DIM);
		}
		context.disableScissor();

		BookletUi.drawOverlayFooter(context, font, x, y, w, h, Component.translatable("vmrbooklet.overlay.timeline.footer"));
	}
}
