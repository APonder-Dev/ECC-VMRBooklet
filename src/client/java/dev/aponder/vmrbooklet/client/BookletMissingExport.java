package dev.aponder.vmrbooklet.client;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.BookletSort;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * {@code /vbooklet missing}: copies a plain newline list of everything you
 * haven't obtained yet to the clipboard - meant for pasting into a notes app
 * or a Discord message when planning a farming/trading trip, not for
 * restoring progress (that's the share/backup codes).
 */
public final class BookletMissingExport {

	private BookletMissingExport() {
	}

	public static int run(FabricClientCommandSource source) {
		StringBuilder list = new StringBuilder();
		int count = 0;
		for (BookletEntry entry : BookletData.orderedBy(BookletSort.ECC)) {
			int obtained = BookletLocalProgress.obtained(entry.itemId());
			if (obtained >= entry.required()) {
				continue;
			}
			list.append(entry.displayName().replace('_', ' '))
					.append(" (").append(obtained).append('/').append(entry.required()).append(')')
					.append('\n');
			count++;
		}

		if (count == 0) {
			source.sendFeedback(Component.literal("Nothing missing - your booklet is 100% complete!")
					.withStyle(ChatFormatting.GREEN));
			return 1;
		}

		// Minecraft's own clipboard (GLFW-backed, via KeyboardHandler) - not
		// java.awt.Toolkit, which throws in this game's JVM (AWT runs headless).
		Minecraft.getInstance().keyboardHandler.setClipboard(list.toString());
		source.sendFeedback(Component.literal("Copied " + count + " missing item(s) to the clipboard.")
				.withStyle(ChatFormatting.GREEN));
		return 1;
	}
}
