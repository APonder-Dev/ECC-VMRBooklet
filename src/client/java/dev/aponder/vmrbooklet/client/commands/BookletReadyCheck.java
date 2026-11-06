package dev.aponder.vmrbooklet.client.commands;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.client.data.BookletLocalProgress;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * {@code /vbooklet ready}: which items you're carrying right now still count
 * toward the booklet - what's worth handing in or selling before you go
 * anywhere else.
 */
public final class BookletReadyCheck {

	private static final int MAX_LINES = 20;

	private BookletReadyCheck() {
	}

	public static int run(FabricClientCommandSource source) {
		Inventory inventory = source.getPlayer().getInventory();
		// Insertion-ordered, so the output follows your inventory's own slot order.
		Map<BookletEntry, Integer> held = new LinkedHashMap<>();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			BookletEntry entry = BookletData.byItem(stack.getItem());
			if (entry != null) {
				held.merge(entry, stack.getCount(), Integer::sum);
			}
		}

		int needed = 0;
		int finishable = 0;
		int shown = 0;
		for (Map.Entry<BookletEntry, Integer> e : held.entrySet()) {
			BookletEntry entry = e.getKey();
			int remaining = entry.required() - BookletLocalProgress.obtained(entry.itemId());
			if (remaining <= 0) {
				continue;
			}
			needed++;
			boolean enough = e.getValue() >= remaining;
			if (enough) {
				finishable++;
			}
			if (shown < MAX_LINES) {
				shown++;
				source.sendFeedback(Component.translatable(enough ? "vmrbooklet.cmd.ready.line_enough" : "vmrbooklet.cmd.ready.line",
								entry.displayName().replace('_', ' '), e.getValue(), remaining)
						.withStyle(enough ? ChatFormatting.GREEN : ChatFormatting.GRAY));
			}
		}

		if (needed == 0) {
			source.sendFeedback(Component.translatable("vmrbooklet.cmd.ready.none").withStyle(ChatFormatting.GRAY));
			return 1;
		}
		if (needed > shown) {
			source.sendFeedback(Component.translatable("vmrbooklet.cmd.ready.more", needed - shown).withStyle(ChatFormatting.DARK_GRAY));
		}
		source.sendFeedback(Component.translatable("vmrbooklet.cmd.ready.summary", needed, finishable).withStyle(ChatFormatting.GOLD));
		return 1;
	}
}
