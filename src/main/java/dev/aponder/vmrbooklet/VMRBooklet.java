package dev.aponder.vmrbooklet;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-only tracker: there is no server-side component at all. This mod never
 * touches the server you're connected to (including the real public server it
 * was built for) - all state lives locally, fed by {@code /upload <sheet-link>}.
 */
public class VMRBooklet implements ModInitializer {

	public static final String MOD_ID = "vmrbooklet";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		BookletData.load();
		LOGGER.info("VMR Booklet initialized with {} items.", BookletData.size());
	}
}
