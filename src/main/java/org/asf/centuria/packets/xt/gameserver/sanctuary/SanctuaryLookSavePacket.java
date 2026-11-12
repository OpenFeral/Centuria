package org.asf.centuria.packets.xt.gameserver.sanctuary;

import java.io.IOException;

import org.asf.centuria.Centuria;
import org.asf.centuria.data.XtReader;
import org.asf.centuria.data.XtWriter;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.networking.smartfox.SmartfoxClient;
import org.asf.centuria.packets.xt.IXtPacket;

public class SanctuaryLookSavePacket implements IXtPacket<SanctuaryLookSavePacket> {

	private static final String PACKET_ID = "sls";

	public String lookSlotId = null;
	public String lookSlotName = null;

	@Override
	public String id() {
		return PACKET_ID;
	}

	@Override
	public SanctuaryLookSavePacket instantiate() {
		return new SanctuaryLookSavePacket();
	}

	@Override
	public void parse(XtReader reader) throws IOException {
		lookSlotId = reader.read();
		lookSlotName = reader.read();
	}

	@Override
	public void build(XtWriter writer) throws IOException {
		writer.writeInt(DATA_PREFIX); // Data prefix

		writer.writeString(lookSlotId);

		writer.writeString(DATA_SUFFIX); // Data suffix
	}

	@Override
	public boolean handle(SmartfoxClient client) throws IOException {
		// Switch sanctuary look
		Player plr = (Player) client.container;

		// Log
		if (Centuria.debugMode) {
			Centuria.logger.debug("[SANCTUARYEDITOR] [SAVELOOK]  Client to server (lookSlotId: " + lookSlotId
					+ ", lookSlotName: " + lookSlotName + ")");
		}

		// save active look into that slot

		plr.account.getSaveSpecificInventory().getSanctuaryAccessor().saveSanctuaryLookToSlot(plr.activeSanctuaryLook,
				lookSlotId, lookSlotName);

		// Send
		for (String change : plr.account.getSaveSpecificInventory().getAccessor().getChangedInventories())
			plr.account.getSaveSpecificInventory().getAccessor().transferUpdatedItemsToPlayer(plr, change);

		// Send look save
		plr.client.sendPacket(this);
		if (Centuria.debugMode) {
			Centuria.logger.debug("[SANCTUARYEDITOR] [SAVELOOK]  Server to client SSL: " + this.build());
		}

		return true;
	}

}
