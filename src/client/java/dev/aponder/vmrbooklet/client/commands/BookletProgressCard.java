package dev.aponder.vmrbooklet.client.commands;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.BookletSort;
import dev.aponder.vmrbooklet.VMRBooklet;
import dev.aponder.vmrbooklet.client.data.BookletCategories;
import dev.aponder.vmrbooklet.client.data.BookletLocalProgress;
import dev.aponder.vmrbooklet.client.gui.BookletScreenStats;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

/**
 * {@code /vbooklet card}: renders a shareable PNG summary of your progress
 * (overall %, streak, best categories) into {@code config/vmrbooklet/cards/}.
 * Stats are gathered on the client thread (the category lookup needs it);
 * the image is drawn and written on a background thread with plain AWT,
 * which works without a display.
 */
public final class BookletProgressCard {

	private static final int WIDTH = 640;
	private static final int HEIGHT = 360;
	private static final int TOP_CATEGORIES = 5;
	private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("vmrbooklet").resolve("cards");
	private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private record CategoryStat(String label, int completed, int total) {
		double fraction() {
			return total == 0 ? 0 : completed / (double) total;
		}
	}

	/** Everything the image needs, resolved to plain strings/numbers before leaving the client thread. */
	private record CardData(String title, String player, int completed, int total, int streak,
			String streakLabel, String categoriesLabel, List<CategoryStat> categories, String footer) {
	}

	private BookletProgressCard() {
	}

	public static int run(FabricClientCommandSource source) {
		Minecraft client = source.getClient();
		CardData data = gather(source.getPlayer().getName().getString());
		Path file = DIR.resolve("card-" + LocalDateTime.now().format(FILE_STAMP) + ".png");
		source.sendFeedback(Component.translatable("vmrbooklet.cmd.card.drawing").withStyle(ChatFormatting.GRAY));

		Thread.ofVirtual().name("vmrbooklet-card").start(() -> {
			try {
				Files.createDirectories(DIR);
				ImageIO.write(draw(data), "png", file.toFile());
				Component link = Component.literal(file.getFileName().toString())
						.withStyle(style -> style.withClickEvent(new ClickEvent.OpenFile(file)).withUnderlined(true));
				client.execute(() -> source.sendFeedback(Component.translatable("vmrbooklet.cmd.card.saved", link).withStyle(ChatFormatting.GREEN)));
			} catch (IOException | RuntimeException | Error e) {
				// AWT can throw Errors (missing native font libraries) - report, don't crash.
				VMRBooklet.LOGGER.warn("Failed to render progress card.", e);
				String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
				client.execute(() -> source.sendError(Component.translatable("vmrbooklet.cmd.card.failed", reason)));
			}
		});
		return 1;
	}

	private static CardData gather(String player) {
		Map<BookletCategories.Category, int[]> byCategory = new EnumMap<>(BookletCategories.Category.class);
		int completed = 0;
		for (BookletEntry entry : BookletData.orderedBy(BookletSort.ECC)) {
			boolean done = BookletLocalProgress.obtained(entry.itemId()) >= entry.required();
			if (done) {
				completed++;
			}
			int[] counts = byCategory.computeIfAbsent(BookletCategories.categoryOf(entry.item()), k -> new int[2]);
			counts[1]++;
			if (done) {
				counts[0]++;
			}
		}
		List<CategoryStat> categories = new ArrayList<>();
		for (Map.Entry<BookletCategories.Category, int[]> e : byCategory.entrySet()) {
			if (e.getKey() != BookletCategories.Category.OTHER && e.getValue()[1] > 0) {
				categories.add(new CategoryStat(e.getKey().label(), e.getValue()[0], e.getValue()[1]));
			}
		}
		categories.sort(Comparator.comparingDouble(CategoryStat::fraction).reversed());
		int streak = BookletScreenStats.computeStreakDays();
		return new CardData(
				Component.translatable("vmrbooklet.title").getString(),
				player,
				completed,
				BookletData.size(),
				streak,
				Component.translatable("vmrbooklet.card.streak", streak).getString(),
				Component.translatable("vmrbooklet.card.top_categories").getString(),
				List.copyOf(categories.subList(0, Math.min(TOP_CATEGORIES, categories.size()))),
				LocalDate.now().toString());
	}

	private static BufferedImage draw(CardData data) {
		BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

			g.setPaint(new GradientPaint(0, 0, new Color(0x21212E), 0, HEIGHT, new Color(0x14141D)));
			g.fillRoundRect(0, 0, WIDTH, HEIGHT, 28, 28);
			g.setColor(new Color(0x494E68));
			g.setStroke(new BasicStroke(2f));
			g.drawRoundRect(1, 1, WIDTH - 3, HEIGHT - 3, 28, 28);

			Color accent = new Color(0xFFD36B);
			Color text = new Color(0xEDEDF4);
			Color dim = new Color(0xA4A9C2);

			g.setColor(accent);
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
			g.drawString(data.title(), 32, 52);
			g.setColor(dim);
			g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
			g.drawString(data.player(), 32, 76);

			double fraction = data.total() == 0 ? 0 : data.completed() / (double) data.total();
			g.setColor(text);
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 64));
			g.drawString(String.format(Locale.ROOT, "%.1f%%", fraction * 100), 32, 156);
			g.setColor(dim);
			g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
			g.drawString(data.completed() + " / " + data.total(), 36, 184);
			if (data.streak() > 1) {
				g.setColor(accent);
				g.drawString(data.streakLabel(), 36, 210);
			}
			drawBar(g, 32, 228, 260, 14, fraction, accent);

			int colX = 330;
			int rowY = 52;
			g.setColor(accent);
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
			g.drawString(data.categoriesLabel(), colX, rowY);
			rowY += 22;
			g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
			for (CategoryStat stat : data.categories()) {
				g.setColor(text);
				g.drawString(stat.label(), colX, rowY + 12);
				String count = stat.completed() + "/" + stat.total();
				g.setColor(dim);
				g.drawString(count, WIDTH - 32 - g.getFontMetrics().stringWidth(count), rowY + 12);
				drawBar(g, colX, rowY + 18, WIDTH - 32 - colX, 8, stat.fraction(), new Color(0x4CAF50));
				rowY += 42;
			}

			g.setColor(dim);
			g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
			g.drawString(data.footer(), 32, HEIGHT - 24);
		} finally {
			g.dispose();
		}
		return image;
	}

	private static void drawBar(Graphics2D g, int x, int y, int w, int h, double fraction, Color fill) {
		g.setColor(new Color(0x2D2F42));
		g.fillRoundRect(x, y, w, h, h, h);
		int filled = (int) Math.round(w * Math.max(0, Math.min(1, fraction)));
		if (filled > 0) {
			g.setColor(fill);
			g.fillRoundRect(x, y, Math.max(h, filled), h, h, h);
		}
	}
}
