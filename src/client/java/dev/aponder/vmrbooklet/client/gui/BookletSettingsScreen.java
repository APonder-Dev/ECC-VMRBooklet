package dev.aponder.vmrbooklet.client.gui;

import dev.aponder.vmrbooklet.client.BookletSettings;
import dev.aponder.vmrbooklet.client.GoogleServiceAccount;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The screen Mod Menu opens for this mod (see {@link dev.aponder.vmrbooklet.client.VMRBookletModMenuIntegration}) -
 * a plain settings panel, separate from {@link BookletScreen}'s own item
 * browser. Everything here mirrors a setting already reachable via chat
 * commands ({@code /upload}, the booklet's own Notify button); this is just a
 * more discoverable home for them for anyone who has Mod Menu installed.
 *
 * <p>The sheet-link field here only remembers the link (same storage
 * {@code /upload <link>} uses) - it deliberately doesn't trigger a sync
 * itself, since the actual upload/feedback machinery is built around a chat
 * command source that a plain settings screen doesn't have one of.
 */
public class BookletSettingsScreen extends Screen {

	private static final int COLOR_BACKDROP = 0xC0000000;
	private static final int COLOR_TEXT_DIM = 0xFF9A9AA6;

	private final Screen parent;
	private EditBox sheetLinkBox;

	public BookletSettingsScreen(Screen parent) {
		super(Component.literal("VMR Booklet Settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int centerX = width / 2;
		int y = height / 2 - 70;

		addRenderableWidget(Button.builder(Component.literal(notifyLabel()), b -> {
			BookletSettings.setScanNotifications(!BookletSettings.scanNotifications());
			b.setMessage(Component.literal(notifyLabel()));
		}).bounds(centerX - 100, y, 200, 20).build());
		y += 28;

		sheetLinkBox = new EditBox(this.font, centerX - 150, y, 300, 20, Component.literal("Google Sheet link"));
		sheetLinkBox.setMaxLength(512);
		sheetLinkBox.setValue(nullToEmpty(BookletSettings.lastSheetLink()));
		sheetLinkBox.setResponder(value -> BookletSettings.setLastSheetLink(value.trim()));
		addRenderableWidget(sheetLinkBox);
		y += 34;

		addRenderableWidget(Button.builder(Component.literal("Open Booklet"), b -> {
			Minecraft.getInstance().setScreenAndShow(new BookletScreen());
		}).bounds(centerX - 100, y, 200, 20).build());
		y += 28;

		addRenderableWidget(Button.builder(Component.literal("Done"), b -> {
			Minecraft.getInstance().setScreenAndShow(parent);
		}).bounds(centerX - 100, y, 200, 20).build());

		setInitialFocus(sheetLinkBox);
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private String notifyLabel() {
		return "Scan notifications: " + (BookletSettings.scanNotifications() ? "On" : "Off");
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, width, height, COLOR_BACKDROP);

		Component title = Component.literal("VMR Booklet Settings").withStyle(ChatFormatting.BOLD);
		context.text(this.font, title, width / 2 - this.font.width(title) / 2, height / 2 - 100, 0xFFFFD36B);

		Component sheetLabel = Component.literal("Google Sheet link (used by /upload with no argument):");
		context.text(this.font, sheetLabel, width / 2 - 150, height / 2 - 36, COLOR_TEXT_DIM);

		String syncLine = "Sheet write-back: " + (GoogleServiceAccount.isConfigured() ? "configured" : "not set up")
				+ "  |  Run /upload to sync with the link above";
		context.text(this.font, Component.literal(syncLine), width / 2 - this.font.width(syncLine) / 2, height / 2 + 32, COLOR_TEXT_DIM);

		super.extractRenderState(context, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
