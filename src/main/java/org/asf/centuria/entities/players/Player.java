package org.asf.centuria.entities.players;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.logging.log4j.MarkerManager;
import org.asf.centuria.Centuria;
import org.asf.centuria.accounts.AccountManager;
import org.asf.centuria.accounts.CenturiaAccount;
import org.asf.centuria.data.XtWriter;
import org.asf.centuria.entities.generic.Quaternion;
import org.asf.centuria.entities.generic.Vector3;
import org.asf.centuria.entities.generic.Velocity;
import org.asf.centuria.entities.inventoryitems.InventoryItem;
import org.asf.centuria.entities.objects.WorldObjectMoveNodeData;
import org.asf.centuria.entities.objects.WorldObjectPositionInfo;
import org.asf.centuria.entities.trading.Trade;
import org.asf.centuria.entities.uservars.UserVarValue;
import org.asf.centuria.enums.objects.WorldObjectMoverNodeType;
import org.asf.centuria.interactions.dataobjects.StateInfo;
import org.asf.centuria.interactions.groupobjects.GroupObject;
import org.asf.centuria.interactions.modules.QuestManager;
import org.asf.centuria.minigames.AbstractMinigame;
import org.asf.centuria.networking.gameserver.GameServer;
import org.asf.centuria.networking.smartfox.SmartfoxClient;
import org.asf.centuria.packets.xt.gameserver.avatar.AvatarObjectInfoPacket;
import org.asf.centuria.packets.xt.gameserver.object.ObjectDeletePacket;
import org.asf.centuria.packets.xt.gameserver.object.ObjectUpdatePacket;
import org.asf.centuria.packets.xt.gameserver.relationship.RelationshipJumpToPlayerPacket;
import org.asf.centuria.packets.xt.gameserver.room.RoomJoinPacket;
import org.asf.centuria.seasonpasses.SeasonPassDefinition;
import org.asf.centuria.seasonpasses.SeasonPassManager;
import org.asf.centuria.social.SocialManager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

@SuppressWarnings("unused")
public class Player {

	/**
	 * Retrieves objects from the connection container, used to store information in
	 * clients.
	 * 
	 * @since Beta 1.5.3
	 * @param type Object type
	 * @return Object instance or null
	 */
	public <T> T getObject(Class<T> type) {
		return client.getObject(type);
	}

	/**
	 * Adds objects to the connection container, used to store information in
	 * clients.
	 * 
	 * @since Beta 1.5.3
	 * @param obj Object to add
	 */
	public void addObject(Object obj) {
		client.addObject(obj);
	}

	//
	// Moderation (SYNC ONLY)
	//
	public boolean ghostMode = false;
	public boolean overrideTpLocks = false;

	/**
	 * Avoid usage of this field, its not always in sync with the permission
	 * manager, use it only for high-performance code
	 */
	public boolean hasModPerms = false;

	//
	// Blocking and such (again, for sync)
	//
	public ArrayList<String> syncBlockedPlayers = new ArrayList<String>();

	/**
	 * Updates sync to hide/show blocked/unblocked players
	 * 
	 * @param targetPlayerID Target player
	 * @param blocked        True if the player was blocked, false if unblocked
	 */
	public void updateSyncBlock(String targetPlayerID, boolean blocked) {
		if (blocked) {
			// Blocked

			// Check permissions
			CenturiaAccount blockedPlayer = AccountManager.getInstance().getAccount(targetPlayerID);

			// Load permission level
			String permLevel = "member";
			if (blockedPlayer.getSaveSharedInventory().containsItem("permissions")) {
				permLevel = blockedPlayer.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
						.get("permissionLevel").getAsString();
			}
			if (!GameServer.hasPerm(permLevel, "moderator") && !syncBlockedPlayers.contains(targetPlayerID)) {
				// Block sync
				syncBlockedPlayers.add(targetPlayerID);

				// If the player is ingame, remove this player from them
				Player plr = blockedPlayer.getOnlinePlayerInstance();
				if (plr != null) {
					destroyAt(plr);
				}
			}
		} else {
			// Unblocked
			if (syncBlockedPlayers.contains(targetPlayerID)) {
				syncBlockedPlayers.remove(targetPlayerID);

				// Locate account
				CenturiaAccount blockedPlayer = AccountManager.getInstance().getAccount(targetPlayerID);

				// If the player is ingame, show this player to them
				Player plr = blockedPlayer.getOnlinePlayerInstance();
				if (plr != null && roomReady && plr.roomReady && plr.room.equals(room) && plr.levelID.equals(levelID)) {
					syncTo(plr, WorldObjectMoverNodeType.InitPosition);
				}
			}
		}
	}

