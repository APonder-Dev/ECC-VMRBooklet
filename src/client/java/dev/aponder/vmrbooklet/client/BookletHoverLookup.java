package dev.aponder.vmrbooklet.client;

import dev.aponder.vmrbooklet.BookletData;
import dev.aponder.vmrbooklet.BookletEntry;
import dev.aponder.vmrbooklet.client.gui.BookletScreen;
import dev.aponder.vmrbooklet.client.mixin.AbstractContainerScreenAccessor;
import dev.aponder.vmrbooklet.client.mixin.AbstractRecipeBookScreenAccessor;
import dev.aponder.vmrbooklet.client.mixin.CreativeModeInventoryScreenAccessor;
import dev.aponder.vmrbooklet.client.mixin.RecipeBookComponentAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.world.inventory.Slot;

/**
 * Hover any item in any container (your inventory, a chest, a shulker box,
 * the creative tabs) and press the booklet key to open the booklet on that
 * item. Vanilla never delivers key mappings while a screen is open, so this
 * hooks each container screen's own key events instead.
 */
final class BookletHoverLookup {

	private BookletHoverLookup() {
	}

	/** Called once per container screen instance. */
	static void attach(AbstractContainerScreen<?> screen) {
		ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
			if (!VMRBookletClient.openBookletKey().matches(event) || isTyping(s)) {
				return true;
			}
			Slot slot = ((AbstractContainerScreenAccessor) screen).vmrbooklet$hoveredSlot();
			if (slot == null || !slot.hasItem()) {
				return true;
			}
			BookletEntry entry = BookletData.byItem(slot.getItem().getItem());
			if (entry == null) {
				return true;
			}
			Minecraft client = Minecraft.getInstance();
			// Deferred out of the key event, and the container closed properly
			// first, so the server isn't left thinking the chest is still open.
			client.execute(() -> {
				if (client.player != null) {
					client.player.closeContainer();
				}
				client.setScreenAndShow(BookletScreen.openingOn(entry));
			});
			return false;
		});
	}

	/** True while a text field in this screen has focus - "b" typed there is text, not the booklet key. */
	private static boolean isTyping(Screen screen) {
		if (screen.getFocused() instanceof EditBox box && box.isFocused()) {
			return true;
		}
		if (screen instanceof CreativeModeInventoryScreen) {
			EditBox search = ((CreativeModeInventoryScreenAccessor) screen).vmrbooklet$searchBox();
			if (search != null && search.isVisible() && search.isFocused()) {
				return true;
			}
		}
		if (screen instanceof AbstractRecipeBookScreen<?>) {
			RecipeBookComponent<?> book = ((AbstractRecipeBookScreenAccessor) screen).vmrbooklet$recipeBookComponent();
			if (book != null && book.isVisible()) {
				EditBox search = ((RecipeBookComponentAccessor) book).vmrbooklet$searchBox();
				return search != null && search.isFocused();
			}
		}
		return false;
	}
}
