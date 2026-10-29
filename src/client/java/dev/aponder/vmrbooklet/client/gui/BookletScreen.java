package dev.aponder.vmrbooklet.client.gui;

import dev.aponder.vmrbooklet.BookletClassifier;
import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.BookletSort;
import dev.aponder.vmrbooklet.BookletState;
import dev.aponder.vmrbooklet.client.BookletAcquisitionNotes;
import dev.aponder.vmrbooklet.client.BookletActivityLog;
import dev.aponder.vmrbooklet.client.BookletAutoPush;
import dev.aponder.vmrbooklet.client.BookletFavorites;
import dev.aponder.vmrbooklet.client.BookletFriends;
import dev.aponder.vmrbooklet.client.BookletLocalProgress;
import dev.aponder.vmrbooklet.client.BookletRecipeLookup;
import dev.aponder.vmrbooklet.client.BookletSettings;
import dev.aponder.vmrbooklet.client.GoogleServiceAccount;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A card-style, list-and-detail browser over every booklet item: live search,
 * a completion filter, the ECC/Creative sort, a row of clickable player tabs
 * (you + any imported friends), a scrollable list on the left (each row with
 * its own mini progress bar) and a stat panel on the right. Progress normally
 * comes from {@code /upload}, the live scanner reading the real plugin's own
 * GUI, or {@code /sell} - the only in-GUI edit is the +/- next to "Amount:" on
 * your own progress (never on a friend's read-only tab), for manually
 * correcting whatever those automatic sources missed or got wrong.
 */
public class BookletScreen extends Screen {

	private enum ShowFilter {
		ALL, COMPLETED, PARTIAL, UNDISCOVERED, FAVORITES;

		ShowFilter next() {
			return values()[(ordinal() + 1) % values().length];
		}

		String label() {
			return switch (this) {
				case ALL -> "Show: All";
				case COMPLETED -> "Show: Completed";
				case PARTIAL -> "Show: Partial";
				case UNDISCOVERED -> "Show: Undiscovered";
				case FAVORITES -> "Show: Favorites";
			};
		}

		boolean matches(BookletState state, boolean favorite) {
			return switch (this) {
				case ALL -> true;
				case COMPLETED -> state == BookletState.ACHIEVED;
				case PARTIAL -> state == BookletState.IN_PROGRESS;
				case UNDISCOVERED -> state == BookletState.UNDISCOVERED;
				case FAVORITES -> favorite;
			};
		}
	}

	/** One clickable tab in the player row: {@code key == null} means "You" (your own local progress). */
	private record Tab(String key, String label, int x, int width) {
	}

	// Palette - a warmer, slightly more saturated navy/indigo base (rather than
	// flat neutral gray) with a vivid blue for selection/active state and gold
	// for the primary accent, so the UI reads as "a place with color" instead
	// of one uniform dark-gray tone throughout.
	private static final int COLOR_BACKDROP = 0xC0000000;
	private static final int COLOR_PANEL = 0xFF21212E;
	private static final int COLOR_HEADER = 0xFF191A26;
	private static final int COLOR_BORDER = 0xFF494E68;
	private static final int COLOR_LIST_BG = 0xFF1A1B27;
	private static final int COLOR_DETAIL_BG = 0xFF1A1B27;
	private static final int COLOR_ROW_HOVER = 0xFF2E3148;
	private static final int COLOR_ROW_SELECTED = 0xFF3E72B8;
	private static final int COLOR_TAB_ACTIVE = 0xFF3E72B8;
	private static final int COLOR_TAB_INACTIVE = 0xFF272A3C;
	private static final int COLOR_ACCENT = 0xFFFFD36B;
	private static final int COLOR_TEXT = 0xFFEDEDF4;
	private static final int COLOR_TEXT_DIM = 0xFFA4A9C2;
	private static final int COLOR_BAR_TRACK = 0xFF2D2F42;
	// A visibly lighter, higher-contrast palette just for the recipe panel - the
	// rest of the UI's near-black tones made the grid lines all but invisible.
	private static final int COLOR_RECIPE_PANEL = 0xFF403C4A;
	private static final int COLOR_RECIPE_PANEL_BORDER = 0xFF5D5868;
	private static final int COLOR_RECIPE_SLOT = 0xFF29262F;
	private static final int COLOR_RECIPE_SLOT_BORDER = 0xFF938CA0;

	private static final SystemToast.SystemToastId COPY_ID_TOAST_ID = new SystemToast.SystemToastId();

	private static final int ROW_HEIGHT = 22;
	private static final int HEADER_HEIGHT = 34;
	private static final int TAB_ROW_HEIGHT = 16;
	private static final int TOOLBAR_HEIGHT = 20;
	private static final int FOOTER_HEIGHT = 22;

	// Remembered across re-opens for the rest of the game session.
	private static ShowFilter sessionShowFilter = ShowFilter.ALL;
	private static BookletSort sessionSort = BookletSort.ECC;
	private static String sessionSearch = "";
	private static double sessionScrollRows = 0;
	private static BookletEntry sessionSelected;
	private static String sessionViewingPlayer = null; // null = your own progress

	private ShowFilter showFilter = sessionShowFilter;
	private BookletSort sort = sessionSort;
	private String search = sessionSearch;
	private String viewingPlayer = sessionViewingPlayer;

	private EditBox searchBox;

	private List<BookletEntry> filtered = List.of();
	private BookletEntry selected = sessionSelected;
	private double scrollRows = sessionScrollRows;
	private boolean showHelp = !BookletSettings.hasSeenOnboarding();
	private boolean showStats = false;
	private double statsScrollRows = 0;
	private boolean showRecent = false;
	private final List<RecentRowRect> recentRowRects = new ArrayList<>();

	private record RecentRowRect(int y, int height, String itemId) {
	}

	{
		// Marked as soon as the auto-shown overlay is decided, not only on an
		// explicit "?" click - otherwise closing the screen via Escape/"Done"
		// without touching "?" would show the overlay again on every future open.
		if (showHelp) {
			BookletSettings.markOnboardingSeen();
		}
	}

	private int panelX, panelY, panelWidth, panelHeight;
	private int tabRowY;
	private int listX, listY, listWidth, listHeight;
	private int detailX, detailY, detailWidth, detailHeight;

	// The recipe lookup walks the whole client recipe book - cached per selected
	// item (not recomputed every frame) and only looked up at all once something
	// is actually selected. A list, not a single recipe: some items have more
	// than one legitimate crafting recipe.
	private BookletEntry recipeLookedUpFor;
	private List<BookletRecipeLookup.Recipe> cachedRecipes = List.of();
	// What this item is an ingredient of - same per-selection caching idea.
	private List<ItemStack> cachedUsedIn = List.of();
	// Screen-space rects of the "used in" icons drawn this frame (by
	// BookletRecipePanel.drawUsedIn), so mouseClicked() can hit-test them without
	// redoing the whole layout computation itself.
	private List<BookletRecipePanel.UsedInIcon> usedInIconRects = List.of();
	// Screen-space rects (and the ItemStack each one is) for every ingredient
	// slot, recipe output slot, and "Used in" icon drawn this frame - checked
	// against the mouse each frame to show a name tooltip on hover, the same
	// way vanilla's own recipe book does for its ingredient/output slots.
	private List<BookletRecipePanel.HoverSlot> recipeHoverSlots = List.of();
	// Which page of recipes/"used in" icons is showing for the current
	// selection, reset to 0 whenever the selection changes - see
	// BookletRecipePanel.SectionResult for how a click on the "Page X/Y" line
	// advances these.
	private int recipePage;
	private int usedInPage;
	private BookletRecipePanel.ClickRect moreRecipesRect;
	private BookletRecipePanel.ClickRect moreUsedInRect;
	private int recipeTotalPages = 1;
	private int usedInTotalPages = 1;

	// Browser-style back history: every navigateTo() pushes the item you were
	// looking at before, so following a "Used in" icon (or an ingredient-filter
	// row) deep into the booklet can be undone one step at a time instead of
	// only by re-searching from scratch.
	private final java.util.ArrayDeque<BookletEntry> selectionHistory = new java.util.ArrayDeque<>();
	private static final int MAX_SELECTION_HISTORY = 50;

	// Set by clicking an ingredient slot in a recipe panel - narrows the main
	// list to only items that use that ingredient, same data as the "Used in:"
	// section but applied to the whole list instead of just shown as icons.
	private String ingredientFilterItemId;
	private String ingredientFilterLabel;
	private java.util.Set<String> ingredientFilterMatchIds = java.util.Set.of();
	private static final int FILTER_BANNER_HEIGHT = 14;
	private BookletRecipePanel.ClickRect clearIngredientFilterRect;

	// Which row (if any) is highlighted because it matches whatever recipe
	// ingredient/output/"Used in" icon the mouse is currently hovering - lets
	// you see at a glance whether you already have that item, straight from the
	// recipe panel rather than having to search for it.
	private BookletEntry hoveredIngredientEntry;

	private static final int LETTER_RAIL_WIDTH = 10;

	// The exact y drawDetail() placed the "Amount:" line at, this frame - read by
	// amountButtonY() instead of a hand-maintained offset constant, which had
	// drifted out of sync with the real layout and put the buttons a full text
	// line above where they belonged.
	private int amountLineY;
	// Same idea for the "Type:" line's Copy ID button.
	private int typeLineY;

	private static BookletScreen current;

	public BookletScreen() {
		super(Component.literal("VMR Booklet"));
		current = this;
	}

	public static BookletScreen current() {
		return current;
	}

	public void onProgressUpdated() {
		refreshFiltered();
	}

	@Override
	public void onClose() {
		if (current == this) {
			current = null;
		}
		sessionShowFilter = showFilter;
		sessionSort = sort;
		sessionSearch = search;
		sessionScrollRows = scrollRows;
		sessionSelected = selected;
		sessionViewingPlayer = viewingPlayer;
		super.onClose();
	}

	@Override
	protected void init() {
		// Clamped to a sane minimum - at very small window sizes, width/height - 32
		// could otherwise go negative and cascade into negative list/detail widths.
		// 300 (not just "non-negative") because the toolbar's two buttons are laid
		// out at fixed offsets from the right edge (innerWidth - 204 / - 100) with a
		// search box filling the rest (innerWidth - 212) - below ~300 that search box
		// goes negative-width and the buttons overlap it instead of sitting past it.
		panelWidth = Math.max(300, Math.min(width - 32, 760));
		panelHeight = Math.max(160, Math.min(height - 32, 440));
		panelX = (width - panelWidth) / 2;
		panelY = (height - panelHeight) / 2;

		int innerX = panelX + 8;
		int innerWidth = panelWidth - 16;

		tabRowY = panelY + HEADER_HEIGHT + 2;
		int toolbarY = tabRowY + TAB_ROW_HEIGHT + 2;

		searchBox = new EditBox(this.font, innerX, toolbarY, innerWidth - 212, 14, Component.literal("Search"));
		searchBox.setMaxLength(48);
		searchBox.setValue(search);
		searchBox.setResponder(value -> {
			search = value.trim().toLowerCase(Locale.ROOT);
			refreshFiltered();
		});
		addRenderableWidget(searchBox);

		addRenderableWidget(Button.builder(Component.literal(showFilter.label()), b -> {
			showFilter = showFilter.next();
			b.setMessage(Component.literal(showFilter.label()));
			scrollRows = 0;
			refreshFiltered();
		}).bounds(innerX + innerWidth - 204, toolbarY, 100, 14).build());

		addRenderableWidget(Button.builder(Component.literal("Sort: " + sort.name()), b -> {
			sort = sort.other();
			b.setMessage(Component.literal("Sort: " + sort.name()));
			scrollRows = 0;
			refreshFiltered();
		}).bounds(innerX + innerWidth - 100, toolbarY, 100, 14).build());

		int footerY = panelY + panelHeight - FOOTER_HEIGHT + 2;

		addRenderableWidget(Button.builder(Component.literal("Next unfinished"), b -> jumpToNextUnfinished())
				.bounds(innerX, footerY, 110, 14)
				.build());

		addRenderableWidget(Button.builder(Component.literal(notifyLabel()), b -> {
			BookletSettings.setScanNotifications(!BookletSettings.scanNotifications());
			b.setMessage(Component.literal(notifyLabel()));
		}).bounds(innerX + innerWidth - 110, footerY, 110, 14).build());

		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
				.bounds(panelX + panelWidth / 2 - 40, footerY, 80, 14)
				.build());

		int helpButtonX = panelX + 8 + this.font.width("VMR Booklet") + 10;
		addRenderableWidget(Button.builder(Component.literal("?"), b -> {
			showHelp = !showHelp;
			showStats = false;
			showRecent = false;
			BookletSettings.markOnboardingSeen();
		}).bounds(helpButtonX, panelY + 6, 14, 14).build());

		addRenderableWidget(Button.builder(Component.literal("%"), b -> {
			showStats = !showStats;
			showHelp = false;
			showRecent = false;
		}).bounds(helpButtonX + 16, panelY + 6, 14, 14).build());

		addRenderableWidget(Button.builder(Component.literal("R"), b -> {
			showRecent = !showRecent;
			showHelp = false;
			showStats = false;
		}).bounds(helpButtonX + 32, panelY + 6, 14, 14).build());

		listX = innerX;
		listY = toolbarY + TOOLBAR_HEIGHT + 2;
		// Reserves a thin strip on the right of the list for the A-Z jump rail -
		// carved out of the list's own width (not the gap to the detail panel)
		// so it never overlaps the count text/progress bar each row already
		// right-aligns within listWidth.
		listWidth = (int) (innerWidth * 0.42) - LETTER_RAIL_WIDTH;
		listHeight = panelY + panelHeight - FOOTER_HEIGHT - listY - 2;

		detailX = listX + listWidth + LETTER_RAIL_WIDTH + 8;
		detailY = listY;
		detailWidth = innerX + innerWidth - detailX;
		detailHeight = listHeight;

		setInitialFocus(searchBox);
		refreshFiltered();
	}

	private List<Tab> computeTabs() {
		List<Tab> tabs = new ArrayList<>();
		int x = panelX + 8;

		int youWidth = this.font.width("You") + 10;
		tabs.add(new Tab(null, "You", x, youWidth));
		x += youWidth + 2;

		for (String name : BookletFriends.names()) {
			// Short form ("5m", not "(5 minutes ago)") - these tabs have no wrap or
			// horizontal scroll, so with several friends every extra character here
			// pushes later tabs further off the edge of the panel.
			String label = name + " ·" + formatAgoShort(BookletFriends.importedAtMillis(name));
			int w = this.font.width(label) + 10;
			tabs.add(new Tab(name, label, x, w));
			x += w + 2;
		}
		return tabs;
	}

	private String formatSyncedAgo() {
		return formatAgo(BookletSettings.lastSyncedMillis());
	}

	/** Relative age (e.g. "3h ago", "2d ago") of a stored timestamp, or "never" if it's unset. */
	private static String formatAgo(long last) {
		if (last <= 0) {
			return "never";
		}

		long seconds = Math.max(0, (System.currentTimeMillis() - last) / 1000);
		if (seconds < 60) return seconds + "s ago";
		long minutes = seconds / 60;
		if (minutes < 60) return minutes + "m ago";
		long hours = minutes / 60;
		if (hours < 24) return hours + "h ago";
		return (hours / 24) + "d ago";
	}

	/** Compact form of {@link #formatAgo} for the tab row, e.g. "5m", "3h", "2d". */
	private static String formatAgoShort(long last) {
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

	private String notifyLabel() {
		return "Notify: " + (BookletSettings.scanNotifications() ? "On" : "Off");
	}

	/** Routes every progress read through whichever player tab is currently active. */
	private int obtainedFor(BookletEntry entry) {
		if (viewingPlayer == null) {
			return BookletLocalProgress.obtained(entry.itemId());
		}
		return BookletFriends.progressOf(viewingPlayer).getOrDefault(entry.itemId(), 0);
	}

	private void jumpToNextUnfinished() {
		if (filtered.isEmpty()) {
			return;
		}

		int startIndex = selected == null ? -1 : filtered.indexOf(selected);
		for (int offset = 1; offset <= filtered.size(); offset++) {
			int index = (startIndex + offset) % filtered.size();
			BookletEntry entry = filtered.get(index);
			int obtained = obtainedFor(entry);
			if (BookletClassifier.classify(entry, obtained) != BookletState.ACHIEVED) {
				selectAndReveal(index);
				return;
			}
		}
	}

	/** Selects filtered.get(index) and, if it's scrolled out of view, scrolls just enough to bring it back in. */
	private void selectAndReveal(int index) {
		navigateTo(filtered.get(index));
		int rowsVisible = Math.max(1, listHeight / ROW_HEIGHT);
		if (index < scrollRows || index >= scrollRows + rowsVisible) {
			// Clamped the same way refreshFiltered() clamps scrollRows - without this,
			// landing near the end of a long list (End, PageDown, "Next unfinished")
			// could scroll past the real max, leaving blank rows under the last item.
			int maxScroll = Math.max(0, filtered.size() - rowsVisible);
			scrollRows = Math.max(0, Math.min(maxScroll, index - rowsVisible / 2));
		}
	}

	/** Selects a new item, pushing whatever was selected before onto the back-history stack first. */
	private void navigateTo(BookletEntry entry) {
		if (selected != null && selected != entry) {
			selectionHistory.push(selected);
			while (selectionHistory.size() > MAX_SELECTION_HISTORY) {
				selectionHistory.removeLast();
			}
		}
		selected = entry;
	}

	/** Pops the most recent entry off the back-history stack, if any, and selects it. */
	private void navigateBack() {
		if (!selectionHistory.isEmpty()) {
			selected = selectionHistory.pop();
		}
	}

	/** Arrow-key list navigation: moves the selection by delta rows within the filtered list, clamped (no wrap). */
	private void moveSelection(int delta) {
		if (filtered.isEmpty()) {
			return;
		}

		int current = selected == null ? -1 : filtered.indexOf(selected);
		int next;
		if (current < 0) {
			next = delta > 0 ? 0 : filtered.size() - 1;
		} else {
			next = Math.max(0, Math.min(filtered.size() - 1, current + delta));
		}
		selectAndReveal(next);
	}

	private void refreshFiltered() {
		if (viewingPlayer != null && !BookletFriends.names().contains(viewingPlayer)) {
			viewingPlayer = null;
		}

		List<BookletEntry> ordered = BookletData.orderedBy(sort);
		List<BookletEntry> result = new ArrayList<>();

		for (BookletEntry entry : ordered) {
			if (!search.isEmpty() && !entry.displayName().toLowerCase(Locale.ROOT).replace('_', ' ').contains(search)) {
				continue;
			}

			int obtained = obtainedFor(entry);
			BookletState state = BookletClassifier.classify(entry, obtained);

			if (!showFilter.matches(state, BookletFavorites.isFavorite(entry.itemId()))) continue;

			if (ingredientFilterItemId != null && !ingredientFilterMatchIds.contains(entry.itemId())) continue;

			result.add(entry);
		}

		filtered = result;
		int maxScroll = Math.max(0, filtered.size() - listHeight / ROW_HEIGHT);
		scrollRows = Math.max(0, Math.min(scrollRows, maxScroll));

		if (selected != null && !filtered.contains(selected)) {
			selected = null;
		}
	}

	/** Narrows the main list to only items whose recipe uses this ingredient - set by clicking an ingredient slot in a recipe panel. */
	private void setIngredientFilter(BookletEntry ingredient) {
		ingredientFilterItemId = ingredient.itemId();
		ingredientFilterLabel = ingredient.displayName().replace('_', ' ');
		List<ItemStack> usedIn = BookletRecipeLookup.findUsedIn(ingredient.item(), ingredient.itemId());
		java.util.Set<String> ids = new java.util.HashSet<>();
		for (ItemStack stack : usedIn) {
			BookletEntry entry = BookletData.byItem(stack.getItem());
			if (entry != null) {
				ids.add(entry.itemId());
			}
		}
		ingredientFilterMatchIds = ids;
		scrollRows = 0;
		refreshFiltered();
	}

	private void clearIngredientFilter() {
		ingredientFilterItemId = null;
		ingredientFilterLabel = null;
		ingredientFilterMatchIds = java.util.Set.of();
		refreshFiltered();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		// Fully opaque backdrop so nothing of the world/other players shows through.
		context.fill(0, 0, width, height, COLOR_BACKDROP);
		context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, COLOR_PANEL);
		border(context, panelX, panelY, panelWidth, panelHeight);

		// Header
		context.fill(panelX + 1, panelY + 1, panelX + panelWidth - 1, panelY + HEADER_HEIGHT, COLOR_HEADER);
		context.text(this.font, Component.literal("VMR Booklet").withStyle(ChatFormatting.BOLD),
				panelX + 8, panelY + 7, COLOR_ACCENT);

		int completedCount = 0;
		for (BookletEntry entry : BookletData.orderedBy(BookletSort.ECC)) {
			if (obtainedFor(entry) >= entry.required()) {
				completedCount++;
			}
		}
		Component counter = Component.literal(completedCount + " / " + BookletData.size() + " collected");
		context.text(this.font, counter, panelX + panelWidth - 8 - this.font.width(counter), panelY + 7, COLOR_TEXT_DIM);

		int barX = panelX + 8;
		int barY = panelY + 20;
		int barWidth = panelWidth - 16;
		float fraction = BookletData.size() == 0 ? 0f : completedCount / (float) BookletData.size();
		drawBar(context, barX, barY, barWidth, 5, fraction, COLOR_ACCENT);

		Component synced = Component.literal("Last synced: " + formatSyncedAgo()
				+ "  |  Sheet sync: " + (GoogleServiceAccount.isConfigured() ? "On" : "Off"));
		context.text(this.font, synced, panelX + panelWidth - 8 - this.font.width(synced), panelY + 26, COLOR_TEXT_DIM);

		// Session-only, so it's quietly absent until you've actually completed
		// something since launching the game - not worth a permanent "0" sitting
		// in the header every time you open this.
		int sessionCount = BookletLocalProgress.sessionObtainedCount();
		if (sessionCount > 0) {
			context.text(this.font, Component.literal(sessionCount + " obtained this session"),
					panelX + 8, panelY + 26, COLOR_TEXT_DIM);
		}

		// Player tabs - clipped to the panel: with several friends (easily reached
		// in a multiplayer context) the un-wrapped tab row can run past the right
		// edge, and clipping keeps that "some tabs unreachable past the edge"
		// instead of "tabs rendering outside the panel entirely".
		context.enableScissor(panelX + 1, tabRowY, panelX + panelWidth - 1, tabRowY + TAB_ROW_HEIGHT);
		List<Tab> tabs = computeTabs();
		for (Tab tab : tabs) {
			boolean active = (tab.key() == null) == (viewingPlayer == null) && (tab.key() == null || tab.key().equals(viewingPlayer));
			int tabColor = active ? COLOR_TAB_ACTIVE : COLOR_TAB_INACTIVE;
			context.fill(tab.x(), tabRowY, tab.x() + tab.width(), tabRowY + TAB_ROW_HEIGHT, tabColor);
			border(context, tab.x(), tabRowY, tab.width(), TAB_ROW_HEIGHT);
			context.text(this.font, Component.literal(tab.label()), tab.x() + 5, tabRowY + 4,
					active ? 0xFFFFFFFF : COLOR_TEXT_DIM);
		}
		context.disableScissor();

		// List + detail panels
		context.fill(listX, listY, listX + listWidth, listY + listHeight, COLOR_LIST_BG);
		border(context, listX, listY, listWidth, listHeight);
		context.fill(detailX, detailY, detailX + detailWidth, detailY + detailHeight, COLOR_DETAIL_BG);
		border(context, detailX, detailY, detailWidth, detailHeight);

		// Computed from LAST frame's recipeHoverSlots (this frame's copy isn't
		// known until drawDetail() runs further down) - one frame of lag on a
		// pure highlight is imperceptible, and it lets the list rows below use
		// it without restructuring draw order around the detail panel.
		hoveredIngredientEntry = null;
		if (!showHelp && !showStats && !showRecent) {
			for (BookletRecipePanel.HoverSlot slot : recipeHoverSlots) {
				if (mouseX >= slot.x() && mouseX < slot.x() + slot.size()
						&& mouseY >= slot.y() && mouseY < slot.y() + slot.size()) {
					hoveredIngredientEntry = BookletData.byItem(slot.stack().getItem());
					break;
				}
			}
		}

		int listContentY = listY;
		int listContentHeight = listHeight;
		clearIngredientFilterRect = null;
		if (ingredientFilterItemId != null) {
			context.fill(listX, listY, listX + listWidth, listY + FILTER_BANNER_HEIGHT, COLOR_ROW_SELECTED);
			String bannerText = "Uses: " + ingredientFilterLabel;
			context.text(this.font, Component.literal(bannerText).withStyle(ChatFormatting.BOLD),
					listX + 3, listY + 3, COLOR_TEXT);
			String clearText = "✕";
			int clearX = listX + listWidth - 3 - this.font.width(clearText);
			context.text(this.font, Component.literal(clearText).withStyle(ChatFormatting.BOLD), clearX, listY + 3, COLOR_TEXT);
			clearIngredientFilterRect = new BookletRecipePanel.ClickRect(clearX - 2, listY, listX + listWidth - (clearX - 2), FILTER_BANNER_HEIGHT);
			listContentY += FILTER_BANNER_HEIGHT;
			listContentHeight -= FILTER_BANNER_HEIGHT;
		}

		context.enableScissor(listX + 1, listContentY + 1, listX + listWidth - 1, listContentY + listContentHeight - 1);
		int rowsVisible = listContentHeight / ROW_HEIGHT + 2;
		int firstRow = (int) scrollRows;
		for (int i = 0; i < rowsVisible; i++) {
			int index = firstRow + i;
			if (index < 0 || index >= filtered.size()) continue;

			BookletEntry entry = filtered.get(index);
			int rowY = listContentY + (int) ((index - scrollRows) * ROW_HEIGHT);
			if (rowY + ROW_HEIGHT < listContentY || rowY > listContentY + listContentHeight) continue;

			boolean hovered = mouseX >= listX && mouseX < listX + listWidth && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
			boolean isSelected = entry == selected;
			if (isSelected) {
				context.fill(listX + 1, rowY, listX + listWidth - 1, rowY + ROW_HEIGHT, COLOR_ROW_SELECTED);
			} else if (hovered) {
				context.fill(listX + 1, rowY, listX + listWidth - 1, rowY + ROW_HEIGHT, COLOR_ROW_HOVER);
			}
			if (entry == hoveredIngredientEntry) {
				// A bright left-edge accent bar, layered on top of whatever fill
				// already happened above - visible regardless of selected/hover
				// state, so you can tell at a glance (from the recipe panel) whether
				// you already have an ingredient without losing your place in it.
				context.fill(listX + 1, rowY, listX + 4, rowY + ROW_HEIGHT, COLOR_ACCENT);
			}

			int obtained = obtainedFor(entry);
			BookletState state = BookletClassifier.classify(entry, obtained);

			context.item(new ItemStack(entry.item(), 1), listX + 3, rowY + 2);

			ChatFormatting color = switch (state) {
				case ACHIEVED -> ChatFormatting.GREEN;
				case IN_PROGRESS -> ChatFormatting.YELLOW;
				case UNDISCOVERED -> ChatFormatting.GRAY;
			};

			String rowName = (BookletFavorites.isFavorite(entry.itemId()) ? "★ " : "") + entry.displayName().replace('_', ' ');
			context.text(this.font, Component.literal(rowName).withStyle(color), listX + 24, rowY + 1, COLOR_TEXT);

			String countText = obtained + "/" + entry.required();
			int countWidth = this.font.width(countText);
			int rowBarRight = listX + listWidth - 4 - countWidth - 4;
			int rowBarLeft = listX + 24;
			float rowFraction = entry.required() == 0 ? 0f : Math.min(1f, obtained / (float) entry.required());
			int rowBarColor = state == BookletState.ACHIEVED ? 0xFF4CAF50 : state == BookletState.IN_PROGRESS ? 0xFFD4B13A : 0xFF44444E;
			drawBar(context, rowBarLeft, rowY + 13, Math.max(10, rowBarRight - rowBarLeft), 4, rowFraction, rowBarColor);
			context.text(this.font, Component.literal(countText), listX + listWidth - 4 - countWidth, rowY + 12, COLOR_TEXT_DIM);
		}
		context.disableScissor();

		if (filtered.isEmpty()) {
			context.text(this.font, Component.literal("No items match.").withStyle(ChatFormatting.GRAY),
					listX + 8, listContentY + 8, COLOR_TEXT_DIM);
		}

		drawLetterRail(context, mouseX, mouseY);

		if (selected != null) {
			// Unlike the list, the help overlay and the tab row, this panel's
			// content was never clipped to its own bounds - an item with multiple
			// recipes plus a long "Used in" list can genuinely run past the bottom
			// border at small window sizes, spilling into whatever's below the panel.
			context.enableScissor(detailX + 1, detailY + 1, detailX + detailWidth - 1, detailY + detailHeight - 1);
			drawDetail(context, selected);
			context.disableScissor();
		} else {
			// Otherwise drawDetail() (which repopulates this) never runs, and clicks
			// in this now-blank area would still hit-test against icon rects from
			// whatever was selected last, silently jumping to an unrelated item.
			usedInIconRects = List.of();
			recipeHoverSlots = List.of();
			moreRecipesRect = null;
			moreUsedInRect = null;
			context.text(this.font, Component.literal("Select an item to see details").withStyle(ChatFormatting.GRAY),
					detailX + 8, detailY + 8, COLOR_TEXT_DIM);
		}

		// Checked after the scissor above is disabled, and before any overlay that
		// might be covering the detail panel right now (an overlay click-through
		// would otherwise still happen to land on a slot and pop its tooltip).
		if (!showHelp && !showStats && !showRecent) {
			for (BookletRecipePanel.HoverSlot slot : recipeHoverSlots) {
				if (mouseX >= slot.x() && mouseX < slot.x() + slot.size()
						&& mouseY >= slot.y() && mouseY < slot.y() + slot.size()) {
					context.setTooltipForNextFrame(this.font, slot.stack(), mouseX, mouseY);
					break;
				}
			}
		}

		if (showHelp) {
			drawHelpOverlay(context);
		}
		if (showStats) {
			drawStatsOverlay(context);
		}
		if (showRecent) {
			drawRecentOverlay(context);
		}

		super.extractRenderState(context, mouseX, mouseY, delta);
	}

	private static final String[] HELP_LINES = {
			"/vbooklet - open or close this screen (same as the B key)",
			"/upload <google-sheet-link> - track progress from your own sheet's \"Obtained\" column",
			"/upload - re-sync using whichever link you last used",
			"/vbooklet undo - revert the last /upload or /vbooklet restore",
			"/vbooklet backup - copy a code of YOUR progress (no Sheets needed)",
			"/vbooklet restore <code> - restore your progress from a backup code",
			"/export booklet - copy a code of your progress to share with a friend",
			"/import <code> - add a friend's code as a read-only tab above the list",
			"/vbooklet export-csv - write your full progress to a local CSV file",
			"/vbooklet unknown - list booklet items this mod doesn't recognize yet (page through /booklet first)",
			"/vbooklet missing - copy a list of everything you haven't obtained yet to the clipboard",
			"B key - toggle this screen",
			"Click the star on an item to favorite it, or right-click a row to favorite it directly",
			"Show: Favorites filters the list to just your favorites",
			"The % button shows completion by release and a leaderboard vs your imported friends",
			"The R button shows items you've recently completed",
			"Up/Down arrows move the list selection, Enter jumps to the first result",
			"Page Up/Page Down jump a full panel of items, Home/End jump to the top/bottom of the list",
			"Click a letter on the right edge of the list to jump to it",
			"Click a recipe's ingredient to filter the list to everything else that uses it",
			"The < button (once you've followed a link) goes back to what you were looking at before",
			"You'll get a toast at 25/50/75/100% overall booklet completion, once each",
			"Opening the real server /booklet and paging through it also tracks progress automatically.",
	};

	private void drawHelpOverlay(GuiGraphicsExtractor context) {
		int x = listX;
		int y = listY;
		int w = detailX + detailWidth - listX;
		int h = listHeight;

		context.fill(x, y, x + w, y + h, 0xF0101014);
		border(context, x, y, w, h);

		// Scissored to its own box - at small window sizes there isn't room for all
		// the help lines, and clipping (rather than letting text spill past the
		// panel) keeps that case merely cramped instead of visually broken.
		context.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);

		context.text(this.font, Component.literal("What this mod can do").withStyle(ChatFormatting.BOLD),
				x + 8, y + 8, COLOR_ACCENT);

		int lineY = y + 24;
		for (String line : HELP_LINES) {
			context.text(this.font, Component.literal(line).withStyle(ChatFormatting.GRAY), x + 8, lineY, COLOR_TEXT);
			lineY += 11;
		}

		context.text(this.font, Component.literal("Click the ? button again to close this.").withStyle(ChatFormatting.DARK_GRAY),
				x + 8, y + h - 14, COLOR_TEXT_DIM);

		context.disableScissor();
	}

	private record ReleaseStat(String release, int completed, int total) {
	}

	/** Groups every item by its {@code release} field and tallies completion for whichever tab is active. */
	private List<ReleaseStat> computeReleaseStats() {
		// TreeMap, not HashMap - release strings are "NNN - Name" (e.g. "031 -
		// 1.8"), so sorting the keys lexically already sorts them chronologically,
		// with no need to parse/compare anything ourselves.
		java.util.TreeMap<String, int[]> byRelease = new java.util.TreeMap<>();
		for (BookletEntry entry : BookletData.orderedBy(BookletSort.ECC)) {
			int[] counts = byRelease.computeIfAbsent(entry.release(), k -> new int[2]);
			counts[1]++;
			if (obtainedFor(entry) >= entry.required()) {
				counts[0]++;
			}
		}

		List<ReleaseStat> stats = new ArrayList<>(byRelease.size());
		for (var entry : byRelease.entrySet()) {
			stats.add(new ReleaseStat(entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
		}
		return stats;
	}

	private static final int STATS_ROW_HEIGHT = 13;
	private static final int STATS_ROWS_VISIBLE = 8;

	private record LeaderboardRow(String label, float fraction, boolean isYou) {
	}

	/** You first in the data, then every imported friend, sorted by completion % descending. */
	private List<LeaderboardRow> computeLeaderboard() {
		List<LeaderboardRow> rows = new ArrayList<>();
		rows.add(new LeaderboardRow("You", completionFraction(null), true));
		for (String name : BookletFriends.names()) {
			rows.add(new LeaderboardRow(name, completionFraction(name), false));
		}
		rows.sort((a, b) -> Float.compare(b.fraction(), a.fraction()));
		return rows;
	}

	private float completionFraction(String player) {
		int total = BookletData.size();
		if (total == 0) {
			return 0f;
		}
		int done = 0;
		for (BookletEntry entry : BookletData.orderedBy(BookletSort.ECC)) {
			int obtained = player == null
					? BookletLocalProgress.obtained(entry.itemId())
					: BookletFriends.progressOf(player).getOrDefault(entry.itemId(), 0);
			if (obtained >= entry.required()) {
				done++;
			}
		}
		return done / (float) total;
	}

	private void drawStatsOverlay(GuiGraphicsExtractor context) {
		int x = listX;
		int y = listY;
		int w = detailX + detailWidth - listX;
		int h = listHeight;

		context.fill(x, y, x + w, y + h, 0xF0101014);
		border(context, x, y, w, h);

		// Leaderboard - you plus every imported friend, ranked by completion %, so
		// you can compare at a glance instead of tabbing through each one.
		List<LeaderboardRow> leaderboard = computeLeaderboard();
		context.text(this.font, Component.literal("Leaderboard").withStyle(ChatFormatting.BOLD), x + 8, y + 8, COLOR_ACCENT);
		int rowY = y + 20;
		for (LeaderboardRow row : leaderboard) {
			String pctText = Math.round(row.fraction() * 100) + "%";
			int pctWidth = this.font.width(pctText);
			context.text(this.font, Component.literal(row.label()).withStyle(row.isYou() ? ChatFormatting.BOLD : ChatFormatting.RESET),
					x + 8, rowY, row.isYou() ? COLOR_ACCENT : COLOR_TEXT);
			int barRight = x + w - 8 - pctWidth - 6;
			int barLeft = x + w / 2;
			int barColor = row.fraction() >= 1f ? 0xFF4CAF50 : 0xFFD4B13A;
			drawBar(context, barLeft, rowY + 1, Math.max(10, barRight - barLeft), 7, row.fraction(), barColor);
			context.text(this.font, Component.literal(pctText), x + w - 8 - pctWidth, rowY, COLOR_TEXT_DIM);
			rowY += STATS_ROW_HEIGHT;
		}

		rowY += 4;
		context.fill(x + 8, rowY, x + w - 8, rowY + 1, COLOR_BORDER);
		rowY += 6;

		List<ReleaseStat> stats = computeReleaseStats();
		int listTop = rowY;
		int listBottom = y + h - 14;
		int rowsVisible = Math.max(1, (listBottom - listTop) / STATS_ROW_HEIGHT);
		int maxScroll = Math.max(0, stats.size() - rowsVisible);
		statsScrollRows = Math.max(0, Math.min(maxScroll, statsScrollRows));

		context.text(this.font, Component.literal("Completion by release").withStyle(ChatFormatting.BOLD),
				x + 8, listTop, COLOR_ACCENT);
		listTop += 12;

		context.enableScissor(x + 1, listTop, x + w - 1, listBottom);
		int firstRow = (int) statsScrollRows;
		for (int i = 0; firstRow + i < stats.size() && listTop + i * STATS_ROW_HEIGHT < listBottom; i++) {
			ReleaseStat stat = stats.get(firstRow + i);
			int rowAt = listTop + i * STATS_ROW_HEIGHT;

			context.text(this.font, Component.literal(stat.release()), x + 8, rowAt, COLOR_TEXT);

			String countText = stat.completed() + "/" + stat.total();
			int countWidth = this.font.width(countText);
			int barRight = x + w - 8 - countWidth - 6;
			int barLeft = x + w / 2;
			float fraction = stat.total() == 0 ? 0f : stat.completed() / (float) stat.total();
			int barColor = stat.completed() >= stat.total() ? 0xFF4CAF50 : stat.completed() > 0 ? 0xFFD4B13A : 0xFF44444E;
			drawBar(context, barLeft, rowAt + 1, Math.max(10, barRight - barLeft), 7, fraction, barColor);
			context.text(this.font, Component.literal(countText), x + w - 8 - countWidth, rowAt, COLOR_TEXT_DIM);
		}
		context.disableScissor();

		context.text(this.font, Component.literal("Click the % button again to close this. Scroll to see more.")
				.withStyle(ChatFormatting.DARK_GRAY), x + 8, y + h - 12, COLOR_TEXT_DIM);
	}

	private static final int RECENT_ROW_HEIGHT = 20;

	private void drawRecentOverlay(GuiGraphicsExtractor context) {
		int x = listX;
		int y = listY;
		int w = detailX + detailWidth - listX;
		int h = listHeight;

		context.fill(x, y, x + w, y + h, 0xF0101014);
		border(context, x, y, w, h);

		context.text(this.font, Component.literal("Recently completed").withStyle(ChatFormatting.BOLD), x + 8, y + 8, COLOR_ACCENT);

		recentRowRects.clear();
		List<BookletActivityLog.Entry> recent = BookletActivityLog.recent();
		if (recent.isEmpty()) {
			context.text(this.font, Component.literal("Nothing completed yet this install.").withStyle(ChatFormatting.GRAY),
					x + 8, y + 24, COLOR_TEXT_DIM);
		}

		int rowY = y + 22;
		int bottomLimit = y + h - 14;
		int shown = 0;
		for (BookletActivityLog.Entry entry : recent) {
			if (rowY + RECENT_ROW_HEIGHT > bottomLimit) {
				break;
			}
			BookletEntry bookletEntry = BookletData.byItemId(entry.itemId());
			String name = bookletEntry != null ? bookletEntry.displayName().replace('_', ' ') : entry.itemId();

			if (bookletEntry != null) {
				context.item(new ItemStack(bookletEntry.item(), 1), x + 8, rowY + 1);
			}
			context.text(this.font, Component.literal(name), x + 28, rowY + 2, COLOR_TEXT);
			String ago = formatAgo(entry.timestampMillis());
			context.text(this.font, Component.literal(ago).withStyle(ChatFormatting.GRAY),
					x + w - 8 - this.font.width(ago), rowY + 2, COLOR_TEXT_DIM);

			recentRowRects.add(new RecentRowRect(rowY, RECENT_ROW_HEIGHT, entry.itemId()));
			rowY += RECENT_ROW_HEIGHT;
			shown++;
		}

		if (shown < recent.size()) {
			context.text(this.font, Component.literal("+" + (recent.size() - shown) + " more not shown")
					.withStyle(ChatFormatting.DARK_GRAY), x + 8, Math.min(rowY, bottomLimit), COLOR_TEXT_DIM);
		}

		context.text(this.font, Component.literal("Click an item to jump to it. Click the R button again to close this.")
				.withStyle(ChatFormatting.DARK_GRAY), x + 8, y + h - 12, COLOR_TEXT_DIM);
	}

	private static final String LETTER_RAIL_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

	private int letterRailX() {
		return listX + listWidth;
	}

	/** A thin A-Z strip along the right edge of the list - click a letter to scroll to the first item (in whatever sort/filter is active) starting with it. */
	private void drawLetterRail(GuiGraphicsExtractor context, int mouseX, int mouseY) {
		int railX = letterRailX();
		float rowH = listHeight / (float) LETTER_RAIL_CHARS.length();
		for (int i = 0; i < LETTER_RAIL_CHARS.length(); i++) {
			int rowY = listY + Math.round(i * rowH);
			int rowBottom = listY + Math.round((i + 1) * rowH);
			boolean hovered = mouseX >= railX && mouseX < railX + LETTER_RAIL_WIDTH && mouseY >= rowY && mouseY < rowBottom;
			String letter = String.valueOf(LETTER_RAIL_CHARS.charAt(i));
			int textX = railX + (LETTER_RAIL_WIDTH - this.font.width(letter)) / 2;
			int textY = rowY + (rowBottom - rowY - this.font.lineHeight) / 2;
			context.text(this.font, Component.literal(letter), textX, textY, hovered ? COLOR_ACCENT : COLOR_TEXT_DIM);
		}
	}

	/** Returns the letter rail's row at this y (0-25), or -1 if y isn't within the rail's vertical span. */
	private int letterRailRowAt(int y) {
		if (y < listY || y >= listY + listHeight) {
			return -1;
		}
		float rowH = listHeight / (float) LETTER_RAIL_CHARS.length();
		int row = (int) ((y - listY) / rowH);
		return Math.max(0, Math.min(LETTER_RAIL_CHARS.length() - 1, row));
	}

	/**
	 * Scrolls (without changing the selection) to the first item in the current
	 * filtered list whose name starts with this letter. The active sort is
	 * usually ECC/Creative order, not alphabetical, so unlike a real A-Z index
	 * this doesn't assume names are monotonically ordered - no match just does
	 * nothing, rather than jumping somewhere unrelated as a "nearest" guess
	 * would.
	 */
	private void jumpToLetter(char letter) {
		for (int i = 0; i < filtered.size(); i++) {
			String name = filtered.get(i).displayName().replace('_', ' ');
			if (!name.isEmpty() && Character.toUpperCase(name.charAt(0)) == letter) {
				int rowsVisible = Math.max(1, listHeight / ROW_HEIGHT);
				scrollRows = Math.max(0, Math.min(Math.max(0, filtered.size() - rowsVisible), i - rowsVisible / 2));
				return;
			}
		}
	}

	private void drawBar(GuiGraphicsExtractor context, int x, int y, int width, int height, float fraction, int color) {
		context.fill(x, y, x + width, y + height, COLOR_BAR_TRACK);
		int filled = (int) (width * Math.max(0f, Math.min(1f, fraction)));
		if (filled > 0) {
			context.fill(x, y, x + filled, y + height, color);
		}
	}

	private void border(GuiGraphicsExtractor context, int x, int y, int w, int h) {
		border(context, x, y, w, h, COLOR_BORDER);
	}

	private void border(GuiGraphicsExtractor context, int x, int y, int w, int h, int color) {
		context.fill(x, y, x + w, y + 1, color);
		context.fill(x, y + h - 1, x + w, y + h, color);
		context.fill(x, y, x + 1, y + h, color);
		context.fill(x + w - 1, y, x + w, y + h, color);
	}

	private void drawDetail(GuiGraphicsExtractor context, BookletEntry entry) {
		int obtained = obtainedFor(entry);
		int remaining = Math.max(0, entry.required() - obtained);
		boolean achieved = obtained >= entry.required();

		int px = detailX + 8;
		int py = detailY + 8;

		context.fill(px, py, px + detailWidth - 16, py + 26, COLOR_HEADER);
		context.item(new ItemStack(entry.item(), 1), px + 4, py + 5);
		context.text(this.font, Component.literal(entry.displayName().replace('_', ' ')).withStyle(ChatFormatting.BOLD),
				px + 26, py + 9, COLOR_ACCENT);
		// Header's position is always this fixed first-thing-drawn offset, never
		// subject to drift from earlier variable-height content (unlike
		// amountLineY/typeLineY below), so a literal py here is safe.
		boolean favorite = BookletFavorites.isFavorite(entry.itemId());
		drawActionButton(context, favoriteButtonX(), favoriteButtonY(), "★", favorite ? COLOR_ACCENT : COLOR_TEXT_DIM);

		if (!selectionHistory.isEmpty()) {
			drawActionButton(context, backButtonX(), backButtonY(), "<", COLOR_TEXT);
		}

		py += 34;
		float fraction = entry.required() == 0 ? 0f : Math.min(1f, obtained / (float) entry.required());
		int barColor = achieved ? 0xFF4CAF50 : obtained > 0 ? 0xFFD4B13A : 0xFF44444E;
		drawBar(context, px, py, detailWidth - 16, 8, fraction, barColor);

		py += 17;
		context.text(this.font, Component.literal("Type: " + entry.itemId()), px, py, COLOR_TEXT_DIM);
		// Same "record the real y, don't hardcode an offset" rule as amountLineY
		// below - see that field's comment for why.
		typeLineY = py;
		drawActionButton(context, copyIdButtonX(), copyIdButtonY(), "⧉", 0xFFE4E4E8);
		py += 16;
		context.text(this.font, Component.literal("Amount: " + obtained + " / " + entry.required()), px, py, COLOR_TEXT);
		// Recorded in a field (not a hand-maintained constant) so amountButtonY()
		// and mouseClicked()'s hit-test can never drift from where this text
		// actually lands - a hardcoded offset here previously went stale and put
		// the buttons a full line above the text they were meant to sit next to.
		amountLineY = py;
		if (viewingPlayer == null) {
			// Manual correction, not another tracking source - only offered on your
			// own progress, never on a friend's read-only imported snapshot.
			drawActionButton(context, minusButtonX(), amountButtonY(), "-", 0xFFE4E4E8);
			drawActionButton(context, plusButtonX(), amountButtonY(), "+", 0xFFE4E4E8);
		}
		py += 16;
		context.text(this.font, Component.literal("Remaining: " + remaining), px, py, COLOR_TEXT);
		py += 16;
		context.text(this.font, Component.literal("Achieved: " + (achieved ? "Yes" : "No"))
						.withStyle(achieved ? ChatFormatting.GREEN : ChatFormatting.RED),
				px, py, COLOR_TEXT);
		py += 16;
		context.text(this.font, Component.literal("Released: " + entry.release()), px, py, COLOR_TEXT_DIM);

		// detailWidth is this screen's own internal layout share of panelWidth,
		// which has a hardcoded 300px floor (see init()) regardless of the real
		// game window - on a window narrower than that floor, detailWidth itself
		// claims more horizontal room than the window actually has, so anything
		// sized purely off detailWidth can still run past the real right edge of
		// the screen. Clamping against this.width (the actual window width) here
		// is what the recipe panel's own width-fit math was missing.
		int availableWidth = Math.min(detailWidth - 16, this.width - px - 8);

		List<BookletRecipePanel.HoverSlot> hoverSlots = new ArrayList<>();

		boolean selectionChanged = recipeLookedUpFor != entry;
		if (selectionChanged) {
			recipeLookedUpFor = entry;
			cachedRecipes = BookletRecipeLookup.findAll(entry.item(), entry.itemId());
			recipePage = 0;
			usedInPage = 0;
		}
		moreRecipesRect = null;
		moreUsedInRect = null;
		if (!cachedRecipes.isEmpty()) {
			py += DETAIL_SECTION_GAP;
			// Same idea as the width clamp just above, but vertical: a 3x3 shaped
			// recipe's panel is tall, and two of them stacked could run past the
			// bottom of the (scissor-clipped) detail area on a short window - which
			// doesn't show as a clean "+1 more recipe not shown" like the normal
			// overflow case, it just silently chops the second panel's own border
			// off mid-draw. Capping how many panels drawRecipes even attempts to
			// the vertical room actually left keeps that same clean fallback text.
			int availableHeight = detailY + detailHeight - py - 4;
			BookletRecipePanel.SectionResult result = BookletRecipePanel.drawRecipes(
					context, this.font, px, py, availableWidth, availableHeight, cachedRecipes, recipePage, hoverSlots);
			py = result.py();
			recipeTotalPages = result.totalPages();
			moreRecipesRect = result.moreRect();
		}

		// Not an "else" - some items (smithing templates, the netherite upgrade
		// template, ...) have a real recipe (duplicating one you already have)
		// AND still need a note for how to get the FIRST one, which no recipe
		// explains. Roughly a third of the booklet's items have no recipe at
		// all, where this is the only thing that fills the panel; a handful of
		// others have both.
		String note = BookletAcquisitionNotes.forItem(entry.itemId());
		if (note != null) {
			py += DETAIL_SECTION_GAP;
			py = drawAcquisitionNote(context, px, py, availableWidth, note);
		}

		if (selectionChanged) {
			cachedUsedIn = BookletRecipeLookup.findUsedIn(entry.item(), entry.itemId());
		}
		if (cachedUsedIn.isEmpty()) {
			usedInIconRects = List.of();
		} else {
			// Same gap every time, regardless of whether a crafting panel came
			// before it - a consistent rhythm between sections, rather than one
			// gap size when stacked under stats directly and a different (smaller)
			// one when stacked under a recipe panel.
			py += DETAIL_SECTION_GAP;
			int usedInAvailableHeight = detailY + detailHeight - py - 4;
			List<BookletRecipePanel.UsedInIcon> icons = new ArrayList<>();
			BookletRecipePanel.SectionResult result = BookletRecipePanel.drawUsedIn(
					context, this.font, px, py, availableWidth, usedInAvailableHeight, cachedUsedIn, usedInPage, icons, hoverSlots);
			py = result.py();
			usedInTotalPages = result.totalPages();
			moreUsedInRect = result.moreRect();
			usedInIconRects = icons;
		}

		recipeHoverSlots = hoverSlots;
	}

	/** A bordered box of wrapped text explaining how to actually obtain a no-recipe item - the acquisition-note equivalent of a recipe panel. */
	private int drawAcquisitionNote(GuiGraphicsExtractor context, int px, int py, int availableWidth, String note) {
		int pad = 8;
		int panelW = availableWidth;
		List<net.minecraft.util.FormattedCharSequence> lines = this.font.split(Component.literal(note), panelW - pad * 2);
		int lineHeight = 11;
		int panelH = pad * 2 + 14 + lines.size() * lineHeight;

		context.fill(px, py, px + panelW, py + panelH, COLOR_RECIPE_PANEL);
		border(context, px, py, panelW, panelH, COLOR_RECIPE_PANEL_BORDER);

		context.text(this.font, Component.literal("How to get this").withStyle(ChatFormatting.BOLD), px + pad, py + pad, COLOR_ACCENT);

		int lineY = py + pad + 14;
		for (net.minecraft.util.FormattedCharSequence line : lines) {
			context.text(this.font, line, px + pad, lineY, COLOR_TEXT);
			lineY += lineHeight;
		}

		return py + panelH;
	}

	private static final int DETAIL_SECTION_GAP = 18;

	// Every small inline action button (favorite, copy id, -/+) shares this one
	// size/style now - they used to be three different shapes (a 14px square, a
	// 34x10 text pill, and 10px squares), which read as visually inconsistent/
	// "unstable" rather than like one coherent button family.
	private static final int ACTION_BUTTON_SIZE = 12;

	private void drawActionButton(GuiGraphicsExtractor context, int x, int y, String glyph, int glyphColor) {
		context.fill(x, y, x + ACTION_BUTTON_SIZE, y + ACTION_BUTTON_SIZE, COLOR_RECIPE_SLOT);
		border(context, x, y, ACTION_BUTTON_SIZE, ACTION_BUTTON_SIZE, COLOR_RECIPE_SLOT_BORDER);
		Component label = Component.literal(glyph);
		context.text(this.font, label, x + (ACTION_BUTTON_SIZE - this.font.width(label)) / 2, y + 2, glyphColor);
	}

	private int minusButtonX() {
		return detailX + detailWidth - 8 - ACTION_BUTTON_SIZE * 2 - 4;
	}

	private int plusButtonX() {
		return detailX + detailWidth - 8 - ACTION_BUTTON_SIZE;
	}

	private int amountButtonY() {
		return amountLineY - 1;
	}

	/** Header is always this fixed first-drawn offset (detailY+8), vertically centering a 12px button in the 26px header bar. */
	private int favoriteButtonX() {
		return detailX + detailWidth - 16 - ACTION_BUTTON_SIZE;
	}

	private int favoriteButtonY() {
		return detailY + 15;
	}

	private int backButtonX() {
		return favoriteButtonX() - ACTION_BUTTON_SIZE - 4;
	}

	private int backButtonY() {
		return favoriteButtonY();
	}

	/** Manually nudges the selected item's own progress by delta, clamped to [0, required]. */
	private void adjustSelectedAmount(int delta) {
		if (selected == null || viewingPlayer != null) {
			return;
		}
		int before = BookletLocalProgress.obtained(selected.itemId());
		int after = selected.clamp(before + delta);
		if (after == before) {
			return;
		}
		boolean changed = BookletLocalProgress.mergeIfDifferent(Map.of(selected.itemId(), after));
		if (changed) {
			refreshFiltered();
			BookletAutoPush.scheduleFromRemembered();
		}
	}

	private int copyIdButtonX() {
		return detailX + detailWidth - 8 - ACTION_BUTTON_SIZE;
	}

	private int copyIdButtonY() {
		return typeLineY - 1;
	}

	/** Copies the selected item's display name ("Turtle_Scute"), not its registry id, to the clipboard. */
	private void copySelectedItemId() {
		if (selected == null) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		// Minecraft's own clipboard (GLFW-backed, via KeyboardHandler) - not
		// java.awt.Toolkit, which throws a HeadlessException (null message, so it
		// looked like the button silently did nothing) in this game's JVM.
		client.keyboardHandler.setClipboard(selected.displayName());
		client.gui.toastManager().addToast(new SystemToast(
				COPY_ID_TOAST_ID,
				Component.literal("VMR Booklet"),
				Component.literal("Copied \"" + selected.displayName() + "\" to the clipboard.")
		));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		if (super.mouseClicked(event, doubled)) {
			return true;
		}

		if (showRecent) {
			for (RecentRowRect row : recentRowRects) {
				if (event.y() >= row.y() && event.y() < row.y() + row.height()
						&& event.x() >= listX && event.x() < detailX + detailWidth) {
					BookletEntry target = BookletData.byItemId(row.itemId());
					if (target != null) {
						navigateTo(target);
					}
					return true;
				}
			}
		}

		if (showHelp || showStats || showRecent) {
			// The overlay is covering the list/detail area - don't let a click land on
			// a row hidden underneath it. Tabs stay clickable (they're above it).
			if (event.y() >= listY) {
				return true;
			}
		}

		if (selected != null) {
			if (viewingPlayer == null) {
				int buttonY = amountButtonY();
				if (event.y() >= buttonY && event.y() < buttonY + ACTION_BUTTON_SIZE) {
					int minusX = minusButtonX();
					int plusX = plusButtonX();
					if (event.x() >= minusX && event.x() < minusX + ACTION_BUTTON_SIZE) {
						adjustSelectedAmount(-1);
						return true;
					}
					if (event.x() >= plusX && event.x() < plusX + ACTION_BUTTON_SIZE) {
						adjustSelectedAmount(1);
						return true;
					}
				}
			}

			// Unlike the amount buttons, Copy ID and the favorite star are drawn (and
			// so must be hit-testable) regardless of which player's tab is viewed.
			int copyY = copyIdButtonY();
			int copyX = copyIdButtonX();
			if (event.x() >= copyX && event.x() < copyX + ACTION_BUTTON_SIZE
					&& event.y() >= copyY && event.y() < copyY + ACTION_BUTTON_SIZE) {
				copySelectedItemId();
				return true;
			}

			int favX = favoriteButtonX();
			int favY = favoriteButtonY();
			if (event.x() >= favX && event.x() < favX + ACTION_BUTTON_SIZE
					&& event.y() >= favY && event.y() < favY + ACTION_BUTTON_SIZE) {
				BookletFavorites.toggle(selected.itemId());
				return true;
			}

			if (!selectionHistory.isEmpty()) {
				int backX = backButtonX();
				int backY = backButtonY();
				if (event.x() >= backX && event.x() < backX + ACTION_BUTTON_SIZE
						&& event.y() >= backY && event.y() < backY + ACTION_BUTTON_SIZE) {
					navigateBack();
					return true;
				}
			}

			if (moreRecipesRect != null && moreRecipesRect.contains((int) event.x(), (int) event.y())) {
				recipePage = (recipePage + 1) % Math.max(1, recipeTotalPages);
				return true;
			}
			if (moreUsedInRect != null && moreUsedInRect.contains((int) event.x(), (int) event.y())) {
				usedInPage = (usedInPage + 1) % Math.max(1, usedInTotalPages);
				return true;
			}

			// An ingredient slot in a recipe panel (not the output slot, which has
			// no click action here) - narrows the main list to "what else uses
			// this" instead of jumping straight to one item, since an ingredient
			// is typically used by several things.
			for (BookletRecipePanel.HoverSlot slot : recipeHoverSlots) {
				if (!slot.isIngredient()) continue;
				if (event.x() >= slot.x() && event.x() < slot.x() + slot.size()
						&& event.y() >= slot.y() && event.y() < slot.y() + slot.size()) {
					BookletEntry ingredientEntry = BookletData.byItem(slot.stack().getItem());
					if (ingredientEntry != null) {
						setIngredientFilter(ingredientEntry);
					}
					return true;
				}
			}
		}

		for (BookletRecipePanel.UsedInIcon icon : usedInIconRects) {
			if (event.x() >= icon.x() && event.x() < icon.x() + icon.size()
					&& event.y() >= icon.y() && event.y() < icon.y() + icon.size()) {
				BookletEntry target = BookletData.byItemId(icon.itemId());
				if (target != null) {
					// Jumps straight to that item's own details. Deliberately doesn't
					// touch search/filter/scroll - the target may not be in the
					// currently filtered list, and that's fine; the detail panel shows
					// it directly either way.
					navigateTo(target);
				}
				return true;
			}
		}

		if (event.y() >= tabRowY && event.y() < tabRowY + TAB_ROW_HEIGHT) {
			for (Tab tab : computeTabs()) {
				if (event.x() >= tab.x() && event.x() < tab.x() + tab.width()) {
					viewingPlayer = tab.key();
					scrollRows = 0;
					selected = null;
					selectionHistory.clear();
					refreshFiltered();
					return true;
				}
			}
		}

		int railX = letterRailX();
		if (event.x() >= railX && event.x() < railX + LETTER_RAIL_WIDTH && event.y() >= listY && event.y() < listY + listHeight) {
			int row = letterRailRowAt((int) event.y());
			if (row >= 0) {
				jumpToLetter(LETTER_RAIL_CHARS.charAt(row));
			}
			return true;
		}

		if (clearIngredientFilterRect != null && clearIngredientFilterRect.contains((int) event.x(), (int) event.y())) {
			clearIngredientFilter();
			return true;
		}

		int listContentY = listY + (ingredientFilterItemId != null ? FILTER_BANNER_HEIGHT : 0);
		if (event.x() >= listX && event.x() < listX + listWidth && event.y() >= listContentY && event.y() < listY + listHeight) {
			int index = (int) (scrollRows + (event.y() - listContentY) / ROW_HEIGHT);
			if (index >= 0 && index < filtered.size()) {
				// MouseButtonInfo.button() is NOT the raw 0-indexed GLFW button - Minecraft
				// remaps it to InputConstants.MOUSE_BUTTON_LEFT/MIDDLE/RIGHT (1/2/3) before
				// it reaches screen code. The previous "button() == 1" check here actually
				// matched LEFT-click (button 1), so left-click favorited and right-click
				// (and even middle-click) opened the item - exactly backwards from the
				// in-game help text below. MOUSE_BUTTON_RIGHT (3) is the real right-click.
				if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
					// A quick action on that row's item WITHOUT changing your current
					// selection/detail view - toggling favorite is the one action worth
					// being able to do without losing what you're looking at.
					BookletFavorites.toggle(filtered.get(index).itemId());
				} else {
					navigateTo(filtered.get(index));
				}
			}
			return true;
		}
		// Still inside the list box but on the filter banner itself (not its
		// clear button, already handled above) - absorb the click rather than
		// letting it fall through to whatever's visually behind the panel.
		if (ingredientFilterItemId != null && event.x() >= listX && event.x() < listX + listWidth
				&& event.y() >= listY && event.y() < listContentY) {
			return true;
		}

		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (showStats) {
			int maxScroll = Math.max(0, computeReleaseStats().size() - 8);
			statsScrollRows = Math.max(0, Math.min(maxScroll, statsScrollRows - scrollY));
			return true;
		}
		if (!showHelp && !showRecent && mouseX >= listX && mouseX < listX + listWidth && mouseY >= listY && mouseY < listY + listHeight) {
			int maxScroll = Math.max(0, filtered.size() - listHeight / ROW_HEIGHT);
			scrollRows = Math.max(0, Math.min(maxScroll, scrollRows - scrollY));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_ESCAPE) {
			onClose();
			return true;
		}

		// Checked ahead of the search box (unlike the other list-nav keys below):
		// EditBox itself consumes Home/End to move the text cursor, so if these
		// waited behind super.keyPressed() like Up/Down do, they'd silently do
		// nothing to the list whenever the search box still had focus.
		boolean overlayOpen = showHelp || showStats || showRecent;
		if (!overlayOpen && !filtered.isEmpty()) {
			if (event.key() == InputConstants.KEY_HOME) {
				selectAndReveal(0);
				return true;
			}
			if (event.key() == InputConstants.KEY_END) {
				selectAndReveal(filtered.size() - 1);
				return true;
			}
		}

		// Let the focused widget (the search box) see it first - e.g. Left/Right
		// for cursor movement. Up/Down/Enter aren't handled by EditBox, so they
		// fall through to the list navigation below even while typing a search.
		if (super.keyPressed(event)) {
			return true;
		}

		if (!overlayOpen) {
			if (event.key() == InputConstants.KEY_UP) {
				moveSelection(-1);
				return true;
			}
			if (event.key() == InputConstants.KEY_DOWN) {
				moveSelection(1);
				return true;
			}
			if (event.key() == InputConstants.KEY_PAGEUP) {
				moveSelection(-Math.max(1, listHeight / ROW_HEIGHT));
				return true;
			}
			if (event.key() == InputConstants.KEY_PAGEDOWN) {
				moveSelection(Math.max(1, listHeight / ROW_HEIGHT));
				return true;
			}
			if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
				if (selected == null && !filtered.isEmpty()) {
					selectAndReveal(0);
				}
				// Relinquishes the search box so a following Up/Down immediately
				// navigates the list instead of doing nothing inside the text field.
				if (searchBox.isFocused()) {
					searchBox.setFocused(false);
				}
				return true;
			}
		}

		return false;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
