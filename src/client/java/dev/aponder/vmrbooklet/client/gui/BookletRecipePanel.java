package dev.aponder.vmrbooklet.client.gui;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.client.BookletRecipeLookup;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Pure rendering for the two recipe-related blocks of {@link BookletScreen}'s
 * detail panel: the "Crafting" grid(s) and the "Used in" icon row. Pulled out
 * of {@link BookletScreen} itself since neither touches any screen state
 * beyond the coordinates/font/data it's explicitly given - kept as its own
 * class so this chunk of layout math doesn't keep growing inside an already
 * large {@code Screen} subclass.
 */
final class BookletRecipePanel {

	// A visibly lighter, higher-contrast palette than the rest of the UI's
	// near-black tones, which made the grid lines all but invisible.
	private static final int COLOR_PANEL = 0xFF3A3A44;
	private static final int COLOR_SLOT = 0xFF26262E;
	private static final int COLOR_SLOT_BORDER = 0xFF8C8C9A;
	private static final int COLOR_TEXT_DIM = 0xFF9A9AA6;
	// Was a near-black 0x2B2B2B, meant for a light panel background - but this
	// panel is actually a dark navy-gray, so that dark text read as almost
	// illegible. Light text reads correctly against the panel's real color.
	private static final int COLOR_PANEL_TEXT = 0xFFEDEDF4;
	// A distinct, link-like color (plus underline) for the "click to see more"
	// lines, so they read as interactive rather than looking like the same
	// plain dim-gray "+N more" note this used to be before it was clickable.
	private static final int COLOR_LINK = 0xFF7FB3FF;

	// The slot size we'd like to use, but never wider than the available detail
	// panel width actually allows - see drawOne()'s fitSlotSize(), which solves
	// for the largest slot that still keeps the whole panel (grid + arrow +
	// output + padding) on-screen instead of running past the detail area's own
	// clip region, as a fixed 22px did on a narrow window (the output slot and
	// the panel's own right border got clipped off entirely).
	private static final int DESIRED_RECIPE_SLOT = 22;
	private static final int MIN_RECIPE_SLOT = 14;
	private static final int MAX_RECIPES_SHOWN = 2;
	// A fixed 3-column grid area regardless of any particular recipe's actual
	// width (1-3) - otherwise a 1- or 2-wide recipe's panel comes out visibly
	// narrower than a 3-wide one, and two stacked recipes (an item with more
	// than one known recipe) line up raggedly instead of matching widths.
	private static final int RECIPE_GRID_COLS = 3;
	private static final int RECIPE_GRID_ROWS = 3;

	private static final int USED_IN_ICON = 22;
	private static final int MAX_USED_IN_SHOWN = 24;

	private static final int PAD = 8;
	// Tall enough to hold a standard 16px item icon (the station indicator) next
	// to the title text, vertically centered against it.
	private static final int TITLE_H = 16;

	record UsedInIcon(int x, int y, int size, String itemId) {
	}

	/**
	 * An item-icon rect to show a name tooltip for on hover - every ingredient
	 * slot, every recipe's output slot, and every "Used in" icon.
	 *
	 * @param isIngredient true only for a recipe's own ingredient slots - lets
	 *                     the caller offer a "filter to what else uses this"
	 *                     click there, distinct from the output slot/"Used in"
	 *                     icons (which already jump straight to that item).
	 */
	record HoverSlot(int x, int y, int size, ItemStack stack, boolean isIngredient) {
	}