	//
	// Other fields
	//

	public long keepAliveLast;

	public SmartfoxClient client;
	public CenturiaAccount account;

	public boolean awaitingPlayerSync;

	public String activeLook;
	public String activeSanctuaryLook;
	public boolean sanctuaryPreloadCompleted = false;

	public HashMap<String, Long> respawnItems = new HashMap<String, Long>();

	public String pendingLookDefID = "8254";
	public String pendingLookID = null;

	public boolean roomReady = false;
	public boolean wasInChat = false;

	public int levelType = 0;
	public int previousLevelType = 0;
	public String levelID = "0";
	public String pendingLevelID = "0";

	public String previousRoom = "";
	public String previousLevelID = "0";

	public String pendingRoom = "0";
	public String room = null;

	public String respawn = null;

	public Vector3 lastPos = new Vector3(0, -1000, 0);
	public Vector3 lastHeading = new Vector3(0, -1000, 0);
	public Quaternion lastRot = new Quaternion(0, 0, 0, 0);

	public int lastAction;
	public boolean disableSync;
	public boolean comingFromMinigame;
	public AbstractMinigame currentGame;

	// Teleports
	public String teleportDestination;
	public Vector3 targetPos;
	public Quaternion targetRot;

	// Trades
	public Trade tradeEngagedIn;

	// Quests
	public int questProgress;
	public ArrayList<String> interactions = new ArrayList<String>();
	public ArrayList<GroupObject> groupOjects = new ArrayList<GroupObject>();
	public HashMap<String, Integer> states = new HashMap<String, Integer>();
	public HashMap<Integer, Integer> taskProgress = new HashMap<Integer, Integer>();
	public HashMap<String, ArrayList<StateInfo>> stateObjects = new HashMap<String, ArrayList<StateInfo>>();
	public boolean questStarted = false;
	public int questObjective = 0;

	// Season passes
	private SeasonPassDefinition currentPass;

	public SeasonPassDefinition getCurrentSeasonPass() {
		if (currentPass == null)
			currentPass = SeasonPassManager.getCurrentPass();
		return currentPass;
	}

	public SeasonPassDefinition updateSeasonPass() {
		currentPass = SeasonPassManager.getCurrentPass();
		return currentPass;
	}

	public void destroyAt(Player player) {
		// Delete character
		ObjectDeletePacket packet = new ObjectDeletePacket(account.getAccountID());
		player.client.sendPacket(packet);
	}

	public void syncTo(Player player, WorldObjectMoverNodeType nodeType) {
		if (ghostMode && !player.hasModPerms || player.disableSync)
			return; // Ghosting

		// Check block
		if (!player.hasModPerms && this.syncBlockedPlayers.contains(player.account.getAccountID()))
			return; // Do not sync to blocked players

		// Find avatar
		JsonArray items = account.getSaveSpecificInventory().getItem("avatars").getAsJsonArray();
		JsonObject lookObj = null;
		for (JsonElement itm : items) {
			if (itm.isJsonObject()) {
				JsonObject obj = itm.getAsJsonObject();
				if (obj.get("id").getAsString().equals(activeLook)) {
					lookObj = obj;
					break;
				}
			}
		}

		if (lookObj != null) {
			// Spawn player
			AvatarObjectInfoPacket packet = new AvatarObjectInfoPacket();

			// Object creation parameters
			packet.id = account.getAccountID();
			packet.defId = "852"; // TODO: Move to static final (const)
			packet.ownerId = account.getAccountID();

			packet.lastMove = new WorldObjectMoveNodeData();
			packet.lastMove.serverTime = System.currentTimeMillis() / 1000;
			packet.lastMove.positionInfo = new WorldObjectPositionInfo(lastPos.x, lastPos.y, lastPos.z, lastRot.x,
					lastRot.y, lastRot.z, lastRot.w);
			packet.lastMove.velocity = new Velocity();
			packet.lastMove.nodeType = nodeType;
			packet.lastMove.actorActionType = lastAction;

			// Look and name
			packet.look = lookObj.get("components").getAsJsonObject().get("AvatarLook").getAsJsonObject().get("info")
					.getAsJsonObject();
			packet.displayName = GameServer.getPlayerNameWithPrefix(account);
			packet.unknownValue = 0; // TODO: What is this??
			player.client.sendPacket(packet);
		}
	}

