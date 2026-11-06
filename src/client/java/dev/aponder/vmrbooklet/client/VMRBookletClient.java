package dev.aponder.vmrbooklet.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.VMRBooklet;
import dev.aponder.vmrbooklet.client.commands.BookletCsvExport;
import dev.aponder.vmrbooklet.client.commands.BookletMissingExport;
import dev.aponder.vmrbooklet.client.commands.BookletNeededTree;
import dev.aponder.vmrbooklet.client.commands.BookletNoteCommand;
import dev.aponder.vmrbooklet.client.commands.BookletProgressCard;
import dev.aponder.vmrbooklet.client.commands.BookletReadyCheck;
import dev.aponder.vmrbooklet.client.commands.BookletTradeFinder;
import dev.aponder.vmrbooklet.client.data.BookletGoals;
import dev.aponder.vmrbooklet.client.data.BookletLocalProgress;
import dev.aponder.vmrbooklet.client.gui.BookletCelebrations;
import dev.aponder.vmrbooklet.client.gui.BookletGoalHud;
import dev.aponder.vmrbooklet.client.gui.BookletItemTooltip;
import dev.aponder.vmrbooklet.client.gui.BookletScreen;
import dev.aponder.vmrbooklet.client.scanning.BookletLiveScanner;
import dev.aponder.vmrbooklet.client.scanning.BookletSellScanner;
import dev.aponder.vmrbooklet.client.scanning.BookletServerTracker;
import dev.aponder.vmrbooklet.client.scanning.BookletUnknownItemLog;
import dev.aponder.vmrbooklet.client.sync.BookletShareCode;
import dev.aponder.vmrbooklet.client.sync.BookletShareImport;
import dev.aponder.vmrbooklet.client.sync.BookletUpload;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Everything here is client-side only: commands, the keybind, the screen. This
 * mod never registers anything server-side and never touches the server you're
 * connected to - progress comes entirely from {@code /upload}.
 */
public class VMRBookletClient implements ClientModInitializer {

