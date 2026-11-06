package dev.aponder.vmrbooklet.client.commands;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.client.data.BookletAcquisitionNotes;
import dev.aponder.vmrbooklet.client.data.BookletLocalProgress;
import dev.aponder.vmrbooklet.client.data.BookletStaticRecipeData;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * {@code /vbooklet need <item>}: walks an item's own bundled recipe (and that
 * recipe's ingredients, recursively) to build a "go get these" list of every
 * raw/leaf material further down the chain you don't have enough of yet -
 * not just the item itself. Only follows {@link BookletStaticRecipeData}'s
 * bundled recipe data, keyed by item id, so the walk never needs to resolve a
 * live {@code Item} at every step (and works the same whether or not you've
 * personally unlocked that recipe server-side).
 *
 * <p>Stops descending into an item's own recipe - treating it as a leaf to go
 * gather directly - the moment that item has an {@link BookletAcquisitionNotes}
 * entry, even if it also happens to have a recipe on file. That matters for
 * the handful of items whose only "recipe" is a pure compress/decompress loop
 * with a sibling item (Raw Iron <-> Raw Iron Block, Stone <-> Cobblestone,
 * etc.) - those have a real acquisition note precisely because the recipe
 * alone isn't a real source.
 *
 * <p>{@link #buildTree} walks the exact same logic as the clipboard command
 * below but returns the full parent/child structure instead of a flattened
 * leaf set, for the booklet screen's own live "N" (Needed) overlay to render
 * as an expandable tree; the clipboard command flattens that same tree to its
 * leaves, so both always agree.
 */
public final class BookletNeededTree {

	private static final int MAX_DEPTH = 12;

	private BookletNeededTree() {
	}

	/**
	 * One node of {@link #buildTree}'s result - a leaf has no children. A
	 * {@code reference} node stands in for a shared ingredient whose full
	 * subtree was already shown under an earlier parent.
	 */
	public record Node(String itemId, boolean leaf, List<Node> children, boolean reference) {
	}

	public static int run(FabricClientCommandSource source, String query) {
		BookletEntry root = BookletData.byQuery(query);
		if (root == null) {
			source.sendError(Component.translatable("vmrbooklet.cmd.no_match", query));
			return 0;
		}

		String rootName = root.displayName().replace('_', ' ');

		if (BookletLocalProgress.obtained(root.itemId()) >= root.required()) {
			source.sendFeedback(Component.translatable("vmrbooklet.cmd.need.have_enough", rootName)
					.withStyle(ChatFormatting.GREEN));
			return 1;
		}

		LinkedHashSet<String> leaves = new LinkedHashSet<>();
		collectLeaves(buildTree(root.itemId()), leaves);

		if (leaves.size() == 1 && leaves.contains(root.itemId())) {
			// No recipe to walk at all (or it's gated behind its own acquisition
			// note) - there's nothing further down the chain to report.
			String note = BookletAcquisitionNotes.forItem(root.itemId());
			source.sendFeedback((note != null
					? Component.translatable("vmrbooklet.cmd.need.no_chain_note", rootName, note)
					: Component.translatable("vmrbooklet.cmd.need.no_chain", rootName))
					.withStyle(ChatFormatting.YELLOW));
			return 1;
		}

		StringBuilder clip = new StringBuilder();
		int count = 0;
		for (String itemId : leaves) {
			BookletEntry entry = BookletData.byItemId(itemId);
			String name = entry != null ? entry.displayName().replace('_', ' ') : itemId;
			int obtained = entry != null ? BookletLocalProgress.obtained(itemId) : 0;
			int required = entry != null ? entry.required() : 1;

			clip.append(name).append(" (").append(obtained).append('/').append(required).append(')');
			String note = BookletAcquisitionNotes.forItem(itemId);
			if (note != null) {
				clip.append("\n  -> ").append(note);
			}
			clip.append('\n');
			count++;
		}

		BookletClipboardExport.copyAndNotify(source, clip.toString(),
				Component.translatable("vmrbooklet.cmd.need.copied", count, rootName));
		return 1;
	}

	/**
	 * Depth-first walk of what {@code rootItemId} still needs, as a tree. An
	 * item you already have enough of contributes nothing; one with no bundled
	 * recipe, its own acquisition note, or past the depth cap is a leaf to go
	 * gather; anything else expands into its best recipe's distinct ingredients.
	 * Returns {@code null} if the root itself is already satisfied.
	 *
	 * <p>{@code path} tracks only the current branch, not every item seen in the
	 * whole walk - cycling back to an ancestor means the recipe chosen up this
	 * branch doesn't bottom out, so that item becomes a leaf instead of silently
	 * vanishing (which a single global "visited" set would do, dropping real
	 * requirements like Iron Ingot). {@code resolved} is the separate whole-walk
	 * memo, so a later branch reaching an already-settled item doesn't redo it.
	 */
	public static Node buildTree(String rootItemId) {
		return buildNode(rootItemId, 0, new ArrayDeque<>(), new HashSet<>(), new HashSet<>());
	}

	/** Every non-reference leaf, in walk order - the flat "go get these" list the chat command copies. */
	private static void collectLeaves(Node node, LinkedHashSet<String> out) {
		if (node == null || node.reference()) {
			return;
		}
		if (node.leaf()) {
			out.add(node.itemId());
			return;
		}
		for (Node child : node.children()) {
			collectLeaves(child, out);
		}
	}

	private static Node buildNode(String itemId, int depth, Deque<String> path, Set<String> resolved, Set<String> shown) {
		if (path.contains(itemId)) {
			shown.add(itemId);
			return new Node(itemId, true, List.of(), false);
		}
		if (!resolved.add(itemId)) {
			// Already settled on an earlier branch. If it was shown there, point back
			// to it so this parent doesn't look like it needs nothing; if it was
			// skipped as already satisfied, it stays skipped here too.
			return shown.contains(itemId) ? new Node(itemId, true, List.of(), true) : null;
		}

		BookletEntry entry = BookletData.byItemId(itemId);
		int obtained = entry != null ? BookletLocalProgress.obtained(itemId) : 0;
		int required = entry != null ? entry.required() : 1;
		if (entry != null && obtained >= required) {
			return null;
		}

		List<BookletStaticRecipeData.Recipe> recipes = BookletStaticRecipeData.forItem(itemId);
		boolean hasOwnNote = BookletAcquisitionNotes.forItem(itemId) != null;
		if (recipes.isEmpty() || hasOwnNote || depth >= MAX_DEPTH) {
			shown.add(itemId);
			return new Node(itemId, true, List.of(), false);
		}

		BookletStaticRecipeData.Recipe recipe = pickRecipe(recipes);
		LinkedHashSet<String> distinctIngredients = new LinkedHashSet<>();
		for (String ingredientId : recipe.ingredientItemIds()) {
			if (ingredientId != null) {
				distinctIngredients.add(ingredientId);
			}
		}

		List<Node> children = new ArrayList<>();
		path.push(itemId);
		try {
			for (String ingredientId : distinctIngredients) {
				Node child = buildNode(ingredientId, depth + 1, path, resolved, shown);
				if (child != null) {
					children.add(child);
				}
			}
		} finally {
			path.pop();
		}
		shown.add(itemId);
		return new Node(itemId, false, children, false);
	}

	// Furnace (smelting ore/raw material) and stonecutter (reshaping a single
	// simpler block) recipes are almost always vanilla's "primary" source for
	// whatever they produce - a crafting-table alternative alongside one of
	// those is typically either the circular block-unpack/nugget-compaction
	// kind, or a convenience substitute, not the normal way to get there.
	private static final List<String> PREFERRED_STATIONS = List.of("furnace", "stonecutter");

	/**
	 * Picking recipes.get(0) blindly runs straight into a "fake"
	 * compress/decompress recipe (Iron Ingot's own recipe list leads with
	 * "unpack an Iron Block into 9 ingots" before the real smelting recipe,
	 * same for several other ingots/ores) - walking into one of those instead
	 * of a real recipe sends this straight back the way it came, which is
	 * exactly the cycle buildNode() above has to fall back from.
	 */
	private static BookletStaticRecipeData.Recipe pickRecipe(List<BookletStaticRecipeData.Recipe> recipes) {
		for (String preferredStation : PREFERRED_STATIONS) {
			for (BookletStaticRecipeData.Recipe recipe : recipes) {
				if (preferredStation.equals(recipe.station())) {
					return recipe;
				}
			}
		}
		for (BookletStaticRecipeData.Recipe recipe : recipes) {
			if (!looksLikeBlockUnpack(recipe)) {
				return recipe;
			}
		}
		return recipes.get(0);
	}

	private static boolean looksLikeBlockUnpack(BookletStaticRecipeData.Recipe recipe) {
		if (recipe.outputCount() != 9) {
			return false;
		}
		long distinctIngredients = recipe.ingredientItemIds().stream().filter(id -> id != null).distinct().count();
		return distinctIngredients == 1;
	}
}
