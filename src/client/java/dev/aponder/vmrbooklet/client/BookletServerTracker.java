package dev.aponder.vmrbooklet.client;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks which backend server on the network (EcoCityCraft sits behind a
 * proxy with several backend servers - /main, /skyblock, /vmr, /lobby, etc.)
 * the player is currently connected to. There's no vanilla/Fabric API for
 * this - a Fabric client only ever sees one continuous connection to the
 * proxy - so this watches for the network's own chat banners that announce a
 * server switch and remembers the last one seen.
 */
public final class BookletServerTracker {

	// "Welcome to EcoCityCraft's VMR Server." - the full welcome banner shown on
	// first connect (and apparently on some switches).
	private static final Pattern WELCOME_BANNER = Pattern.compile("Welcome to EcoCityCraft's (\\S+) Server\\.");
	// "CONNECTED TO: VMR Server" - a shorter banner also seen on server switches.
	private static final Pattern CONNECTED_TO = Pattern.compile("CONNECTED TO:\\s*(\\S+)\\s*Server");

	private static volatile String currentServer;

	private BookletServerTracker() {
	}

	/** @return whether the player is (as far as this mod can tell) currently on the VMR backend server. */
	public static boolean isInVmr() {
		return "VMR".equalsIgnoreCase(currentServer);
	}

	public static void register() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay) {
				return; // action bar text, not a chat line
			}
			onMessage(message.getString());
		});
	}

	private static void onMessage(String text) {
		Matcher welcome = WELCOME_BANNER.matcher(text);
		if (welcome.find()) {
			currentServer = welcome.group(1);
			return;
		}
		Matcher connected = CONNECTED_TO.matcher(text);
		if (connected.find()) {
			currentServer = connected.group(1);
		}
	}
}
