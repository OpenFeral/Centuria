package org.asf.centuria.packets.xt.gameserver.object;

import java.io.IOException;

import org.asf.centuria.Centuria;
import org.asf.centuria.data.XtReader;
import org.asf.centuria.data.XtWriter;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.networking.gameserver.GameServer;
import org.asf.centuria.networking.smartfox.SmartfoxClient;
import org.asf.centuria.packets.xt.IXtPacket;

public class ObjectRespawnRequestPacket implements IXtPacket<ObjectRespawnRequestPacket> {

	private static final String PACKET_ID = "orr";

	private String data;
	private String playerUUID;

	@Override
	public ObjectRespawnRequestPacket instantiate() {
		return new ObjectRespawnRequestPacket();
	}

	@Override
	public String id() {
		return PACKET_ID;
	}

	@Override
	public void parse(XtReader reader) throws IOException {
	}

	@Override
	public void build(XtWriter writer) throws IOException {
		writer.writeInt(DATA_PREFIX); // Data prefix

		writer.writeString(playerUUID);
		writer.writeString(data);

		writer.writeString(DATA_SUFFIX); // Data suffix
	}

	@Override
	public boolean handle(SmartfoxClient client) throws IOException {
		// Object respawn
		Player plr = (Player) client.container;
		data = plr.respawn;
		playerUUID = plr.account.getAccountID();
		plr.client.sendPacket(this);

		XtWriter pk = new XtWriter();
		pk.writeString("ou");
		pk.writeInt(DATA_PREFIX); // Data prefix
		pk.writeString(playerUUID);
		pk.add("0");
		pk.add(Long.toString(System.currentTimeMillis() / 1000));
		pk.writeString(data);
		pk.writeString("0%0%0%0");
		pk.writeString("0");
		pk.writeString(DATA_SUFFIX); // Data suffix

		// Log if in debug
		if (Centuria.debugMode)
			Centuria.logger.debug("Respawn set: " + plr.account.getDisplayName() + ": " + plr.respawn.replace("%", ", "));

		// Broadcast respawn
		GameServer srv = (GameServer) client.getServer();
		for (Player player : srv.getPlayers()) {
			if (plr.room != null && player.room != null && player.room.equals(plr.room) && player != plr) {
				player.client.sendPacket(pk.encode());
			}
		}

		return true;
	}

}
