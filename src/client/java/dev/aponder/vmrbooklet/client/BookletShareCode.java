package dev.aponder.vmrbooklet.client;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.BookletSort;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Encodes a player's progress into a compact, copy-pasteable text code (no
 * server involved at all): two items packed per byte (4 bits each - every
 * item's required count tops out at 10, which fits comfortably in a nibble),
 * gzip compressed on top (repeated values, like a run of fully-achieved
 * items, still compress further), then base64'd, with a short magic prefix
 * and the exporter's name baked in. Decodes back the same way.
 *
 * <p>Nibble-packing matters most for codes gzip can't shrink much on its own -
 * a player with varied partial progress across hundreds of items is close to
 * random from a compressor's point of view, so halving the raw payload size
 * up front (versus a full byte per item) is what actually keeps those codes
 * short, not the compression step.
 *
 * <p>Carries a {@link Type} byte so a friend's share code and your own backup
 * code - otherwise identical in shape - can't be fed into the wrong command by
 * mistake (e.g. pasting a friend's /export into /vbooklet restore, silently
 * overwriting your own real progress with theirs).
 */
public final class BookletShareCode {

	private static final String PREFIX_V2 = "VMRB2:";
	// Still decodable for anyone who generated a code before this format existed -
	// one byte per item, no nibble-packing.
	private static final String PREFIX_V1_LEGACY = "VMRB1:";

	public enum Type {
		/** From {@code /export booklet} - meant for someone else's {@code /import}. */
		SHARE((byte) 1),
		/** From {@code /vbooklet backup} - meant for your own {@code /vbooklet restore}. */
		BACKUP((byte) 2);

		final byte marker;

		Type(byte marker) {
			this.marker = marker;
		}

		static Type fromMarker(byte marker) {
			for (Type type : values()) {
				if (type.marker == marker) {
					return type;
				}
			}
			throw new IllegalArgumentException("That share code is corrupted (unknown type).");
		}
	}

	private BookletShareCode() {
	}

	public static String encode(Type type, String exporterName, Map<String, Integer> progress) {
		List<BookletEntry> ordered = BookletData.orderedBy(BookletSort.ECC);

		byte[] nameBytes = exporterName.getBytes(StandardCharsets.UTF_8);
		int packedLength = (ordered.size() + 1) / 2;
		byte[] raw = new byte[2 + nameBytes.length + packedLength];
		raw[0] = type.marker;
		raw[1] = (byte) nameBytes.length;
		System.arraycopy(nameBytes, 0, raw, 2, nameBytes.length);

		int offset = 2 + nameBytes.length;
		for (int i = 0; i < ordered.size(); i += 2) {
			int high = nibble(progress, ordered, i);
			int low = i + 1 < ordered.size() ? nibble(progress, ordered, i + 1) : 0;
			raw[offset + i / 2] = (byte) ((high << 4) | low);
		}

		try {
			ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
			try (GZIPOutputStream gzip = new GZIPOutputStream(byteOut)) {
				gzip.write(raw);
			}
			return PREFIX_V2 + Base64.getUrlEncoder().withoutPadding().encodeToString(byteOut.toByteArray());
		} catch (IOException e) {
			throw new RuntimeException("Failed to encode share code", e);
		}
	}

	private static int nibble(Map<String, Integer> progress, List<BookletEntry> ordered, int index) {
		int obtained = progress.getOrDefault(ordered.get(index).itemId(), 0);
		// Nibble-packed storage caps at 15 regardless of this item's own required() -
		// not the same [0, required] domain clamp used everywhere else, so this one
		// stays its own expression rather than delegating to BookletEntry.clamp().
		return Math.max(0, Math.min(15, obtained));
	}

	public static boolean looksLikeCode(String text) {
		if (text == null) {
			return false;
		}
		String trimmed = text.trim();
		return trimmed.startsWith(PREFIX_V2) || trimmed.startsWith(PREFIX_V1_LEGACY);
	}

	public record Decoded(Type type, String exporterName, Map<String, Integer> progress) {
	}

	public static Decoded decode(String code) {
		String trimmed = code.trim();
		if (trimmed.startsWith(PREFIX_V2)) {
			return decodeV2(trimmed.substring(PREFIX_V2.length()));
		}
		if (trimmed.startsWith(PREFIX_V1_LEGACY)) {
			return decodeV1Legacy(trimmed.substring(PREFIX_V1_LEGACY.length()));
		}
		throw new IllegalArgumentException("That doesn't look like a valid booklet share code.");
	}

	private static Decoded decodeV2(String payload) {
		byte[] raw = gunzipBase64(payload);
		if (raw.length < 2) {
			throw new IllegalArgumentException("That share code is empty or corrupted.");
		}

		Type type = Type.fromMarker(raw[0]);
		int nameLength = raw[1] & 0xFF;
		if (raw.length < 2 + nameLength) {
			throw new IllegalArgumentException("That share code is corrupted.");
		}
		String name = new String(raw, 2, nameLength, StandardCharsets.UTF_8);

		List<BookletEntry> ordered = BookletData.orderedBy(BookletSort.ECC);
		int offset = 2 + nameLength;
		Map<String, Integer> progress = new HashMap<>();
		for (int i = 0; i < ordered.size(); i++) {
			int byteIndex = offset + i / 2;
			if (byteIndex >= raw.length) {
				break;
			}
			int packed = raw[byteIndex] & 0xFF;
			int value = (i % 2 == 0) ? (packed >> 4) & 0xF : packed & 0xF;
			if (value > 0) {
				progress.put(ordered.get(i).itemId(), value);
			}
		}

		return new Decoded(type, name, progress);
	}

	/** Pre-nibble-packing format: a full byte per item instead of two per byte. */
	private static Decoded decodeV1Legacy(String payload) {
		byte[] raw = gunzipBase64(payload);
		if (raw.length < 2) {
			throw new IllegalArgumentException("That share code is empty or corrupted.");
		}

		Type type = Type.fromMarker(raw[0]);
		int nameLength = raw[1] & 0xFF;
		if (raw.length < 2 + nameLength) {
			throw new IllegalArgumentException("That share code is corrupted.");
		}
		String name = new String(raw, 2, nameLength, StandardCharsets.UTF_8);

		List<BookletEntry> ordered = BookletData.orderedBy(BookletSort.ECC);
		int offset = 2 + nameLength;
		Map<String, Integer> progress = new HashMap<>();
		for (int i = 0; i < ordered.size() && offset + i < raw.length; i++) {
			int value = raw[offset + i] & 0xFF;
			if (value > 0) {
				progress.put(ordered.get(i).itemId(), value);
			}
		}

		return new Decoded(type, name, progress);
	}

	// A real code packs ~1657 items at 4-8 bits each - a few KB uncompressed at
	// most. This is just a generous ceiling against a maliciously crafted gzip
	// bomb (a tiny compressed payload that inflates to hundreds of MB/GB) - a
	// friend's or stranger's share/backup code runs through this on the main
	// thread (importCode/restoreBackup), so an unbounded readAllBytes() here
	// would freeze or OOM the game from pasting a hostile code.
	private static final int MAX_DECOMPRESSED_BYTES = 1_000_000;

	private static byte[] gunzipBase64(String payload) {
		byte[] compressed;
		try {
			compressed = Base64.getUrlDecoder().decode(payload);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("That doesn't look like a valid booklet share code.", e);
		}

		try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int total = 0;
			int read;
			while ((read = gzip.read(buffer)) != -1) {
				total += read;
				if (total > MAX_DECOMPRESSED_BYTES) {
					throw new IllegalArgumentException("That doesn't look like a valid booklet share code.");
				}
				out.write(buffer, 0, read);
			}
			return out.toByteArray();
		} catch (IOException e) {
			throw new IllegalArgumentException("That doesn't look like a valid booklet share code.", e);
		}
	}

	/** Decodes and asserts the code is the expected type, with a clear error otherwise. */
	public static Decoded decodeExpecting(String code, Type expected) {
		Decoded decoded = decode(code);
		if (decoded.type() != expected) {
			String got = decoded.type() == Type.SHARE ? "a friend's /export code" : "a /vbooklet backup code";
			String wanted = expected == Type.SHARE ? "a friend's /export code (use /import)" : "a /vbooklet backup code (use /vbooklet restore)";
			throw new IllegalArgumentException("That's " + got + ", not " + wanted + ".");
		}
		return decoded;
	}
}
