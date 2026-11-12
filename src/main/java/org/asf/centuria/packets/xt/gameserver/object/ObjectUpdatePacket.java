package org.asf.centuria.packets.xt.gameserver.object;

import java.io.IOException;

import org.apache.logging.log4j.MarkerManager;
import org.asf.centuria.Centuria;
import org.asf.centuria.data.XtReader;
import org.asf.centuria.data.XtWriter;
import org.asf.centuria.entities.generic.Quaternion;
import org.asf.centuria.entities.generic.Vector3;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.enums.objects.WorldObjectMoverNodeType;
import org.asf.centuria.networking.chatserver.ChatClient;
import org.asf.centuria.networking.chatserver.networking.SendMessage;
import org.asf.centuria.networking.gameserver.GameServer;
import org.asf.centuria.networking.smartfox.SmartfoxClient;
import org.asf.centuria.packets.xt.IXtPacket;
import org.asf.centuria.social.SocialManager;

import com.google.gson.JsonObject;

public class ObjectUpdatePacket implements IXtPacket<ObjectUpdatePacket> {

	private static final String PACKET_ID = "ou";

	// Main fields
	public String id;
	public long time;
	public int mode;

	// UUID-mode
	public String targetUUID;

	// Coordiante-mode
	public Vector3 position = new Vector3();
	public Vector3 heading = new Vector3();
	public Quaternion rotation = new Quaternion();
	public float speed;

	// Action
	public int action = -1;

	@Override
	public ObjectUpdatePacket instantiate() {
		return new ObjectUpdatePacket();
	}

	@Override
	public String id() {
		return PACKET_ID;
	}

	@Override
	public void parse(XtReader reader) throws IOException {
		mode = reader.readInt();

		// Check mode
		switch (mode) {
			case 0:
				// Initial
			case 2: {
				// Move
				// Same as initial

				// Read position
				position.x = reader.readDouble();
				position.y = reader.readDouble();
				position.z = reader.readDouble();

				// Read heading
				heading.x = reader.readDouble();
				heading.y = reader.readDouble();
				heading.z = reader.readDouble();

				// Read rotation
				rotation.x = reader.readDouble();
				rotation.y = reader.readDouble();
				rotation.z = reader.readDouble();
				rotation.w = reader.readDouble();

				// Read speed
				speed = reader.readFloat();
				break;
			}
			case 4: {
				// Action

				// Read position
				position.x = reader.readDouble();
				position.y = reader.readDouble();
				position.z = reader.readDouble();

				// Read heading
				heading.x = reader.readDouble();
				heading.y = reader.readDouble();
				heading.z = reader.readDouble();

				// Read rotation
				rotation.x = reader.readDouble();
				rotation.y = reader.readDouble();
				rotation.z = reader.readDouble();
				rotation.w = reader.readDouble();

				// Read speed
				speed = reader.readFloat();

				// Read action
				action = reader.readInt();
				break;
			}
			case 5: {
				// TP
				targetUUID = reader.read();
				break;
			}
			default:
				// Print out world object update call..
				if (Centuria.debugMode) {
					Centuria.logger.debug("[OBJECTS] [OU] Unhandled Mode " + mode + ": " + reader.readRemaining());
				}
		}

	}

	@Override
	public void build(XtWriter writer) throws IOException {
		writer.writeInt(DATA_PREFIX); // Data prefix

		// Main fields
		writer.writeString(id);
		writer.writeInt(mode);
		writer.writeLong(time);

		// Write position
		writer.writeDouble(position.x);
		writer.writeDouble(position.y);
		writer.writeDouble(position.z);

		// Then WW's fucking nightmare: DONT ASK BC IDK HOW BUT IT WORKS
		writer.writeDouble(heading.x);
		writer.writeDouble(rotation.y);
		writer.writeDouble(rotation.z);
		writer.writeDouble(rotation.w);
		writer.writeDouble(heading.y);
		writer.writeDouble(heading.z);
		writer.writeDouble(rotation.x);
		writer.writeFloat(speed);

		// Write action
		writer.writeInt(action);

		writer.writeString(DATA_SUFFIX);
	}

