package dev.aponder.vmrbooklet.client.commands;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The "build a list, copy it, tell the player" tail end shared by every
 * clipboard-export command ({@code missing}, {@code trades}, {@code need}) -
 * each used to hand-roll the same three lines.
 */
public final class BookletClipboardExport {

	private BookletClipboardExport() {
	}

	public static void copyAndNotify(FabricClientCommandSource source, String content, Component feedback) {
		// Minecraft's own clipboard (GLFW-backed, via KeyboardHandler) - not
		// java.awt.Toolkit, which throws in this game's JVM (AWT runs headless).
		Minecraft.getInstance().keyboardHandler.setClipboard(content);
		source.sendFeedback(feedback.copy().withStyle(ChatFormatting.GREEN));
	}
}
