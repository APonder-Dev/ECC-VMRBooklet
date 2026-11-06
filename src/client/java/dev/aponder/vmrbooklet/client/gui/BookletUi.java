package dev.aponder.vmrbooklet.client.gui;

import dev.aponder.vmrbooklet.BookletState;
import dev.aponder.vmrbooklet.client.data.BookletSettings;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Shared palette and drawing primitives for every booklet screen piece, so
 * the overlays, dropdowns, recipe panel and main screen stop carrying their
 * own copies of the same border/bar/colour code.
 */
final class BookletUi {

	// A warmer, slightly saturated navy/indigo base with a vivid blue for
	// selection and gold for the primary accent.
	static final int COLOR_BACKDROP = 0xC0000000;
	static final int COLOR_PANEL = 0xFF21212E;
	static final int COLOR_HEADER = 0xFF191A26;
	static final int COLOR_BORDER = 0xFF494E68;
	static final int COLOR_LIST_BG = 0xFF1A1B27;
	static final int COLOR_ROW_HOVER = 0xFF2E3148;
	static final int COLOR_ROW_SELECTED = 0xFF3E72B8;
	static final int COLOR_TAB_INACTIVE = 0xFF272A3C;
	static final int COLOR_ACCENT = 0xFFFFD36B;
	static final int COLOR_TEXT = 0xFFEDEDF4;
	static final int COLOR_TEXT_DIM = 0xFFA4A9C2;
	static final int COLOR_BAR_TRACK = 0xFF2D2F42;
	static final int COLOR_OVERLAY_BG = 0xF0101014;

	private static final int COLOR_BAR_DONE = 0xFF4CAF50;
	private static final int COLOR_BAR_PARTIAL = 0xFFD4B13A;
	private static final int COLOR_BAR_EMPTY = 0xFF44444E;
	// Colorblind-safe alternative (blue/orange/gray): distinguishable under
	// red-green deficiencies, where the default green/gold pair collapses.
	private static final int COLOR_BAR_DONE_CB = 0xFF3D8BE0;
	private static final int COLOR_BAR_PARTIAL_CB = 0xFFF08A24;

	// The recipe/acquisition-note panels use a lighter, higher-contrast palette -
	// the near-black tones above made their grid lines all but invisible.
	static final int COLOR_RECIPE_PANEL = 0xFF403C4A;
	static final int COLOR_RECIPE_PANEL_BORDER = 0xFF5D5868;
	static final int COLOR_RECIPE_SLOT = 0xFF29262F;
	static final int COLOR_RECIPE_SLOT_BORDER = 0xFF938CA0;

	/** Where an overlay's scrollable rows start below its top edge (under the title). */
	static final int OVERLAY_LIST_TOP = 22;
	/** How far above an overlay's bottom edge its scrollable rows stop (above the footer hint). */
	static final int OVERLAY_LIST_BOTTOM_INSET = 14;

	private BookletUi() {
	}

	static void border(GuiGraphicsExtractor context, int x, int y, int w, int h) {
		border(context, x, y, w, h, COLOR_BORDER);
	}

	static void border(GuiGraphicsExtractor context, int x, int y, int w, int h, int color) {
		context.fill(x, y, x + w, y + 1, color);
		context.fill(x, y + h - 1, x + w, y + h, color);
		context.fill(x, y, x + 1, y + h, color);
		context.fill(x + w - 1, y, x + w, y + h, color);
	}

	static void drawBar(GuiGraphicsExtractor context, int x, int y, int width, int height, float fraction, int color) {
		context.fill(x, y, x + width, y + height, COLOR_BAR_TRACK);
		int filled = (int) (width * Math.max(0f, Math.min(1f, fraction)));
		if (filled > 0) {
			context.fill(x, y, x + filled, y + height, color);
		}
	}

	static int barDone() {
		return BookletSettings.colorblind() ? COLOR_BAR_DONE_CB : COLOR_BAR_DONE;
	}

	static int barPartial() {
		return BookletSettings.colorblind() ? COLOR_BAR_PARTIAL_CB : COLOR_BAR_PARTIAL;
	}

	static int barEmpty() {
		return COLOR_BAR_EMPTY;
	}

	/** Done / started / untouched bar color, following the colorblind setting. */
	static int progressColor(int obtained, int required) {
		if (obtained >= required) {
			return barDone();
		}
		return obtained > 0 ? barPartial() : barEmpty();
	}

