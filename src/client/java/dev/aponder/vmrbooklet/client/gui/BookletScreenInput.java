package dev.aponder.vmrbooklet.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.client.VMRBookletClient;
import dev.aponder.vmrbooklet.client.data.BookletCategories;
import dev.aponder.vmrbooklet.client.data.BookletFavorites;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * Mouse and keyboard handling for {@link BookletScreen}, kept apart from its
 * layout and rendering. Every handler reads and writes the screen's own
 * package-private state through {@code s}; the screen's overrides just
 * delegate here, and {@code s.superX(...)} reaches the vanilla widget handling.
 */
final class BookletScreenInput {

	// GLFW's Shift | Ctrl | Alt | Super bits; Caps/Num Lock bits are ignored.
	private static final int MODIFIER_MASK = 0x0F;

	private BookletScreenInput() {
	}

	private static BookletScreen.Overlay overlayForKey(int key) {
		return switch (key) {
			case InputConstants.KEY_H -> BookletScreen.Overlay.HELP;
			case InputConstants.KEY_S -> BookletScreen.Overlay.STATS;
			case InputConstants.KEY_R -> BookletScreen.Overlay.RECENT;
			case InputConstants.KEY_T -> BookletScreen.Overlay.TIMELINE;
			case InputConstants.KEY_N -> BookletScreen.Overlay.NEEDED;
			case InputConstants.KEY_F -> BookletScreen.Overlay.FRIENDS;
			default -> null;
		};
	}

	static boolean mouseClicked(BookletScreen s, MouseButtonEvent event, boolean doubled) {
		if (s.categoryDropdownOpen) {
			// Any click while the dropdown is open closes it - a hit on one of its
			// rows also applies that category; everything else is "click away to
			// dismiss", absorbed rather than falling through.
			BookletCategories.Category[] values = BookletCategories.Category.values();
			int dropdownBottomLimit = s.panelY + s.panelHeight - BookletScreen.FOOTER_HEIGHT;
			int index = BookletDropdowns.indexAt(s.categoryRowX, s.categoryRowY, s.categoryRowWidth, values.length + 1,
					dropdownBottomLimit, event.x(), event.y());
			if (index >= 0) {
				s.categoryFilter = index == 0 ? null : values[index - 1];
				// A dropdown pick doesn't go through the button's own click lambda,
				// so the button face has to be updated here.
				s.categoryButton.setMessage(s.categoryLabel());
				s.scrollRows = 0;
				s.refreshFiltered();
			}
			s.categoryDropdownOpen = false;
			return true;
		}

		if (s.showDropdownOpen) {
			BookletScreen.ShowFilter[] values = BookletScreen.ShowFilter.values();
			int dropdownBottomLimit = s.panelY + s.panelHeight - BookletScreen.FOOTER_HEIGHT;
			int index = BookletDropdowns.indexAt(s.showRowX, s.showRowY, s.showRowWidth, values.length,
					dropdownBottomLimit, event.x(), event.y());
			if (index >= 0) {
				s.showFilter = values[index];
				s.showFilterButton.setMessage(s.showFilter.label());
				s.scrollRows = 0;
				s.refreshFiltered();
			}
			s.showDropdownOpen = false;
			return true;
		}

		// Checked BEFORE super.mouseClicked(): the default handler finds the
		// row's Button at this position, which rejects anything but left-click,
		// and then returns true anyway - a right-click would be swallowed.
		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT
				&& event.x() >= s.categoryRowX && event.x() < s.categoryRowX + s.categoryRowWidth
				&& event.y() >= s.categoryRowY && event.y() < s.categoryRowY + 14) {
			s.categoryDropdownOpen = true;
			s.showDropdownOpen = false;
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT
				&& event.x() >= s.showRowX && event.x() < s.showRowX + s.showRowWidth
				&& event.y() >= s.showRowY && event.y() < s.showRowY + 14) {
			s.showDropdownOpen = true;
			s.categoryDropdownOpen = false;
			return true;
		}

		if (s.superMouseClicked(event, doubled)) {
			return true;
		}

		boolean inOverlayColumns = event.x() >= s.listX && event.x() < s.detailX + s.detailWidth;