	/**
	 * Attempts to teleport the player to another player's sanctuary.
	 * 
	 * @param sanctuaryOwner The owner of the sanctuary.
	 * @return If the join was a success or not.
	 */
	public boolean teleportToSanctuary(String sanctuaryOwner) {
		return teleportToSanctuary(sanctuaryOwner, false);
	}

	/**
	 * Attempts to teleport the player to another player's sanctuary.
	 * 
	 * @param sanctuaryOwner The owner of the sanctuary.
	 * @param overrideLocks  True to override TP locks, false otherwise
	 * @return If the join was a success or not.
	 */
	public boolean teleportToSanctuary(String sanctuaryOwner, boolean overrideLocks) {
		try {
			boolean isAllowed = true;

			// Load player object
			Player player = this;

			// Find owner
			CenturiaAccount sancOwner = AccountManager.getInstance().getAccount(sanctuaryOwner);
			if (!sancOwner.getSaveSpecificInventory().containsItem("201")) {
				Player plr = sancOwner.getOnlinePlayerInstance();
				if (plr != null)
					plr.activeSanctuaryLook = sancOwner.getActiveSanctuaryLook();
			}

			// Check owner
			boolean isOwner = player.account.getAccountID().equals(sanctuaryOwner);

			if (!isOwner && !overrideLocks) {
				// Load privacy settings
				int privSetting = 0;
				UserVarValue val = sancOwner.getSaveSpecificInventory().getUserVarAccesor().getPlayerVarValue("17544",
						0);
				if (val != null)
					privSetting = val.value;

				// Verify access
				if (privSetting == 2) {
					// Nobody
					isAllowed = false;
				} else if (privSetting == 1) {
					// Followers
					// Check if the owner follows the current player
					if (!SocialManager.getInstance().getPlayerIsFollowing(sanctuaryOwner,
							player.account.getAccountID())) {
						isAllowed = false;
					}
				}

				// Check sanc existence
				JsonObject sanctuaryInfo = sancOwner.getSaveSpecificInventory().getSanctuaryAccessor()
						.getSanctuaryLook(sancOwner.getActiveSanctuaryLook());
				if (sanctuaryInfo == null)
					isAllowed = false;

				// Check block
				if (!player.overrideTpLocks && SocialManager.getInstance().getPlayerIsBlocked(sancOwner.getAccountID(),
						player.account.getAccountID()))
					isAllowed = false;
			}

			// Build room join
			RoomJoinPacket join = new RoomJoinPacket();
			join.success = isAllowed || overrideLocks;
			join.levelType = 2;
			join.levelID = "1689";
			join.roomIdentifier = "sanctuary_" + sanctuaryOwner;
			join.teleport = sanctuaryOwner;

			if (isAllowed || overrideLocks) {
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
				player.pendingRoom = "sanctuary_" + sanctuaryOwner;
				player.levelType = join.levelType;

				// Reset quest data
				taskProgress.clear();
				questProgress = 0;
				questStarted = false;
				questObjective = 0;

				// End current game
				if (currentGame != null) {
					currentGame.onExit(this);
					currentGame = null;
				}

				// Reset states
				states.clear();
				stateObjects.clear();
				interactions.clear();
				groupOjects.clear();

				// Clear respawn items
				respawnItems.clear();
			} else {
				client.sendPacket(join);
				return false;
			}

			// Log
			Centuria.logger.info("Player " + player.account.getDisplayName() + " is joining sanctuary room "
					+ sancOwner.getDisplayName());

			// Send packet
			client.sendPacket(join);

			return true;
		} catch (Exception e) {
			e.printStackTrace();
			client.sendPacket(new RoomJoinPacket().markAsFailed());
			return false;
		}
	}

