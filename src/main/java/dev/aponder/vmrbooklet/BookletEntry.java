package dev.aponder.vmrbooklet;

import net.minecraft.world.item.Item;

/**
 * One row of the VMR Booklet: a vanilla item, its two sort positions (ECC release
 * order and creative-inventory order) and how many of it a player must bank to
 * achieve it.
 */
public record BookletEntry(
		Item item,
		String itemId,
		String displayName,
		int eccOrder,
		int creativeOrder,
		int required,
		String release
) {
}
