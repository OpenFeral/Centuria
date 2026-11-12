package org.asf.centuria.networking.chatserver;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.TimeZone;
import java.util.UUID;

import org.asf.centuria.Centuria;
import org.asf.centuria.accounts.AccountManager;
import org.asf.centuria.accounts.CenturiaAccount;
import org.asf.centuria.dms.DMManager;
import org.asf.centuria.dms.PrivateChatMessage;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.modules.eventbus.EventBus;
import org.asf.centuria.modules.events.chat.ChatLoginEvent;
import org.asf.centuria.networking.chatserver.networking.SendMessage;
import org.asf.centuria.networking.chatserver.networking.moderator.ModeratorClient;
import org.asf.centuria.networking.chatserver.proxies.OcProxyInfo;
import org.asf.centuria.networking.chatserver.rooms.ChatRoom;
import org.asf.centuria.networking.chatserver.rooms.ChatRoomTypes;
import org.asf.centuria.networking.gameserver.GameServer;
import org.asf.centuria.networking.persistentservice.BasePersistentServiceClient;
import org.asf.centuria.social.SocialManager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ChatClient extends BasePersistentServiceClient<ChatClient, ChatServer> {

	private CenturiaAccount player;
	private HashMap<String, ChatRoom> rooms = new HashMap<String, ChatRoom>();
	private HashMap<String, ChatRoom> localRooms = new HashMap<String, ChatRoom>();

	// Room lock
	public boolean isReady = false;

	private ArrayList<OcProxyMetadata> proxies = new ArrayList<OcProxyMetadata>();

	public static class OcProxyMetadata {
		public String name;
		public String prefix;
		public String suffix;
	}

	/**
	 * Retrieves all OC proxy metadata
	 * 
	 * @return Array of OcProxyMetadata instances
	 */
	public OcProxyMetadata[] getOcProxyMetadata() {
		synchronized (proxies) {
			return proxies.toArray(t -> new OcProxyMetadata[t]);
		}
	}

	/**
	 * Reloads all OC proxies
	 */
	public void reloadProxies() {
		// Reload
		synchronized (proxies) {
			// Clear
			proxies.clear();

			// Retrieve all
			for (OcProxyInfo proxy : OcProxyInfo.allOfUser(getPlayer())) {
				OcProxyMetadata d = new OcProxyMetadata();
				d.name = proxy.displayName;
				d.prefix = proxy.triggerPrefix;
				d.suffix = proxy.triggerSuffix;
				proxies.add(d);
			}
		}
	}

	public ChatClient(Socket client, ChatServer server) {
		super(client, server);
	}

	@Override
	protected void stop() {
		synchronized (rooms) {
			String[] leftRooms = rooms.keySet().toArray(t -> new String[t]);
			rooms.clear();
			localRooms.clear();
			for (String room : leftRooms)
				getServer().leaveRoom(room);
		}

		// Send to moderator clients
		for (ChatClient client : getServer().getClients()) {
			if (client.getObject(ModeratorClient.class) == null)
				continue;

			// Check moderator perms
			String permLevel = "member";
			if (client.getPlayer().getSaveSharedInventory().containsItem("permissions")) {
				permLevel = client.getPlayer().getSaveSharedInventory().getItem("permissions").getAsJsonObject()
						.get("permissionLevel").getAsString();
			}
			if (!GameServer.hasPerm(permLevel, "moderator"))
				continue;

			// Send packet
			JsonObject response = new JsonObject();
			response.addProperty("eventId", "centuria.moderatorclient.playerdisconnected");
			response.addProperty("success", true);
			response.addProperty("uuid", getPlayer().getAccountID());
			client.sendPacket(response);
		}
	}

	@Override
	protected void runClient() throws IOException {
		// Read initial packet
		JsonObject handshakeStart = readRawPacket();

		// Check validity
		if (!handshakeStart.get("cmd").getAsString().equals("sessions.start")) {
			// Invalid request, too early to send different packet
			disconnect();
			return;
		}

		// Parse payload
		String token = handshakeStart.get("auth_token").getAsString();

		// Verify signature
		String verifyD = token.split("\\.")[0] + "." + token.split("\\.")[1];
		String sig = token.split("\\.")[2];
		if (!Centuria.verify(verifyD.getBytes("UTF-8"), Base64.getUrlDecoder().decode(sig))) {
			disconnect();
			return;
		}

		// Verify expiry
		JsonObject jwtPl = JsonParser
				.parseString(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), "UTF-8"))
				.getAsJsonObject();
		if (!jwtPl.has("exp") || jwtPl.get("exp").getAsLong() < System.currentTimeMillis() / 1000) {
			disconnect();
			return;
		}

		// Parse JWT
		JsonObject payload = JsonParser
				.parseString(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), "UTF-8"))
				.getAsJsonObject();

		// Verify access
		if (!payload.has("acs") || !payload.get("acs").getAsString().equals("gameplay")) {
			disconnect();
			return;
		}

		// Locate account
		CenturiaAccount acc = AccountManager.getInstance().getAccount(payload.get("uuid").getAsString());
		if (acc == null) {
			disconnect();
			return;
		}

		// Rename thread
		Thread.currentThread()
				.setName("Chat Client Thread: " + acc.getDisplayName() + " [ID " + acc.getAccountID() + ", Address "
						+ ((InetSocketAddress) getSocket().getRemoteSocketAddress()).getAddress().getHostAddress()
						+ "]");

		// Remove sensitive info and fire event
		handshakeStart.remove("auth_token");
		ChatLoginEvent evt = new ChatLoginEvent(getServer(), acc, this, handshakeStart);
		EventBus.getInstance().dispatchEvent(evt);
		if (evt.isCancelled()) {
			disconnect(); // Cancelled
			return;
		}

		// Check moderator perms
		String permLevel = "member";
		if (acc.getSaveSharedInventory().containsItem("permissions")) {
			permLevel = acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject().get("permissionLevel")
					.getAsString();
		}

		// Check maintenance mode
		if (Centuria.gameServer.maintenance) {
			boolean lockout = true;

			// Check permissions
			if (GameServer.hasPerm(permLevel, "admin")) {
				lockout = false;
			}

			if (lockout || Centuria.gameServer.shutdown) {
				disconnect();
				return;
			}
		}

		// Check bans
		if (acc.isBanned()) {
			disconnect();
			return;
		}

		// Check ip ban
		InetSocketAddress ip = (InetSocketAddress) getSocket().getRemoteSocketAddress();
		InetAddress addr = ip.getAddress();
		String ipaddr = addr.getHostAddress();
		if (GameServer.isIPBanned(ipaddr, acc, Centuria.gameServer.vpnIpsV4, Centuria.gameServer.vpnIpsV6,
				Centuria.gameServer.whitelistFile)) {
			disconnect();
			return;
		}

		// Check if the player is ingame
		Player plr = acc.getOnlinePlayerInstance();
		if (plr != null) {
			// Check if the player was in chat
			if (plr.wasInChat && plr.room != null)
				joinRoom(plr.room, ChatRoomTypes.ROOM_CHAT);
		} else {
			// Security checks
			// Check moderator perms
			if (!GameServer.hasPerm(permLevel, "moderator")) {
				// No access to chat when offline if not a mod
				disconnect();
				return;
			}
		}

		// Log the login attempt
		Centuria.logger.info("Chat login from IP: " + getSocket().getRemoteSocketAddress() + ": " + acc.getLoginName());

		// Save account in memory
		player = acc;
		Centuria.logger.info("Player " + getPlayer().getDisplayName() + " connected to the chat server.");

		// Load DMs into memory
		if (acc.getSaveSharedInventory().containsItem("dms")) {
			// Load and sanitize dms
			JsonObject dms = acc.getSaveSharedInventory().getItem("dms").getAsJsonObject();
			ArrayList<String> toRemove = new ArrayList<String>();
			for (String user : dms.keySet()) {
				// Clean DM participants
				String dmID = dms.get(user).getAsString();
				int participantC = 0;
				if (DMManager.getInstance().dmExists(dmID)) {
					String[] participants = DMManager.getInstance().getDMParticipants(dmID);
					participantC = participants.length;
					for (String participant : participants) {
						if (!participant.startsWith("plaintext:")) {
							// Check account
							if (AccountManager.getInstance().getAccount(participant) == null) {
								participantC--;
								DMManager.getInstance().removeParticipant(dmID, participant);
							}
						}
					}
				}

				// Check validity
				if (AccountManager.getInstance().getAccount(user) == null || participantC <= 1) {
					toRemove.add(user);
					continue;
				}

				// Check if all the others blocked the member
				boolean hasNonBlocked = false;
				for (String p2 : DMManager.getInstance().getDMParticipants(dmID)) {
					if (!p2.equals(acc.getAccountID()) && !p2.startsWith("plaintext:")) {
						if (!SocialManager.getInstance().socialListExists(p2)
								|| !SocialManager.getInstance().getPlayerIsBlocked(p2, acc.getAccountID()))
							hasNonBlocked = true;
					}
				}
				if (!hasNonBlocked && !GameServer.hasPerm(permLevel, "moderator")) {
					// Skip joining the room to avoid the client receiving any sign of the account
					// that blocked them still existing
					continue;
				}

				// Join room
				joinRoom(dms.get(user).getAsString(), ChatRoomTypes.PRIVATE_CHAT);
			}

			// Remove nonexistent and invalid dms
			for (String user : toRemove) {
				if (DMManager.getInstance().dmExists(dms.get(user).getAsString()))
					DMManager.getInstance().deleteDM(dms.get(user).getAsString());
				dms.remove(user);
			}

			// Save if needed
			if (toRemove.size() != 0)
				acc.getSaveSharedInventory().setItem("dms", dms);
		}

		// FIXME: load modules
		// FIXME: update gcs module to load at the right time

		// Send success
		JsonObject res = new JsonObject();
		res.addProperty("eventId", "sessions.start");
		res.addProperty("success", true);
		sendPacket(res);

		// Reload proxies
		reloadProxies();

		// Send to moderator clients
		for (ChatClient client : getServer().getClients()) {
			if (client.getObject(ModeratorClient.class) == null)
				continue;

			// Check moderator perms
			String permLevel2 = "member";
			if (client.getPlayer().getSaveSharedInventory().containsItem("permissions")) {
				permLevel2 = client.getPlayer().getSaveSharedInventory().getItem("permissions").getAsJsonObject()
						.get("permissionLevel").getAsString();
			}
			if (!GameServer.hasPerm(permLevel2, "moderator"))
				continue;

			// Send packet
			JsonObject response = new JsonObject();
			response.addProperty("eventId", "centuria.moderatorclient.playerconnected");
			response.addProperty("success", true);
			response.addProperty("uuid", getPlayer().getAccountID());
			client.sendPacket(response);
		}
	}

	/**
	 * Retrieves the connected player object
	 * 
	 * @return CenturiaAccount instance
	 */
	public CenturiaAccount getPlayer() {
		return player;
	}

	/**
	 * Checks if the client is in a specific chat room
	 * 
	 * @param room Chat room to check
	 * @return True if the client is in the specified room, false otherwise
	 */
	public boolean isInRoom(String room) {
		synchronized (rooms) {
			if (rooms.containsKey(room))
				return true; // Player is part of this chat room
			return false; // Player is not part of this chat room
		}
	}

	/**
	 * Leaves a chat room
	 * 
	 * @param room Room to leave
	 */
	public void leaveRoom(String room) {
		boolean left = false;
		String oldType = null;
		synchronized (rooms) {
			ChatRoom roomInstance = null;
			if (rooms.containsKey(room)) {
				roomInstance = rooms.get(room);
				oldType = roomInstance.getType();
				rooms.remove(room);
				localRooms.remove(room);
				left = true;
			}
			if (roomInstance != null)
				leftRoom(roomInstance);
			getServer().leaveRoom(room);
		}
		if (left && !oldType.equals(ChatRoomTypes.PRIVATE_CHAT)) {
			// Send to moderator clients
			for (ChatClient client : getServer().getClients()) {
				if (client.getObject(ModeratorClient.class) == null)
					continue;

				// Check moderator perms
				String permLevel = "member";
				if (client.getPlayer().getSaveSharedInventory().containsItem("permissions")) {
					permLevel = client.getPlayer().getSaveSharedInventory().getItem("permissions").getAsJsonObject()
							.get("permissionLevel").getAsString();
				}
				if (!GameServer.hasPerm(permLevel, "moderator"))
					continue;

				// Send packet
				JsonObject response = new JsonObject();
				response.addProperty("eventId", "centuria.moderatorclient.playerleftroom");
				response.addProperty("success", true);
				response.addProperty("conversationId", room);
				response.addProperty("conversationType", "room");
				response.addProperty("uuid", getPlayer().getAccountID());
				client.sendPacket(response);
			}
		}
	}

	/**
	 * Joins a chat room
	 * 
	 * @param room Room to join
	 * @param type Room type
	 */
	public void joinRoom(String room, String type) {
		boolean joined = false;
		synchronized (rooms) {
			if (!rooms.containsKey(room)) {
				ChatRoom roomInstance = getServer().joinRoom(type, room);
				ChatRoom localRoom = new ChatRoom(false, type, room, getServer());
				localRooms.put(room, localRoom);
				rooms.put(room, roomInstance);
				joined = true;
				joinedRoom(roomInstance);
				SendMessage.joinedRoom(this, roomInstance);
			}
		}
		if (joined && !type.equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT)) {
			// Send to moderator clients
			for (ChatClient client : getServer().getClients()) {
				if (client.getObject(ModeratorClient.class) == null)
					continue;

				// Check moderator perms
				String permLevel = "member";
				if (client.getPlayer().getSaveSharedInventory().containsItem("permissions")) {
					permLevel = client.getPlayer().getSaveSharedInventory().getItem("permissions").getAsJsonObject()
							.get("permissionLevel").getAsString();
				}
				if (!GameServer.hasPerm(permLevel, "moderator"))
					continue;

				// Send packet
				JsonObject response = new JsonObject();
				response.addProperty("eventId", "centuria.moderatorclient.playerjoinedroom");
				response.addProperty("success", true);
				response.addProperty("conversationId", room);
				response.addProperty("conversationType", "room");
				response.addProperty("uuid", getPlayer().getAccountID());
				client.sendPacket(response);
			}
		}
	}

	private void joinedRoom(ChatRoom roomInstance) {
		// Send welcome if needed
		String welcomeDm = Centuria.textFilterProperties.getOrDefault("private-chat-notice",
				"Welcome to direct messages! Keep in mind that while staff doesn't actively monitor these, this is still a 13+ space and there are players as young as 13, so we ask to try to keep your private chats safe and respectful. As this is a game with a lot of minors, these are not encrypted, and while not actively monitored, in cases of emergency and serious reports, can be pulled from the server if the need is severe enough.\\\\\\\\\\\\\\\\n\\\\\\\\\\\\\\\\nPlease do note that chat filters are still active in private chats as well and while staff doesnt monitor private chats, filter triggers do still alert the moderators, and in cases of severe reports, staff can request the server chats from the operators. For more information refer to the server rules.")
				.replace("\\n", "\n");
		if (roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT)) {
			// Check state
			DMManager manager = DMManager.getInstance();
			if (!manager.hasSentWelcome(roomInstance.getRoomID()) && manager.dmExists(roomInstance.getRoomID())) {
				// Write message
				SimpleDateFormat fmt = new SimpleDateFormat("yyyy'-'MM'-'dd'T'HH':'mm':'ssXXX");
				fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
				PrivateChatMessage msg = new PrivateChatMessage();
				msg.content = welcomeDm;
				msg.source = new UUID(0, 0).toString();
				msg.sentAt = fmt.format(new Date());
				manager.saveDMMessge(roomInstance.getRoomID(), msg);

				// Set state
				manager.setSentWelcome(roomInstance.getRoomID(), true);
			}
		}
	}

	private void leftRoom(ChatRoom roomInstance) {
		// TODO Auto-generated method stub
	}

	/**
	 * Retrieves an array of all chat rooms
	 * 
	 * @return Array of chat room instances
	 */
	public ChatRoom[] getRoomInstances() {
		synchronized (rooms) {
			return rooms.values().toArray(t -> new ChatRoom[t]);
		}
	}

	/**
	 * Retrieves an array of all chat rooms
	 * 
	 * @return Array of chat room instances
	 */
	public ChatRoom[] getLocalRoomInstances() {
		synchronized (rooms) {
			return localRooms.values().toArray(t -> new ChatRoom[t]);
		}
	}

	/**
	 * Retrieves chat rooms by ID
	 * 
	 * @param id Room ID
	 * @return ChatRoom instance or null
	 */
	public ChatRoom getRoom(String id) {
		synchronized (rooms) {
			return rooms.get(id);
		}
	}

	/**
	 * Retrieves chat rooms by ID
	 * 
	 * @param id Room ID
	 * @return ChatRoom instance or null
	 */
	public ChatRoom getLocalRoom(String id) {
		synchronized (rooms) {
			return localRooms.get(id);
		}
	}

}