	/**
	 * Attempts to teleport the player to a specific room.
	 * 
	 * @param levelID        The id of the level.
	 * @param levelType      The type of the level.
	 * @param iisRoomID      The IIS ROOM ID (UNUSED CURRENTLY)
	 * @param roomIdentifier The string indentifier for the level.
	 * @param teleport       TODO: I'm not sure what this is?
	 * @return If the join was a success or not.
	 */
	public boolean teleportToRoom(String levelID, int levelType, int iisRoomID, String roomIdentifier,
			String teleport) {
		try {
			// Load the requested room
			Player plr = this;

			// Sync
			GameServer srv = (GameServer) client.getServer();
			for (Player player : srv.getPlayers()) {
				if (plr.room != null && player.room != null && player.room.equals(plr.room) && player != plr) {
					plr.destroyAt(player);
				}
			}

			// Assign room
			RoomJoinPacket join = new RoomJoinPacket();
			join.levelType = levelType;
			join.levelID = levelID;
			join.teleport = teleport;
			join.roomIdentifier = roomIdentifier;

			plr.roomReady = false;
			plr.pendingLevelID = levelID;
			plr.pendingRoom = roomIdentifier;
			plr.levelType = levelType;

			// Reset quest data
			plr.taskProgress.clear();
			plr.questProgress = 0;
			plr.questStarted = false;
			plr.questObjective = 0;

			// End current game
			if (currentGame != null) {
				currentGame.onExit(plr);
				currentGame = null;
			}

			// Reset states
			states.clear();
			stateObjects.clear();
			interactions.clear();
			groupOjects.clear();

			// Clear respawn items
			plr.respawnItems.clear();

			// Log
			Centuria.logger.debug(MarkerManager.getMarker("JOINROOM"),
					"Client to server (room: " + plr.pendingRoom + ", level: " + plr.pendingLevelID + ")");

			// Send response
			client.sendPacket(join);

			return true;
		} catch (Exception e) {
			e.printStackTrace();
			client.sendPacket(new RoomJoinPacket().markAsFailed());
			return false;
		}
	}

	/**
	 * Attempts to teleport the player to a specific room, targeting a player to
	 * teleport to.
	 * 
	 * @param levelID        The id of the level.
	 * @param levelType      The type of the level.
	 * @param iisRoomID      The IIS ROOM ID (UNUSED CURRENTLY)
	 * @param roomIdentifier The string indentifier for the level.
	 * @param teleport       TODO: I'm not sure what this is?
	 * @param targetedPlayer the player targeted to teleport to.
	 * @return If the join was a success or not.
	 */
	private boolean teleportToRoom(String levelID, int levelType, int iisRoomID, String roomIdentifier, String teleport,
			Player targetedPlayer) {
		try {
			// Load the requested room
			Player plr = this;

			// Sync
			GameServer srv = (GameServer) client.getServer();
			for (Player player : srv.getPlayers()) {
				if (plr.room != null && player.room != null && player.room.equals(plr.room) && player != plr) {
					plr.destroyAt(player);
				}
			}

			// Assign room
			RoomJoinPacket join = new RoomJoinPacket();
			join.levelType = levelType;
			join.levelID = levelID;
			join.roomIdentifier = plr.pendingRoom;
			join.teleport = teleport;
			join.roomIdentifier = roomIdentifier;

			plr.roomReady = false;
			plr.pendingLevelID = levelID;
			plr.pendingRoom = roomIdentifier;
			plr.levelType = levelType;

			plr.teleportDestination = targetedPlayer.account.getAccountID();
			plr.targetPos = new Vector3(targetedPlayer.lastPos.x, targetedPlayer.lastPos.y, targetedPlayer.lastPos.z);
			plr.targetRot = new Quaternion(targetedPlayer.lastRot.x, targetedPlayer.lastRot.y, targetedPlayer.lastRot.z,
					targetedPlayer.lastRot.w);

			plr.respawnItems.clear();

			// Reset quest data
			plr.taskProgress.clear();
			plr.questProgress = 0;
			plr.questStarted = false;
			plr.questObjective = 0;

			// End current game
			if (currentGame != null) {
				currentGame.onExit(plr);
				currentGame = null;
			}

			// Reset states
			states.clear();
			stateObjects.clear();
			interactions.clear();
			groupOjects.clear();

			// Log
			Centuria.logger.debug(MarkerManager.getMarker("JOINROOM"),
					" Client to server (room: " + plr.pendingRoom + ", level: " + plr.pendingLevelID + ")");

			// Send response
			client.sendPacket(join);

			return true;
		} catch (Exception e) {
			e.printStackTrace();
			client.sendPacket(new RoomJoinPacket().markAsFailed());
			return false;
		}
	}