		if (s.overlay == BookletScreen.Overlay.RECENT && inOverlayColumns) {
			for (BookletOverlays.RecentRowRect row : s.recentRowRects) {
				if (event.y() >= row.y() && event.y() < row.y() + row.height()) {
					s.revealFromOverlay(row.itemId());
					return true;
				}
			}
		}

		if (s.overlay == BookletScreen.Overlay.NEEDED && inOverlayColumns) {
			for (BookletNeededTreeView.Row row : s.neededRowRects) {
				if (event.y() >= row.y() && event.y() < row.y() + row.height()) {
					if (row.hasChildren()) {
						// A node with children is a step in the chain - toggle it rather
						// than navigating.
						if (!s.expandedNeededIds.remove(row.itemId())) {
							s.expandedNeededIds.add(row.itemId());
						}
					} else if (!row.reference()) {
						s.revealFromOverlay(row.itemId());
					}
					return true;
				}
			}
		}

		if (s.overlay == BookletScreen.Overlay.FRIENDS && inOverlayColumns) {
			for (BookletFriendCompareOverlay.Row row : s.friendCompareRowRects) {
				if (event.y() >= row.y() && event.y() < row.y() + row.height()) {
					s.revealFromOverlay(row.itemId());
					return true;
				}
			}
		}

		// An overlay covers the list/detail area - don't let a click land on a row
		// hidden underneath it. Tabs stay clickable (they're above it).
		if (s.anyOverlayOpen() && event.y() >= s.listY) {
			return true;
		}

		if (s.selected != null) {
			if (s.viewingPlayer == null) {
				int buttonY = s.amountButtonY();
				if (event.y() >= buttonY && event.y() < buttonY + BookletScreen.ACTION_BUTTON_SIZE) {
					int minusX = s.minusButtonX();
					int plusX = s.plusButtonX();
					if (event.x() >= minusX && event.x() < minusX + BookletScreen.ACTION_BUTTON_SIZE) {
						s.adjustSelectedAmount(-1);
						return true;
					}
					if (event.x() >= plusX && event.x() < plusX + BookletScreen.ACTION_BUTTON_SIZE) {
						s.adjustSelectedAmount(1);
						return true;
					}
				}
			}

			// Unlike the amount buttons, Copy ID and the favorite star are drawn (and
			// so must be hit-testable) regardless of which player's tab is viewed.
			int copyY = s.copyIdButtonY();
			int copyX = s.copyIdButtonX();
			if (event.x() >= copyX && event.x() < copyX + BookletScreen.ACTION_BUTTON_SIZE
					&& event.y() >= copyY && event.y() < copyY + BookletScreen.ACTION_BUTTON_SIZE) {
				s.copySelectedItemId();
				return true;
			}

			int favX = s.favoriteButtonX();
			int favY = s.favoriteButtonY();
			if (event.x() >= favX && event.x() < favX + BookletScreen.ACTION_BUTTON_SIZE
					&& event.y() >= favY && event.y() < favY + BookletScreen.ACTION_BUTTON_SIZE) {
				BookletFavorites.toggle(s.selected.itemId());
				return true;
			}

			if (!s.selectionHistory.isEmpty()) {
				int backX = s.backButtonX();
				int backY = s.backButtonY();
				if (event.x() >= backX && event.x() < backX + BookletScreen.ACTION_BUTTON_SIZE
						&& event.y() >= backY && event.y() < backY + BookletScreen.ACTION_BUTTON_SIZE) {
					s.navigateBack();
					return true;
				}
			}

			if (s.moreRecipesRect != null && event.y() >= s.detailBodyTopY && s.moreRecipesRect.contains((int) event.x(), (int) event.y())) {
				s.recipePage = (s.recipePage + 1) % Math.max(1, s.recipeTotalPages);
				return true;
			}
			if (s.moreUsedInRect != null && event.y() >= s.detailBodyTopY && s.moreUsedInRect.contains((int) event.x(), (int) event.y())) {
				s.usedInPage = (s.usedInPage + 1) % Math.max(1, s.usedInTotalPages);
				return true;
			}

			// An ingredient slot (not the output slot) narrows the main list to
			// "what else uses this". Guarded to below the fixed header so a slot the
			// detail scroll pushed up behind it can't be clicked through.
			for (BookletRecipePanel.HoverSlot slot : s.recipeHoverSlots) {
				if (!slot.isIngredient()) continue;
				if (event.x() >= slot.x() && event.x() < slot.x() + slot.size()
						&& event.y() >= Math.max(slot.y(), s.detailBodyTopY) && event.y() < slot.y() + slot.size()) {
					BookletEntry ingredientEntry = BookletData.byItem(slot.stack().getItem());
					if (ingredientEntry != null) {
						s.setIngredientFilter(ingredientEntry);
					}
					return true;
				}
			}
		}

