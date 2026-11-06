package dev.aponder.vmrbooklet.client.commands;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.BookletSort;
import dev.aponder.vmrbooklet.client.data.BookletLocalProgress;
import dev.aponder.vmrbooklet.client.sync.BookletFriends;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * {@code /vbooklet trades}: cross-references everything you're still missing
 * against every imported friend's own (read-only) progress - {@link
 * BookletFriends} already stores that data for the side-by-side player tabs,
 * but nothing previously compared it to your own gaps. Lists, for each item
 * you don't have enough of yet, which of your imported friends already do -
 * so you know who to actually ask, instead of re-reading every friend tab by
 * hand. {@link #findHaveIt()} is the same computation, reused by the booklet
 * screen's own live "F" overlay instead of only being reachable through this
 * clipboard-and-chat command.
 */
public final class BookletTradeFinder {

	private BookletTradeFinder() {
	}

	/**
	 * For every item you're still missing, which of your imported friends
	 * already have enough - preserves booklet (ECC) order, and omits any item
	 * none of your friends have, same as the command output below.
	 */
	public static Map<BookletEntry, List<String>> findHaveIt() {
		List<String> friends = BookletFriends.names();
		Map<BookletEntry, List<String>> haveIt = new LinkedHashMap<>();
		if (friends.isEmpty()) {
			return haveIt;
		}

		for (BookletEntry entry : BookletData.orderedBy(BookletSort.ECC)) {
			if (BookletLocalProgress.obtained(entry.itemId()) >= entry.required()) {
				continue;
			}
			List<String> who = new ArrayList<>();
			for (String friend : friends) {
				int friendAmount = BookletFriends.progressOf(friend).getOrDefault(entry.itemId(), 0);
				if (friendAmount >= entry.required()) {
					who.add(friend);
				}
			}
			if (!who.isEmpty()) {
				haveIt.put(entry, who);
			}
		}
		return haveIt;
	}

	public static int run(FabricClientCommandSource source) {
		if (BookletFriends.names().isEmpty()) {
			source.sendFeedback(Component.translatable("vmrbooklet.cmd.trades.no_friends")
					.withStyle(ChatFormatting.GRAY));
			return 1;
		}

		Map<BookletEntry, List<String>> haveIt = findHaveIt();
		if (haveIt.isEmpty()) {
			source.sendFeedback(Component.translatable("vmrbooklet.cmd.trades.nothing")
					.withStyle(ChatFormatting.GRAY));
			return 1;
		}

		StringBuilder clip = new StringBuilder();
		for (Map.Entry<BookletEntry, List<String>> row : haveIt.entrySet()) {
			clip.append(row.getKey().displayName().replace('_', ' '))
					.append(" - ").append(String.join(", ", row.getValue()))
					.append('\n');
		}

		BookletClipboardExport.copyAndNotify(source, clip.toString(),
				Component.translatable("vmrbooklet.cmd.trades.copied", haveIt.size()));
		return 1;
	}
}
