package dev.aponder.vmrbooklet;

/** Which order the booklet grid is paged in. Matches the sheet's "Sort Settings" tab. */
public enum BookletSort {
	ECC,
	CREATIVE;

	public BookletSort other() {
		return this == ECC ? CREATIVE : ECC;
	}
}
