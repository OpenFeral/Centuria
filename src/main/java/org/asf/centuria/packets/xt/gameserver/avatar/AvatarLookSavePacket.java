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
import org.asf.centuria.packets.xt.gameserver.inventory.InventoryItemPacket;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class AvatarLookSavePacket implements IXtPacket<AvatarLookSavePacket> {

	private static final String PACKET_ID = "alz";

	private String lookID;
	private String lookName;
	private String lookData;

	@Override
	public AvatarLookSavePacket instantiate() {
		return new AvatarLookSavePacket();
	}

	@Override
	public String id() {
		return PACKET_ID;
	}

	@Override
	public void parse(XtReader reader) throws IOException {
		lookID = reader.read();
		lookName = reader.read();
		reader.read();
		lookData = reader.read();
	}

	@Override
	public void build(XtWriter writer) throws IOException {
		writer.writeInt(DATA_PREFIX);
		writer.writeString(lookID);
		writer.writeString(DATA_SUFFIX);
	}

	@Override
	public boolean handle(SmartfoxClient client) throws IOException {
		// Avatar save
		try {
			Player plr = (Player) client.container;

			// Log
			if (Centuria.debugMode) {
				Centuria.logger.debug(
						"[AVATAREDITOR] [SAVELOOK]  Client to server (look: " + lookID + ", name: " + lookName + ")");
			}

			// Parse avatar
			JsonObject lookData = JsonParser.parseString(this.lookData).getAsJsonObject();

			// Save avatar to inventory
			JsonArray items = plr.account.getSaveSpecificInventory().getItem("avatars").getAsJsonArray();
			JsonObject lookObj = null;
			for (JsonElement itm : items) {
				if (itm.isJsonObject()) {
					JsonObject obj = itm.getAsJsonObject();
					if (obj.get("id").getAsString().equals(lookID)) {
						lookObj = obj;
						break;
					}
				}
			}
			JsonArray update = new JsonArray();
			if (lookObj != null) {
				JsonObject ts = new JsonObject();
				ts.addProperty("ts", System.currentTimeMillis());
				JsonObject nm = new JsonObject();
				nm.addProperty("name", lookName);
				JsonObject al = new JsonObject();
				al.addProperty("gender", 0);
				al.add("info", lookData);
				JsonObject components = lookObj.get("components").getAsJsonObject();
				components.remove("Timestamp");
				components.remove("AvatarLook");
				components.remove("Name");
				components.add("Timestamp", ts);
				components.add("AvatarLook", al);
				components.add("Name", nm);
				update.add(lookObj);
			}
			plr.account.getSaveSpecificInventory().setItem("avatars", items);

			// Prevent double save
			plr.pendingLookID = null;
			plr.pendingLookDefID = "8254";

			// Update avatar object in client inventory
			InventoryItemPacket pkt = new InventoryItemPacket();
			pkt.item = update;
			client.sendPacket(pkt);

			// Sync
			GameServer srv = (GameServer) client.getServer();
			plr.lastAction = 0;
			for (Player player : srv.getPlayers()) {
				if (plr.room != null && player.room != null && player.room.equals(plr.room) && player != plr) {
					plr.syncTo(player, WorldObjectMoverNodeType.InitPosition);
				}
			}

			// Send response
			client.sendPacket(this);
		} catch (Exception exception) {
			Centuria.logger.error("[AVATAREDITOR] [SAVELOOK] Exception Caught: ");
			exception.printStackTrace();
			lookID = "";
			client.sendPacket(this);
		}

		return true;
	}

}
