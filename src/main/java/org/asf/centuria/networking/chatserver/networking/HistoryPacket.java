package org.asf.centuria.networking.chatserver.networking;

import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.UUID;

import org.asf.centuria.Centuria;
import org.asf.centuria.dms.DMManager;
import org.asf.centuria.dms.PrivateChatMessage;
import org.asf.centuria.entities.uservars.UserVarValue;
import org.asf.centuria.networking.chatserver.ChatClient;
import org.asf.centuria.networking.chatserver.rooms.ChatRoomTypes;
import org.asf.centuria.networking.gameserver.GameServer;
import org.asf.centuria.social.SocialManager;
import org.asf.centuria.textfilter.TextFilterService;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class HistoryPacket extends AbstractChatPacket {

	public String convo;
	public int cursor;
	public int pageSize;
	public boolean includeMessages;

	@Override
	public String id() {
		return "conversations.history";
	}

	@Override
	public AbstractChatPacket instantiate() {
		return new HistoryPacket();
	}

	@Override
	public void parse(JsonObject data) {
		convo = data.get("conversationId").getAsString();
		pageSize = data.get("pageSize").getAsInt();
		if (data.has("cursor") && !data.get("cursor").getAsString().isEmpty())
			cursor = data.get("cursor").getAsInt();
		includeMessages = data.get("include_messages").getAsBoolean();
	}

	@Override
	public void build(JsonObject data) {
	}

	@Override
	public boolean handle(ChatClient client) {
		// Send response
		JsonObject res = new JsonObject();

		// Time format
		SimpleDateFormat fmt = new SimpleDateFormat("yyyy'-'MM'-'dd'T'HH':'mm':'ssXXX");
		fmt.setTimeZone(TimeZone.getTimeZone("UTC"));

		// Check moderator perms
		String permLevel = "member";
		if (client.getPlayer().getSaveSharedInventory().containsItem("permissions")) {
			permLevel = client.getPlayer().getSaveSharedInventory().getItem("permissions").getAsJsonObject()
					.get("permissionLevel").getAsString();
		}

		// Load messages
		int cursorCurrent = cursor;
		int messageOffset = cursorCurrent * pageSize;
		int dmHistorySize = 0;
		DMManager manager = DMManager.getInstance();
		if (client.isInRoom(convo) && client.getRoom(convo).getType().equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT)
				&& manager.dmExists(convo)) {
			// Check if all the others blocked the member
			boolean hasNonBlocked = false;
			for (String p2 : DMManager.getInstance().getDMParticipants(convo)) {
				if (!p2.equals(client.getPlayer().getAccountID()) && !p2.startsWith("plaintext:")) {
					if (!SocialManager.getInstance().socialListExists(p2)
							|| !SocialManager.getInstance().getPlayerIsBlocked(p2, client.getPlayer().getAccountID()))
						hasNonBlocked = true;
				}
			}
			if (hasNonBlocked || GameServer.hasPerm(permLevel, "moderator")) {
				if (includeMessages) {
					JsonArray msgs = new JsonArray();
					int indexInPage = 0;
					PrivateChatMessage[] messages = manager.getDMHistory(convo, client.getPlayer().getAccountID());
					dmHistorySize = messages.length;
					for (int i = messages.length - 1 - messageOffset; i >= 0; i--) {
						PrivateChatMessage msg = messages[i];

						// Build message object
						JsonObject obj = new JsonObject();

						// Load user settings
						int filterSettingSelf = 0;
						UserVarValue valS = client.getPlayer().getSaveSpecificInventory().getUserVarAccesor()
								.getPlayerVarValue("9362", 0);
						if (valS != null)
							filterSettingSelf = valS.value;

						// Add body, re-filter message if needed
						obj.addProperty("body",
								TextFilterService.getInstance().filterString(msg.content, filterSettingSelf != 0));
						obj.addProperty("conversation_id", convo);
						obj.addProperty("conversation_type", "private");
						obj.add("mask", null);
						try {
							obj.addProperty("message_id",
									UUID.nameUUIDFromBytes((msg.sentAt + convo + msg.content).getBytes("UTF-8"))
											.toString());
						} catch (UnsupportedEncodingException e) {
							e.printStackTrace();
						}
						obj.addProperty("sent_at", msg.sentAt);
						obj.addProperty("source", msg.source);
						msgs.add(obj);

						// Increase index
						indexInPage++;
						if (indexInPage >= pageSize)
							break;
					}
					res.add("messages", msgs);
				} else
					dmHistorySize = manager.getDMHistory(convo, client.getPlayer().getAccountID()).length;
			} else if (includeMessages)
				res.add("messages", new JsonArray()); // blocked
		} else if (client.getRoom(convo).getType().equalsIgnoreCase(ChatRoomTypes.ROOM_CHAT)) {
			// Dummy
			if (includeMessages) {
				JsonArray msgs = new JsonArray();

				// Get message
				String welcomePublic = Centuria.textFilterProperties.getOrDefault("public-chat-notice",
						"Welcome to the public chat! Please keep in mind that these chats are monitored by the server moderators. As Fer.al is host to a playerbase of players as young as 13, there are chat filters present on the server, however we try to keep them relatively relaxed. Check the server rules for more information, and Welcome to Fer.al!")
						.replace("\\n", "\n");

				// Build message object
				JsonObject obj = new JsonObject();
				obj.addProperty("body", welcomePublic);
				obj.addProperty("conversation_id", convo);
				obj.addProperty("conversation_type", "room");
				obj.add("mask", null);
				obj.addProperty("message_id", "synthetic_welcome");
				obj.addProperty("sent_at", fmt.format(new Date(System.currentTimeMillis())));
				obj.addProperty("source", new UUID(0, 0).toString());
				msgs.add(obj);

				// Add
				res.add("messages", msgs);
			}
			dmHistorySize = 1;
		} else if (includeMessages)
			res.add("messages", new JsonArray());

		res.addProperty("eventId", "conversations.history");
		if ((cursorCurrent + 1) * pageSize < dmHistorySize)
			res.addProperty("cursor", cursorCurrent + 1);
		res.addProperty("success", true);
		client.sendPacket(res);
		return true;
	}

}
