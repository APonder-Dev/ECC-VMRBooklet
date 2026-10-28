package dev.aponder.vmrbooklet;

/**
 * Shared (client + server) rule for what a booklet cell shows. Credit comes from
 * {@code /sell}, which can touch any tracked item in any order, so there is no
 * unlock sequence here — only how much has been banked versus what's required.
 */
public final class BookletClassifier {

	private BookletClassifier() {
	}

	public static BookletState classify(BookletEntry entry, int obtained) {
		if (obtained >= entry.required()) {
			return BookletState.ACHIEVED;
		}
		return obtained > 0 ? BookletState.IN_PROGRESS : BookletState.UNDISCOVERED;
	}
}