		for (BookletRecipePanel.UsedInIcon icon : s.usedInIconRects) {
			if (event.x() >= icon.x() && event.x() < icon.x() + icon.size()
					&& event.y() >= Math.max(icon.y(), s.detailBodyTopY) && event.y() < icon.y() + icon.size()) {
				BookletEntry target = BookletData.byItemId(icon.itemId());
				if (target != null) {
					// Jumps straight to that item's details without touching
					// search/filter/scroll - the detail panel shows it either way.
					s.navigateTo(target);
				}
				return true;
			}
		}

		if (event.y() >= s.tabRowY && event.y() < s.tabRowY + BookletScreen.TAB_ROW_HEIGHT) {
			for (BookletScreen.Tab tab : s.computeTabs()) {
				if (event.x() >= tab.x() && event.x() < tab.x() + tab.width()) {
					s.selectTab(tab.key());
					return true;
				}
			}
		}

		int railX = s.letterRailX();
		if (event.x() >= railX && event.x() < railX + BookletScreen.LETTER_RAIL_WIDTH && event.y() >= s.listY && event.y() < s.listY + s.listHeight) {
			int row = s.letterRailRowAt((int) event.y());
			if (row >= 0) {
				s.jumpToLetter(BookletScreen.LETTER_RAIL_CHARS.charAt(row));
			}
			return true;
		}

		if (s.clearIngredientFilterRect != null && s.clearIngredientFilterRect.contains((int) event.x(), (int) event.y())) {
			s.clearIngredientFilter();
			return true;
		}

