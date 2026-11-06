package dev.aponder.vmrbooklet.client.gui;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.client.data.BookletLocalProgress;
import dev.aponder.vmrbooklet.client.data.BookletSettings;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Tints container slots that hold an item you still need for the booklet, so
 * sorting loot or a shulker box shows at a glance what's worth keeping.
 * Called from a mixin right after vanilla draws each slot; the slot's own
 * x/y are already in the container's translated coordinate space.
 */
public final class BookletSlotHighlight {

	private static final int SLOT_SIZE = 16;
	private static final int TINT = 0x50FFD36B;
	private static final int EDGE = 0xC0FFD36B;
	private static final int TINT_CB = 0x5040A0FF;
	private static final int EDGE_CB = 0xC040A0FF;

	private BookletSlotHighlight() {
	}

	public static void afterSlot(Screen screen, GuiGraphicsExtractor context, Slot slot) {
		// The creative tabs are a catalog of every item, not things you own -
		// highlighting half of it would just be noise.
		if (!BookletSettings.highlightNeeded() || screen instanceof CreativeModeInventoryScreen) {
			return;
		}
		ItemStack stack = slot.getItem();
		if (stack.isEmpty()) {
			return;
		}
		BookletEntry entry = BookletData.byItem(stack.getItem());
		if (entry == null || BookletLocalProgress.obtained(entry.itemId()) >= entry.required()) {
			return;
		}
		boolean cb = BookletSettings.colorblind();
		int x = slot.x;
		int y = slot.y;
		context.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, cb ? TINT_CB : TINT);
		BookletUi.border(context, x, y, SLOT_SIZE, SLOT_SIZE, cb ? EDGE_CB : EDGE);
	}
}
