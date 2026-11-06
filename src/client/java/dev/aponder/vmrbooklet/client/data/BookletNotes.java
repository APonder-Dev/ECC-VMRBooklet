package dev.aponder.vmrbooklet.client.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aponder.vmrbooklet.VMRBooklet;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Your own per-item notes ("trade hall, 2nd librarian") and an optional saved
 * location for each, shown in the booklet's detail panel. Set with
 * {@code /vbooklet note}; persisted to {@code notes.json}.
 */
public final class BookletNotes {

	public record Waypoint(int x, int y, int z, String dimension) {
	}

	/** Either part may be absent: {@code text} empty, {@code waypoint} null. */
	public record Note(String text, Waypoint waypoint) {
		boolean isEmpty() {
			return text.isEmpty() && waypoint == null;
		}
	}

	private static final Path FILE = FabricLoader.getInstance().getConfigDir()
			.resolve("vmrbooklet").resolve("notes.json");

	private static final Map<String, Note> NOTES = new LinkedHashMap<>();
	private static boolean savingDisabled = false;

	static {
		load();
	}

	private BookletNotes() {
	}

	public static synchronized Note get(String itemId) {
		return NOTES.get(itemId);
	}

	public static synchronized void setText(String itemId, String text) {
		Note existing = NOTES.get(itemId);
		put(itemId, new Note(text.trim(), existing == null ? null : existing.waypoint()));
	}

	public static synchronized void setWaypoint(String itemId, Waypoint waypoint) {
		Note existing = NOTES.get(itemId);
		put(itemId, new Note(existing == null ? "" : existing.text(), waypoint));
	}

	/** @return whether there was anything to clear. */
	public static synchronized boolean clear(String itemId) {
		boolean removed = NOTES.remove(itemId) != null;
		if (removed) {
			save();
		}
		return removed;
	}

	private static void put(String itemId, Note note) {
		if (note.isEmpty()) {
			NOTES.remove(itemId);
		} else {
			NOTES.put(itemId, note);
		}
		save();
	}

	private static void load() {
		if (!Files.exists(FILE)) {
			return;
		}
		// Same all-or-nothing rule as the goal lists: a half-read file never
		// becomes the in-memory state that the next save would write back.
		Map<String, Note> parsed = new LinkedHashMap<>();
		try (Reader reader = Files.newBufferedReader(FILE)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
				JsonObject obj = entry.getValue().getAsJsonObject();
				String text = obj.has("text") ? obj.get("text").getAsString() : "";
				Waypoint waypoint = null;
				if (obj.has("waypoint")) {
					JsonObject wp = obj.getAsJsonObject("waypoint");
					waypoint = new Waypoint(wp.get("x").getAsInt(), wp.get("y").getAsInt(), wp.get("z").getAsInt(),
							wp.has("dimension") ? wp.get("dimension").getAsString() : "");
				}
				Note note = new Note(text, waypoint);
				if (!note.isEmpty()) {
					parsed.put(entry.getKey(), note);
				}
			}
		} catch (IOException | RuntimeException e) {
			Path backup = FILE.resolveSibling(FILE.getFileName() + ".bak");
			try {
				Files.copy(FILE, backup, StandardCopyOption.REPLACE_EXISTING);
				VMRBooklet.LOGGER.warn("Couldn't read booklet notes - kept a copy at {} and started empty.", backup, e);
			} catch (IOException copyFailure) {
				savingDisabled = true;
				VMRBooklet.LOGGER.warn("Couldn't read booklet notes or back them up - started empty, and won't save over the file this session.", e);
			}
			return;
		}
		NOTES.putAll(parsed);
	}

	private static void save() {
		if (savingDisabled) {
			return;
		}
		try {
			Files.createDirectories(FILE.getParent());
			JsonObject root = new JsonObject();
			for (Map.Entry<String, Note> entry : NOTES.entrySet()) {
				JsonObject obj = new JsonObject();
				Note note = entry.getValue();
				if (!note.text().isEmpty()) {
					obj.addProperty("text", note.text());
				}
				if (note.waypoint() != null) {
					JsonObject wp = new JsonObject();
					wp.addProperty("x", note.waypoint().x());
					wp.addProperty("y", note.waypoint().y());
					wp.addProperty("z", note.waypoint().z());
					wp.addProperty("dimension", note.waypoint().dimension());
					obj.add("waypoint", wp);
				}
				root.add(entry.getKey(), obj);
			}
			BookletConfigFiles.writeJson(FILE, root);
		} catch (IOException e) {
			VMRBooklet.LOGGER.warn("Failed to save booklet notes.", e);
		}
	}
}
