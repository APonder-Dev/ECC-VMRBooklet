package dev.aponder.vmrbooklet.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.aponder.vmrbooklet.client.gui.BookletSettingsScreen;

/**
 * Wires {@link BookletSettingsScreen} into Mod Menu's per-mod settings
 * button. Only ever loaded by Fabric Loader if the player's own game actually
 * has Mod Menu installed - this mod never requires it (see the
 * {@code modCompileOnly} dependency in build.gradle and the lack of any
 * "modmenu" entry under this mod's own "depends" in fabric.mod.json).
 */
public final class VMRBookletModMenuIntegration implements ModMenuApi {

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return BookletSettingsScreen::new;
	}
}
