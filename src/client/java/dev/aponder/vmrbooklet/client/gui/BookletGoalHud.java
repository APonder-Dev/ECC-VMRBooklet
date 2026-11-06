package dev.aponder.vmrbooklet.client.gui;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.VMRBooklet;
import dev.aponder.vmrbooklet.client.data.BookletGoals;
import dev.aponder.vmrbooklet.client.data.BookletLocalProgress;
import dev.aponder.vmrbooklet.client.data.BookletSettings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * A small on-screen list of the active goal list's items and counts, in a
 * configurable corner (Mod Menu settings). Off by default. Unfinished items
 * are listed first; the row list is rebuilt at most twice a second.
 */
public final class BookletGoalHud {

	private static final int MAX_ROWS = 8;
	private static final int ROW_HEIGHT = 18;
	private static final int PAD = 4;
	private static final int MARGIN = 4;
	private static final int MAX_NAME_WIDTH = 120;
	private static final int BACKGROUND = 0x90000000;
	private static final long REFRESH_MILLIS = 500L;

	private record Row(BookletEntry entry, int obtained) {
		boolean done() {
			return obtained >= entry.required();
		}
	}

	private static List<Row> rows = List.of();
	private static int doneCount;
	private static int totalCount;
	private static long refreshedAtMillis;

	private BookletGoalHud() {
	}

	public static void register() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(VMRBooklet.MOD_ID, "goal_tracker"), BookletGoalHud::extract);
	}

	private static void extract(GuiGraphicsExtractor context, DeltaTracker deltaTracker) {
		if (!BookletSettings.hudEnabled()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.getDebugOverlay().showDebugScreen()) {
			return;
		}
		refreshIfStale();
		if (totalCount == 0) {
			return;
		}

		Font font = client.font;
		Component title = Component.translatable("vmrbooklet.hud.title", BookletGoals.activeListName(), doneCount, totalCount);
		int width = font.width(title);
		List<String> names = new ArrayList<>(rows.size());
		for (Row row : rows) {
			String name = BookletUi.ellipsize(font, row.entry().displayName().replace('_', ' '), MAX_NAME_WIDTH);
			names.add(name);
			width = Math.max(width, 18 + font.width(name) + 6 + font.width(countText(row)));
		}
		int extra = totalCount - rows.size();
		Component more = Component.translatable("vmrbooklet.hud.more", extra);
		if (extra > 0) {
			width = Math.max(width, font.width(more));
		}
		int height = 12 + rows.size() * ROW_HEIGHT + (extra > 0 ? 10 : 0);

		int boxW = width + PAD * 2;
		int boxH = height + PAD * 2;
		BookletSettings.HudCorner corner = BookletSettings.hudCorner();
		boolean right = corner == BookletSettings.HudCorner.TOP_RIGHT || corner == BookletSettings.HudCorner.BOTTOM_RIGHT;
		boolean bottom = corner == BookletSettings.HudCorner.BOTTOM_LEFT || corner == BookletSettings.HudCorner.BOTTOM_RIGHT;
		int x = right ? context.guiWidth() - boxW - MARGIN : MARGIN;
		// Bottom corners sit above the hotbar/chat area rather than on top of it.
		int y = bottom ? context.guiHeight() - boxH - 48 : MARGIN;

		context.fill(x, y, x + boxW, y + boxH, BACKGROUND);
		int cx = x + PAD;
		int cy = y + PAD;
		context.text(font, title, cx, cy, BookletUi.COLOR_ACCENT);
		cy += 12;
		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			context.item(new ItemStack(row.entry().item(), 1), cx, cy);
			int color = row.done() ? BookletUi.barDone() : BookletUi.COLOR_TEXT;
			context.text(font, names.get(i), cx + 18, cy + 4, color);
			String count = countText(row);
			context.text(font, count, x + boxW - PAD - font.width(count), cy + 4, row.done() ? BookletUi.barDone() : BookletUi.COLOR_TEXT_DIM);
			cy += ROW_HEIGHT;
		}
		if (extra > 0) {
			context.text(font, more, cx, cy, BookletUi.COLOR_TEXT_DIM);
		}
	}

	private static String countText(Row row) {
		return row.done() ? "✓" : row.obtained() + "/" + row.entry().required();
	}

	private static void refreshIfStale() {
		long now = System.currentTimeMillis();
		if (now - refreshedAtMillis < REFRESH_MILLIS) {
			return;
		}
		refreshedAtMillis = now;
		List<Row> all = new ArrayList<>();
		for (String itemId : BookletGoals.all()) {
			BookletEntry entry = BookletData.byItemId(itemId);
			if (entry != null) {
				all.add(new Row(entry, BookletLocalProgress.obtained(itemId)));
			}
		}
		// Unfinished first (closest to done at the top), finished after.
		all.sort(Comparator.comparing(Row::done)
				.thenComparingDouble(row -> -(row.obtained() / (double) Math.max(1, row.entry().required()))));
		doneCount = (int) all.stream().filter(Row::done).count();
		totalCount = all.size();
		rows = List.copyOf(all.subList(0, Math.min(MAX_ROWS, all.size())));
	}
}
