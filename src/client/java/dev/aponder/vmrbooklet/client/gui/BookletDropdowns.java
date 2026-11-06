package dev.aponder.vmrbooklet.client.gui;

import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * One generic right-click-opened dropdown, shared by {@link BookletScreen}'s
 * Category and Show filter rows - the two used to be near-identical copies
 * differing only in how many entries they had and how each entry's label/
 * selected-state was decided, which this takes as a label function and a
 * selected-predicate instead.
 */
final class BookletDropdowns {

	private static final int ROW_HEIGHT = 12;

	private BookletDropdowns() {
	}

	/** How tall the dropdown's own panel is, clamped so it never draws past {@code bottomLimit}. */
	private static int height(int entryCount, int bottomLimit, int dropdownY) {
		return Math.min(entryCount * ROW_HEIGHT, Math.max(ROW_HEIGHT, bottomLimit - dropdownY));
	}

	/**
	 * Draws every entry (0..entryCount-1) as one row each, right under
	 * {@code (x, rowY)}. {@code isSelected}/{@code label} are given the entry
	 * index, not a particular enum - the caller decides what index 0 means
	 * (the Category dropdown reserves it for "All").
	 */
	static void draw(GuiGraphicsExtractor context, Font font, int x, int rowY, int width, int entryCount,
			int bottomLimit, int mouseX, int mouseY, IntPredicate isSelected, IntFunction<Component> label) {
		int dropdownY = rowY + 14 + 1;
		int dropdownHeight = height(entryCount, bottomLimit, dropdownY);

		context.fill(x, dropdownY, x + width, dropdownY + dropdownHeight, BookletUi.COLOR_PANEL);
		BookletUi.border(context, x, dropdownY, width, dropdownHeight);
		context.enableScissor(x + 1, dropdownY + 1, x + width - 1, dropdownY + dropdownHeight - 1);

		for (int i = 0; i < entryCount; i++) {
			int entryY = dropdownY + i * ROW_HEIGHT;
			if (entryY >= dropdownY + dropdownHeight) break;

			boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= entryY && mouseY < entryY + ROW_HEIGHT;
			if (isSelected.test(i)) {
				context.fill(x + 1, entryY, x + width - 1, entryY + ROW_HEIGHT, BookletUi.COLOR_ROW_SELECTED);
			} else if (hovered) {
				context.fill(x + 1, entryY, x + width - 1, entryY + ROW_HEIGHT, BookletUi.COLOR_ROW_HOVER);
			}
			context.text(font, label.apply(i), x + 4, entryY + 2, BookletUi.COLOR_TEXT);
		}

		context.disableScissor();
	}

	/** @return the entry index a click at {@code (clickX, clickY)} landed on, or -1 if it missed the dropdown entirely. */
	static int indexAt(int x, int rowY, int width, int entryCount, int bottomLimit, double clickX, double clickY) {
		int dropdownY = rowY + 14 + 1;
		int dropdownHeight = height(entryCount, bottomLimit, dropdownY);

		if (clickX < x || clickX >= x + width || clickY < dropdownY || clickY >= dropdownY + dropdownHeight) {
			return -1;
		}
		int index = (int) ((clickY - dropdownY) / ROW_HEIGHT);
		return index >= 0 && index < entryCount ? index : -1;
	}
}
