package dev.aponder.vmrbooklet.client.gui;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.client.commands.BookletNeededTree;
import dev.aponder.vmrbooklet.client.data.BookletLocalProgress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The booklet screen's "N" overlay - a live, expandable rendering of {@link
 * BookletNeededTree#buildTree}, instead of that command's clipboard-only
 * output. The tree itself comes from {@link BookletScreenData}, which
 * rebuilds it whenever progress changes, so an ingredient you just bumped past
 * its requirement drops out without reselecting the item.
 */
final class BookletNeededTreeView {

	private static final int ROW_HEIGHT = 16;
	private static final int INDENT = 12;

	record Row(int y, int height, String itemId, boolean hasChildren, boolean reference) {
	}

	private BookletNeededTreeView() {
	}

	/**
	 * @param root the walk for {@code selected} (null when it's already satisfied
	 *             or has nothing to walk)
	 * @param rows cleared and repopulated with every visible row this frame,
	 *             for the caller's own click hit-testing (expand/collapse a
	 *             node with children, or jump straight to a leaf).
	 */
	static void draw(GuiGraphicsExtractor context, Font font, int x, int y, int w, int h,
			BookletEntry selected, BookletNeededTree.Node root, Set<String> expandedIds, BookletUi.ScrollState scroll, List<Row> rows) {
		rows.clear();
		Component closeHint = Component.translatable("vmrbooklet.overlay.needed.footer_empty");

		if (selected == null) {
			BookletUi.drawOverlayFrame(context, font, x, y, w, h, Component.translatable("vmrbooklet.overlay.needed.title_none"));
			BookletUi.drawOverlayMessage(context, font, x, y, w, Component.translatable("vmrbooklet.overlay.needed.select"), ChatFormatting.GRAY);
			BookletUi.drawOverlayFooter(context, font, x, y, w, h, closeHint);
			return;
		}

		String rootName = selected.displayName().replace('_', ' ');
		BookletUi.drawOverlayFrame(context, font, x, y, w, h, Component.translatable("vmrbooklet.overlay.needed.title", rootName));

		if (BookletLocalProgress.obtained(selected.itemId()) >= selected.required()) {
			BookletUi.drawOverlayMessage(context, font, x, y, w, Component.translatable("vmrbooklet.overlay.needed.have_enough", rootName), BookletUi.yesNoFormatting(true));
			BookletUi.drawOverlayFooter(context, font, x, y, w, h, closeHint);
			return;
		}

		List<BookletNeededTree.Node> flat = new ArrayList<>();
		List<Integer> depths = new ArrayList<>();
		if (root != null) {
			flattenChildren(root, 0, expandedIds, flat, depths);
		}

		if (flat.isEmpty()) {
			BookletUi.drawOverlayMessage(context, font, x, y, w, Component.translatable("vmrbooklet.overlay.needed.no_chain"), ChatFormatting.GRAY);
			BookletUi.drawOverlayFooter(context, font, x, y, w, h, closeHint);
			return;
		}

		int listTop = y + BookletUi.OVERLAY_LIST_TOP;
		int listBottom = y + h - BookletUi.OVERLAY_LIST_BOTTOM_INSET;
		scroll.clampTo(flat.size(), (listBottom - listTop) / ROW_HEIGHT);

		context.enableScissor(x + 1, listTop, x + w - 1, listBottom);
		int firstRow = scroll.firstRow();
		for (int i = 0; firstRow + i < flat.size() && listTop + i * ROW_HEIGHT < listBottom; i++) {
			BookletNeededTree.Node node = flat.get(firstRow + i);
			int depth = depths.get(firstRow + i);
			int rowY = listTop + i * ROW_HEIGHT;
			int rowX = x + 8 + depth * INDENT;

			BookletEntry entry = BookletData.byItemId(node.itemId());
			boolean hasChildren = !node.children().isEmpty();
			// A non-leaf whose ingredients are all already satisfied has nothing left
			// to expand - it's craftable right now.
			boolean readyToCraft = !node.leaf() && !hasChildren;

			String prefix = hasChildren ? (expandedIds.contains(node.itemId()) ? "▼ " : "▶ ") : "   ";
			String baseName = entry != null ? entry.displayName().replace('_', ' ') : node.itemId();
			String name;
			if (node.reference()) {
				name = Component.translatable("vmrbooklet.overlay.needed.see_above", baseName).getString();
			} else if (readyToCraft) {
				name = Component.translatable("vmrbooklet.overlay.needed.ready", baseName).getString();
			} else {
				name = baseName;
			}

			int countWidth = 0;
			if (entry != null && !node.reference()) {
				int obtained = BookletLocalProgress.obtained(entry.itemId());
				String countText = obtained + "/" + entry.required();
				countWidth = font.width(countText);
				context.text(font, Component.literal(countText).withStyle(ChatFormatting.GRAY),
						x + w - 8 - countWidth, rowY + 4, BookletUi.COLOR_TEXT_DIM);
			}

			if (entry != null) {
				context.item(new ItemStack(entry.item(), 1), rowX + font.width(prefix), rowY);
			}
			int textX = rowX + font.width(prefix) + 18;
			int nameRoom = x + w - 8 - countWidth - 6 - textX;
			context.text(font, Component.literal(prefix), rowX, rowY + 4, BookletUi.COLOR_TEXT_DIM);
			context.text(font, Component.literal(BookletUi.ellipsize(font, name, nameRoom)), textX, rowY + 4,
					node.reference() ? BookletUi.COLOR_TEXT_DIM : BookletUi.COLOR_TEXT);

			rows.add(new Row(rowY, ROW_HEIGHT, node.itemId(), hasChildren, node.reference()));
		}
		context.disableScissor();

		BookletUi.drawOverlayFooter(context, font, x, y, w, h, Component.translatable("vmrbooklet.overlay.needed.footer"));
	}

	private static void flattenChildren(BookletNeededTree.Node node, int depth, Set<String> expandedIds,
			List<BookletNeededTree.Node> outFlat, List<Integer> outDepths) {
		for (BookletNeededTree.Node child : node.children()) {
			outFlat.add(child);
			outDepths.add(depth);
			if (!child.leaf() && !child.children().isEmpty() && expandedIds.contains(child.itemId())) {
				flattenChildren(child, depth + 1, expandedIds, outFlat, outDepths);
			}
		}
	}
}
