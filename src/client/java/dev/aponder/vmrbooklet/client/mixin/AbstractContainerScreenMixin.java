package dev.aponder.vmrbooklet.client.mixin;

import dev.aponder.vmrbooklet.client.gui.BookletSlotHighlight;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {

	// RETURN, not TAIL: extractSlot has early returns (quick-craft drag preview).
	@Inject(method = "extractSlot", at = @At("RETURN"))
	private void vmrbooklet$highlightNeeded(GuiGraphicsExtractor context, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
		BookletSlotHighlight.afterSlot((Screen) (Object) this, context, slot);
	}
}
