package dev.aponder.vmrbooklet.client.gui;

import dev.aponder.vmrbooklet.BookletClassifier;
import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.BookletState;
import dev.aponder.vmrbooklet.client.data.BookletGoals;
import dev.aponder.vmrbooklet.client.data.BookletLocalProgress;
import dev.aponder.vmrbooklet.client.data.BookletSettings;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Adds a "Booklet: 3/5" (or "✓ Booklet") line under the name of any booklet
 * item's tooltip - in every inventory, chest, villager trade and recipe view,
 * not just inside the booklet screen.
 *
 * <p>Vanilla can build tooltips off the render thread (the creative search
 * index), so this only reads thread-safe state: the concurrent progress map
 * and volatile settings.
 */
public final class BookletItemTooltip {

	private BookletItemTooltip() {
	}

	public static void register() {
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
			if (!BookletSettings.tooltipLine() || stack.isEmpty() || lines.isEmpty()) {
				return;
			}
			BookletEntry entry = BookletData.byItem(stack.getItem());
			if (entry == null) {
				return;
			}
			int obtained = BookletLocalProgress.obtained(entry.itemId());
			BookletState state = BookletClassifier.classify(entry, obtained);
			MutableComponent line = state == BookletState.ACHIEVED
					? Component.translatable("vmrbooklet.tooltip.done").withStyle(BookletUi.stateFormatting(state))
					: Component.translatable("vmrbooklet.tooltip.progress", obtained, entry.required()).withStyle(BookletUi.stateFormatting(state));
			if (BookletGoals.isGoal(entry.itemId())) {
				line.append(Component.translatable("vmrbooklet.tooltip.goal").withStyle(ChatFormatting.GOLD));
			}
			// Right under the item name, ahead of enchantments/lore/advanced-id lines.
			lines.add(1, line);
		});
	}
}