	@Override
	public boolean handle(SmartfoxClient client) throws IOException {
		// Object update
		Player plr = (Player) client.container;
		if (plr.room == null)
			return true;

		// Check if awaiting players
		if (plr.awaitingPlayerSync) {
			// Sync current player to others
			for (Player player : ((GameServer) client.getServer()).getPlayers()) {
				if (plr.room != null && player.room != null && player.room.equals(plr.room) && player != plr) {
					plr.syncTo(player, WorldObjectMoverNodeType.InitPosition);
					Centuria.logger.debug(MarkerManager.getMarker("WorldReadyPacket"), "Syncing player "
							+ player.account.getDisplayName() + " to " + plr.account.getDisplayName());
				}
			}
			plr.awaitingPlayerSync = false;

			// Notify if ghosting
			if (plr.ghostMode)
				Centuria.systemMessage(plr, "Reminder: you are ghosting", true);

			// Alert about room state if needed
			ChatClient chatClient = Centuria.chatServer.getClient(plr.account.getAccountID());
			if (chatClient != null)
				SendMessage.playerJoinedWorld(chatClient, plr.room);

			// Send message if joining since the DM system update
			if (!plr.account.getSaveSharedInventory().containsItem("dmsystemupdated")) {
				// Send message
				Centuria.systemMessage(plr,
						"Welcome back to Fer.al! There have been huge changes to private chat since the time you last logged on.\n\nFirstly, private chat history size is now unlimited! While the public chats still do not record their history, private chats do and are no longer capped at 20 messages.\n\nWhat is important to remember is that as of this update, if a private chat goes inactive for over 60 days, it will get deleted. This is to conserve server storage, you will get a reminder about the limit should the conversation be inactive for 30 days or more.\n\nEnjoy!\n\n- Centuria Development Team",
						true);

				// Mark up to date
				plr.account.getSaveSharedInventory().setItem("dmsystemupdated", new JsonObject());
			}
		}

		// Add fields
		id = plr.account.getAccountID();
		time = System.currentTimeMillis() / 1000;

		// UUID-based teleport
		if (mode == 5) {
			boolean success = false;

			// First attempt to find a player with the ID
			for (Player player : ((GameServer) client.getServer()).getPlayers()) {
				if (player.account.getAccountID().equals(targetUUID)) {
					// Check social system
					if (player.roomReady && player.levelType != 1
							&& (!SocialManager.getInstance().socialListExists(targetUUID) || !SocialManager
									.getInstance().getPlayerIsBlocked(targetUUID, plr.account.getAccountID())
									|| (player.overrideTpLocks && player.hasModPerms))) {
						// Mark success
						success = true;

						// Load coordinates
						position.x = player.lastPos.x;
						position.y = player.lastPos.y;
						position.z = player.lastPos.z;
						rotation.w = player.lastRot.w;
						rotation.x = player.lastRot.x;
						rotation.y = player.lastRot.y;
						rotation.z = player.lastRot.z;
						break;
					}
				}
			}

			// FIXME: support teleporter pads / destinations
			// FIXME: make sure the client doesnt break with these

			// Cancel if not found
			if (!success)
				return true;
		}

		// Save position
		plr.lastHeading = heading;
		plr.lastPos = position;
		plr.lastRot = rotation;
		if (mode == 4)
			plr.lastAction = action;

		// Broadcast sync
		GameServer srv = (GameServer) client.getServer();
		for (Player player : srv.getPlayers()) {
			if (player != plr && player.room != null && player.room.equals(plr.room)
					&& (!plr.ghostMode || player.hasModPerms) && !player.disableSync
					&& (!plr.syncBlockedPlayers.contains(player.account.getAccountID()) || player.hasModPerms)) {
				player.client.sendPacket(this);
			}
		}

		return true;
	}

}
