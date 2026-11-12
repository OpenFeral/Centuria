package org.asf.centuria.packets.xt.gameserver.minigame;

import java.io.IOException;

import org.asf.centuria.Centuria;
import org.asf.centuria.data.XtReader;
import org.asf.centuria.data.XtWriter;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.networking.smartfox.SmartfoxClient;
import org.asf.centuria.packets.xt.IXtPacket;
import org.asf.centuria.minigames.AbstractMinigame;

public class MinigameMessagePacket implements IXtPacket<MinigameMessagePacket> {

	private static final String PACKET_ID = "mm";

	public String command;
	public String data;

	@Override
	public MinigameMessagePacket instantiate() {
		return new MinigameMessagePacket();
	}

	@Override
	public String id() {
		return PACKET_ID;
	}

	@Override
	public void parse(XtReader reader) throws IOException {
		command = reader.read();
		data = reader.readRemaining();
	}

	@Override
	public void build(XtWriter writer) throws IOException {
		// Log
		if (Centuria.debugMode) {
			Centuria.logger.debug("[MINIGAME] [MESSAGE] Server to client (command: " + command + ")");
		}

		writer.writeInt(DATA_PREFIX); // padding

		writer.writeString(command);
		writer.writeString(data);

		writer.writeString(DATA_SUFFIX); // Data suffix
	}

	@Override
	public boolean handle(SmartfoxClient client) throws IOException {

		Player plr = (Player) client.container;

		// Log
		if (Centuria.debugMode) {
			Centuria.logger.debug("[MINIGAME] [MESSAGE] Client to server (command: " + command + ")");
		}

		// Find minigame
		AbstractMinigame game = plr.currentGame;
		if (game != null) // Handle if found
			game.handleMessage(plr, this);

		return true;
	}

}
