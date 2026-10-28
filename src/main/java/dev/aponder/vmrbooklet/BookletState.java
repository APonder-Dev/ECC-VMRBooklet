package dev.aponder.vmrbooklet;

/**
 * What a single booklet cell currently shows. Driven entirely by banked amount
 * versus required amount — see {@link BookletClassifier}.
 */
public enum BookletState {
	/** Fully banked (obtained >= required). Real icon, full tooltip. */
	ACHIEVED,
	/** Partially banked. Real icon, full tooltip. */
	IN_PROGRESS,
	/** Never sold any. No icon, no tooltip. */
	UNDISCOVERED
}