	private static KeyMapping openBookletKey;
	private static KeyMapping focusSearchKey;
	private static long lastToggleMillis = 0L;
	private static final long TOGGLE_DEBOUNCE_MILLIS = 250L;
	// Minecraft itself exposes no public getter for "the current screen" in this
	// version - tracked by hand via ScreenEvents so the B key can tell whether
	// some OTHER screen (title screen, a sign, the real Creative inventory, chat)
	// currently has it, rather than stealing every "b" keystroke typed anywhere.
	private static boolean otherScreenOpen = false;
	// Weak so a closed-and-discarded screen's entry doesn't keep it alive just
	// because this set still references it.
	private static final Set<Screen> trackedForRemoval =
			Collections.newSetFromMap(new WeakHashMap<>());

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
							.executes(ctx -> BookletMissingExport.run(ctx.getSource()))
							.then(ClientCommands.literal("notes")
									.executes(ctx -> BookletMissingExport.runWithNotes(ctx.getSource())))
							.then(ClientCommands.literal("bycategory")
									.executes(ctx -> BookletMissingExport.runByCategory(ctx.getSource()))))
					.then(ClientCommands.literal("trades")
							.executes(ctx -> BookletTradeFinder.run(ctx.getSource())))
					.then(ClientCommands.literal("ready")
							.executes(ctx -> BookletReadyCheck.run(ctx.getSource())))
					.then(ClientCommands.literal("card")
							.executes(ctx -> BookletProgressCard.run(ctx.getSource())))
					.then(ClientCommands.literal("reload")
							.executes(VMRBookletClient::runReload))
					.then(ClientCommands.literal("note")
							.then(ClientCommands.argument("args", StringArgumentType.greedyString())
									.executes(ctx -> BookletNoteCommand.run(ctx.getSource(),
											StringArgumentType.getString(ctx, "args")))))
					.then(ClientCommands.literal("need")
							.then(ClientCommands.argument("item", StringArgumentType.greedyString())
									.executes(ctx -> BookletNeededTree.run(ctx.getSource(),
											StringArgumentType.getString(ctx, "item")))))
					.then(ClientCommands.literal("goal")
							.executes(VMRBookletClient::runGoalList)
							.then(ClientCommands.literal("clear")
									.executes(VMRBookletClient::runGoalClear))
							.then(ClientCommands.literal("lists")
									.executes(VMRBookletClient::runGoalLists))
							.then(ClientCommands.literal("list")
									.then(ClientCommands.argument("name", StringArgumentType.greedyString())
											.executes(VMRBookletClient::runGoalSwitchList)))
							.then(ClientCommands.literal("delete")
									.then(ClientCommands.argument("name", StringArgumentType.greedyString())
											.executes(VMRBookletClient::runGoalDeleteList)))
							.then(ClientCommands.argument("item", StringArgumentType.greedyString())
									.executes(VMRBookletClient::runGoalToggle)))
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

		KeyMapping.Category category = KeyMapping.Category.register(
				Identifier.fromNamespaceAndPath(VMRBooklet.MOD_ID, "main"));

		openBookletKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.vmrbooklet.open",
				InputConstants.Type.KEYBOARD,
				InputConstants.KEY_B,
				category
		));

		// Vanilla only delivers KeyMapping clicks while no screen is open, so this
		// is checked by BookletScreen.keyPressed itself, never by the tick handler.
		// Unbound by default: "/" and Ctrl+F already focus search inside the
		// booklet, and binding "/" here would show as a conflict with chat commands.
		focusSearchKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.vmrbooklet.search",
				InputConstants.Type.KEYBOARD,
				InputConstants.UNKNOWN.getValue(),
				category
		));

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof BookletScreen) {
				otherScreenOpen = false;
				return;
			}
			otherScreenOpen = true;
			// AFTER_INIT re-fires every time an already-open screen is re-init'd (a
			// window resize while it's up, for instance), not just on first open -
			// trackedForRemoval (identity-keyed, weak) stops that from stacking up a
			// fresh, never-cleaned-up remove() listener on the same screen instance
			// each time.
			if (trackedForRemoval.add(screen)) {
				ScreenEvents.remove(screen).register(s -> otherScreenOpen = false);
				if (screen instanceof AbstractContainerScreen<?> containerScreen) {
					BookletHoverLookup.attach(containerScreen);
				}
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openBookletKey.consumeClick()) {
				// KeyMapping tracks the raw keypress regardless of what's focused, so
				// typing "b" into an unrelated open screen's own text field (the real
				// Creative Inventory's search box, a sign, an anvil, chat) would both
				// land that character there AND satisfy this click - silently ripping
				// that screen away under the player. Only ever act on B when nothing
				// else is open, or when it's this mod's own screen being closed.
				if (otherScreenOpen) {
					continue;
				}

				long now = System.currentTimeMillis();
				if (now - lastToggleMillis < TOGGLE_DEBOUNCE_MILLIS) {
					// Some systems fire more than one click per physical press (key
					// repeat quirks); without this guard that flips the screen open
					// then immediately closed again in the same burst.
					continue;
				}
				lastToggleMillis = now;

				// Closing with B is handled by BookletScreen.keyPressed - no click
				// ever reaches here while a screen (including the booklet) is open.
				client.setScreenAndShow(new BookletScreen());
			}
		});

		BookletItemTooltip.register();
		BookletGoalHud.register();
		// Leaving a server is the other natural end of a session.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(BookletCelebrations::notifySessionSummary));

		BookletLiveScanner.register();
		BookletServerTracker.register();
		BookletSellScanner.register();
	}

	public static KeyMapping openBookletKey() {
		return openBookletKey;
	}

	public static KeyMapping focusSearchKey() {
		return focusSearchKey;
	}

	/** A share code (from someone's /export booklet) imports as a friend tab; anything else is treated as a sheet link. */
	private static int dispatchLinkOrCode(CommandContext<FabricClientCommandSource> ctx) {
		String text = StringArgumentType.getString(ctx, "link");
		if (BookletShareCode.looksLikeCode(text)) {
			return BookletShareImport.importCode(ctx.getSource(), text);
		}
		return BookletUpload.run(ctx.getSource(), text);
	}

	private static int runReload(CommandContext<FabricClientCommandSource> ctx) {
		BookletData.LoadResult result = BookletData.load();
		if (result.error() != null) {
			ctx.getSource().sendError(Component.translatable("vmrbooklet.cmd.reload.custom_failed",
					BookletData.customFile().toString(), result.error()));
		}
		ctx.getSource().sendFeedback(Component.translatable(result.custom() ? "vmrbooklet.cmd.reload.custom" : "vmrbooklet.cmd.reload.bundled",
				result.loaded(), result.skipped()).withStyle(ChatFormatting.GREEN));
		return 1;
	}

	private static int runUnknown(CommandContext<FabricClientCommandSource> ctx) {
		int count = BookletUnknownItemLog.count();
		if (count == 0) {
			ctx.getSource().sendFeedback(Component.translatable("vmrbooklet.cmd.unknown.none")
					.withStyle(ChatFormatting.GRAY));
			return 1;
		}
		ctx.getSource().sendFeedback(Component.translatable("vmrbooklet.cmd.unknown.found", count, BookletUnknownItemLog.file().toString())
				.withStyle(ChatFormatting.YELLOW));
		return 1;
	}

	private static int runGoalToggle(CommandContext<FabricClientCommandSource> ctx) {
		String query = StringArgumentType.getString(ctx, "item");
		BookletEntry entry = BookletData.byQuery(query);
		if (entry == null) {
			ctx.getSource().sendError(Component.translatable("vmrbooklet.cmd.no_match", query));
			return 0;
		}
		boolean nowGoal = BookletGoals.toggle(entry.itemId());
		ctx.getSource().sendFeedback(Component.translatable(nowGoal ? "vmrbooklet.cmd.goal.added" : "vmrbooklet.cmd.goal.removed",
				entry.displayName().replace('_', ' '), BookletGoals.activeListName())
				.withStyle(ChatFormatting.GREEN));
		return 1;
	}

	private static int runGoalClear(CommandContext<FabricClientCommandSource> ctx) {
		BookletGoals.clear();
		ctx.getSource().sendFeedback(Component.translatable("vmrbooklet.cmd.goal.cleared", BookletGoals.activeListName())
				.withStyle(ChatFormatting.GREEN));
		return 1;
	}

	private static int runGoalLists(CommandContext<FabricClientCommandSource> ctx) {
		for (String name : BookletGoals.listNames()) {
			boolean active = name.equals(BookletGoals.activeListName());
			ctx.getSource().sendFeedback(Component.literal((active ? "* " : "  ") + name)
					.withStyle(active ? ChatFormatting.GOLD : ChatFormatting.GRAY));
		}
		return 1;
	}

	private static int runGoalSwitchList(CommandContext<FabricClientCommandSource> ctx) {
		String name = StringArgumentType.getString(ctx, "name").trim();
		if (name.isEmpty()) {
			ctx.getSource().sendError(Component.translatable("vmrbooklet.cmd.goal.empty_name"));
			return 0;
		}
		boolean created = BookletGoals.switchOrCreate(name);
		ctx.getSource().sendFeedback(Component.translatable(created ? "vmrbooklet.cmd.goal.created" : "vmrbooklet.cmd.goal.switched",
				BookletGoals.activeListName())
				.withStyle(ChatFormatting.GREEN));
		return 1;
	}

	private static int runGoalDeleteList(CommandContext<FabricClientCommandSource> ctx) {
		String name = StringArgumentType.getString(ctx, "name").trim();
		return switch (BookletGoals.delete(name)) {
			case DELETED -> {
				ctx.getSource().sendFeedback(Component.translatable("vmrbooklet.cmd.goal.deleted", name, BookletGoals.activeListName()).withStyle(ChatFormatting.GREEN));
				yield 1;
			}
			case IS_DEFAULT -> {
				ctx.getSource().sendError(Component.translatable("vmrbooklet.cmd.goal.delete_default"));
				yield 0;
			}
			case NOT_FOUND -> {
				ctx.getSource().sendError(Component.translatable("vmrbooklet.cmd.goal.not_found", name));
				yield 0;
			}
		};
	}

	private static int runGoalList(CommandContext<FabricClientCommandSource> ctx) {
		var goals = BookletGoals.all();
		if (goals.isEmpty()) {
			ctx.getSource().sendFeedback(Component.translatable("vmrbooklet.cmd.goal.none").withStyle(ChatFormatting.GRAY));
			return 1;
		}
		int done = 0;
		for (String itemId : goals) {
			BookletEntry entry = BookletData.byItemId(itemId);
			if (entry == null) continue;
			int obtained = BookletLocalProgress.obtained(itemId);
			boolean achieved = obtained >= entry.required();
			if (achieved) done++;
			ctx.getSource().sendFeedback(Component.literal(
					(achieved ? "✓ " : "  ") + entry.displayName().replace('_', ' ') + " (" + obtained + "/" + entry.required() + ")")
					.withStyle(achieved ? ChatFormatting.GREEN : ChatFormatting.GRAY));
		}
		ctx.getSource().sendFeedback(Component.translatable("vmrbooklet.cmd.goal.summary", done, goals.size())
				.withStyle(ChatFormatting.GOLD));
		return 1;
	}

	private static int runUndo(CommandContext<FabricClientCommandSource> ctx) {
		if (BookletLocalProgress.undo()) {
			if (BookletScreen.current() != null) {
				BookletScreen.current().onProgressUpdated();
			}
			ctx.getSource().sendFeedback(Component.translatable("vmrbooklet.cmd.undo.done")
					.withStyle(ChatFormatting.GREEN));
			return 1;
		}
		ctx.getSource().sendError(Component.translatable("vmrbooklet.cmd.undo.nothing"));
		return 0;
	}
}
