package dev.aponder.vmrbooklet.client.gui;

import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.BookletState;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The search box's mini query language. Space-separated tokens:
 * <ul>
 *   <li>{@code @nether} - category label contains "nether"</li>
 *   <li>{@code #1.21} - release contains "1.21"</li>
 *   <li>{@code !done}, {@code !todo}, {@code !partial}, {@code !new} - completion state</li>
 *   <li>{@code >50%}, {@code <50%}, {@code >=25}, {@code <=75%} - your own progress on the item</li>
 *   <li>anything else - must appear in the item name</li>
 * </ul>
 * Tokens of the same kind are OR'ed ({@code @combat @redstone}); different
 * kinds and plain words are AND'ed.
 */
final class BookletSearchQuery {

	static final BookletSearchQuery EMPTY = new BookletSearchQuery(List.of(), List.of(), List.of(), EnumSet.noneOf(BookletState.class), -1, 101);

	private final List<String> words;
	private final List<String> categories;
	private final List<String> releases;
	private final Set<BookletState> states;
	// Exclusive-style bounds normalized to inclusive percent: matches when minPercent <= pct <= maxPercent.
	private final double minPercent;
	private final double maxPercent;

	private BookletSearchQuery(List<String> words, List<String> categories, List<String> releases,
			Set<BookletState> states, double minPercent, double maxPercent) {
		this.words = words;
		this.categories = categories;
		this.releases = releases;
		this.states = states;
		this.minPercent = minPercent;
		this.maxPercent = maxPercent;
	}

	static BookletSearchQuery parse(String raw) {
		String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
		if (text.isEmpty()) {
			return EMPTY;
		}
		List<String> words = new ArrayList<>();
		List<String> categories = new ArrayList<>();
		List<String> releases = new ArrayList<>();
		Set<BookletState> states = EnumSet.noneOf(BookletState.class);
		double min = -1;
		double max = 101;

		for (String token : text.split("\\s+")) {
			if (token.length() == 1 && "@#!<>".indexOf(token.charAt(0)) >= 0) {
				// A bare prefix while the rest is still being typed - don't empty the list.
				continue;
			}
			if (token.length() > 1 && token.charAt(0) == '@') {
				categories.add(normalize(token.substring(1)));
			} else if (token.length() > 1 && token.charAt(0) == '#') {
				releases.add(token.substring(1));
			} else if (token.equals("!todo") || token.equals("!unfinished")) {
				states.add(BookletState.IN_PROGRESS);
				states.add(BookletState.UNDISCOVERED);
			} else if (token.length() > 1 && token.charAt(0) == '!' && stateOf(token.substring(1)) != null) {
				states.add(stateOf(token.substring(1)));
			} else if (isPercentToken(token)) {
				boolean greater = token.charAt(0) == '>';
				boolean inclusive = token.length() > 1 && token.charAt(1) == '=';
				double value = Double.parseDouble(token.substring(inclusive ? 2 : 1).replace("%", ""));
				// Strict comparisons nudge the bound by a hair so ">50%" excludes exactly 50%.
				if (greater) {
					min = Math.max(min, inclusive ? value : value + 1e-9);
				} else {
					max = Math.min(max, inclusive ? value : value - 1e-9);
				}
			} else {
				words.add(token.replace('_', ' '));
			}
		}
		return new BookletSearchQuery(List.copyOf(words), List.copyOf(categories), List.copyOf(releases), states, min, max);
	}

	boolean isEmpty() {
		return this == EMPTY;
	}

	/**
	 * @param obtained      the active tab's progress on this item
	 * @param categoryLabel only invoked when an {@code @} token is present - the
	 *                      category lookup is the one expensive part
	 */
	boolean matches(BookletEntry entry, int obtained, BookletState state, Supplier<String> categoryLabel) {
		if (!words.isEmpty()) {
			String name = entry.displayName().toLowerCase(Locale.ROOT).replace('_', ' ');
			for (String word : words) {
				if (!name.contains(word)) {
					return false;
				}
			}
		}
		if (!states.isEmpty() && !states.contains(state)) {
			return false;
		}
		if (minPercent >= 0 || maxPercent <= 100) {
			double pct = entry.required() <= 0 ? 100 : Math.min(100.0, obtained * 100.0 / entry.required());
			if (pct < minPercent || pct > maxPercent) {
				return false;
			}
		}
		if (!releases.isEmpty()) {
			String release = entry.release().toLowerCase(Locale.ROOT);
			if (releases.stream().noneMatch(release::contains)) {
				return false;
			}
		}
		if (!categories.isEmpty()) {
			String label = normalize(categoryLabel.get());
			if (categories.stream().noneMatch(label::contains)) {
				return false;
			}
		}
		return true;
	}

	private static String normalize(String text) {
		return text.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace("&", "");
	}

	private static BookletState stateOf(String word) {
		return switch (word) {
			case "done", "complete", "completed" -> BookletState.ACHIEVED;
			case "partial", "started" -> BookletState.IN_PROGRESS;
			case "new", "undiscovered", "none" -> BookletState.UNDISCOVERED;
			default -> null;
		};
	}

	private static boolean isPercentToken(String token) {
		return token.matches("[<>]=?\\d{1,3}(\\.\\d+)?%?");
	}
}
