package dev.aponder.vmrbooklet.client.gui;

import dev.aponder.vmrbooklet.BookletClassifier;
import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.BookletSort;
import dev.aponder.vmrbooklet.BookletState;
import dev.aponder.vmrbooklet.client.commands.BookletNoteCommand;
import dev.aponder.vmrbooklet.client.data.BookletAcquisitionNotes;
import dev.aponder.vmrbooklet.client.data.BookletCategories;
import dev.aponder.vmrbooklet.client.data.BookletFavorites;
import dev.aponder.vmrbooklet.client.data.BookletLocalProgress;
import dev.aponder.vmrbooklet.client.data.BookletNotes;
import dev.aponder.vmrbooklet.client.data.BookletRecipeLookup;
import dev.aponder.vmrbooklet.client.data.BookletSettings;
import dev.aponder.vmrbooklet.client.sync.BookletAutoPush;
import dev.aponder.vmrbooklet.client.sync.BookletFriends;
import dev.aponder.vmrbooklet.client.sync.GoogleServiceAccount;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

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

	enum ShowFilter {
		ALL, COMPLETED, UNFINISHED, PARTIAL, UNDISCOVERED, FAVORITES;

		ShowFilter next() {
			return values()[(ordinal() + 1) % values().length];
		}

		/** The button face, e.g. "Show: Completed". */
		Component label() {
			return Component.translatable("vmrbooklet.show", shortLabel());
		}

		/** The dropdown row text - label() without the repeated "Show: " prefix. */
		Component shortLabel() {
			return Component.translatable("vmrbooklet.show." + name().toLowerCase(Locale.ROOT));
		}

		boolean matches(BookletState state, boolean favorite) {
			return switch (this) {
				case ALL -> true;
				case COMPLETED -> state == BookletState.ACHIEVED;
				// Everything still standing between you and 100% - Partial and
				// Undiscovered combined.
				case UNFINISHED -> state != BookletState.ACHIEVED;
				case PARTIAL -> state == BookletState.IN_PROGRESS;
				case UNDISCOVERED -> state == BookletState.UNDISCOVERED;
				case FAVORITES -> favorite;
			};
		}
	}

	/** Which full-panel overlay (if any) currently covers the list and detail panel. At most one at a time. */
	enum Overlay {
		NONE, HELP, STATS, RECENT, TIMELINE, NEEDED, FRIENDS
	}

	/** One header toggle button: its glyph, the overlay it opens, and its hover tooltip. */
	private record HeaderButton(String glyph, Overlay overlay, String tooltipKey) {
	}

	private static final List<HeaderButton> HEADER_BUTTONS = List.of(
			new HeaderButton("?", Overlay.HELP, "vmrbooklet.button.help"),
			new HeaderButton("%", Overlay.STATS, "vmrbooklet.button.stats"),
			new HeaderButton("R", Overlay.RECENT, "vmrbooklet.button.recent"),
			new HeaderButton("T", Overlay.TIMELINE, "vmrbooklet.button.timeline"),
			new HeaderButton("N", Overlay.NEEDED, "vmrbooklet.button.needed"),
			new HeaderButton("F", Overlay.FRIENDS, "vmrbooklet.button.friends"));

	/** One clickable tab in the player row: {@code key == null} means "You" (your own local progress). */
	record Tab(String key, Component label, int x, int width) {
	}

	private static final SystemToast.SystemToastId COPY_ID_TOAST_ID = new SystemToast.SystemToastId();

	static final int ROW_HEIGHT = 22;
	private static final int HEADER_HEIGHT = 34;
	static final int TAB_ROW_HEIGHT = 16;
	private static final int TOOLBAR_HEIGHT = 20;
	private static final int CATEGORY_ROW_HEIGHT = 20;
	static final int FOOTER_HEIGHT = 22;

	// Remembered across re-opens for the rest of the game session.
	private static ShowFilter sessionShowFilter = ShowFilter.ALL;
	private static BookletSort sessionSort = BookletSort.ECC;
	private static String sessionSearch = "";
	private static double sessionScrollRows = 0;
	private static BookletEntry sessionSelected;
	private static String sessionViewingPlayer = null; // null = your own progress
	// null = "Category: All" - Category lives in BookletCategories as a plain
	// creative-tab bucket list with no booklet-specific ALL member of its own.
	private static BookletCategories.Category sessionCategoryFilter = null;

	ShowFilter showFilter = sessionShowFilter;
	private BookletSort sort = sessionSort;
	private String search = sessionSearch;
	String viewingPlayer = sessionViewingPlayer;
	BookletCategories.Category categoryFilter = sessionCategoryFilter;

	EditBox searchBox;

	List<BookletEntry> filtered = List.of();
	BookletEntry selected = sessionSelected;
	double scrollRows = sessionScrollRows;

	Overlay overlay = BookletSettings.hasSeenOnboarding() ? Overlay.NONE : Overlay.HELP;
	// One scroll position shared by whichever overlay is open - reset each time
	// an overlay opens, and clamped by that overlay's own draw call.
	final BookletUi.ScrollState overlayScroll = new BookletUi.ScrollState();
	final List<BookletOverlays.RecentRowRect> recentRowRects = new ArrayList<>();
	// Which nodes of the needed-tree are expanded, keyed by item id - scoped to
	// this screen instance, never persisted.
	final Set<String> expandedNeededIds = new HashSet<>();
	final List<BookletNeededTreeView.Row> neededRowRects = new ArrayList<>();
	// Expand state and scroll belong to one root item; a different selection starts fresh.
	private BookletEntry neededTreeRoot;
	final List<BookletFriendCompareOverlay.Row> friendCompareRowRects = new ArrayList<>();

	private final BookletScreenData data = new BookletScreenData();

	{
		// Marked as soon as the auto-shown overlay is decided, not only on an
		// explicit "?" click - otherwise closing the screen without touching "?"
		// would show the overlay again on every future open.
		if (overlay == Overlay.HELP) {
			BookletSettings.markOnboardingSeen();
		}
	}

	int panelX, panelY, panelWidth, panelHeight;
	int tabRowY;
	int categoryRowX, categoryRowY, categoryRowWidth;
	// Right-click the category row to open this instead of left-click-cycling -
	// lets you jump to any category in one click.
	boolean categoryDropdownOpen = false;
	// Kept so a dropdown selection (which doesn't go through the button's own
	// left-click lambda) can still push the new label onto the button itself.
	Button categoryButton;
	int showRowX, showRowY, showRowWidth;
	// Same idea as categoryDropdownOpen, for the Show button.
	boolean showDropdownOpen = false;
	Button showFilterButton;
	int listX, listY, listWidth, listHeight;
	int detailX, detailY, detailWidth, detailHeight;

	// The recipe lookup walks the whole client recipe book - cached per selected
	// item and only looked up at all once something is actually selected. A
	// list, not a single recipe: some items have more than one recipe.
	private BookletEntry recipeLookedUpFor;
	private List<BookletRecipeLookup.Recipe> cachedRecipes = List.of();
	// What this item is an ingredient of - same per-selection caching idea.
	private List<ItemStack> cachedUsedIn = List.of();
	// Screen-space rects of the "used in" icons drawn this frame, so the click
	// handler can hit-test them without redoing the layout.
	List<BookletRecipePanel.UsedInIcon> usedInIconRects = List.of();
	// Every ingredient slot, recipe output slot and "Used in" icon drawn this
	// frame - checked against the mouse to show a name tooltip on hover, the
	// same way vanilla's recipe book does.
	List<BookletRecipePanel.HoverSlot> recipeHoverSlots = List.of();
	// Which page of recipes/"used in" icons is showing for the current
	// selection, reset to 0 whenever the selection changes.
	int recipePage;
	int usedInPage;
	BookletRecipePanel.ClickRect moreRecipesRect;
	BookletRecipePanel.ClickRect moreUsedInRect;
	int recipeTotalPages = 1;
	int usedInTotalPages = 1;

	// Browser-style back history: every navigateTo() pushes the item you were
	// looking at before, so following a "Used in" icon deep into the booklet can
	// be undone one step at a time.
	final ArrayDeque<BookletEntry> selectionHistory = new ArrayDeque<>();
	private static final int MAX_SELECTION_HISTORY = 50;

	// Set by clicking an ingredient slot in a recipe panel - narrows the main
	// list to only items that use that ingredient.
	String ingredientFilterItemId;
	private String ingredientFilterLabel;
	private Set<String> ingredientFilterMatchIds = Set.of();
	static final int FILTER_BANNER_HEIGHT = 14;
	BookletRecipePanel.ClickRect clearIngredientFilterRect;

	// Which row (if any) is highlighted because it matches whatever recipe
	// ingredient/output/"Used in" icon the mouse is currently hovering.
	private BookletEntry hoveredIngredientEntry;

	static final int LETTER_RAIL_WIDTH = 10;

	// The exact y drawDetail() placed the "Amount:" and "Type:" lines at this
	// frame - read by the button-position helpers instead of hand-maintained
	// offsets, which had drifted out of sync with the real layout before.
	private int amountLineY;
	private int typeLineY;

	// How far the detail panel's scrollable body (recipe + acquisition note +
	// "used in", below the fixed icon/name/progress header) is scrolled up, in
	// pixels; reset to 0 whenever the selection changes.
	double detailScrollPixels = 0;
	// The body's natural (unscrolled) height drawn this frame, and where it
	// starts - the scroll handler clamps against these instead of redoing the layout.
	int detailContentHeight = 0;
	int detailBodyTopY;
	static final int DETAIL_SCROLL_STEP = 24;

	static final long CHAR_SWALLOW_WINDOW_NANOS = 50_000_000L;
	long swallowCharUntilNanos = 0L;

	private static BookletScreen current;

	// Set by openingOn(): revealed once init() has built the list.
	private BookletEntry pendingReveal;

	public BookletScreen() {
		super(Component.translatable("vmrbooklet.title"));
		current = this;
	}

	/** A booklet screen that opens with this item selected and scrolled into view. */
	public static BookletScreen openingOn(BookletEntry entry) {
		BookletScreen screen = new BookletScreen();
		screen.pendingReveal = entry;
		screen.overlay = Overlay.NONE;
		return screen;
	}

	public static BookletScreen current() {
		return current;
	}

	public void onProgressUpdated() {
		data.invalidate();
		refreshFiltered();
	}

	/** Moves keyboard focus into the search box. */
	public void focusSearch() {
		this.setFocused(searchBox);
		searchBox.setFocused(true);
	}

	boolean anyOverlayOpen() {
		return overlay != Overlay.NONE;
	}

	/** Closes whichever overlay is open plus both dropdowns. */
	void closeAllOverlays() {
		overlay = Overlay.NONE;
		categoryDropdownOpen = false;
		showDropdownOpen = false;
	}

	/** Header button behavior: opens {@code target} (closing anything else), or closes it if it's already open. */
	void toggleOverlay(Overlay target) {
		Overlay next = overlay == target ? Overlay.NONE : target;
		closeAllOverlays();
		overlay = next;
		overlayScroll.reset();
		if (target == Overlay.HELP) {
			BookletSettings.markOnboardingSeen();
		}
	}

	@Override
	public void onClose() {
		saveSessionState();
		super.onClose();
		BookletCelebrations.notifySessionSummary();
	}

	// Also runs when another screen replaces this one without onClose() (death
	// screen, disconnect) - otherwise current() would stay non-null and the B key
	// would keep trying to "close" a booklet that isn't showing.
	@Override
	public void removed() {
		saveSessionState();
		super.removed();
	}

	private void saveSessionState() {
		if (current == this) {
			current = null;
		}
		sessionShowFilter = showFilter;
		sessionSort = sort;
		sessionSearch = search;
		sessionScrollRows = scrollRows;
		sessionSelected = selected;
		sessionViewingPlayer = viewingPlayer;
		sessionCategoryFilter = categoryFilter;
	}

	// Returning to this same instance from a child screen (settings, a link
	// confirmation) re-adds it without running the constructor again.
	@Override
	public void added() {
		super.added();
		current = this;
	}

	@Override
	protected void init() {
		// Clamped to a sane minimum: the toolbar's two buttons sit at fixed offsets
		// from the right edge (innerWidth - 204 / - 100) with the search box filling
		// the rest (innerWidth - 212) - below ~300 that box goes negative-width.
		panelWidth = Math.max(300, Math.min(width - 32, 760));
		panelHeight = Math.max(182, Math.min(height - 32, 440));
		panelX = (width - panelWidth) / 2;
		panelY = (height - panelHeight) / 2;

		int innerX = panelX + 8;
		int innerWidth = panelWidth - 16;

		tabRowY = panelY + HEADER_HEIGHT + 2;
		int toolbarY = tabRowY + TAB_ROW_HEIGHT + 2;

		searchBox = new EditBox(this.font, innerX, toolbarY, innerWidth - 212, 14, Component.translatable("vmrbooklet.search"));
		searchBox.setMaxLength(96);
		searchBox.setValue(search);
		searchBox.setHint(Component.translatable("vmrbooklet.search.hint").withStyle(ChatFormatting.DARK_GRAY));
		searchBox.setResponder(value -> {
			search = value.trim().toLowerCase(Locale.ROOT);
			refreshFiltered();
		});
		addRenderableWidget(searchBox);

		showRowX = innerX + innerWidth - 204;
		showRowY = toolbarY;
		showRowWidth = 100;
		showDropdownOpen = false;
		showFilterButton = addRenderableWidget(Button.builder(showFilter.label(), b -> {
			showFilter = showFilter.next();
			b.setMessage(showFilter.label());
			scrollRows = 0;
			refreshFiltered();
		}).bounds(showRowX, showRowY, showRowWidth, 14)
				.tooltip(Tooltip.create(Component.translatable("vmrbooklet.button.show.tooltip")))
				.build());

		addRenderableWidget(Button.builder(sortLabel(), b -> {
			sort = sort.other();
			b.setMessage(sortLabel());
			scrollRows = 0;
			refreshFiltered();
		}).bounds(innerX + innerWidth - 100, toolbarY, 100, 14)
				.tooltip(Tooltip.create(Component.translatable("vmrbooklet.button.sort.tooltip")))
				.build());

		categoryRowX = innerX;
		categoryRowY = toolbarY + TOOLBAR_HEIGHT + 2;
		categoryRowWidth = innerWidth;
		categoryDropdownOpen = false;
		categoryButton = addRenderableWidget(Button.builder(categoryLabel(), b -> {
			categoryFilter = nextCategory(categoryFilter);
			b.setMessage(categoryLabel());
			scrollRows = 0;
			refreshFiltered();
		}).bounds(categoryRowX, categoryRowY, categoryRowWidth, 14)
				.tooltip(Tooltip.create(Component.translatable("vmrbooklet.button.category.tooltip")))
				.build());

		int footerY = panelY + panelHeight - FOOTER_HEIGHT + 2;

		addRenderableWidget(Button.builder(Component.translatable("vmrbooklet.button.next_unfinished"), b -> jumpToNextUnfinished())
				.bounds(innerX, footerY, 110, 14)
				.tooltip(Tooltip.create(Component.translatable("vmrbooklet.button.next_unfinished.tooltip")))
				.build());

		addRenderableWidget(Button.builder(notifyLabel(), b -> {
			BookletSettings.setScanNotifications(!BookletSettings.scanNotifications());
			b.setMessage(notifyLabel());
		}).bounds(innerX + innerWidth - 110, footerY, 110, 14)
				.tooltip(Tooltip.create(Component.translatable("vmrbooklet.button.notify.tooltip")))
				.build());

		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(panelX + panelWidth / 2 - 40, footerY, 80, 14)
				.build());

		int headerButtonX = panelX + 8 + this.font.width(titleComponent()) + 10;
		for (HeaderButton header : HEADER_BUTTONS) {
			addRenderableWidget(Button.builder(Component.literal(header.glyph()), b -> toggleOverlay(header.overlay()))
					.bounds(headerButtonX, panelY + 6, 14, 14)
					.tooltip(Tooltip.create(Component.translatable(header.tooltipKey())))
					.build());
			headerButtonX += 16;
		}

		listX = innerX;
		listY = categoryRowY + CATEGORY_ROW_HEIGHT + 2;
		// Reserves a thin strip on the right of the list for the A-Z jump rail -
		// carved out of the list's own width so it never overlaps the count
		// text/progress bar each row right-aligns within listWidth.
		listWidth = (int) (innerWidth * 0.42) - LETTER_RAIL_WIDTH;
		listHeight = panelY + panelHeight - FOOTER_HEIGHT - listY - 2;

		detailX = listX + listWidth + LETTER_RAIL_WIDTH + 8;
		detailY = listY;
		detailWidth = innerX + innerWidth - detailX;
		detailHeight = listHeight;

		setInitialFocus(searchBox);
		refreshFiltered();

		if (pendingReveal != null) {
			revealPending();
		}
	}

	/**
	 * Shows the item openingOn() was asked for. If the remembered search or
	 * filters hide it, they're reset - the point of opening on an item is
	 * seeing it.
	 */
	private void revealPending() {
		BookletEntry target = pendingReveal;
		pendingReveal = null;
		if (!filtered.contains(target)) {
			showFilter = ShowFilter.ALL;
			showFilterButton.setMessage(showFilter.label());
			categoryFilter = null;
			categoryButton.setMessage(categoryLabel());
			ingredientFilterItemId = null;
			ingredientFilterLabel = null;
			ingredientFilterMatchIds = Set.of();
			// Fires the responder, which refreshes the list.
			searchBox.setValue("");
			refreshFiltered();
		}
		int index = filtered.indexOf(target);
		if (index >= 0) {
			selectAndReveal(index);
		} else {
			navigateTo(target);
		}
		// Typing should go to the list, not straight into the search box.
		searchBox.setFocused(false);
		setFocused(null);
	}

	private static Component titleComponent() {
		return Component.translatable("vmrbooklet.title").withStyle(ChatFormatting.BOLD);
	}

	List<Tab> computeTabs() {
		List<Tab> tabs = new ArrayList<>();
		int x = panelX + 8;

		Component you = Component.translatable("vmrbooklet.tab.you");
		int youWidth = this.font.width(you) + 10;
		tabs.add(new Tab(null, you, x, youWidth));
		x += youWidth + 2;

		for (String name : BookletFriends.names()) {
			// Short age ("5m", not "5 minutes ago") - these tabs have no wrap or
			// horizontal scroll, so every extra character pushes later tabs off the panel.
			Component label = Component.literal(name + " ·" + BookletUi.formatAgoShort(BookletFriends.importedAtMillis(name)));
			int w = this.font.width(label) + 10;
			tabs.add(new Tab(name, label, x, w));
			x += w + 2;
		}
		return tabs;
	}

	private Component sortLabel() {
		return Component.translatable("vmrbooklet.sort", sort.name());
	}

	private static Component notifyLabel() {
		return Component.translatable("vmrbooklet.notify",
				Component.translatable(BookletSettings.scanNotifications() ? "options.on" : "options.off"));
	}

	Component categoryLabel() {
		return Component.translatable("vmrbooklet.category", categoryEntryLabel(categoryFilter));
	}

	static Component categoryEntryLabel(BookletCategories.Category category) {
		return category == null ? Component.translatable("vmrbooklet.category.all") : Component.literal(category.label());
	}

	/** Cycles null (All) -> each {@link BookletCategories.Category} in enum order -> back to null. */
	private static BookletCategories.Category nextCategory(BookletCategories.Category current) {
		BookletCategories.Category[] values = BookletCategories.Category.values();
		if (current == null) {
			return values[0];
		}
		int next = current.ordinal() + 1;
		return next < values.length ? values[next] : null;
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
	void selectAndReveal(int index) {
		navigateTo(filtered.get(index));
		int rowsVisible = listRowsVisible();
		if (index < scrollRows || index >= scrollRows + rowsVisible) {
			// Clamped the same way refreshFiltered() clamps scrollRows, so landing
			// near the end of a long list can't scroll past the real max.
			int maxScroll = Math.max(0, filtered.size() - rowsVisible);
			scrollRows = Math.max(0, Math.min(maxScroll, index - rowsVisible / 2));
		}
	}

	/**
	 * Row click inside a full-panel overlay: the overlay covers the list and
	 * detail panel, so it has to close for the selection to be visible at all.
	 */
	void revealFromOverlay(String itemId) {
		BookletEntry target = BookletData.byItemId(itemId);
		if (target == null) {
			return;
		}
		closeAllOverlays();
		int index = filtered.indexOf(target);
		if (index >= 0) {
			selectAndReveal(index);
		} else {
			navigateTo(target);
		}
	}

	/** Selects a new item, pushing whatever was selected before onto the back-history stack first. */
	void navigateTo(BookletEntry entry) {
		if (selected != null && selected != entry) {
			selectionHistory.push(selected);
			while (selectionHistory.size() > MAX_SELECTION_HISTORY) {
				selectionHistory.removeLast();
			}
		}
		selected = entry;
	}

	/** Pops the most recent entry off the back-history stack, if any, and selects it. */
	void navigateBack() {
		if (!selectionHistory.isEmpty()) {
			selected = selectionHistory.pop();
		}
	}

	/** Arrow-key list navigation: moves the selection by delta rows within the filtered list, clamped (no wrap). */
	void moveSelection(int delta) {
		if (filtered.isEmpty()) {
			return;
		}

		int currentIndex = selected == null ? -1 : filtered.indexOf(selected);
		int next;
		if (currentIndex < 0) {
			next = delta > 0 ? 0 : filtered.size() - 1;
		} else {
			next = Math.max(0, Math.min(filtered.size() - 1, currentIndex + delta));
		}
		selectAndReveal(next);
	}

	/** Switches the player tab (null = you), resetting selection and scroll since the list's states change. */
	void selectTab(String key) {
		viewingPlayer = key;
		scrollRows = 0;
		selected = null;
		selectionHistory.clear();
		data.invalidate();
		refreshFiltered();
	}

	void refreshFiltered() {
		if (viewingPlayer != null && !BookletFriends.names().contains(viewingPlayer)) {
			viewingPlayer = null;
		}

		List<BookletEntry> ordered = BookletData.orderedBy(sort);
		List<BookletEntry> result = new ArrayList<>();
		BookletSearchQuery query = BookletSearchQuery.parse(search);

		for (BookletEntry entry : ordered) {
			int obtained = obtainedFor(entry);
			BookletState state = BookletClassifier.classify(entry, obtained);

			if (!query.isEmpty() && !query.matches(entry, obtained, state, () -> BookletCategories.categoryOf(entry.item()).label())) {
				continue;
			}

			if (!showFilter.matches(state, BookletFavorites.isFavorite(entry.itemId()))) continue;

			if (categoryFilter != null && BookletCategories.categoryOf(entry.item()) != categoryFilter) continue;

			if (ingredientFilterItemId != null && !ingredientFilterMatchIds.contains(entry.itemId())) continue;

			result.add(entry);
		}

		filtered = result;
		int maxScroll = Math.max(0, filtered.size() - listContentHeight() / ROW_HEIGHT);
		scrollRows = Math.max(0, Math.min(scrollRows, maxScroll));

		if (selected != null && !filtered.contains(selected)) {
			selected = null;
		}
	}

	/** Narrows the main list to only items whose recipe uses this ingredient - set by clicking an ingredient slot in a recipe panel. */
	void setIngredientFilter(BookletEntry ingredient) {
		ingredientFilterItemId = ingredient.itemId();
		ingredientFilterLabel = ingredient.displayName().replace('_', ' ');
		List<ItemStack> usedIn = BookletRecipeLookup.findUsedIn(ingredient.item(), ingredient.itemId());
		Set<String> ids = new HashSet<>();
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

	void clearIngredientFilter() {
		ingredientFilterItemId = null;
		ingredientFilterLabel = null;
		ingredientFilterMatchIds = Set.of();
		refreshFiltered();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, width, height, BookletUi.COLOR_BACKDROP);
		context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, BookletUi.COLOR_PANEL);
		BookletUi.border(context, panelX, panelY, panelWidth, panelHeight);

		drawHeader(context);

		// Player tabs - clipped to the panel: with several friends the un-wrapped
		// tab row can run past the right edge.
		context.enableScissor(panelX + 1, tabRowY, panelX + panelWidth - 1, tabRowY + TAB_ROW_HEIGHT);
		for (Tab tab : computeTabs()) {
			boolean active = tab.key() == null ? viewingPlayer == null : tab.key().equals(viewingPlayer);
			int tabColor = active ? BookletUi.COLOR_ROW_SELECTED : BookletUi.COLOR_TAB_INACTIVE;
			context.fill(tab.x(), tabRowY, tab.x() + tab.width(), tabRowY + TAB_ROW_HEIGHT, tabColor);
			BookletUi.border(context, tab.x(), tabRowY, tab.width(), TAB_ROW_HEIGHT);
			context.text(this.font, tab.label(), tab.x() + 5, tabRowY + 4, active ? 0xFFFFFFFF : BookletUi.COLOR_TEXT_DIM);
		}
		context.disableScissor();

		// List + detail panels
		context.fill(listX, listY, listX + listWidth, listY + listHeight, BookletUi.COLOR_LIST_BG);
		BookletUi.border(context, listX, listY, listWidth, listHeight);
		context.fill(detailX, detailY, detailX + detailWidth, detailY + detailHeight, BookletUi.COLOR_LIST_BG);
		BookletUi.border(context, detailX, detailY, detailWidth, detailHeight);

		// Whichever overlay is open fully covers the list+detail area below the
		// tab row, so none of that content is drawn at all this frame - not just
		// "drawn and then covered". GuiGraphicsExtractor defers every draw call
		// into a render-state list, and that list isn't guaranteed to composite
		// strictly in call order across element types (fills vs. text) - a fill
		// drawn after some text can still end up behind it once submitted.
		boolean overlayOpen = anyOverlayOpen();

		// Computed from LAST frame's recipeHoverSlots (this frame's copy isn't
		// known until drawDetail() runs further down) - one frame of lag on a
		// pure highlight is imperceptible.
		hoveredIngredientEntry = null;
		if (!overlayOpen) {
			BookletRecipePanel.HoverSlot slot = hoverSlotAt(mouseX, mouseY);
			if (slot != null) {
				hoveredIngredientEntry = BookletData.byItem(slot.stack().getItem());
			}
		}

		clearIngredientFilterRect = null;
		if (overlayOpen) {
			// Nothing drawn this frame means nothing for a stray click to hit-test
			// against once the overlay closes.
			clearDetailHitRects();
		} else {
			drawList(context, mouseX, mouseY);
			drawLetterRail(context, mouseX, mouseY);

			if (selected != null) {
				// Clipped to its own bounds - an item with several recipes plus a long
				// "Used in" list can run past the bottom border at small window sizes.
				context.enableScissor(detailX + 1, detailY + 1, detailX + detailWidth - 1, detailY + detailHeight - 1);
				drawDetail(context, selected);
				context.disableScissor();
			} else {
				// Otherwise clicks in this now-blank area would still hit-test against
				// icon rects from whatever was selected last.
				clearDetailHitRects();
				context.text(this.font, Component.translatable("vmrbooklet.detail.select").withStyle(ChatFormatting.GRAY),
						detailX + 8, detailY + 8, BookletUi.COLOR_TEXT_DIM);
			}

			// Checked after the detail scissor is disabled.
			BookletRecipePanel.HoverSlot slot = hoverSlotAt(mouseX, mouseY);
			if (slot != null) {
				context.setTooltipForNextFrame(this.font, slot.stack(), mouseX, mouseY);
			}
		}

		drawOverlay(context);

		super.extractRenderState(context, mouseX, mouseY, delta);

		// Drawn AFTER the widgets: the Show dropdown opens downward right into the
		// Category button's space, and drawing it before the widget pass let that
		// button paint over the dropdown's first rows.
		int dropdownBottomLimit = panelY + panelHeight - FOOTER_HEIGHT;
		if (categoryDropdownOpen) {
			BookletCategories.Category[] values = BookletCategories.Category.values();
			BookletDropdowns.draw(context, this.font, categoryRowX, categoryRowY, categoryRowWidth, values.length + 1,
					dropdownBottomLimit, mouseX, mouseY,
					i -> (i == 0 ? null : values[i - 1]) == categoryFilter,
					i -> categoryEntryLabel(i == 0 ? null : values[i - 1]));
		}
		if (showDropdownOpen) {
			ShowFilter[] values = ShowFilter.values();
			BookletDropdowns.draw(context, this.font, showRowX, showRowY, showRowWidth, values.length,
					dropdownBottomLimit, mouseX, mouseY,
					i -> values[i] == showFilter,
					i -> values[i].shortLabel());
		}
	}

	private void clearDetailHitRects() {
		usedInIconRects = List.of();
		recipeHoverSlots = List.of();
		moreRecipesRect = null;
		moreUsedInRect = null;
	}

	private BookletRecipePanel.HoverSlot hoverSlotAt(int mouseX, int mouseY) {
		for (BookletRecipePanel.HoverSlot slot : recipeHoverSlots) {
			if (mouseX >= slot.x() && mouseX < slot.x() + slot.size()
					&& mouseY >= slot.y() && mouseY < slot.y() + slot.size()) {
				return slot;
			}
		}
		return null;
	}

	private void drawHeader(GuiGraphicsExtractor context) {
		context.fill(panelX + 1, panelY + 1, panelX + panelWidth - 1, panelY + HEADER_HEIGHT, BookletUi.COLOR_HEADER);
		context.text(this.font, titleComponent(), panelX + 8, panelY + 7, BookletUi.COLOR_ACCENT);

		int completedCount = data.completedCount(viewingPlayer);
		Component counter = Component.translatable("vmrbooklet.header.collected", completedCount, BookletData.size());
		context.text(this.font, counter, panelX + panelWidth - 8 - this.font.width(counter), panelY + 7, BookletUi.COLOR_TEXT_DIM);

		float fraction = BookletData.size() == 0 ? 0f : completedCount / (float) BookletData.size();
		BookletUi.drawBar(context, panelX + 8, panelY + 20, panelWidth - 16, 5, fraction, BookletUi.COLOR_ACCENT);

		Component synced = Component.translatable("vmrbooklet.header.synced",
				BookletUi.formatAgo(BookletSettings.lastSyncedMillis()),
				Component.translatable(GoogleServiceAccount.isConfigured() ? "options.on" : "options.off"));
		int syncedWidth = this.font.width(synced);
		context.text(this.font, synced, panelX + panelWidth - 8 - syncedWidth, panelY + 26, BookletUi.COLOR_TEXT_DIM);

		// Each part only shows when there's something to say, joined into one
		// left-aligned line so whichever subset is present lands on the same spot.
		List<String> leftHeaderParts = new ArrayList<>();
		int sessionCount = BookletLocalProgress.sessionObtainedCount();
		if (sessionCount > 0) {
			leftHeaderParts.add(Component.translatable("vmrbooklet.header.session", sessionCount).getString());
		}
		if (BookletSettings.showStreakLine()) {
			int streakDays = data.streakDays();
			// A lone day isn't a streak yet.
			if (streakDays > 1) {
				leftHeaderParts.add(Component.translatable("vmrbooklet.header.streak", streakDays).getString());
			}
		}
		if (BookletSettings.showGoalLine()) {
			BookletScreenData.GoalProgress goals = data.goalProgress();
			if (goals.total() > 0) {
				leftHeaderParts.add(Component.translatable("vmrbooklet.header.goal", goals.done(), goals.total()).getString());
			}
		}
		if (!leftHeaderParts.isEmpty()) {
			// Shares this line with the right-aligned sync text, so on narrow panels
			// (laptop resolutions at GUI scale 3) it tightens, then truncates.
			int leftRoom = panelWidth - 16 - syncedWidth - 12;
			String leftText = String.join("  |  ", leftHeaderParts);
			if (this.font.width(leftText) > leftRoom) {
				leftText = String.join(" | ", leftHeaderParts);
			}
			leftText = BookletUi.ellipsize(this.font, leftText, leftRoom);
			if (!leftText.isEmpty()) {
				context.text(this.font, Component.literal(leftText), panelX + 8, panelY + 26, BookletUi.COLOR_TEXT_DIM);
			}
		}
	}

	private void drawList(GuiGraphicsExtractor context, int mouseX, int mouseY) {
		int listContentY = listY;
		int listContentHeight = listHeight;
		if (ingredientFilterItemId != null) {
			context.fill(listX, listY, listX + listWidth, listY + FILTER_BANNER_HEIGHT, BookletUi.COLOR_ROW_SELECTED);
			String clearText = "✕";
			int clearX = listX + listWidth - 3 - this.font.width(clearText);
			String bannerText = BookletUi.ellipsize(this.font,
					Component.translatable("vmrbooklet.list.uses", ingredientFilterLabel).getString(), clearX - 4 - (listX + 3));
			context.text(this.font, Component.literal(bannerText).withStyle(ChatFormatting.BOLD), listX + 3, listY + 3, BookletUi.COLOR_TEXT);
			context.text(this.font, Component.literal(clearText).withStyle(ChatFormatting.BOLD), clearX, listY + 3, BookletUi.COLOR_TEXT);
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
			if (entry == selected) {
				context.fill(listX + 1, rowY, listX + listWidth - 1, rowY + ROW_HEIGHT, BookletUi.COLOR_ROW_SELECTED);
			} else if (hovered) {
				context.fill(listX + 1, rowY, listX + listWidth - 1, rowY + ROW_HEIGHT, BookletUi.COLOR_ROW_HOVER);
			}
			if (entry == hoveredIngredientEntry) {
				// A bright left-edge accent bar over whatever fill happened above, so
				// you can tell from the recipe panel whether you already have an ingredient.
				context.fill(listX + 1, rowY, listX + 4, rowY + ROW_HEIGHT, BookletUi.COLOR_ACCENT);
			}

			int obtained = obtainedFor(entry);
			BookletState state = BookletClassifier.classify(entry, obtained);

			context.item(new ItemStack(entry.item(), 1), listX + 3, rowY + 2);

			ChatFormatting color = BookletUi.stateFormatting(state);

			String countText = obtained + "/" + entry.required();
			int countWidth = this.font.width(countText);
			String rowName = BookletUi.stateMark(state) + (BookletFavorites.isFavorite(entry.itemId()) ? "★ " : "")
					+ entry.displayName().replace('_', ' ');
			rowName = BookletUi.ellipsize(this.font, rowName, listWidth - 24 - 4);
			context.text(this.font, Component.literal(rowName).withStyle(color), listX + 24, rowY + 1, BookletUi.COLOR_TEXT);

			int rowBarRight = listX + listWidth - 4 - countWidth - 4;
			int rowBarLeft = listX + 24;
			float rowFraction = entry.required() == 0 ? 0f : Math.min(1f, obtained / (float) entry.required());
			BookletUi.drawBar(context, rowBarLeft, rowY + 13, Math.max(10, rowBarRight - rowBarLeft), 4, rowFraction,
					BookletUi.progressColor(obtained, entry.required()));
			context.text(this.font, Component.literal(countText), listX + listWidth - 4 - countWidth, rowY + 12, BookletUi.COLOR_TEXT_DIM);
		}
		context.disableScissor();

		if (filtered.isEmpty()) {
			context.text(this.font, Component.translatable("vmrbooklet.list.empty").withStyle(ChatFormatting.GRAY),
					listX + 8, listContentY + 8, BookletUi.COLOR_TEXT_DIM);
		}
	}

	private void drawOverlay(GuiGraphicsExtractor context) {
		int x = listX;
		int y = listY;
		int w = detailX + detailWidth - listX;
		int h = listHeight;
		switch (overlay) {
			case NONE -> {
			}
			case HELP -> BookletOverlays.drawHelpOverlay(context, this.font, x, y, w, h, overlayScroll);
			case STATS -> BookletOverlays.drawStatsOverlay(context, this.font, x, y, w, h,
					data.leaderboard(), data.releaseStats(viewingPlayer), data.pace(), overlayScroll);
			case RECENT -> BookletOverlays.drawRecentOverlay(context, this.font, x, y, w, h, data.recent(), overlayScroll, recentRowRects);
			case TIMELINE -> BookletOverlays.drawTimelineOverlay(context, this.font, x, y, w, h, data.dailyStats(), overlayScroll);
			case NEEDED -> {
				if (selected != neededTreeRoot) {
					neededTreeRoot = selected;
					expandedNeededIds.clear();
					overlayScroll.reset();
				}
				BookletNeededTreeView.draw(context, this.font, x, y, w, h,
						selected, data.neededTree(selected), expandedNeededIds, overlayScroll, neededRowRects);
			}
			case FRIENDS -> BookletFriendCompareOverlay.draw(context, this.font, x, y, w, h,
					data.friendHaveIt(), overlayScroll, friendCompareRowRects);
		}
	}

	static final String LETTER_RAIL_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

	int letterRailX() {
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
			context.text(this.font, Component.literal(letter), textX, textY, hovered ? BookletUi.COLOR_ACCENT : BookletUi.COLOR_TEXT_DIM);
		}
	}

	/** Returns the letter rail's row at this y (0-25), or -1 if y isn't within the rail's vertical span. */
	int letterRailRowAt(int y) {
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
	 * usually ECC/Creative order, not alphabetical, so no match just does
	 * nothing rather than jumping to a "nearest" guess.
	 */
	void jumpToLetter(char letter) {
		for (int i = 0; i < filtered.size(); i++) {
			String name = filtered.get(i).displayName().replace('_', ' ');
			if (!name.isEmpty() && Character.toUpperCase(name.charAt(0)) == letter) {
				int rowsVisible = listRowsVisible();
				scrollRows = Math.max(0, Math.min(Math.max(0, filtered.size() - rowsVisible), i - rowsVisible / 2));
				return;
			}
		}
	}

	private void drawDetail(GuiGraphicsExtractor context, BookletEntry entry) {
		int obtained = obtainedFor(entry);
		int remaining = Math.max(0, entry.required() - obtained);
		boolean achieved = obtained >= entry.required();

		int px = detailX + 8;
		int py = detailY + 8;

		context.fill(px, py, px + detailWidth - 16, py + 26, BookletUi.COLOR_HEADER);
		context.item(new ItemStack(entry.item(), 1), px + 4, py + 5);
		int nameRoom = (selectionHistory.isEmpty() ? favoriteButtonX() : backButtonX()) - 4 - (px + 26);
		context.text(this.font, Component.literal(BookletUi.ellipsize(this.font, entry.displayName().replace('_', ' '), nameRoom))
						.withStyle(ChatFormatting.BOLD), px + 26, py + 9, BookletUi.COLOR_ACCENT);
		boolean favorite = BookletFavorites.isFavorite(entry.itemId());
		drawActionButton(context, favoriteButtonX(), favoriteButtonY(), "★", favorite ? BookletUi.COLOR_ACCENT : BookletUi.COLOR_TEXT_DIM);

		if (!selectionHistory.isEmpty()) {
			drawActionButton(context, backButtonX(), backButtonY(), "<", BookletUi.COLOR_TEXT);
		}

		py += 34;
		float fraction = entry.required() == 0 ? 0f : Math.min(1f, obtained / (float) entry.required());
		BookletUi.drawBar(context, px, py, detailWidth - 16, 8, fraction, BookletUi.progressColor(obtained, entry.required()));

		py += 17;
		context.text(this.font, Component.translatable("vmrbooklet.detail.type", entry.itemId()), px, py, BookletUi.COLOR_TEXT_DIM);
		typeLineY = py;
		drawActionButton(context, copyIdButtonX(), copyIdButtonY(), "⧉", 0xFFE4E4E8);
		py += 16;
		context.text(this.font, Component.translatable("vmrbooklet.detail.amount", obtained, entry.required()), px, py, BookletUi.COLOR_TEXT);
		amountLineY = py;
		if (viewingPlayer == null) {
			// Manual correction - only offered on your own progress, never on a
			// friend's read-only imported snapshot.
			drawActionButton(context, minusButtonX(), amountButtonY(), "-", 0xFFE4E4E8);
			drawActionButton(context, plusButtonX(), amountButtonY(), "+", 0xFFE4E4E8);
		}
		py += 16;
		context.text(this.font, Component.translatable("vmrbooklet.detail.remaining", remaining), px, py, BookletUi.COLOR_TEXT);
		py += 16;
		context.text(this.font, Component.translatable("vmrbooklet.detail.achieved",
						Component.translatable(achieved ? "gui.yes" : "gui.no"))
						.withStyle(BookletUi.yesNoFormatting(achieved)),
				px, py, BookletUi.COLOR_TEXT);
		py += 16;
		context.text(this.font, Component.translatable("vmrbooklet.detail.released", entry.release()), px, py, BookletUi.COLOR_TEXT_DIM);

		// detailWidth comes from panelWidth, which has a 300px floor regardless of
		// the real window - clamping against this.width keeps the recipe panel on
		// screen when the window is narrower than that floor.
		int availableWidth = Math.min(detailWidth - 16, this.width - px - 8);

		List<BookletRecipePanel.HoverSlot> hoverSlots = new ArrayList<>();

		boolean selectionChanged = recipeLookedUpFor != entry;
		if (selectionChanged) {
			recipeLookedUpFor = entry;
			cachedRecipes = BookletRecipeLookup.findAll(entry.item(), entry.itemId());
			recipePage = 0;
			usedInPage = 0;
			detailScrollPixels = 0;
		}
		moreRecipesRect = null;
		moreUsedInRect = null;

		// Everything below is the scrollable body - the icon/name/progress block
		// above stays fixed. `py` is where things are drawn (shifted up by the
		// scroll), while `py + scrollOffset` is the unscrolled layout that the
		// available-height budget is measured against, so scrolling never changes
		// how recipes/used-in paginate.
		detailBodyTopY = py;
		int scrollOffset = (int) detailScrollPixels;
		py -= scrollOffset;
		context.enableScissor(detailX + 1, detailBodyTopY, detailX + detailWidth - 1, detailY + detailHeight - 1);

		if (!cachedRecipes.isEmpty()) {
			py += DETAIL_SECTION_GAP;
			int availableHeight = detailY + detailHeight - (py + scrollOffset) - 4;
			BookletRecipePanel.SectionResult result = BookletRecipePanel.drawRecipes(
					context, this.font, px, py, availableWidth, availableHeight, cachedRecipes, recipePage, hoverSlots);
			py = result.py();
			recipeTotalPages = result.totalPages();
			moreRecipesRect = result.moreRect();
		}

		// Not an "else" - some items (smithing templates, ...) have a real recipe
		// AND still need a note for how to get the FIRST one.
		String note = BookletAcquisitionNotes.forItem(entry.itemId());
		if (note != null) {
			py += DETAIL_SECTION_GAP;
			py = drawNotePanel(context, px, py, availableWidth, Component.translatable("vmrbooklet.detail.how_to_get"), note);
		}

		// Your own note (and saved location) from /vbooklet note.
		BookletNotes.Note personal = BookletNotes.get(entry.itemId());
		if (personal != null) {
			StringBuilder body = new StringBuilder(personal.text());
			if (personal.waypoint() != null) {
				if (!body.isEmpty()) {
					body.append('\n');
				}
				body.append(Component.translatable("vmrbooklet.detail.waypoint",
						BookletNoteCommand.formatWaypoint(personal.waypoint())).getString());
			}
			py += DETAIL_SECTION_GAP;
			py = drawNotePanel(context, px, py, availableWidth, Component.translatable("vmrbooklet.detail.your_note"), body.toString());
		}

		if (selectionChanged) {
			cachedUsedIn = BookletRecipeLookup.findUsedIn(entry.item(), entry.itemId());
		}
		if (cachedUsedIn.isEmpty()) {
			usedInIconRects = List.of();
		} else {
			py += DETAIL_SECTION_GAP;
			int usedInAvailableHeight = detailY + detailHeight - (py + scrollOffset) - 4;
			List<BookletRecipePanel.UsedInIcon> icons = new ArrayList<>();
			BookletRecipePanel.SectionResult result = BookletRecipePanel.drawUsedIn(
					context, this.font, px, py, availableWidth, usedInAvailableHeight, cachedUsedIn, usedInPage, icons, hoverSlots);
			py = result.py();
			usedInTotalPages = result.totalPages();
			moreUsedInRect = result.moreRect();
			usedInIconRects = icons;
		}

		context.disableScissor();
		detailContentHeight = (py + scrollOffset) - detailBodyTopY;

		recipeHoverSlots = hoverSlots;
	}

	/**
	 * A bordered box with a bold title and wrapped text - used for the bundled
	 * "how to get this" note and for your own /vbooklet note. Line breaks in
	 * the text start new lines.
	 */
	private int drawNotePanel(GuiGraphicsExtractor context, int px, int py, int availableWidth, Component title, String note) {
		int pad = 8;
		int panelW = availableWidth;
		List<FormattedCharSequence> lines = new ArrayList<>();
		for (String paragraph : note.split("\n")) {
			lines.addAll(this.font.split(Component.literal(paragraph), panelW - pad * 2));
		}
		int lineHeight = 11;
		int panelH = pad * 2 + 14 + lines.size() * lineHeight;

		context.fill(px, py, px + panelW, py + panelH, BookletUi.COLOR_RECIPE_PANEL);
		BookletUi.border(context, px, py, panelW, panelH, BookletUi.COLOR_RECIPE_PANEL_BORDER);

		context.text(this.font, title.copy().withStyle(ChatFormatting.BOLD), px + pad, py + pad, BookletUi.COLOR_ACCENT);

		int lineY = py + pad + 14;
		for (FormattedCharSequence line : lines) {
			context.text(this.font, line, px + pad, lineY, BookletUi.COLOR_TEXT);
			lineY += lineHeight;
		}

		return py + panelH;
	}

	private static final int DETAIL_SECTION_GAP = 18;

	// Every small inline action button (favorite, back, copy id, -/+) shares one size/style.
	static final int ACTION_BUTTON_SIZE = 12;

	private void drawActionButton(GuiGraphicsExtractor context, int x, int y, String glyph, int glyphColor) {
		context.fill(x, y, x + ACTION_BUTTON_SIZE, y + ACTION_BUTTON_SIZE, BookletUi.COLOR_RECIPE_SLOT);
		BookletUi.border(context, x, y, ACTION_BUTTON_SIZE, ACTION_BUTTON_SIZE, BookletUi.COLOR_RECIPE_SLOT_BORDER);
		Component label = Component.literal(glyph);
		context.text(this.font, label, x + (ACTION_BUTTON_SIZE - this.font.width(label)) / 2, y + 2, glyphColor);
	}

	int minusButtonX() {
		return detailX + detailWidth - 8 - ACTION_BUTTON_SIZE * 2 - 4;
	}

	int plusButtonX() {
		return detailX + detailWidth - 8 - ACTION_BUTTON_SIZE;
	}

	int amountButtonY() {
		return amountLineY - 1;
	}

	/** Header is always this fixed first-drawn offset (detailY+8), vertically centering a 12px button in the 26px header bar. */
	int favoriteButtonX() {
		return detailX + detailWidth - 16 - ACTION_BUTTON_SIZE;
	}

	int favoriteButtonY() {
		return detailY + 15;
	}

	int backButtonX() {
		return favoriteButtonX() - ACTION_BUTTON_SIZE - 4;
	}

	int backButtonY() {
		return favoriteButtonY();
	}

	int copyIdButtonX() {
		return detailX + detailWidth - 8 - ACTION_BUTTON_SIZE;
	}

	int copyIdButtonY() {
		return typeLineY - 1;
	}

	/**
	 * The list's actual visible height for row content - listHeight minus the
	 * ingredient-filter banner when that banner is showing. Every scroll clamp
	 * and "rows per page" calculation goes through this so they match what the
	 * list actually draws.
	 */
	int listContentHeight() {
		return listHeight - (ingredientFilterItemId != null ? FILTER_BANNER_HEIGHT : 0);
	}

	/** How many whole list rows fit, at least one. */
	int listRowsVisible() {
		return Math.max(1, listContentHeight() / ROW_HEIGHT);
	}

	/** Manually nudges the selected item's own progress by delta, clamped to [0, required]. */
	void adjustSelectedAmount(int delta) {
		if (selected == null || viewingPlayer != null) {
			return;
		}
		BookletEntry entry = selected;
		int before = BookletLocalProgress.obtained(entry.itemId());
		int after = entry.clamp(before + delta);
		if (after == before) {
			return;
		}
		boolean changed = BookletLocalProgress.mergeIfDifferent(Map.of(entry.itemId(), after));
		if (changed) {
			// Bumping an item's amount can walk it right out of the active Show
			// filter (Undiscovered -> Completed on one click); re-pinning the
			// selection keeps the detail panel on the item you just edited instead
			// of refreshFiltered() silently closing it.
			data.invalidate();
			refreshFiltered();
			selected = entry;
			BookletAutoPush.scheduleFromRemembered();
		}
	}

	/** Copies the selected item's display name ("Turtle_Scute"), not its registry id, to the clipboard. */
	void copySelectedItemId() {
		if (selected == null) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		// Minecraft's own GLFW-backed clipboard - java.awt.Toolkit throws a
		// HeadlessException in this game's JVM.
		client.keyboardHandler.setClipboard(selected.displayName());
		client.gui.toastManager().addToast(new SystemToast(
				COPY_ID_TOAST_ID,
				Component.translatable("vmrbooklet.title"),
				Component.translatable("vmrbooklet.toast.copied", selected.displayName())
		));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		return BookletScreenInput.mouseClicked(this, event, doubled);
	}

	boolean superMouseClicked(MouseButtonEvent event, boolean doubled) {
		return super.mouseClicked(event, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		return BookletScreenInput.mouseScrolled(this, mouseX, mouseY, scrollX, scrollY);
	}

	boolean superMouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		return BookletScreenInput.keyPressed(this, event);
	}

	boolean superKeyPressed(KeyEvent event) {
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (swallowCharUntilNanos != 0L) {
			boolean withinWindow = System.nanoTime() <= swallowCharUntilNanos;
			swallowCharUntilNanos = 0L;
			if (withinWindow) {
				return true;
			}
		}
		return super.charTyped(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
