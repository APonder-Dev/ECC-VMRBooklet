package dev.aponder.vmrbooklet.client.gui;

import dev.aponder.vmrbooklet.client.VMRBookletModMenuIntegration;
import dev.aponder.vmrbooklet.client.data.BookletSettings;
import dev.aponder.vmrbooklet.client.sync.GoogleServiceAccount;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The screen Mod Menu opens for this mod (see {@link VMRBookletModMenuIntegration}) -
 * a plain settings panel, separate from {@link BookletScreen}'s own item
 * browser, for anyone who has Mod Menu installed.
 *
 * <p>The sheet-link field here only remembers the link (same storage
 * {@code /upload <link>} uses) - it deliberately doesn't trigger a sync
 * itself, since the upload/feedback machinery is built around a chat command
 * source that a plain settings screen doesn't have.
 */
public class BookletSettingsScreen extends Screen {

	private static final int COLOR_TEXT_DIM = 0xFF9A9AA6;
	private static final int ROW = 22;
	private static final int COLUMN_WIDTH = 200;
	private static final int COLUMN_GAP = 8;

	private final Screen parent;
	private EditBox sheetLinkBox;
	private int sheetLabelY;
	private int syncLineY;
	private int titleY;
	private int columnWidth = COLUMN_WIDTH;

	public BookletSettingsScreen(Screen parent) {
		super(Component.translatable("vmrbooklet.settings.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int centerX = width / 2;
		// Two columns of toggles, then the sheet link and buttons: about 220px tall.
		int y = Math.max(28, height / 2 - 110);
		titleY = y - 18;
		// Narrow windows (high GUI scale) shrink the columns rather than clip them.
		columnWidth = Math.min(COLUMN_WIDTH, (width - 24 - COLUMN_GAP) / 2);
		int leftX = centerX - columnWidth - COLUMN_GAP / 2;
		int rightX = centerX + COLUMN_GAP / 2;

		addToggle(leftX, y, "vmrbooklet.settings.notifications",
				BookletSettings::scanNotifications, BookletSettings::setScanNotifications);
		addToggle(rightX, y, "vmrbooklet.settings.tooltip_line",
				BookletSettings::tooltipLine, BookletSettings::setTooltipLine);
		y += ROW;
		addToggle(leftX, y, "vmrbooklet.settings.streak_line",
				BookletSettings::showStreakLine, BookletSettings::setShowStreakLine);
		addToggle(rightX, y, "vmrbooklet.settings.highlight",
				BookletSettings::highlightNeeded, BookletSettings::setHighlightNeeded);
		y += ROW;
		addToggle(leftX, y, "vmrbooklet.settings.goal_line",
				BookletSettings::showGoalLine, BookletSettings::setShowGoalLine);
		addToggle(rightX, y, "vmrbooklet.settings.hud",
				BookletSettings::hudEnabled, BookletSettings::setHudEnabled);
		y += ROW;
		addToggle(leftX, y, "vmrbooklet.settings.session_summary",
				BookletSettings::sessionSummary, BookletSettings::setSessionSummary);
		addRenderableWidget(Button.builder(cornerLabel(), b -> {
			BookletSettings.setHudCorner(BookletSettings.hudCorner().next());
			b.setMessage(cornerLabel());
		}).bounds(rightX, y, columnWidth, 20)
				.tooltip(Tooltip.create(Component.translatable("vmrbooklet.settings.hud_corner.tooltip")))
				.build());
		y += ROW;
		addToggle(leftX, y, "vmrbooklet.settings.colorblind",
				BookletSettings::colorblind, BookletSettings::setColorblind);
		y += ROW + 6;

		sheetLabelY = y;
		y += 12;
		sheetLinkBox = new EditBox(this.font, centerX - 150, y, 300, 20, Component.translatable("vmrbooklet.settings.sheet_link"));
		sheetLinkBox.setMaxLength(512);
		sheetLinkBox.setValue(nullToEmpty(BookletSettings.lastSheetLink()));
		sheetLinkBox.setResponder(value -> BookletSettings.setLastSheetLink(value.trim()));
		addRenderableWidget(sheetLinkBox);
		y += 26;

		syncLineY = y;
		y += 16;

		addRenderableWidget(Button.builder(Component.translatable("vmrbooklet.settings.open_booklet"),
				b -> Minecraft.getInstance().setScreenAndShow(new BookletScreen()))
				.bounds(leftX, y, columnWidth, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"),
				b -> Minecraft.getInstance().setScreenAndShow(parent))
				.bounds(rightX, y, columnWidth, 20).build());

		setInitialFocus(sheetLinkBox);
	}

	private static Component cornerLabel() {
		return Component.translatable("vmrbooklet.settings.hud_corner",
				Component.translatable("vmrbooklet.settings.hud_corner." + BookletSettings.hudCorner().name().toLowerCase(Locale.ROOT)));
	}

	/** One On/Off button for a boolean setting; its tooltip key is {@code labelKey + ".tooltip"}. */
	private void addToggle(int x, int y, String labelKey, BooleanSupplier getter, Consumer<Boolean> setter) {
		addRenderableWidget(Button.builder(toggleLabel(labelKey, getter.getAsBoolean()), b -> {
			boolean next = !getter.getAsBoolean();
			setter.accept(next);
			b.setMessage(toggleLabel(labelKey, next));
		}).bounds(x, y, columnWidth, 20)
				.tooltip(Tooltip.create(Component.translatable(labelKey + ".tooltip")))
				.build());
	}

	private static Component toggleLabel(String labelKey, boolean on) {
		return Component.translatable(labelKey, Component.translatable(on ? "options.on" : "options.off"));
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, width, height, BookletUi.COLOR_BACKDROP);

		Component title = this.title.copy().withStyle(ChatFormatting.BOLD);
		context.text(this.font, title, width / 2 - this.font.width(title) / 2, titleY, BookletUi.COLOR_ACCENT);

		context.text(this.font, Component.translatable("vmrbooklet.settings.sheet_link_label"), width / 2 - 150, sheetLabelY, COLOR_TEXT_DIM);

		Component syncLine = Component.translatable(GoogleServiceAccount.isConfigured()
				? "vmrbooklet.settings.write_back_on" : "vmrbooklet.settings.write_back_off");
		context.text(this.font, syncLine, width / 2 - this.font.width(syncLine) / 2, syncLineY, COLOR_TEXT_DIM);

		super.extractRenderState(context, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
