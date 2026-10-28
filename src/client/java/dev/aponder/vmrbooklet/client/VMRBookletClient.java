package dev.aponder.vmrbooklet.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.aponder.vmrbooklet.client.gui.BookletScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;

/**
 * Everything here is client-side only: commands, the keybind, the screen. This
 * mod never registers anything server-side and never touches the server you're
 * connected to - progress comes entirely from {@code /upload}.
 */
public class VMRBookletClient implements ClientModInitializer {

	private static KeyMapping openBookletKey;
	private static long lastToggleMillis = 0L;
	private static final long TOGGLE_DEBOUNCE_MILLIS = 250L;

	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommands.literal("vbooklet")
					.executes(ctx -> {
						// Typing /vbooklet and hitting Enter runs this command WHILE the
						// chat screen is still open - its own Enter handling then closes
						// chat right after, which clobbers whatever screen we just set,
						// so the booklet flashed open for one frame and immediately shut.
						// Deferring to next tick runs this after chat has already closed.
						var client = ctx.getSource().getClient();
						client.execute(() -> client.setScreenAndShow(new BookletScreen()));
						return 1;
					})
					.then(ClientCommands.literal("undo")
							.executes(VMRBookletClient::runUndo))
					.then(ClientCommands.literal("backup")
							.executes(ctx -> BookletShareImport.generateBackup(ctx.getSource())))
					.then(ClientCommands.literal("export-csv")
							.executes(ctx -> BookletCsvExport.run(ctx.getSource())))
					.then(ClientCommands.literal("unknown")
							.executes(VMRBookletClient::runUnknown))
					.then(ClientCommands.literal("missing")
							.executes(ctx -> BookletMissingExport.run(ctx.getSource())))
					.then(ClientCommands.literal("restore")
							// A bare "/vbooklet restore" reads the code straight off the
							// clipboard - codes are long enough that pasting one into the
							// chat box can hit its own input length limit before the
							// command even runs, so this skips the chat box entirely.
							.executes(ctx -> BookletShareImport.restoreFromClipboard(ctx.getSource()))
							.then(ClientCommands.argument("code", StringArgumentType.greedyString())
									.executes(ctx -> BookletShareImport.restoreFromArgument(
											ctx.getSource(), StringArgumentType.getString(ctx, "code"))))));

			dispatcher.register(ClientCommands.literal("upload")
					.executes(ctx -> BookletUpload.runRemembered(ctx.getSource()))
					.then(ClientCommands.argument("link", StringArgumentType.greedyString())
							.executes(VMRBookletClient::dispatchLinkOrCode)));

			dispatcher.register(ClientCommands.literal("import")
					// Same clipboard shortcut as /vbooklet restore - see above.
					.executes(ctx -> BookletShareImport.importFromClipboard(ctx.getSource()))
					.then(ClientCommands.argument("link", StringArgumentType.greedyString())
							.executes(ctx -> BookletShareImport.importFromArgument(
									ctx.getSource(), StringArgumentType.getString(ctx, "link")))));

			// No generic <link> argument here (unlike upload/import) - "export" only
			// ever means "export booklet" (share code) or a plain re-sync. A <link>
			// branch here previously reused dispatchLinkOrCode same as upload/import,
			// which meant "/export <a friend's share code>" would silently IMPORT
			// that code instead of doing anything resembling an export - confusing
			// for a command whose whole name implies the opposite direction.
			dispatcher.register(ClientCommands.literal("export")
					.executes(ctx -> BookletUpload.runRemembered(ctx.getSource()))
					.then(ClientCommands.literal("booklet")
							.executes(ctx -> BookletShareImport.generateCode(ctx.getSource()))));
		});

		openBookletKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.vmrbooklet.open",
				InputConstants.Type.KEYBOARD,
				InputConstants.KEY_B,
				KeyMapping.Category.MISC
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openBookletKey.consumeClick()) {
				long now = System.currentTimeMillis();
				if (now - lastToggleMillis < TOGGLE_DEBOUNCE_MILLIS) {
					// Some systems fire more than one click per physical press (key
					// repeat quirks); without this guard that flips the screen open
					// then immediately closed again in the same burst.
					continue;
				}
				lastToggleMillis = now;

				if (BookletScreen.current() != null) {
					client.setScreenAndShow(null);
				} else {
					client.setScreenAndShow(new BookletScreen());
				}
			}
		});

		BookletLiveScanner.register();
		BookletServerTracker.register();
		BookletSellScanner.register();
	}

	/** A share code (from someone's /export booklet) imports as a friend tab; anything else is treated as a sheet link. */
	private static int dispatchLinkOrCode(CommandContext<FabricClientCommandSource> ctx) {
		String text = StringArgumentType.getString(ctx, "link");
		if (BookletShareCode.looksLikeCode(text)) {
			return BookletShareImport.importCode(ctx.getSource(), text);
		}
		return BookletUpload.run(ctx.getSource(), text);
	}

	private static int runUnknown(CommandContext<FabricClientCommandSource> ctx) {
		int count = BookletUnknownItemLog.count();
		if (count == 0) {
			ctx.getSource().sendFeedback(Component.literal(
					"No unrecognized booklet items seen yet - page through the real /booklet GUI first.")
					.withStyle(ChatFormatting.GRAY));
			return 1;
		}
		ctx.getSource().sendFeedback(Component.literal(
				"Found " + count + " unrecognized booklet item(s). Send me this file: " + BookletUnknownItemLog.file())
				.withStyle(ChatFormatting.YELLOW));
		return 1;
	}

	private static int runUndo(CommandContext<FabricClientCommandSource> ctx) {
		if (BookletLocalProgress.undo()) {
			if (BookletScreen.current() != null) {
				BookletScreen.current().onProgressUpdated();
			}
			ctx.getSource().sendFeedback(Component.literal(
					"Reverted to your progress from before the last /upload or restore.")
					.withStyle(ChatFormatting.GREEN));
			return 1;
		}
		ctx.getSource().sendError(Component.literal("Nothing to undo this session."));
		return 0;
	}
}
