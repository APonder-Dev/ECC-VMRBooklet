package dev.aponder.vmrbooklet.client.gui;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.VMRBooklet;
import dev.aponder.vmrbooklet.client.data.BookletLocalProgress;
import dev.aponder.vmrbooklet.client.data.BookletSettings;
import dev.aponder.vmrbooklet.client.data.BookletSettings.TrackerColor;
import dev.aponder.vmrbooklet.client.data.BookletTracker;
import dev.aponder.vmrbooklet.client.sync.BookletFriends;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;

/**
 * The side tracker: every pinned item (yours or a friend's) as its own small
 * pill along one screen edge - "Elytra 2/5 · Alex". Colors, side, icons and
 * hiding finished rows are all player settings. A row whose count just
 * changed flashes briefly. Hidden with F1 and while any screen is open, and
 * drawn by {@link #drawPreview} for the color editor.
 *
 * <p>Vertically centered when there's room, but it moves out of the way of
 * the server's scoreboard sidebar (on the right) and of the goal HUD when
 * that sits on the same side, dropping rows into "+N more" if the space left
 * is short.
 */
public final class BookletTrackerHud {

	private static final int MAX_ROWS = 12;
	private static final int EDGE_MARGIN = 8;
	private static final int PILL_PAD_X = 3;
	private static final int ROW_GAP = 2;
	private static final int MAX_NAME_WIDTH = 140;
	private static final int CLEARANCE = 4;
	private static final long REFRESH_MILLIS = 250L;
	private static final long FLASH_MILLIS = 1500L;
	// Vanilla's sidebar shows at most 15 scores, 9px each, plus a title row.
	private static final int SIDEBAR_MAX_LINES = 15;
	private static final int SIDEBAR_LINE_HEIGHT = 9;

	/** One drawn line. {@code friend} is null for your own booklet. */
	record Row(BookletEntry entry, String friend, int obtained, long flashUntilMillis) {
		boolean done() {
			return obtained >= entry.required();
		}
	}

	/** A vertical stretch of screen the tracker may use. */
	private record Band(int top, int bottom) {
		int height() {
			return bottom - top;
		}
	}

	private static List<Row> rows = List.of();
	private static int hiddenCount;
	private static long refreshedAtMillis;
	// Last seen count per pin key, so a change can flash its row.
	private static final Map<String, Integer> lastObtained = new HashMap<>();
	private static final Map<String, Long> flashUntil = new HashMap<>();

	private BookletTrackerHud() {
	}