	/**
	 * Attempts to teleport to the previous room.
	 * 
	 * @return If the join was a success or not.
	 */
	public boolean teleportToPreviousRoom() {
		try {
			var plr = this;

			// End current game
			if (currentGame != null) {
				currentGame.onExit(plr);
				currentGame = null;
			}

			// Assign room
			plr.roomReady = false;
			plr.pendingLevelID = plr.previousLevelID;
			plr.pendingRoom = plr.previousRoom;
			plr.levelType = plr.previousLevelType;

			// Send response
			RoomJoinPacket join = new RoomJoinPacket();
			join.levelType = plr.levelType;
			join.roomIdentifier = plr.pendingRoom;
			join.levelID = plr.pendingLevelID;
			client.sendPacket(join);

			// Log
			Centuria.logger.debug(MarkerManager.getMarker("JOINROOM"),
					"Client to server (room: " + plr.pendingRoom + ", level: " + plr.pendingLevelID + ")");

			return true;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}

	}

	/**
	 * Attempts to teleport to the specified player.
	 * 
	 * @param accountID the player id to teleport to.
	 * @return If the join was a success or not.
	 */
	public boolean teleportToPlayer(String accountID) {
		try {
			Player player = this;

			// Find player
			for (Player plr : ((GameServer) client.getServer()).getPlayers()) {
				if ((plr.account.getAccountID().equals(accountID) && plr.roomReady && plr.levelType != 1)
						&& ((!plr.room.equals("room_STAFFROOM")
								&& (!SocialManager.getInstance().socialListExists(accountID) || !SocialManager
										.getInstance().getPlayerIsBlocked(accountID, player.account.getAccountID())))
								|| (player.overrideTpLocks && player.hasModPerms))) {
					// Load privacy settings
					int privSetting = 0;
					UserVarValue val = plr.account.getSaveSpecificInventory().getUserVarAccesor()
							.getPlayerVarValue("17546", 0);
					if (val != null)
						privSetting = val.value;

					// Ghost check
					if (!player.hasModPerms && plr.ghostMode)
						break;

					// Verify privacy settings
					if (!player.overrideTpLocks || !player.hasModPerms) {
						if (privSetting == 1 && !SocialManager.getInstance()
								.getPlayerIsFollowing(plr.account.getAccountID(), player.account.getAccountID()))
							break;
						else if (privSetting == 2)
							break;
					}

					if (!plr.room.equals(player.room)) {
						String teleport = plr.account.getAccountID();

						// Check sanc
						if (plr.levelType == 2 && plr.room.startsWith("sanctuary_")) {
							String sanctuaryOwner = plr.room.substring("sanctuary_".length());
							// Find owner
							CenturiaAccount sancOwner = AccountManager.getInstance().getAccount(sanctuaryOwner);
							if (!sancOwner.getSaveSpecificInventory().containsItem("201")) {
								Player plr2 = sancOwner.getOnlinePlayerInstance();
								if (plr2 != null)
									plr2.activeSanctuaryLook = sancOwner.getActiveSanctuaryLook();
							}
							teleport = sancOwner.getAccountID();

							// Check owner
							boolean isOwner = player.account.getAccountID().equals(sanctuaryOwner);

							if (!isOwner && (!player.overrideTpLocks || !player.hasModPerms)) {
								// Load privacy settings
								privSetting = 0;
								val = sancOwner.getSaveSpecificInventory().getUserVarAccesor()
										.getPlayerVarValue("17544", 0);
								if (val != null)
									privSetting = val.value;

								// Verify access
								if (privSetting == 1
										&& !SocialManager.getInstance().getPlayerIsFollowing(plr.account.getAccountID(),
												player.account.getAccountID()))
									break;
								else if (privSetting == 2)
									break;
							}
						}

						XtWriter writer = new XtWriter();
						writer.writeString("rfjtr");
						writer.writeInt(-1); // data prefix
						writer.writeInt(1); // other world
						writer.writeString("");
						writer.writeString(""); // data suffix
						client.sendPacket(writer.encode());

						// Build room join
						RoomJoinPacket join = new RoomJoinPacket();
						join.levelType = plr.levelType;
						join.levelID = plr.levelID;
						join.roomIdentifier = plr.room;
						join.teleport = teleport;
						player.teleportDestination = plr.account.getAccountID();
						player.targetPos = new Vector3(plr.lastPos.x, plr.lastPos.y, plr.lastPos.z);
						player.targetRot = new Quaternion(plr.lastRot.x, plr.lastRot.y, plr.lastRot.z, plr.lastRot.w);

						// Sync
						GameServer srv = (GameServer) client.getServer();
						for (Player plr2 : srv.getPlayers()) {
							if (plr2.room != null && player.room != null && player.room != null
									&& plr2.room.equals(player.room) && plr2 != player) {
								player.destroyAt(plr2);
							}
						}

						// Assign room
						player.roomReady = false;
						player.pendingLevelID = plr.levelID;
						player.pendingRoom = plr.room;
						player.levelType = plr.levelType;

						// Send packet
						client.sendPacket(join);
					} else {
						XtWriter writer = new XtWriter();
						writer.writeString("rfjtr");
						writer.writeInt(-1); // data prefix
						writer.writeInt(1); // other world
						writer.writeString("");
						writer.writeString(""); // data suffix
						client.sendPacket(writer.encode());

						// Same room, sync player
						ObjectUpdatePacket pkt = new ObjectUpdatePacket();
						pkt.action = 0;
						pkt.mode = 0;
						pkt.id = player.account.getAccountID();
						pkt.position = plr.lastPos;
						pkt.rotation = plr.lastRot;
						pkt.heading = plr.lastHeading;
						pkt.time = System.currentTimeMillis() / 1000;

						// Broadcast sync
						GameServer srv = (GameServer) client.getServer();
						for (Player p : srv.getPlayers()) {
							if (p != player && p.room != null && p.room.equals(player.room)
									&& (!player.ghostMode || p.hasModPerms) && !p.disableSync) {
								p.client.sendPacket(pkt);
							}
						}
					}
					return true;
				}
			}

			XtWriter writer = new XtWriter();
			writer.writeString("rfjtr");
			writer.writeInt(-1); // data prefix
			writer.writeInt(0); // failure
			writer.writeString(""); // data suffix
			client.sendPacket(writer.encode());
			return false;
		} catch (Exception e) {
			e.printStackTrace();

			XtWriter writer = new XtWriter();
			writer.writeString("rfjtr");
			writer.writeInt(-1); // data prefix
			writer.writeInt(0); // failure
			writer.writeString(""); // data suffix
			client.sendPacket(writer.encode());
			return false;
		}
	}

	/**
	 * Gets the players trade list. Will load the trade list if it hasn't been
	 * loaded before.
	 * 
	 * @return A list of items in the player's trade list.
	 */
	public List<InventoryItem> getTradeList() {
		try {
			return this.account.getSaveSpecificInventory().getItemAccessor(this).loadTradeList();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
