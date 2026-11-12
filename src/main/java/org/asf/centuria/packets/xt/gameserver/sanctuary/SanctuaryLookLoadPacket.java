package org.asf.centuria.packets.xt.gameserver.sanctuary;

import java.io.IOException;

import org.asf.centuria.Centuria;
import org.asf.centuria.data.XtReader;
import org.asf.centuria.data.XtWriter;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.networking.gameserver.GameServer;
import org.asf.centuria.networking.smartfox.SmartfoxClient;
import org.asf.centuria.packets.xt.IXtPacket;
import org.asf.centuria.packets.xt.gameserver.room.RoomJoinPacket;

public class SanctuaryLookLoadPacket implements IXtPacket<SanctuaryLookLoadPacket> {

	private static final String PACKET_ID = "sll";

	public String lookId = null;

	@Override
	public String id() {
		return PACKET_ID;
	}

	@Override
	public SanctuaryLookLoadPacket instantiate() {
		return new SanctuaryLookLoadPacket();
	}

	@Override
	public void parse(XtReader reader) throws IOException {
		lookId = reader.read();
	}

	@Override
	public void build(XtWriter writer) throws IOException {
		writer.writeInt(DATA_PREFIX); // Data prefix

		writer.writeBoolean(true);
		writer.writeString(lookId);

		writer.writeString(DATA_SUFFIX); // Data suffix
	}

	@Override
	public boolean handle(SmartfoxClient client) throws IOException {
		// Switch sanctuary look
		Player plr = (Player) client.container;

		// Log
		if (Centuria.debugMode) {
			Centuria.logger.debug("[SANCTUARYEDITOR] [SELECTLOOK]  Client to server (look: " + lookId + ")");
		}

		// Load into active look
		// retooling the save function here to just target the active look slot
		plr.account.getSaveSpecificInventory().getSanctuaryAccessor().saveSanctuaryLookToSlot(lookId,
				plr.activeSanctuaryLook, "");

		// Respond with switch packet and rejoin
		plr.client.sendPacket(this);

		// Sync
		for (Player player : ((GameServer) client.getServer()).getPlayers()) {
			if (player.room != null && player.room.equals("sanctuary_" + plr.account.getAccountID())) {
				// Build room join
				RoomJoinPacket join = new RoomJoinPacket();
				join.levelType = 2;
				join.levelID = "1689";
				join.roomIdentifier = "sanctuary_" + plr.account.getAccountID();
				join.teleport = plr.account.getAccountID();

				// Sync
				GameServer srv = (GameServer) client.getServer();
				for (Player plr2 : srv.getPlayers()) {
					if (plr2.room != null && player.room != null && player.room != null && plr2.room.equals(player.room)
							&& plr2 != player) {
						player.destroyAt(plr2);
					}
				}

				// Assign room
				player.roomReady = false;
				player.pendingLevelID = "1689";
				player.pendingRoom = "sanctuary_" + plr.account.getAccountID();
				player.levelType = join.levelType;

				// Send packet
				player.client.sendPacket(join);
			}
		}

		return true;
	}

}
