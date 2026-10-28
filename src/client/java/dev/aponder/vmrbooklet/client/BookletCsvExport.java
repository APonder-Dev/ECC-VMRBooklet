package dev.aponder.vmrbooklet.client;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.BookletSort;
import dev.aponder.vmrbooklet.VMRBooklet;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * {@code /vbooklet export-csv}: writes your full progress to a plain local
 * CSV file, entirely separate from the mclo.gs share/backup codes - meant for
 * opening in a spreadsheet or just keeping a human-readable copy, not for
 * sharing or restoring through the mod itself.
 */
public final class BookletCsvExport {

	private static final DateTimeFormatter FILENAME_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	private BookletCsvExport() {
	}

	public static int run(FabricClientCommandSource source) {
		StringBuilder csv = new StringBuilder("Item,ItemId,Obtained,Required,Achieved,Release\n");
		for (BookletEntry entry : BookletData.orderedBy(BookletSort.ECC)) {
			int obtained = BookletLocalProgress.obtained(entry.itemId());
			csv.append(csvField(entry.displayName().replace('_', ' '))).append(',')
					.append(csvField(entry.itemId())).append(',')
					.append(obtained).append(',')
					.append(entry.required()).append(',')
					.append(obtained >= entry.required() ? "Yes" : "No").append(',')
					.append(csvField(entry.release())).append('\n');
		}

		Path dir = FabricLoader.getInstance().getConfigDir().resolve("vmrbooklet").resolve("exports");
		Path file = dir.resolve("progress_" + LocalDateTime.now().format(FILENAME_TIMESTAMP) + ".csv");
		try {
			Files.createDirectories(dir);
			Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
			source.sendFeedback(Component.literal("Exported your progress to " + file).withStyle(ChatFormatting.GREEN));
			return 1;
		} catch (IOException e) {
			VMRBooklet.LOGGER.warn("Failed to export progress CSV", e);
			source.sendError(Component.literal("Failed to export: " + e.getMessage()));
			return 0;
		}
	}

	private static String csvField(String value) {
		if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
			return "\"" + value.replace("\"", "\"\"") + "\"";
		}
		return value;
	}
}