	/** Text color for an item's completion state, following the colorblind setting. */
	static ChatFormatting stateFormatting(BookletState state) {
		boolean cb = BookletSettings.colorblind();
		return switch (state) {
			case ACHIEVED -> cb ? ChatFormatting.AQUA : ChatFormatting.GREEN;
			case IN_PROGRESS -> cb ? ChatFormatting.GOLD : ChatFormatting.YELLOW;
			case UNDISCOVERED -> ChatFormatting.GRAY;
		};
	}

	/**
	 * A shape cue in front of a list row name in colorblind mode, so state never
	 * depends on color alone; empty otherwise.
	 */
	static String stateMark(BookletState state) {
		if (!BookletSettings.colorblind()) {
			return "";
		}
		return switch (state) {
			case ACHIEVED -> "✓ ";
			case IN_PROGRESS -> "~ ";
			case UNDISCOVERED -> "";
		};
	}

	/** Yes/no text color (e.g. "Achieved: Yes"), following the colorblind setting. */
	static ChatFormatting yesNoFormatting(boolean yes) {
		if (BookletSettings.colorblind()) {
			return yes ? ChatFormatting.AQUA : ChatFormatting.GOLD;
		}
		return yes ? ChatFormatting.GREEN : ChatFormatting.RED;
	}

	/** Trims text to fit maxWidth pixels, ending in "..." when anything was cut. */
	static String ellipsize(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			return text;
		}
		String dots = "...";
		int room = maxWidth - font.width(dots);
		if (room <= 0) {
			return "";
		}
		return font.plainSubstrByWidth(text, room) + dots;
	}

	/** Relative age (e.g. "3h ago", "2d ago") of a stored timestamp, or "never" if it's unset. */
	static Component formatAgo(long last) {
		if (last <= 0) {
			return Component.translatable("vmrbooklet.time.never");
		}
		return Component.translatable("vmrbooklet.time.ago", formatAgoShort(last));
	}

	/** Compact form of {@link #formatAgo} for the tab row, e.g. "5m", "3h", "2d" - or "?" if unset. */
	static String formatAgoShort(long last) {
		if (last <= 0) {
			return "?";
		}
		long seconds = Math.max(0, (System.currentTimeMillis() - last) / 1000);
		if (seconds < 60) return seconds + "s";
		long minutes = seconds / 60;
		if (minutes < 60) return minutes + "m";
		long hours = minutes / 60;
		if (hours < 24) return hours + "h";
		return (hours / 24) + "d";
	}

	/** Background, border and bold accent title shared by every full-panel overlay. */
	static void drawOverlayFrame(GuiGraphicsExtractor context, Font font, int x, int y, int w, int h, Component title) {
		context.fill(x, y, x + w, y + h, COLOR_OVERLAY_BG);
		border(context, x, y, w, h);
		String text = ellipsize(font, title.getString(), w - 16);
		context.text(font, Component.literal(text).withStyle(ChatFormatting.BOLD), x + 8, y + 8, COLOR_ACCENT);
	}

	/** The dim one-line hint along an overlay's bottom edge, clipped to the overlay's width. */
	static void drawOverlayFooter(GuiGraphicsExtractor context, Font font, int x, int y, int w, int h, Component hint) {
		String text = ellipsize(font, hint.getString(), w - 16);
		context.text(font, Component.literal(text).withStyle(ChatFormatting.DARK_GRAY), x + 8, y + h - 12, COLOR_TEXT_DIM);
	}

	/** A dim message in an overlay's body, for its "nothing to show" states. */
	static void drawOverlayMessage(GuiGraphicsExtractor context, Font font, int x, int y, int w, Component message, ChatFormatting style) {
		String text = ellipsize(font, message.getString(), w - 16);
		context.text(font, Component.literal(text).withStyle(style), x + 8, y + 24, COLOR_TEXT_DIM);
	}

	/**
	 * Row-based scroll position for one overlay. The overlay's draw call sets
	 * the bounds each frame from what it actually laid out ({@link #clampTo});
	 * the screen's scroll handler then only nudges within those bounds, so the
	 * two can never disagree about how far the list can scroll.
	 */
	static final class ScrollState {
		private double rows;
		private int max;

		double rows() {
			return rows;
		}

		int firstRow() {
			return (int) rows;
		}

		void reset() {
			rows = 0;
			max = 0;
		}

		void clampTo(int totalRows, int visibleRows) {
			max = Math.max(0, totalRows - Math.max(1, visibleRows));
			rows = Math.max(0, Math.min(max, rows));
		}

		void scrollBy(double wheelDelta) {
			rows = Math.max(0, Math.min(max, rows - wheelDelta));
		}
	}
}
