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
	/** Clamps a progress amount for this item into the only valid range, [0, required()]. */
	public int clamp(int amount) {
		return Math.max(0, Math.min(amount, required));
	}
}
