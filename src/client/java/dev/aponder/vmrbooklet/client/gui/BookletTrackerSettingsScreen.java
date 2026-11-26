package dev.aponder.vmrbooklet.client.gui;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.client.data.BookletSettings;
import dev.aponder.vmrbooklet.client.data.BookletSettings.TrackerColor;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Side tracker options: on/off, which edge, icons, hiding finished rows, and
 * every color as an editable hex value with a swatch, a few presets and a
 * live preview of what the tracker will look like.
 */
public class BookletTrackerSettingsScreen extends Screen {

	private static final int ROW = 22;
	private static final int COLUMN_WIDTH = 200;
	private static final int COLUMN_GAP = 12;
	private static final int LABEL_WIDTH = 70;
	private static final int SWATCH = 16;
	private static final int PREVIEW_LABEL_HEIGHT = 14;

	private record Preset(String key, int[] colors) {
	}

	// Order matches TrackerColor: background, text, count, done, friend. The
	// first preset is the defaults themselves, so "Reset colors" uses it too.
	private static final List<Preset> PRESETS = List.of(
			new Preset("hologram", BookletSettings.defaultTrackerColors()),
			new Preset("gold", new int[] {0xA0201808, 0xFFFFE7A8, 0xFFFFC23D, 0xFFB8F27C, 0xFFFF9E5E}),
			new Preset("ocean", new int[] {0x90081828, 0xFFDDF4FF, 0xFF6FD3FF, 0xFF7CF2C8, 0xFFB79CFF}),
			new Preset("contrast", new int[] {0xE0000000, 0xFFFFFFFF, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF})
	);

	private final Screen parent;
	private final Map<TrackerColor, EditBox> colorBoxes = new EnumMap<>(TrackerColor.class);
	private final Map<TrackerColor, Integer> labelYs = new EnumMap<>(TrackerColor.class);
	private int leftX;
	private int rightX;
	private int titleY;
	private int previewY;
	private int previewHeight;
	private int columnWidth = COLUMN_WIDTH;

