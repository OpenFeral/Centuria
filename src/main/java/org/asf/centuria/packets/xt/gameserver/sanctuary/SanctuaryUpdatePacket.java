package org.asf.centuria.packets.xt.gameserver.sanctuary;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.asf.centuria.Centuria;
import org.asf.centuria.data.XtReader;
import org.asf.centuria.data.XtWriter;
import org.asf.centuria.entities.generic.Quaternion;
import org.asf.centuria.entities.generic.Vector3;
import org.asf.centuria.entities.generic.Velocity;
import org.asf.centuria.entities.inventoryitems.InventoryItem;
import org.asf.centuria.entities.objects.WorldObjectMoveNodeData;
import org.asf.centuria.entities.objects.WorldObjectPositionInfo;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.entities.sanctuaries.RoomInfoObject;
import org.asf.centuria.entities.sanctuaries.SanctuaryObjectData;
import org.asf.centuria.entities.sanctuaries.UpdateSancObjectItem;
import org.asf.centuria.enums.objects.WorldObjectMoverNodeType;
import org.asf.centuria.enums.sanctuaries.SanctuaryObjectType;
import org.asf.centuria.networking.gameserver.GameServer;
import org.asf.centuria.networking.smartfox.SmartfoxClient;
import org.asf.centuria.packets.xt.IXtPacket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class SanctuaryUpdatePacket implements IXtPacket<SanctuaryUpdatePacket> {

	private static final String PACKET_ID = "ssu";

	private Map<UpdateSancObjectItem, Boolean> additions = new HashMap<UpdateSancObjectItem, Boolean>();
	private List<RoomInfoObject> roomChanges = new ArrayList<RoomInfoObject>();
	private List<String> removals = new ArrayList<String>();
	private JsonObject houseInv;

	@Override
	public String id() {
		return PACKET_ID;
	}

	@Override
	public SanctuaryUpdatePacket instantiate() {
		return new SanctuaryUpdatePacket();
	}

	@Override
	public void parse(XtReader reader) throws IOException {

		// first number is how many ssu's there really are
		int numOfAdditions = reader.readInt();
		for (int i = 0; i < numOfAdditions; i++) {
			UpdateSancObjectItem item = new UpdateSancObjectItem();

			// next line is placementId
			item.objectId = reader.read();

			// then its x y z
			Vector3 position = new Vector3(reader.readDouble(), reader.readDouble(), reader.readDouble());
			// then rot x y z w
			Quaternion rotation = new Quaternion(reader.readDouble(), reader.readDouble(), reader.readDouble(),
					reader.readDouble());

			// then it's uhm.. state
			// THIS AFTERWARDS IS PARENT ID LIKELY
			// THEN AFTER IS GRID ID
			SanctuaryObjectData info = new SanctuaryObjectData(new WorldObjectPositionInfo(position, rotation),
					reader.readInt(), reader.read(), reader.readInt());

			item.objectInfo = info;
			additions.put(item, true);
		}

		// Log
		if (Centuria.debugMode) {
			if (numOfAdditions > 0) {
				Centuria.logger.debug(
						"[SANCTUARY] [UPDATE] Client to server: " + numOfAdditions + " furniture additions...");
			}
		}

		int numOfRemovals = reader.readInt();
		for (int i = 0; i < numOfRemovals; i++) {
			// removals
			removals.add(reader.read());
		}

		// Log
		if (Centuria.debugMode) {
			if (numOfRemovals > 0) {
				System.out
						.println("[SANCTUARY] [UPDATE] Client to server: " + numOfRemovals + " furniture removals...");
			}
		}

		int numOfRoomChanges = reader.readInt();
		for (int i = 0; i < numOfRoomChanges; i++) {
			// room changes
			roomChanges.add(new RoomInfoObject(JsonParser.parseString(reader.read()).getAsJsonObject()));
		}

		// Log
		if (Centuria.debugMode) {
			if (numOfRoomChanges > 0) {
				Centuria.logger.debug("[SANCTUARY] [UPDATE] Client to server: " + numOfRoomChanges + " room updates...");
			}
		}
	}

	@Override
	public void build(XtWriter writer) throws IOException {
		writer.writeInt(-1); // Data prefix

		// Success
		writer.writeBoolean(true);

		writer.writeInt(additions.size()); // number of item additions
		if (additions.size() != 0) {
			for (var item : additions.keySet()) {
				writer.writeString(item.objectId);
			}
		}

		writer.writeInt(removals.size()); // number of removals
		if (removals.size() != 0) {
			for (var item : removals) {
				writer.writeString(item);
			}
		}

		writer.writeString(""); // Data suffix
	}

	@Override
	public boolean handle(SmartfoxClient client) throws IOException {
		// Switch sanctuary look
		Player plr = (Player) client.container;

		// updates
		for (var item : additions.keySet()) {
			additions.replace(item, plr.account.getSaveSpecificInventory().getSanctuaryAccessor()
					.addSanctuaryObject(item.objectId, item.objectInfo, plr.activeSanctuaryLook));
		}

		// removals
		for (var item : removals) {
			plr.account.getSaveSpecificInventory().getSanctuaryAccessor().removeSanctuaryObject(item,
					plr.activeSanctuaryLook);
		}

		// room updates
		houseInv = plr.account.getSaveSpecificInventory().getSanctuaryAccessor()
				.updateSanctuaryRoomData(plr.activeSanctuaryLook, roomChanges.toArray(new RoomInfoObject[0]));

		// Send inventory updates to owner
		for (String change : plr.account.getSaveSpecificInventory().getAccessor().getChangedInventories())
			plr.account.getSaveSpecificInventory().getAccessor().transferUpdatedItemsToPlayer(plr, change);

		// Send self
		plr.client.sendPacket(this);

		// Send update packets
		sendObjectUpdatePackets(client);
		return true;
	}

	public void sendObjectUpdatePackets(SmartfoxClient client) {

		try {
			for (var updateSet : additions.entrySet()) {

				if (updateSet.getValue()) {
					var update = updateSet.getKey();
					var owner = (Player) client.container;
					var furnItem = owner.account.getSaveSpecificInventory().getFurnitureAccessor()
							.getFurnitureData(update.objectId);

					// now do an OI packet
					for (Player player : ((GameServer) client.getServer()).getPlayers()) {
						if (player.room != null && player.room.equals("sanctuary_" + owner.account.getAccountID())) {

							// Send packet
							SanctuaryWorldObjectInfoPacket packet = new SanctuaryWorldObjectInfoPacket();

							// Object creation parameters
							packet.id = update.objectId; // World object ID
							packet.defId = "1751"; // Sanctuary Actor Def Id.
							packet.ownerId = player.room.substring("sanctuary_".length()); // Owner ID

							// Object info
							packet.lastMove = new WorldObjectMoveNodeData();
							packet.lastMove.positionInfo = new WorldObjectPositionInfo(
									update.objectInfo.positionInfo.position.x,
									update.objectInfo.positionInfo.position.y,
									update.objectInfo.positionInfo.position.z,
									update.objectInfo.positionInfo.rotation.x,
									update.objectInfo.positionInfo.rotation.y,
									update.objectInfo.positionInfo.rotation.z,
									update.objectInfo.positionInfo.rotation.w);
							packet.lastMove.velocity = new Velocity();
							packet.lastMove.serverTime = System.currentTimeMillis() / 1000;
							packet.lastMove.actorActionType = 0;
							packet.lastMove.nodeType = WorldObjectMoverNodeType.InitPosition;

							packet.objectType = SanctuaryObjectType.Furniture;
							// Only send json if its not the owner
							packet.writeFurnitureInfo = !player.account.getAccountID()
									.equals(owner.account.getAccountID());
							packet.funitureObject = furnItem;
							packet.sancObjectInfo = update.objectInfo;

							player.client.sendPacket(packet);

							// Log
							if (Centuria.debugMode) {
								Centuria.logger.debug(
										"[SANCTUARY] [UPDATE] Server to client: load object (" + packet.build() + ")");
							}
						}
					}
				} else {
					// item limit, don't spawn the item.
				}

			}

			for (var removedItemId : removals) {
				var owner = (Player) client.container;

				// now do an OD packet
				for (Player player : ((GameServer) client.getServer()).getPlayers()) {
					if (player.room != null && player.room.equals("sanctuary_" + owner.account.getAccountID())) {
						// Send packet
						XtWriter wr = new XtWriter();
						wr.writeString("od");
						wr.writeInt(-1); // data prefix

						// Object creation parameters
						wr.writeString(removedItemId); // World object ID
						wr.writeString(""); // data suffix
						String pk = wr.encode();
						player.client.sendPacket(pk);

						// Log
						if (Centuria.debugMode) {
							Centuria.logger.debug("[SANCTUARY] [UPDATE] Server to client: Delete object (" + pk + ")");
						}
					}
				}
			}

			if (!this.roomChanges.isEmpty()) {
				var owner = (Player) client.container;

				// now do an OI packet
				for (Player player : ((GameServer) client.getServer()).getPlayers()) {
					if (player.room != null && player.room.equals("sanctuary_" + owner.account.getAccountID())) {
						// Send packet
						SanctuaryWorldObjectInfoPacket packet = new SanctuaryWorldObjectInfoPacket();

						// Object creation parameters
						packet.id = houseInv.get(InventoryItem.UUID_PROPERTY_NAME).getAsString(); // World object ID
						packet.defId = "1751"; // Sanctuary Actor Def Id.
						packet.ownerId = player.room.substring("sanctuary_".length()); // Owner ID

						// Object info
						packet.lastMove = new WorldObjectMoveNodeData();
						packet.lastMove.positionInfo = new WorldObjectPositionInfo(0, 0, 0, 0, 0, 0, 0);
						packet.lastMove.velocity = new Velocity();
						packet.lastMove.serverTime = System.currentTimeMillis() / 1000;
						packet.lastMove.actorActionType = 0;
						packet.lastMove.nodeType = WorldObjectMoverNodeType.InitPosition;

						packet.objectType = SanctuaryObjectType.House;
						// Only send json if its not the owner
						packet.writeFurnitureInfo = !player.account.getAccountID().equals(owner.account.getAccountID());
						packet.funitureObject = houseInv;
						packet.sancObjectInfo = new SanctuaryObjectData(
								packet.lastMove.positionInfo, houseInv.get(InventoryItem.COMPONENTS_PROPERTY_NAME)
										.getAsJsonObject().get("House").getAsJsonObject().get("gridId").getAsInt(),
								"", 0);

						player.client.sendPacket(packet);

						// Log
						if (Centuria.debugMode) {
							Centuria.logger.debug(
									"[SANCTUARY] [UPDATE] Server to client: update house (" + packet.build() + ")");
						}

					}
				}
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}