	/** A clickable rect, screen-space - used for the "+N more - click to see more" line at the bottom of a paginated section. */
	record ClickRect(int x, int y, int w, int h) {
		boolean contains(int mx, int my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	/**
	 * What a paginated section drew: where the caller should draw next, how many
	 * pages the full list needs, and (when there's more than one page) the
	 * clickable rect over its "+N more" line - null when everything fit on one
	 * page and there's nothing to click.
	 */
	record SectionResult(int py, int totalPages, ClickRect moreRect) {
	}

	private BookletRecipePanel() {
	}

	/**
	 * Draws one page of up to {@link #MAX_RECIPES_SHOWN} recipe panels for an
	 * item that has at least one known recipe. When more recipes exist than fit
	 * on one page, a clickable "Page X/Y - click for more recipes" line is drawn
	 * and its rect returned so the caller can advance {@code page} on click -
	 * see {@link SectionResult}.
	 */
	private static final int RECIPE_GAP = 9;

	static SectionResult drawRecipes(GuiGraphicsExtractor context, Font font, int px, int py, int availableWidth, int availableHeight, List<BookletRecipeLookup.Recipe> recipes, int page, List<HoverSlot> outHoverSlots) {
		int slot = fitSlotSize(availableWidth);
		int pageSize = Math.min(MAX_RECIPES_SHOWN, recipes.size());

		int pad = PAD;
		int fixedGridW = RECIPE_GRID_COLS * slot;
		int arrowGap = 8;
		int arrowW = 10;
		int baselinePanelW = pad * 2 + fixedGridW + arrowGap + arrowW + arrowGap + slot;
		int labelStartX = pad + 21;

		// Every panel on a page shares the same width and title height, decided
		// ONCE here from the widest label any recipe on this item could show -
		// not a fixed assumption, and not recomputed per panel, so panels never
		// end up visually inconsistent with each other. First choice is to widen
		// the panel (same as before) to fit that label on one line, up to the
		// real available width; only if even that isn't enough room does the
		// title wrap onto more lines instead of truncating or overflowing.
		int worstLabelWidth = 0;
		for (BookletRecipeLookup.Recipe r : recipes) {
			String suffix = recipes.size() > 1 ? " (" + recipes.size() + "/" + recipes.size() + ")" : "";
			// Measured bold, matching how the label is actually drawn below - bold
			// glyphs render wider than their plain-string width, so measuring plain
			// here undercounted the real width and let the suffix clip the border.
			worstLabelWidth = Math.max(worstLabelWidth,
					font.width(Component.literal(r.station().label() + suffix).withStyle(ChatFormatting.BOLD)));
		}
		int panelW = Math.min(availableWidth, Math.max(baselinePanelW, labelStartX + worstLabelWidth + pad));
		int maxLabelWidth = panelW - labelStartX - pad;

		int titleH = TITLE_H;
		if (worstLabelWidth > maxLabelWidth) {
			// Build the actual worst-case string (not just its width) so split()
			// can tell us how many lines it really needs at this panel's width.
			String worstStation = "";
			for (BookletRecipeLookup.Recipe r : recipes) {
				if (font.width(r.station().label()) > font.width(worstStation)) {
					worstStation = r.station().label();
				}
			}
			String worstSuffix = recipes.size() > 1 ? " (" + recipes.size() + "/" + recipes.size() + ")" : "";
			int lines = font.split(Component.literal(worstStation + worstSuffix).withStyle(ChatFormatting.BOLD), Math.max(1, maxLabelWidth)).size();
			titleH = Math.max(TITLE_H, 6 + Math.max(1, lines) * 10);
		}

		// Every panel at this slot size is the same height (the grid area is a
		// fixed 3 rows regardless of a particular recipe's actual shape), so this
		// one panelH applies to all of them - only cap how many we attempt to
		// draw if they genuinely wouldn't all fit, always drawing at least one
		// even if it's tight, rather than showing nothing.
		int panelH = PAD * 2 + titleH + RECIPE_GRID_ROWS * slot;
		// If there's more than one recipe at all, a "Page X/Y" link might end up
		// needed below the last panel - reserve room for it up front so the fit
		// loop below can't let a panel (or two) claim space that line then has
		// nowhere left to go, spilling it (or whatever's drawn after this whole
		// section returns) past the detail area's clip region.
		int pageLinkHeight = RECIPE_GAP + 10;
		int budget = recipes.size() > 1 ? Math.max(panelH, availableHeight - pageLinkHeight) : availableHeight;
		int fitByHeight = 1;
		int usedHeight = panelH;
		while (fitByHeight < pageSize) {
			int next = usedHeight + RECIPE_GAP + panelH;
			if (next > budget) {
				break;
			}
			usedHeight = next;
			fitByHeight++;
		}
		pageSize = Math.min(pageSize, fitByHeight);

		int totalPages = Math.max(1, (recipes.size() + pageSize - 1) / pageSize);
		int currentPage = ((page % totalPages) + totalPages) % totalPages;
		int startIndex = currentPage * pageSize;
		int shown = Math.min(pageSize, recipes.size() - startIndex);

		for (int i = 0; i < shown; i++) {
			if (i > 0) {
				py += RECIPE_GAP;
			}
			BookletRecipeLookup.Recipe recipe = recipes.get(startIndex + i);
			// Numbered against the FULL list, not just this page, so "Crafting
			// (3/5)" still means something once there's more than one page.
			py += drawOne(context, font, px, py, slot, panelW, titleH, recipe, recipes.size() > 1 ? startIndex + i + 1 : -1, recipes.size(), outHoverSlots);
		}

		ClickRect moreRect = null;
		if (totalPages > 1) {
			py += RECIPE_GAP;
			String label = "Page " + (currentPage + 1) + "/" + totalPages + " - click to see more recipes";
			context.text(font, Component.literal(label).withStyle(ChatFormatting.UNDERLINE),
					px, py, COLOR_LINK);
			moreRect = new ClickRect(px, py, font.width(label), 10);
			py += 10;
		}
		return new SectionResult(py, totalPages, moreRect);
	}

	/**
	 * Styled like the familiar crafting-table layout (bordered panel, a bordered
	 * slot per ingredient so the grid lines actually read as a grid, an arrow,
	 * then a separately boxed output slot) rather than plain unbordered squares.
	 * {@code recipeIndex}/{@code recipeCount} label which recipe this is when an
	 * item has more than one ({@code recipeIndex == -1} for "just one").
	 */
	private static int drawOne(GuiGraphicsExtractor context, Font font, int px, int py, int slot, int panelW, int titleH, BookletRecipeLookup.Recipe recipe, int recipeIndex, int recipeCount, List<HoverSlot> outHoverSlots) {
		int startY = py;
		String suffix = recipeIndex < 0 ? "" : " (" + recipeIndex + "/" + recipeCount + ")";
		String label = recipe.station().label() + suffix;

		int pad = PAD;
		int fixedGridW = RECIPE_GRID_COLS * slot;
		int fixedGridH = RECIPE_GRID_ROWS * slot;
		int gridW = recipe.width() * slot;
		int gridH = recipe.height() * slot;
		int arrowGap = 8;
		int arrowW = 10;
		// panelW and titleH are decided once by the caller (drawRecipes), from
		// the widest label ANY recipe on this page could need - every panel on a
		// page is always the same size, never recomputed per recipe here.
		int labelStartX = pad + 21;
		int panelH = pad + titleH + fixedGridH + pad;

		// One unified light panel holding title, grid, arrow and output together -
		// matching the familiar crafting-table layout rather than separate boxes.
		// Bordered in that recipe's station color (amber crafting, fiery furnace,
		// violet smithing, blue stonecutter) so the panels read as distinct kinds
		// of thing at a glance instead of all looking like the same gray box.
		context.fill(px, py, px + panelW, py + panelH, COLOR_PANEL);
		border(context, px, py, panelW, panelH, recipe.station().color());

		// The station icon (crafting table / furnace / smithing table /
		// stonecutter) shows at a glance where this recipe is actually made,
		// which matters once recipes aren't all "the crafting table" anymore.
		context.item(new ItemStack(recipe.station().icon(), 1), px + pad - 2, py + pad - 3);
		// The station icon is a full 16px item icon starting at px+pad-2 (so its
		// right edge is at px+pad+14) - +17 left only a 3px gap before the label,
		// which read as the text crowding/touching the icon rather than sitting
		// clearly next to it.
		int maxLabelWidth = Math.max(1, panelW - labelStartX - pad);
		if (titleH > TITLE_H) {
			// Too narrow even at this page's full width to fit on one line -
			// wrap it (matching the line count drawRecipes already budgeted
			// panelH for) rather than truncating it with "...".
			int lineY = py + pad + 3;
			for (var line : font.split(Component.literal(label).withStyle(ChatFormatting.BOLD), maxLabelWidth)) {
				context.text(font, line, px + labelStartX, lineY, COLOR_PANEL_TEXT);
				lineY += 10;
			}
		} else {
			context.text(font, Component.literal(label).withStyle(ChatFormatting.BOLD), px + labelStartX, py + pad + 3, COLOR_PANEL_TEXT);
		}

		int contentY = py + pad + titleH;
		// Smaller recipes (e.g. a 1x1 or 2x2) are centered within the fixed grid
		// area, same as vanilla's own recipe book does for sub-3x3 recipes.
		int gridX = px + pad + (fixedGridW - gridW) / 2;
		int gridY = contentY + (fixedGridH - gridH) / 2;
		for (int row = 0; row < recipe.height(); row++) {
			for (int col = 0; col < recipe.width(); col++) {
				int index = row * recipe.width() + col;
				ItemStack stack = index < recipe.ingredients().size() ? recipe.ingredients().get(index) : ItemStack.EMPTY;
				int slotX = gridX + col * slot;
				int slotY = gridY + row * slot;
				drawSlot(context, slotX, slotY, slot, stack);
				if (!stack.isEmpty()) {
					outHoverSlots.add(new HoverSlot(slotX, slotY, slot - 2, stack, true));
				}
			}
		}

		int arrowX = px + pad + fixedGridW + arrowGap;
		int arrowCenterY = contentY + fixedGridH / 2;
		context.text(font, Component.literal("→").withStyle(ChatFormatting.BOLD), arrowX, arrowCenterY - 4, COLOR_PANEL_TEXT);

		int outputX = arrowX + arrowW + arrowGap;
		int outputY = contentY + (fixedGridH - slot) / 2;
		drawSlot(context, outputX, outputY, slot, recipe.output());
		if (!recipe.output().isEmpty()) {
			outHoverSlots.add(new HoverSlot(outputX, outputY, slot - 2, recipe.output(), false));
		}

		if (!recipe.output().isEmpty() && recipe.output().getCount() > 1) {
			String countText = String.valueOf(recipe.output().getCount());
			context.text(font, Component.literal(countText).withStyle(ChatFormatting.BOLD),
					outputX + slot - 4 - font.width(countText), outputY + slot - 10, 0xFFFFFFFF);
		}

		return py + panelH - startY; // the gap before whatever comes next is the caller's call, not baked in here
	}

	/** The largest recipe-grid slot size (capped at {@link #DESIRED_RECIPE_SLOT}) whose full panel - grid, arrow, output and padding - still fits within availableWidth, so a narrow window can never clip the panel's own right edge the way a fixed slot size did. */
	private static int fitSlotSize(int availableWidth) {
		int pad = 8;
		int arrowGap = 8;
		int arrowW = 10;
		int fixedOverhead = pad * 2 + arrowGap * 2 + arrowW;
		int maxSlot = (availableWidth - fixedOverhead) / (RECIPE_GRID_COLS + 1);
		return Math.max(MIN_RECIPE_SLOT, Math.min(DESIRED_RECIPE_SLOT, maxSlot));
	}

	private static void drawSlot(GuiGraphicsExtractor context, int x, int y, int slot, ItemStack stack) {
		context.fill(x, y, x + slot - 2, y + slot - 2, COLOR_SLOT);
		border(context, x, y, slot - 2, slot - 2, COLOR_SLOT_BORDER);
		if (!stack.isEmpty()) {
			context.item(stack, x + 1, y + 1);
		}
	}

	/**
	 * Draws one page of up to {@link #MAX_USED_IN_SHOWN} clickable item icons for
	 * every distinct item this one is a known ingredient of. Appends one
	 * {@link UsedInIcon} per rendered icon into {@code outIconRects} (cleared by
	 * the caller beforehand) for the caller to hit-test a left-click-to-jump on -
	 * this class only draws, it doesn't own any click state. When more icons
	 * exist than fit on one page, a clickable "Page X/Y" line is drawn and its
	 * rect returned so the caller can advance {@code page} on click.
	 */
	static SectionResult drawUsedIn(GuiGraphicsExtractor context, Font font, int px, int py, int availableWidth, int availableHeight,
			List<ItemStack> usedIn, int page, List<UsedInIcon> outIconRects, List<HoverSlot> outHoverSlots) {
		context.text(font, Component.literal("Used in:").withStyle(ChatFormatting.BOLD), px, py, COLOR_TEXT_DIM);
		py += 11;
		availableHeight -= 11;

		int perRow = Math.max(1, availableWidth / USED_IN_ICON);
		int pageSize = Math.min(MAX_USED_IN_SHOWN, usedIn.size());
		// Same idea as the recipe section's own height budgeting: cap how many
		// icon rows this page even attempts, so a short remaining space (e.g.
		// right under a recipe section that already claimed most of the detail
		// area) can't push icons - or this section's own "Page X/Y" line - past
		// the clip region instead of just showing fewer per page.
		int pageLinkHeight = USED_IN_ICON;
		int budget = usedIn.size() > pageSize ? Math.max(USED_IN_ICON, availableHeight - pageLinkHeight) : availableHeight;
		int maxRows = Math.max(1, budget / USED_IN_ICON);
		pageSize = Math.min(pageSize, maxRows * perRow);

		int totalPages = Math.max(1, (usedIn.size() + pageSize - 1) / pageSize);
		int currentPage = ((page % totalPages) + totalPages) % totalPages;
		int startIndex = currentPage * pageSize;
		int shown = Math.min(pageSize, usedIn.size() - startIndex);

		int startX = px;
		int x = px;
		int y = py;

		for (int i = 0; i < shown; i++) {
			if (i > 0 && i % perRow == 0) {
				x = startX;
				y += USED_IN_ICON;
			}

			ItemStack stack = usedIn.get(startIndex + i);
			context.fill(x, y, x + USED_IN_ICON - 2, y + USED_IN_ICON - 2, COLOR_SLOT);
			border(context, x, y, USED_IN_ICON - 2, USED_IN_ICON - 2, COLOR_SLOT_BORDER);
			context.item(stack, x + 1, y + 1);

			outHoverSlots.add(new HoverSlot(x, y, USED_IN_ICON - 2, stack, false));

			BookletEntry usedInEntry = BookletData.byItem(stack.getItem());
			if (usedInEntry != null) {
				outIconRects.add(new UsedInIcon(x, y, USED_IN_ICON - 2, usedInEntry.itemId()));
			}

			x += USED_IN_ICON;
		}

		y += USED_IN_ICON;
		ClickRect moreRect = null;
		if (totalPages > 1) {
			String label = "Page " + (currentPage + 1) + "/" + totalPages + " - click to see more";
			context.text(font, Component.literal(label).withStyle(ChatFormatting.UNDERLINE), px, y, COLOR_LINK);
			moreRect = new ClickRect(px, y, font.width(label), 10);
			y += 11;
		}

		return new SectionResult(y, totalPages, moreRect);
	}

	private static void border(GuiGraphicsExtractor context, int x, int y, int w, int h, int color) {
		context.fill(x, y, x + w, y + 1, color);
		context.fill(x, y + h - 1, x + w, y + h, color);
		context.fill(x, y, x + 1, y + h, color);
		context.fill(x + w - 1, y, x + w, y + h, color);
	}
}