	public BookletTrackerSettingsScreen(Screen parent) {
		super(Component.translatable("vmrbooklet.tracker.settings.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		colorBoxes.clear();
		labelYs.clear();
		int centerX = width / 2;
		int y = Math.max(28, height / 2 - 110);
		titleY = y - 18;
		columnWidth = Math.min(COLUMN_WIDTH, (width - 24 - COLUMN_GAP) / 2);
		leftX = centerX - columnWidth - COLUMN_GAP / 2;
		rightX = centerX + COLUMN_GAP / 2;

		// Left column: one hex field per color.
		int colorY = y;
		for (TrackerColor color : TrackerColor.values()) {
			labelYs.put(color, colorY + 6);
			int boxX = leftX + LABEL_WIDTH;
			int boxW = columnWidth - LABEL_WIDTH - SWATCH - 4;
			EditBox box = new EditBox(this.font, boxX, colorY, boxW, 20, colorLabel(color));
			box.setMaxLength(9);
			box.setValue(BookletSettings.formatColor(BookletSettings.trackerColor(color)));
			box.setTooltip(Tooltip.create(Component.translatable("vmrbooklet.tracker.color.tooltip")));
			box.setResponder(value -> {
				Integer argb = BookletSettings.parseColor(value);
				if (argb != null) {
					BookletSettings.setTrackerColorUnsaved(color, argb);
				}
			});
			addRenderableWidget(box);
			colorBoxes.put(color, box);
			colorY += ROW;
		}

		// Presets: two per row under the colors.
		int presetW = (columnWidth - 4) / 2;
		for (int i = 0; i < PRESETS.size(); i++) {
			Preset preset = PRESETS.get(i);
			int px = leftX + (i % 2) * (presetW + 4);
			int py = colorY + 4 + (i / 2) * ROW;
			addRenderableWidget(Button.builder(Component.translatable("vmrbooklet.tracker.preset." + preset.key()), b -> applyColors(preset.colors()))
					.bounds(px, py, presetW, 20).build());
		}
		int leftBottom = colorY + 4 + ((PRESETS.size() + 1) / 2) * ROW;

		// Right column: toggles, then the preview.
		int toggleY = y;
		addToggle(rightX, toggleY, "vmrbooklet.tracker.enabled", BookletSettings::trackerEnabled, BookletSettings::setTrackerEnabled);
		toggleY += ROW;
		addRenderableWidget(Button.builder(sideLabel(), b -> {
			BookletSettings.setTrackerSide(BookletSettings.trackerSide().next());
			b.setMessage(sideLabel());
		}).bounds(rightX, toggleY, columnWidth, 20).build());
		toggleY += ROW;
		addToggle(rightX, toggleY, "vmrbooklet.tracker.icons", BookletSettings::trackerIcons, BookletSettings::setTrackerIcons);
		toggleY += ROW;
		addToggle(rightX, toggleY, "vmrbooklet.tracker.hide_done", BookletSettings::trackerHideDone, BookletSettings::setTrackerHideDone);
		toggleY += ROW + 4;
		previewY = toggleY;
		// Tall enough for three icon rows (3 x 20px) under the 14px "Preview" caption.
		previewHeight = Math.max(PREVIEW_LABEL_HEIGHT + 66, leftBottom - toggleY);

		int buttonY = Math.max(leftBottom, previewY + previewHeight) + 8;
		addRenderableWidget(Button.builder(Component.translatable("vmrbooklet.tracker.reset"),
				b -> applyColors(PRESETS.get(0).colors()))
				.bounds(leftX, buttonY, columnWidth, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"),
				b -> Minecraft.getInstance().setScreenAndShow(parent))
				.bounds(rightX, buttonY, columnWidth, 20).build());
	}

	private void applyColors(int[] colors) {
		BookletSettings.setTrackerColors(colors);
		for (TrackerColor color : TrackerColor.values()) {
			// setValue fires the responder, which re-applies the same value - a no-op.
			colorBoxes.get(color).setValue(BookletSettings.formatColor(BookletSettings.trackerColor(color)));
		}
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreenAndShow(parent);
	}

	// Typed colors only change memory while editing; one save when the screen goes away.
	@Override
	public void removed() {
		BookletSettings.saveTrackerColors();
		super.removed();
	}

	private static Component colorLabel(TrackerColor color) {
		return Component.translatable("vmrbooklet.tracker.color." + color.name().toLowerCase(Locale.ROOT));
	}

	private static Component sideLabel() {
		return Component.translatable("vmrbooklet.tracker.side",
				Component.translatable("vmrbooklet.tracker.side." + BookletSettings.trackerSide().name().toLowerCase(Locale.ROOT)));
	}

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

	/** A few real booklet items for the preview: one of yours in progress, one finished, one of a friend's. */
	private static List<BookletTrackerHud.Row> sampleRows() {
		List<BookletTrackerHud.Row> sample = new ArrayList<>();
		addSample(sample, "minecraft:elytra", null, 1);
		addSample(sample, "minecraft:music_disc_13", null, Integer.MAX_VALUE);
		addSample(sample, "minecraft:diamond", "Alex", 7);
		return sample;
	}

	private static void addSample(List<BookletTrackerHud.Row> sample, String itemId, String friend, int obtained) {
		BookletEntry entry = BookletData.byItemId(itemId);
		if (entry != null) {
			sample.add(new BookletTrackerHud.Row(entry, friend, Math.min(obtained, entry.required()), 0L));
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, width, height, BookletUi.COLOR_BACKDROP);

		Component title = this.title.copy().withStyle(ChatFormatting.BOLD);
		context.text(this.font, title, width / 2 - this.font.width(title) / 2, titleY, BookletUi.COLOR_ACCENT);

		for (TrackerColor color : TrackerColor.values()) {
			int labelY = labelYs.get(color);
			context.text(this.font, colorLabel(color), leftX, labelY, BookletUi.COLOR_TEXT);
			EditBox box = colorBoxes.get(color);
			int swatchX = leftX + columnWidth - SWATCH;
			int swatchY = box.getY() + 2;
			Integer typed = BookletSettings.parseColor(box.getValue());
			// A checkerboard behind the swatch so a translucent color reads as translucent.
			context.fill(swatchX, swatchY, swatchX + SWATCH, swatchY + SWATCH, 0xFF808080);
			context.fill(swatchX, swatchY, swatchX + SWATCH / 2, swatchY + SWATCH / 2, 0xFFC0C0C0);
			context.fill(swatchX + SWATCH / 2, swatchY + SWATCH / 2, swatchX + SWATCH, swatchY + SWATCH, 0xFFC0C0C0);
			if (typed != null) {
				context.fill(swatchX, swatchY, swatchX + SWATCH, swatchY + SWATCH, typed);
			} else {
				context.text(this.font, "!", swatchX + 6, swatchY + 4, 0xFFFF5555);
			}
			BookletUi.border(context, swatchX, swatchY, SWATCH, SWATCH, 0xFF000000);
		}

		// Preview: a stand-in for the game world behind the tracker.
		context.fill(rightX, previewY, rightX + columnWidth, previewY + previewHeight, 0xFF3A5A2A);
		context.fill(rightX, previewY, rightX + columnWidth, previewY + previewHeight / 2, 0xFF6A8FC8);
		BookletUi.border(context, rightX, previewY, columnWidth, previewHeight, 0xFF000000);
		context.text(this.font, Component.translatable("vmrbooklet.tracker.preview"), rightX + 4, previewY + 3, 0xFFFFFFFF);
		// Centered in the area under the caption, so no row size can cover it.
		int rowsTop = previewY + PREVIEW_LABEL_HEIGHT;
		BookletTrackerHud.drawPreview(context, this.font, sampleRows(), rightX, columnWidth,
				rowsTop + (previewY + previewHeight - rowsTop) / 2 + 2);

		super.extractRenderState(context, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