		int listContentY = s.listY + (s.ingredientFilterItemId != null ? BookletScreen.FILTER_BANNER_HEIGHT : 0);
		if (event.x() >= s.listX && event.x() < s.listX + s.listWidth && event.y() >= listContentY && event.y() < s.listY + s.listHeight) {
			int index = (int) (s.scrollRows + (event.y() - listContentY) / BookletScreen.ROW_HEIGHT);
			if (index >= 0 && index < s.filtered.size()) {
				// event.button() is already remapped to InputConstants.MOUSE_BUTTON_*
				// (1/2/3), not the raw 0-indexed GLFW button.
				if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
					// Toggles favorite without changing the current selection.
					BookletFavorites.toggle(s.filtered.get(index).itemId());
				} else {
					s.navigateTo(s.filtered.get(index));
				}
			}
			return true;
		}
		// Still inside the list box but on the filter banner itself (not its clear
		// button) - absorb the click.
		if (s.ingredientFilterItemId != null && event.x() >= s.listX && event.x() < s.listX + s.listWidth
				&& event.y() >= s.listY && event.y() < listContentY) {
			return true;
		}

		return false;
	}

	static boolean mouseScrolled(BookletScreen s, double mouseX, double mouseY, double scrollX, double scrollY) {
		if (s.anyOverlayOpen()) {
			// The open overlay set its own scroll bounds when it was last drawn.
			s.overlayScroll.scrollBy(scrollY);
			return true;
		}
		if (mouseX >= s.listX && mouseX < s.listX + s.listWidth && mouseY >= s.listY && mouseY < s.listY + s.listHeight) {
			int maxScroll = Math.max(0, s.filtered.size() - s.listContentHeight() / BookletScreen.ROW_HEIGHT);
			s.scrollRows = Math.max(0, Math.min(maxScroll, s.scrollRows - scrollY));
			return true;
		}
		if (s.selected != null
				&& mouseX >= s.detailX && mouseX < s.detailX + s.detailWidth
				&& mouseY >= s.detailY && mouseY < s.detailY + s.detailHeight) {
			// detailBodyTopY/detailContentHeight are from the last frame drawDetail()
			// ran - one frame stale, imperceptible.
			int visibleHeight = s.detailY + s.detailHeight - s.detailBodyTopY - 4;
			int maxScroll = Math.max(0, s.detailContentHeight - visibleHeight);
			s.detailScrollPixels = Math.max(0, Math.min(maxScroll, s.detailScrollPixels - scrollY * BookletScreen.DETAIL_SCROLL_STEP));
			return true;
		}
		return s.superMouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	static boolean keyPressed(BookletScreen s, KeyEvent event) {
		boolean overlayOpen = s.anyOverlayOpen();

		if (event.key() == InputConstants.KEY_ESCAPE) {
			if (overlayOpen || s.categoryDropdownOpen || s.showDropdownOpen) {
				s.closeAllOverlays();
			} else {
				s.onClose();
			}
			return true;
		}

		// Vanilla never delivers KeyMapping clicks while a screen is open, so the
		// open/close and search keys have to be matched here rather than in the
		// client tick handler.
		boolean searchFocused = s.searchBox != null && s.searchBox.isFocused();
		if (!searchFocused) {
			if (VMRBookletClient.openBookletKey().matches(event)) {
				s.onClose();
				return true;
			}
			boolean ctrlF = event.key() == InputConstants.KEY_F && event.hasControlDownWithQuirk();
			if (ctrlF || event.key() == InputConstants.KEY_SLASH || VMRBookletClient.focusSearchKey().matches(event)) {
				s.closeAllOverlays();
				s.focusSearch();
				// The same keystroke's character event arrives right after this one
				// and would otherwise be typed straight into the box we just focused.
				s.swallowCharUntilNanos = System.nanoTime() + BookletScreen.CHAR_SWALLOW_WINDOW_NANOS;
				return true;
			}
			// Single-letter overlay toggles, mirroring the header buttons. Only with
			// no Shift/Ctrl/Alt/Super held, so Ctrl+F above and any modified
			// shortcut never collide with them.
			if ((event.modifiers() & MODIFIER_MASK) == 0) {
				BookletScreen.Overlay hotkeyOverlay = overlayForKey(event.key());
				if (hotkeyOverlay != null) {
					s.toggleOverlay(hotkeyOverlay);
					return true;
				}
			}
		}

		// Checked ahead of the search box: EditBox consumes Home/End to move its
		// text cursor, so these would otherwise do nothing to the list while the
		// search box has focus.
		if (!overlayOpen && !s.filtered.isEmpty()) {
			if (event.key() == InputConstants.KEY_HOME) {
				s.selectAndReveal(0);
				return true;
			}
			if (event.key() == InputConstants.KEY_END) {
				s.selectAndReveal(s.filtered.size() - 1);
				return true;
			}
		}

		// Let the focused widget (the search box) see it first - e.g. Left/Right
		// for cursor movement. Up/Down/Enter aren't handled by EditBox, so they
		// fall through to the list navigation below even while typing a search.
		if (s.superKeyPressed(event)) {
			return true;
		}

		if (!overlayOpen) {
			if (event.key() == InputConstants.KEY_UP) {
				s.moveSelection(-1);
				return true;
			}
			if (event.key() == InputConstants.KEY_DOWN) {
				s.moveSelection(1);
				return true;
			}
			if (event.key() == InputConstants.KEY_PAGEUP) {
				s.moveSelection(-s.listRowsVisible());
				return true;
			}
			if (event.key() == InputConstants.KEY_PAGEDOWN) {
				s.moveSelection(s.listRowsVisible());
				return true;
			}
			if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
				if (s.selected == null && !s.filtered.isEmpty()) {
					s.selectAndReveal(0);
				}
				// Relinquishes the search box so a following Up/Down immediately
				// navigates the list instead of doing nothing inside the text field.
				if (s.searchBox.isFocused()) {
					s.searchBox.setFocused(false);
				}
				return true;
			}
		}

		return false;
	}
}
