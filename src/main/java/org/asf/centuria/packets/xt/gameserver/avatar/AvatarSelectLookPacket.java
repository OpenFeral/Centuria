package org.asf.centuria.packets.xt.gameserver.avatar;

import java.io.IOException;

import org.asf.centuria.Centuria;
import org.asf.centuria.data.XtReader;
import org.asf.centuria.data.XtWriter;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.enums.objects.WorldObjectMoverNodeType;
import org.asf.centuria.networking.gameserver.GameServer;
import org.asf.centuria.networking.smartfox.SmartfoxClient;
import org.asf.centuria.packets.xt.IXtPacket;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class AvatarSelectLookPacket implements IXtPacket<AvatarSelectLookPacket> {

	private static final String PACKET_ID = "als";

	private String lookID;

	@Override
	public AvatarSelectLookPacket instantiate() {
		return new AvatarSelectLookPacket();
	}

	@Override
	public String id() {
		return PACKET_ID;
	}

	@Override
	public void parse(XtReader reader) throws IOException {
		lookID = reader.read();
	}

	@Override
	public void build(XtWriter writer) throws IOException {
		writer.writeInt(DATA_PREFIX); // Data prefix

		writer.writeString(lookID);

		writer.writeString(DATA_SUFFIX); // Data suffix
	}

	@Override
	public boolean handle(SmartfoxClient client) throws IOException {
		// Switch looks
		Player plr = (Player) client.container;

		// Log
		if (Centuria.debugMode) {
			Centuria.logger.debug("[AVATAREDITOR] [SELECTLOOK]  Client to server (look: " + lookID + ")");
		}

		// Save the pending look ID
		String lastLook = plr.activeLook;
		plr.pendingLookID = lookID;
		plr.activeLook = plr.pendingLookID;

		// Respond with switch packet
		plr.client.sendPacket(this);

		// Assign the defID
		JsonArray items = plr.account.getSaveSpecificInventory().getItem("avatars").getAsJsonArray();
		JsonObject lookObj = null;
		for (JsonElement itm : items) {
			if (itm.isJsonObject()) {
				JsonObject obj = itm.getAsJsonObject();
				if (obj.get("id").getAsString().equals(plr.activeLook)) {
					// Select object
					lookObj = obj;

					// Check primary
					if (lookObj.has("components") && lookObj.get("components").getAsJsonObject().has("PrimaryLook")) {
						// Save active look
						plr.account.setActiveLook(plr.activeLook);

						// Sync if updated
						if (!lastLook.equals(lookID)) {
							plr.pendingLookDefID = "8254";
							if (lookObj != null) {
								plr.pendingLookDefID = lookObj.get("defId").getAsString();
							}
							plr.lastAction = 0;
							GameServer srv = (GameServer) client.getServer();
							for (Player player : srv.getPlayers()) {
								if (plr.room != null && player.room != null && player.room.equals(plr.room)
										&& player != plr) {
									plr.syncTo(player, WorldObjectMoverNodeType.InitPosition);
								}
							}
						}
					}

					// Break
					break;
				}
			}
		}

		return true;
	}
}
