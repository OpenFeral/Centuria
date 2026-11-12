package org.asf.centuria.packets.xt.gameserver.inventory;

import java.io.IOException;

import org.asf.centuria.Centuria;
import org.asf.centuria.data.XtReader;
import org.asf.centuria.data.XtWriter;
import org.asf.centuria.entities.inspiration.InspirationCombineResult;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.enums.inspiration.InspirationCombineStatus;
import org.asf.centuria.interactions.modules.resourcecollection.levelhooks.EventInfo;
import org.asf.centuria.levelevents.LevelEvent;
import org.asf.centuria.levelevents.LevelEventBus;
import org.asf.centuria.networking.smartfox.SmartfoxClient;
import org.asf.centuria.packets.xt.IXtPacket;

public class InventoryItemInspirationCombinePacket implements IXtPacket<InventoryItemInspirationCombinePacket> {

	private static final String PACKET_ID = "iic";

	public String[] inspirationIds;
	public InspirationCombineResult result;

	@Override
	public InventoryItemInspirationCombinePacket instantiate() {
		return new InventoryItemInspirationCombinePacket();
	}

	@Override
	public String id() {
		return PACKET_ID;
	}

	@Override
	public void parse(XtReader reader) {
		int inspirationCount = reader.readInt();

		inspirationIds = new String[inspirationCount];
		for (int i = 0; i < inspirationCount; i++) {
			inspirationIds[i] = reader.read();
		}
	}

	@Override
	public void build(XtWriter writer) throws IOException {
		writer.writeInt(DATA_PREFIX); // Data prefix

		writer.writeInt(result.combineStatus.value);
		writer.writeString(result.enigmaDefId);

		writer.writeString(DATA_SUFFIX); // Empty suffix
	}

	@Override
	public boolean handle(SmartfoxClient client) throws IOException {
		Player plr = (Player) client.container;

		// Log
		if (Centuria.debugMode) {
			String ids = "";

			for (var id : inspirationIds) {
				ids += id + ", ";
			}

			ids = ids.substring(0, ids.length() - 2);
			Centuria.logger.debug("[INVENTORY] [UPDATE]  Client to server: Combine Inspirations using ids" + ids);
		}

		result = plr.account.getSaveSpecificInventory().getInspirationAccessor().combineInspirations(inspirationIds,
				plr);
		if (result.combineStatus == InspirationCombineStatus.Successful) {
			// Add xp
			EventInfo ev = new EventInfo();
			ev.event = "levelevents.enigmacreated";

			// Find map name
			String map = plr.levelID;
			switch (plr.levelID) {
			case "820":
				map = "cityfera";
				break;
			case "2364":
				map = "bloodtundra";
				break;
			case "9687":
				map = "lakeroot";
				break;
			case "2147":
				map = "mugmyre";
				break;
			case "1689":
				map = "sanctuary";
				break;
			case "3273":
				map = "sunkenthicket";
				break;
			case "1825":
				map = "shatteredbay";
				break;
			}

			// Add tags
			ev.tags.add("enigma:" + result.enigmaDefId);
			ev.tags.add("map:" + map);

			// Dispatch event
			LevelEventBus.dispatch(new LevelEvent(ev.event, ev.tags.toArray(new String[0]), plr));
		}

		// build packet
		plr.client.sendPacket(this);

		if (Centuria.debugMode) {
			Centuria.logger.debug("[INVENTORY] [UPDATE]  Server to client: " + this.build());
		}

		return true;
	}
}
