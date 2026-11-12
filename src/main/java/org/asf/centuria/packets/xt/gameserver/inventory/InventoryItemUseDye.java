package org.asf.centuria.packets.xt.gameserver.inventory;

import java.io.IOException;
import java.util.ArrayList;

import org.asf.centuria.Centuria;
import org.asf.centuria.accounts.PlayerInventory;
import org.asf.centuria.data.XtReader;
import org.asf.centuria.data.XtWriter;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.networking.smartfox.SmartfoxClient;
import org.asf.centuria.packets.xt.IXtPacket;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class InventoryItemUseDye implements IXtPacket<InventoryItemUseDye> {

	private static final String PACKET_ID = "iud";

	public String itemID;
	public ArrayList<DyeInfo> dyes = new ArrayList<DyeInfo>();
	public ArrayList<Integer> undye = new ArrayList<Integer>();

	// Dye info
	private static class DyeInfo {
		public String dye;
		public Integer channel;
	}

	@Override
	public InventoryItemUseDye instantiate() {
		return new InventoryItemUseDye();
	}

	@Override
	public String id() {
		return PACKET_ID;
	}

	@Override
	public void parse(XtReader reader) {
		itemID = reader.read();

		int l = reader.readInt();
		for (int i = 0; i < l; i++) {
			DyeInfo d = new DyeInfo();
			d.dye = reader.read();
			dyes.add(d);
		}

		for (DyeInfo dye : dyes) {
			dye.channel = reader.readInt() + 1;
		}

		if (reader.hasNext()) {
			l = reader.readInt();
			for (int i = 0; i < l; i++) {
				undye.add(reader.readInt() + 1);
			}
		}
	}

	@Override
	public void build(XtWriter writer) {
	}

	@Override
	public boolean handle(SmartfoxClient client) throws IOException {
		Player plr = (Player) client.container;
		PlayerInventory inv = plr.account.getSaveSpecificInventory();

		// Find clothing item
		if (inv.getClothingAccessor().getClothingData(itemID) != null) {
			// Apply dyes
			dyeObject(client, inv.getClothingAccessor().getClothingData(itemID), inv, false);

			// Save clothes
			inv.setItem("100", inv.getItem("100"));
		}

		// Find furniture item
		if (inv.getFurnitureAccessor().getFurnitureData(itemID) != null) {
			// Apply dyes
			dyeObject(client, inv.getFurnitureAccessor().getFurnitureData(itemID), inv, true);

			// Save furniture
			inv.setItem("102", inv.getItem("102"));
		}

		// Log
		if (Centuria.debugMode) {
			String dyeInfo = "";
			for (DyeInfo dye : dyes) {
				if (!dyeInfo.isEmpty())
					dyeInfo += ", ";
				dyeInfo += dye.channel + "=" + dye.dye;
			}
			String undyeInfo = "";
			for (int channel : undye) {
				if (!undyeInfo.isEmpty())
					undyeInfo += ", ";
				undyeInfo += channel;
			}
			Centuria.logger.debug("[INVENTORY] [DYE]  Client to server (item: " + itemID + ", dyes: " + dyeInfo
					+ ", undyes: " + undyeInfo + ")");
		}

		// Update dyes object in client inventory
		InventoryItemPacket pkt = new InventoryItemPacket();
		pkt.item = inv.getItem("111");
		client.sendPacket(pkt);

		return true;
	}

	private void dyeObject(SmartfoxClient client, JsonObject item, PlayerInventory inv, boolean isFurniture) {
		// Find color object
		if (item.has("components") && item.get("components").getAsJsonObject().has("Colorable")) {
			// Apply dyes
			JsonObject target = item.get("components").getAsJsonObject().get("Colorable").getAsJsonObject();
			for (DyeInfo d : dyes) {
				String dye = d.dye;

				// Apply dye color
				String hsv = inv.getDyeAccessor()
						.getDyeHSV(inv.getDyeAccessor().getDyeData(dye).get("defId").getAsString());
				String obj = "color" + d.channel + "HSV";
				if (target.has(obj) && hsv != null) {
					target.get(obj).getAsJsonObject().remove("_hsv");
					target.get(obj).getAsJsonObject().addProperty("_hsv", hsv);
				}

				// Remove dye from inventory
				inv.getDyeAccessor().removeDye(dye);

				// Check if still present
				if (!inv.getAccessor().hasInventoryObjectByItemId("111", dye)) {
					// Send remove
					InventoryItemRemovedPacket pkt = new InventoryItemRemovedPacket();
					pkt.items = new String[] { dye };
					client.sendPacket(pkt);
				} else {
					// Send update
					InventoryItemPacket pkt = new InventoryItemPacket();
					JsonArray arr = new JsonArray();
					arr.add(inv.getAccessor().findInventoryObjectByItemId("111", dye));
					pkt.item = arr;
					client.sendPacket(pkt);
				}
			}

			// Apply undye
			for (int ch : undye) {
				JsonObject hsv;
				if (!isFurniture)
					hsv = inv.getClothingAccessor().getDefaultClothingChannelHSV(item.get("defId").getAsString(), ch);
				else
					hsv = inv.getFurnitureAccessor().getDefaultFurnitureChannelHSV(item.get("defId").getAsString(), ch);
				String obj = "color" + ch + "HSV";
				if (target.has(obj) && hsv != null) {
					target.remove(obj);
					target.add(obj, hsv);
				}
			}
		}

		// Update object in client inventory
		InventoryItemPacket pkt = new InventoryItemPacket();
		JsonArray arr = new JsonArray();
		arr.add(item);
		pkt.item = arr;
		client.sendPacket(pkt);

		// Save changes
		for (String change : inv.getAccessor().getChangedInventories())
			inv.getAccessor().saveUpdatedItems(change, true);
	}

}
