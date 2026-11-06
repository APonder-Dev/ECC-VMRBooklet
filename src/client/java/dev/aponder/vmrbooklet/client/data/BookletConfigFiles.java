package dev.aponder.vmrbooklet.client.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Every config file goes through here so a crash or power loss mid-save leaves
 * either the old file or the new one, never a truncated file that the next
 * launch would read as "start empty".
 */
public final class BookletConfigFiles {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private BookletConfigFiles() {
	}

	public static void writeJson(Path file, JsonElement json) throws IOException {
		Files.createDirectories(file.getParent());
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
			GSON.toJson(json, writer);
		}
		try {
			Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