	public static void register() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(VMRBooklet.MOD_ID, "side_tracker"), BookletTrackerHud::extract);
	}

	private static void extract(GuiGraphicsExtractor context, DeltaTracker deltaTracker) {
		if (!BookletSettings.trackerEnabled() || BookletTracker.isEmpty()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.gui.hud.isHidden() || client.gui.screen() != null
				|| client.getDebugOverlay().showDebugScreen()) {
			return;
		}
		refreshIfStale();
		if (rows.isEmpty() && hiddenCount == 0) {
			return;
		}
		boolean right = BookletSettings.trackerSide() == BookletSettings.TrackerSide.RIGHT;
		Band band = freeBand(client, context.guiHeight(), right);
		draw(context, client.font, rows, hiddenCount, 0, context.guiWidth(), context.guiHeight() / 2, band, System.currentTimeMillis());
	}

	/**
	 * Draws sample rows centered on {@code centerY}, hugging the configured edge
	 * of a {@code areaWidth}-wide area starting at {@code areaX} - the color
	 * editor's live preview.
	 */
	static void drawPreview(GuiGraphicsExtractor context, Font font, List<Row> sample, int areaX, int areaWidth, int centerY) {
		draw(context, font, sample, 0, areaX, areaWidth, centerY, new Band(Integer.MIN_VALUE / 2, Integer.MAX_VALUE / 2), 0L);
	}

	/**
	 * The largest stretch of this edge not covered by the scoreboard sidebar
	 * (right edge only) or the goal HUD (when on the same edge).
	 */
	private static Band freeBand(Minecraft client, int guiHeight, boolean right) {
		List<Band> blocked = new ArrayList<>(2);
		if (right) {
			Band sidebar = sidebarBand(client, guiHeight);
			if (sidebar != null) {
				blocked.add(sidebar);
			}
		}
		BookletGoalHud.Bounds goal = BookletGoalHud.drawnBounds();
		if (goal != null && goal.right() == right) {
			blocked.add(new Band(goal.top(), goal.bottom()));
		}

		// Carve the blocked stretches out of the full height and keep the biggest gap.
		List<Band> free = new ArrayList<>();
		free.add(new Band(CLEARANCE, guiHeight - CLEARANCE));
		for (Band block : blocked) {
			List<Band> next = new ArrayList<>();
			for (Band gap : free) {
				if (block.bottom() + CLEARANCE <= gap.top() || block.top() - CLEARANCE >= gap.bottom()) {
					next.add(gap);
					continue;
				}
				if (block.top() - CLEARANCE > gap.top()) {
					next.add(new Band(gap.top(), block.top() - CLEARANCE));
				}
				if (block.bottom() + CLEARANCE < gap.bottom()) {
					next.add(new Band(block.bottom() + CLEARANCE, gap.bottom()));
				}
			}
			free = next;
		}
		Band best = new Band(CLEARANCE, CLEARANCE);
		for (Band gap : free) {
			if (gap.height() > best.height()) {
				best = gap;
			}
		}
		return best;
	}

	/** Where vanilla draws the scoreboard sidebar, mirroring its own layout math, or null if there isn't one. */
	private static Band sidebarBand(Minecraft client, int guiHeight) {
		if (client.level == null) {
			return null;
		}
		Scoreboard scoreboard = client.level.getScoreboard();
		Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (objective == null) {
			return null;
		}
		int lines = 0;
		for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
			if (!entry.isHidden()) {
				lines++;
			}
		}
		lines = Math.min(lines, SIDEBAR_MAX_LINES);
		int scoresHeight = lines * SIDEBAR_LINE_HEIGHT;
		int bottom = guiHeight / 2 + scoresHeight / 3;
		int top = bottom - scoresHeight - SIDEBAR_LINE_HEIGHT - 1;
		return new Band(top, bottom);
	}

	private static void draw(GuiGraphicsExtractor context, Font font, List<Row> drawRows, int extra,
			int areaX, int areaWidth, int centerY, Band band, long now) {
		boolean icons = BookletSettings.trackerIcons();
		boolean right = BookletSettings.trackerSide() == BookletSettings.TrackerSide.RIGHT;
		int lineHeight = icons ? 18 : 12;
		int rowStep = lineHeight + ROW_GAP;

		// Trim to what fits in the free band, folding the rest into "+N more".
		int fit = Math.max(0, band.height() / rowStep);
		List<Row> shown = drawRows;
		if (drawRows.size() + (extra > 0 ? 1 : 0) > fit) {
			int keep = Math.max(0, Math.min(drawRows.size(), fit - 1));
			extra += drawRows.size() - keep;
			shown = drawRows.subList(0, keep);
		}
		int lines = shown.size() + (extra > 0 ? 1 : 0);
		if (lines == 0) {
			return;
		}

		// Centered on centerY when that fits inside the band, otherwise pushed in.
		int total = lines * rowStep;
		int y = centerY - total / 2;
		y = Math.max(band.top() + 2, Math.min(y, band.bottom() - total + 2));

		int background = BookletSettings.trackerColor(TrackerColor.BACKGROUND);
		int textColor = BookletSettings.trackerColor(TrackerColor.TEXT);
		int countColor = BookletSettings.trackerColor(TrackerColor.COUNT);
		int doneColor = BookletSettings.trackerColor(TrackerColor.DONE);
		int friendColor = BookletSettings.trackerColor(TrackerColor.FRIEND);

		for (Row row : shown) {
			String name = BookletUi.ellipsize(font, row.entry().displayName().replace('_', ' '), MAX_NAME_WIDTH);
			String count = row.done() ? "✓" : row.obtained() + "/" + row.entry().required();
			String friend = row.friend() == null ? null : "· " + row.friend();

			int iconWidth = icons ? 18 : 0;
			int width = iconWidth + font.width(name) + 4 + font.width(count)
					+ (friend == null ? 0 : 4 + font.width(friend));
			int x = areaX + (right ? areaWidth - width - EDGE_MARGIN : EDGE_MARGIN);

			int pill = row.flashUntilMillis() > now ? brighten(background) : background;
			context.fill(x - PILL_PAD_X, y - 2, x + width + PILL_PAD_X, y + lineHeight - 2, pill);

			int textY = icons ? y + 3 : y;
			int cx = x;
			if (icons) {
				context.item(new ItemStack(row.entry().item(), 1), cx, y - 1);
				cx += iconWidth;
			}
			context.text(font, name, cx, textY, row.done() ? doneColor : textColor);
			cx += font.width(name) + 4;
			context.text(font, count, cx, textY, row.done() ? doneColor : countColor);
			cx += font.width(count) + 4;
			if (friend != null) {
				context.text(font, friend, cx, textY, friendColor);
			}
			y += rowStep;
		}

		if (extra > 0) {
			String more = Component.translatable("vmrbooklet.hud.more", extra).getString();
			int width = font.width(more);
			int x = areaX + (right ? areaWidth - width - EDGE_MARGIN : EDGE_MARGIN);
			context.fill(x - PILL_PAD_X, y - 2, x + width + PILL_PAD_X, y + 10, background);
			context.text(font, more, x, y, textColor);
		}
	}

	/** The flash tint: same alpha, each channel pushed halfway toward white. */
	private static int brighten(int argb) {
		int a = argb >>> 24;
		int r = ((argb >> 16) & 0xFF);
		int g = ((argb >> 8) & 0xFF);
		int b = argb & 0xFF;
		r += (255 - r) / 2;
		g += (255 - g) / 2;
		b += (255 - b) / 2;
		return Math.max(a, 0xC0) << 24 | r << 16 | g << 8 | b;
	}

	private static void refreshIfStale() {
		long now = System.currentTimeMillis();
		if (now - refreshedAtMillis < REFRESH_MILLIS) {
			return;
		}
		refreshedAtMillis = now;

		boolean hideDone = BookletSettings.trackerHideDone();
		List<String> friends = BookletFriends.names();
		List<Row> built = new ArrayList<>();
		Set<String> pinnedKeys = new HashSet<>();
		int skipped = 0;
		for (BookletTracker.Pin pin : BookletTracker.all()) {
			BookletEntry entry = BookletData.byItemId(pin.itemId());
			if (entry == null || (pin.friend() != null && !friends.contains(pin.friend()))) {
				continue;
			}
			int obtained = pin.friend() == null
					? BookletLocalProgress.obtained(entry.itemId())
					: BookletFriends.progressOf(pin.friend()).getOrDefault(entry.itemId(), 0);

			String key = pin.key();
			pinnedKeys.add(key);
			Integer previous = lastObtained.put(key, obtained);
			if (previous != null && previous != obtained) {
				flashUntil.put(key, now + FLASH_MILLIS);
			}

			Row row = new Row(entry, pin.friend(), obtained, flashUntil.getOrDefault(key, 0L));
			// A pin that just finished stays up until its flash ends, so finishing
			// it is visible even with "Hide finished" on.
			if (hideDone && row.done() && row.flashUntilMillis() <= now) {
				continue;
			}
			if (built.size() >= MAX_ROWS) {
				skipped++;
				continue;
			}
			built.add(row);
		}
		// Forget unpinned items, so pinning one again later starts fresh instead
		// of flashing against a count from before it was unpinned.
		lastObtained.keySet().retainAll(pinnedKeys);
		flashUntil.keySet().retainAll(pinnedKeys);
		rows = List.copyOf(built);
		hiddenCount = skipped;
	}
}
