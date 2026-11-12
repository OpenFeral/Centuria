package org.asf.centuria.networking.chatserver.networking;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TimeZone;
import java.util.UUID;
import java.util.stream.Stream;

import org.apache.logging.log4j.MarkerManager;
import org.asf.centuria.Centuria;
import org.asf.centuria.updater.PolyUpdaterClient;
import org.asf.centuria.updater.collections.PolyCollection;
import org.asf.centuria.accounts.AccountManager;
import org.asf.centuria.accounts.CenturiaAccount;
import org.asf.centuria.accounts.highlevel.ItemAccessor;
import org.asf.centuria.dms.DMManager;
import org.asf.centuria.dms.PrivateChatMessage;
import org.asf.centuria.entities.generic.Vector3;
import org.asf.centuria.entities.inventoryitems.twiggles.TwiggleItem;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.entities.trading.Trade;
import org.asf.centuria.entities.uservars.UserVarValue;
import org.asf.centuria.enums.objects.WorldObjectMoverNodeType;
import org.asf.centuria.enums.twiggles.TwiggleState;
import org.asf.centuria.interactions.modules.QuestManager;
import org.asf.centuria.ipbans.IpBanManager;
import org.asf.centuria.modules.eventbus.EventBus;
import org.asf.centuria.modules.events.accounts.AccountDisconnectEvent;
import org.asf.centuria.modules.events.accounts.MiscModerationEvent;
import org.asf.centuria.modules.events.accounts.AccountDisconnectEvent.DisconnectType;
import org.asf.centuria.modules.events.chat.ChatMessageBroadcastEvent;
import org.asf.centuria.modules.events.chat.ChatMessageReceivedEvent;
import org.asf.centuria.modules.events.chatcommands.ChatCommandEvent;
import org.asf.centuria.modules.events.chatcommands.ModuleCommandSyntaxListEvent;
import org.asf.centuria.modules.events.maintenance.MaintenanceEndEvent;
import org.asf.centuria.modules.events.maintenance.MaintenanceStartEvent;
import org.asf.centuria.modules.events.updates.ServerUpdateEvent;
import org.asf.centuria.networking.chatserver.ChatClient;
import org.asf.centuria.networking.chatserver.ChatClient.OcProxyMetadata;
import org.asf.centuria.networking.chatserver.ChatServer;
import org.asf.centuria.networking.chatserver.networking.moderator.ModeratorClient;
import org.asf.centuria.networking.chatserver.proxies.OcProxyInfo;
import org.asf.centuria.networking.chatserver.proxies.ProxySession;
import org.asf.centuria.networking.chatserver.rooms.ChatRoom;
import org.asf.centuria.networking.chatserver.rooms.ChatRoomTypes;
import org.asf.centuria.networking.gameserver.GameServer;
import org.asf.centuria.networking.voicechatserver.VoiceChatClient;
import org.asf.centuria.packets.xt.gameserver.inventory.InventoryItemDownloadPacket;
import org.asf.centuria.packets.xt.gameserver.room.RoomJoinPacket;
import org.asf.centuria.social.SocialManager;
import org.asf.centuria.textfilter.FilterMode;
import org.asf.centuria.textfilter.FilterSeverity;
import org.asf.centuria.textfilter.PhraseFilter;
import org.asf.centuria.textfilter.PhraseFilterSet;
import org.asf.centuria.textfilter.TextFilterService;
import org.asf.centuria.textfilter.context.TextFilterContextMemory;
import org.asf.centuria.textfilter.context.WrappedTextFilterContextMemory;
import org.asf.centuria.textfilter.impl.stringbuilders.ModeratorPlainStringBuilder;
import org.asf.centuria.textfilter.impl.stringbuilders.ModeratorStringBuilder;
import org.asf.centuria.textfilter.result.FilterResult;
import org.asf.centuria.textfilter.result.TextPart;
import org.asf.centuria.textfilter.result.WordMatch;
import org.asf.centuria.util.io.DataWriter;
import org.asf.connective.tasks.AsyncTaskManager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class SendMessage extends AbstractChatPacket {

	public String packetId = "chat.postMessage";

	private static String NIL_UUID = new UUID(0, 0).toString();

	public static ArrayList<String> clearanceCodes = new ArrayList<String>();
	private static Random rnd = new Random();

	private static ModeratorStringBuilder moderatorMessageStringBuilderRed = new ModeratorStringBuilder("red");
	private static ModeratorStringBuilder moderatorMessageStringBuilderOrange = new ModeratorStringBuilder("orange");
	private static ModeratorPlainStringBuilder moderatorMessageStringBuilderPlain = new ModeratorPlainStringBuilder();

	private static boolean heightenedSensitivityConfigInited = false;
	private static long heightenedSensitivityDeactivateTimer;
	private static long heightenedSensitivityChatReactivateTimer;
	private static int heightenedSensitivityTriggerThreshold;
	private static long heightenedSensitivityTriggerMaxAge;
	private static int heightenedSensitivityChatDisableThreshold1;
	private static int heightenedSensitivityChatDisableThreshold2;
	private static long heightenedSensitivityChatDisableMaxAge1;
	private static long heightenedSensitivityChatDisableMaxAge2;

	private static OutputStream chatLogBinary;

	static {
		try {
			// Open chat log binary file
			File chatLogFile = new File("logs/chatlog.bin");
			chatLogFile.getParentFile().mkdirs();
			chatLogBinary = new FileOutputStream(chatLogFile);
		} catch (IOException e) {
			// Log
			Centuria.logger.warn(
					"Could not open the chat log binary! Chat logging will not be available for this session!", e);
		}
	}

	public String room;
	private ChatRoom roomInstance;
	public String roomType;

	public String message;
	public String messageHighlighted;
	public String messagePlain;
	public String messageHighlightedPlain;
	public String originalMessage;

	public boolean moderatorMessage = false;

	public boolean alertingMessage = false;
	public boolean criticalAlertingMessage = false;
	public boolean blockedMessage = false;

	public TextPart[] messagePartsWriter;
	public FilterResult filterResultWriter;

	public String sourceWriter;
	public String authorWriter;
	public String sentAtWriter;

	@Override
	public String id() {
		return packetId;
	}

	@Override
	public AbstractChatPacket instantiate() {
		return new SendMessage();
	}

	@Override
	public void parse(JsonObject data) {
		message = data.get("message").getAsString();
		room = data.get("conversationId").getAsString();
	}

	@Override
	public void build(JsonObject data) {
		data.addProperty("conversationType", roomType);
		data.addProperty("conversationId", room);
		if (moderatorMessage && messageHighlighted != null)
			data.addProperty("message", messageHighlighted);
		else
			data.addProperty("message", message);
		if (moderatorMessage && messageHighlightedPlain != null && !messageHighlightedPlain.equals(message))
			data.addProperty("messagePlain", messageHighlightedPlain);
		else if (messagePlain != null && !message.equals(messagePlain))
			data.addProperty("messagePlain", messagePlain);
		if (moderatorMessage) {
			data.addProperty("originalMessage", originalMessage == null ? message : originalMessage); // Only for mods
			if (messagePartsWriter != null && messagePartsWriter.length != 0)
				data.add("messageParts", messagePartsAsJson(messagePartsWriter)); // Text parts
			if (filterResultWriter != null) {
				// Filter result
				data.addProperty("filterMatch", filterResultWriter.isMatch());
				if (filterResultWriter.isMatch()) {
					data.addProperty("filterSeverity", filterResultWriter.getSeverity().toString().toLowerCase());
					data.addProperty("filterReason", filterResultWriter.getPrimaryFilterReason());
					data.add("filterMatchedWords", matchedWordsAsJson(filterResultWriter.getMatches()));
				}
			}
		}
		if (alertingMessage || criticalAlertingMessage || blockedMessage) {
			data.addProperty("alertingMessage", alertingMessage);
			data.addProperty("criticalAlertingMessage", criticalAlertingMessage);
			data.addProperty("blockedMessage", blockedMessage);
		}
		data.addProperty("source", sourceWriter);
		if (authorWriter != null)
			data.addProperty("author", authorWriter);
		data.addProperty("sentAt", sentAtWriter);
		data.addProperty("success", true);
	}

	private class ChatRateLimit {
		public int chatMessageCount = 0;
		public long rateLimitStart = 0;
		public long rateLimitEnableTime = -1;

		public boolean isRateLimited() {
			return rateLimitEnableTime != -1 && (System.currentTimeMillis() - rateLimitEnableTime) < 15000;
		}
	}

	private static class ChatFilterMemory {
		public long lastFlag = 0;
		public int flagCount = 0;
	}

	private static class HeightenedSensitivityFlags {
		public ChatRoom roomInst;

		public boolean active = false;
		public long disableAfter = -1;
		public boolean wasAutoactivate;

		public String activationReason;

		public long lastFlagAutoactivate = 0;
		public int flagCountAutoactivate = 0;

		public boolean chatDisabled = false;
		public long renableChatAter = -1;

		public long lastFlagChatdisable = 0;
		public int flagCountChatdisable = 0;

		public long lastFlagChatdisableSecondary = 0;
		public int flagCountChatdisableSecondary = 0;

		public void enableChat(ChatServer server) {
			if (!chatDisabled)
				return;
			chatDisabled = false;
			renableChatAter = -1;
			flagCountChatdisable = 0;
			flagCountChatdisableSecondary = 0;
			lastFlagChatdisable = System.currentTimeMillis();
			lastFlagChatdisableSecondary = System.currentTimeMillis();

			// Moderation log
			if (roomInst.getType().equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT)) {
				EventBus.getInstance()
						.dispatchEvent(
								new MiscModerationEvent("chatfilter.mute",
										"Chat has been re-enabled in room " + formatRoomName(null, roomInst),
										Map.of("Private chat room", formatRoomName(null, roomInst), "Room",
												roomInst.getRoomID(), "Resulting action", "chat re-enabled"),
										"SYSTEM", null));
			} else {
				EventBus.getInstance()
						.dispatchEvent(new MiscModerationEvent("chatfilter.mute",
								"Chat has been re-enabled in room " + formatRoomName(null, roomInst),
								Map.of("Room", formatRoomName(null, roomInst), "Resulting action", "chat re-enabled"),
								"SYSTEM", null));
			}

			// Time format
			SimpleDateFormat fmt = new SimpleDateFormat("yyyy'-'MM'-'dd'T'HH':'mm':'ssXXX");
			fmt.setTimeZone(TimeZone.getTimeZone("UTC"));

			// Announce chat reopen
			for (ChatClient client : server.getClients()) {
				if (client != null && client.isInRoom(roomInst.getRoomID())) {
					// Check moderator perms
					String permLevel = "member";
					if (client.getPlayer().getSaveSharedInventory().containsItem("permissions")) {
						permLevel = client.getPlayer().getSaveSharedInventory().getItem("permissions").getAsJsonObject()
								.get("permissionLevel").getAsString();
					}

					SendMessage res = new SendMessage();
					SimpleDateFormat fmt2 = new SimpleDateFormat("dd'-'MM'-'yyyy HH':'mm':'ss");
					fmt2.setTimeZone(TimeZone.getTimeZone("UTC"));
					res.roomType = roomInst.getType();
					res.room = roomInst.getRoomID();
					res.message = "The chat has been re-enabled, we apologize about the inconvenience!";
					res.sourceWriter = NIL_UUID;
					res.sentAtWriter = fmt.format(new Date());
					res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
					client.sendPacket(res);
				}
			}
		}

		public void disableChat(ChatServer server) {
			if (chatDisabled)
				return;
			chatDisabled = true;
			flagCountChatdisable = 0;
			flagCountChatdisableSecondary = 0;
			lastFlagChatdisable = System.currentTimeMillis();
			lastFlagChatdisableSecondary = System.currentTimeMillis();

			// Time format
			SimpleDateFormat fmt = new SimpleDateFormat("yyyy'-'MM'-'dd'T'HH':'mm':'ssXXX");
			fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
			String reason = activationReason;

			// Announce chat disable
			for (ChatClient client : server.getClients()) {
				if (client != null && client.isInRoom(roomInst.getRoomID())) {
					// Check moderator perms
					String permLevel = "member";
					if (client.getPlayer().getSaveSharedInventory().containsItem("permissions")) {
						permLevel = client.getPlayer().getSaveSharedInventory().getItem("permissions").getAsJsonObject()
								.get("permissionLevel").getAsString();
					}

					SendMessage res = new SendMessage();
					SimpleDateFormat fmt2 = new SimpleDateFormat("dd'-'MM'-'yyyy HH':'mm':'ss");
					fmt2.setTimeZone(TimeZone.getTimeZone("UTC"));
					res.roomType = roomInst.getType();
					res.room = roomInst.getRoomID();
					if (roomInst.getType().equalsIgnoreCase(ChatRoomTypes.ROOM_CHAT)) {
						res.message = "Due to the large amount of filter triggers within this chat room, the chat has been temporarily disabled until a staff member can get online. We apologize about the inconvenience! The team has already been alerted about the chat being disabled and will get on to investigate and re-enable it as soon as possible!"
								+ (renableChatAter != -1
										? "\n\nChat re-enables at " + fmt2.format(new Date(renableChatAter)) + " UTC ("
												+ formatTimeRelative(renableChatAter - System.currentTimeMillis())
												+ " from now)" + " or whenever a staff member gets online."
										: "")
								+ (reason != null ? "\nReason of activation of heightened sensitivity mode: " + reason
										: "");
					} else {
						res.message = "Due to the large amount of filter triggers within this chat room, the chat has been temporarily disabled, the chat will re-enable when the chat reactivation timer is met. We apologize about the inconvenience!"
								+ (renableChatAter != -1
										? "\n\nChat re-enables at " + fmt2.format(new Date(renableChatAter)) + " UTC ("
												+ formatTimeRelative(renableChatAter - System.currentTimeMillis())
												+ " from now)"
										: "")
								+ (reason != null ? "\nReason of activation of heightened sensitivity mode: " + reason
										: "");
					}
					res.sourceWriter = NIL_UUID;
					res.sentAtWriter = fmt.format(new Date());
					res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
					client.sendPacket(res);
				}
			}
		}

		public void activateHeightenedSensitivity(ChatServer server) {
			if (active)
				return;
			active = true;
			lastFlagAutoactivate = 0;
			flagCountAutoactivate = 0;

			// Reason
			String reason = activationReason;

			// Time format
			SimpleDateFormat fmt = new SimpleDateFormat("yyyy'-'MM'-'dd'T'HH':'mm':'ssXXX");
			fmt.setTimeZone(TimeZone.getTimeZone("UTC"));

			// Announce heightened sensitivity
			for (ChatClient client : server.getClients()) {
				if (client != null && client.isInRoom(roomInst.getRoomID())) {
					// Check moderator perms
					String permLevel = "member";
					if (client.getPlayer().getSaveSharedInventory().containsItem("permissions")) {
						permLevel = client.getPlayer().getSaveSharedInventory().getItem("permissions").getAsJsonObject()
								.get("permissionLevel").getAsString();
					}
					SendMessage res = new SendMessage();
					SimpleDateFormat fmt2 = new SimpleDateFormat("dd'-'MM'-'yyyy HH':'mm':'ss");
					fmt2.setTimeZone(TimeZone.getTimeZone("UTC"));
					res.roomType = roomInst.getType();
					res.room = roomInst.getRoomID();
					if (roomInst.getType().equalsIgnoreCase(ChatRoomTypes.ROOM_CHAT)) {
						if (disableAfter == -1)
							res.message = (wasAutoactivate
									? "Due to the large amount of filter triggers within this chat room without staff being present, the chat has been placed in heightened sensitivity mode, filters are temporarily more aggressive until staff disables this mode. Please avoid using swears and/or sensitive language until staff disables this mode."
									: "This chat room has been placed in heightened sensitivity mode by the server staff, filters are temporarily more aggressive. Please avoid using swears and/or sensitive language until staff disables this mode.")
									+ (reason != null
											? "\nReason of activation of heightened sensitivity mode: " + reason
											: "");
						else
							res.message = (wasAutoactivate
									? "Due to the large amount of filter triggers within this chat room without staff being present, the chat has been placed in heightened sensitivity mode, filters are temporarily more aggressive until staff disables this mode or until the sensitivity mode timer lapses. Please avoid using swears and/or sensitive language until staff disables this mode."
									: "This chat room has been placed in heightened sensitivity mode by the server staff, filters are temporarily more aggressive. Please avoid using swears and/or sensitive language until staff disables this mode.")
									+ (disableAfter != -1
											? "\n\nChat disables strict filtering at "
													+ fmt2.format(new Date(disableAfter)) + " UTC ("
													+ formatTimeRelative(disableAfter - System.currentTimeMillis())
													+ " from now)" + " or whenever a staff member disables this mode."
											: "")
									+ (reason != null
											? "\nReason of activation of heightened sensitivity mode: " + reason
											: "");
					} else
						res.message = (wasAutoactivate
								? "Due to the large amount of filter triggers within this chat, the chat has been placed in heightened sensitivity mode, filters are temporarily more aggressive until staff disables this mode or until the sensitivity mode timer lapses. Please avoid using swears and/or sensitive language until staff disables this mode."
								: "This chat room has been placed in heightened sensitivity mode by the server staff, filters are temporarily more aggressive. Please avoid using swears and/or sensitive language until staff disables this mode.")
								+ (disableAfter != -1
										? "\n\nChat disables strict filtering at " + fmt2.format(new Date(disableAfter))
												+ " UTC ("
												+ formatTimeRelative(disableAfter - System.currentTimeMillis())
												+ " from now)" + " or whenever a staff member disables this mode."
										: "")
								+ (reason != null ? "\nReason of activation of heightened sensitivity mode: " + reason
										: "");
					res.sourceWriter = NIL_UUID;
					res.sentAtWriter = fmt.format(new Date());
					res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
					client.sendPacket(res);
				}
			}
		}

		public void deactivateHeightenedSensitivity(ChatServer server, boolean reenabledChat) {
			if (!active)
				return;
			active = false;
			disableAfter = -1;
			activationReason = null;
			wasAutoactivate = false;
			lastFlagAutoactivate = 0;
			flagCountAutoactivate = 0;

			// Reenable chat
			if (chatDisabled) {
				chatDisabled = false;
				renableChatAter = -1;
				flagCountChatdisable = 0;
				flagCountChatdisableSecondary = 0;
				lastFlagChatdisable = System.currentTimeMillis();
				lastFlagChatdisableSecondary = System.currentTimeMillis();

				// Moderation log
				if (roomInst.getType().equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT)) {
					EventBus.getInstance()
							.dispatchEvent(new MiscModerationEvent("chatfilter.mute",
									"Chat has been re-enabled in room " + formatRoomName(null, roomInst),
									Map.of("Private chat room", formatRoomName(null, roomInst), "Room",
											roomInst.getRoomID(), "Resulting action", "chat re-enabled"),
									"SYSTEM", null));
				} else {
					EventBus.getInstance()
							.dispatchEvent(new MiscModerationEvent("chatfilter.mute",
									"Chat has been re-enabled in room " + formatRoomName(null, roomInst), Map.of("Room",
											formatRoomName(null, roomInst), "Resulting action", "chat re-enabled"),
									"SYSTEM", null));
				}
			}

			if (!reenabledChat) {
				// Time format
				SimpleDateFormat fmt = new SimpleDateFormat("yyyy'-'MM'-'dd'T'HH':'mm':'ssXXX");
				fmt.setTimeZone(TimeZone.getTimeZone("UTC"));

				// Announce heightened sensitivity
				for (ChatClient client : server.getClients()) {
					if (client != null && client.isInRoom(roomInst.getRoomID())) {
						// Check moderator perms
						String permLevel = "member";
						if (client.getPlayer().getSaveSharedInventory().containsItem("permissions")) {
							permLevel = client.getPlayer().getSaveSharedInventory().getItem("permissions")
									.getAsJsonObject().get("permissionLevel").getAsString();
						}
						SendMessage res = new SendMessage();
						SimpleDateFormat fmt2 = new SimpleDateFormat("dd'-'MM'-'yyyy HH':'mm':'ss");
						fmt2.setTimeZone(TimeZone.getTimeZone("UTC"));
						res.roomType = roomInst.getType();
						res.room = roomInst.getRoomID();
						res.message = "The chat no longer is in heightened sensitivity mode, filters are relaxed again, we apologize about the inconvenience.";
						res.sourceWriter = NIL_UUID;
						res.sentAtWriter = fmt.format(new Date());
						res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
						client.sendPacket(res);
					}
				}
			}
		}
	}

	public static void playerJoinedWorld(ChatClient client, String room) {
		ChatRoom roomInstance = client.getRoom(room);
		if (roomInstance != null) {
			// Call setup
			playerRoomSetup(client, roomInstance);
		}
	}

	public static void joinedRoom(ChatClient client, ChatRoom roomInstance) {
		HeightenedSensitivityFlags flags = roomInstance.getObject(HeightenedSensitivityFlags.class);
		if (flags == null) {
			flags = new HeightenedSensitivityFlags();
			flags.roomInst = roomInstance;
			roomInstance.addObject(flags);
		}

		// Check moderator perms
		boolean reenabledChat = false;
		String permLevel = "member";
		if (client.getPlayer().getSaveSharedInventory().containsItem("permissions")) {
			permLevel = client.getPlayer().getSaveSharedInventory().getItem("permissions").getAsJsonObject()
					.get("permissionLevel").getAsString();
		}
		if (GameServer.hasPerm(permLevel, "moderator")) {
			// Staff
			// Cap at zero
			flags.flagCountAutoactivate = 0;
			flags.lastFlagAutoactivate = System.currentTimeMillis();

			// Reactivate chat if needed
			if (flags.chatDisabled) {
				flags.enableChat(client.getServer());
				reenabledChat = true;
			}
		}

		// Deactivate heightened sensitivity if needed
		if (flags.active && !flags.chatDisabled && flags.wasAutoactivate && heightenedSensitivityDeactivateTimer != -1
				&& System.currentTimeMillis() >= flags.disableAfter) {
			// Disable
			flags.deactivateHeightenedSensitivity(client.getServer(), reenabledChat);

			// Check if private
			if (roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT)) {
				EventBus.getInstance()
						.dispatchEvent(new MiscModerationEvent("chatfilter.heightenedstrictness.deactivate",
								"Heightened sensitivity mode deactivated",
								Map.of("Private chat room", formatRoomName(client, roomInstance), "Room",
										roomInstance.getRoomID(), "Action", "deactivated heightened sensitivity mode"),
								"SYSTEM", null));
			} else {
				EventBus.getInstance()
						.dispatchEvent(new MiscModerationEvent("chatfilter.heightenedstrictness.deactivate",
								"Heightened sensitivity mode deactivated",
								Map.of("Room", formatRoomName(client, roomInstance), "Action",
										"deactivated heightened sensitivity mode"),
								"SYSTEM", null));
			}
		}

		// Call function if needed
		if (roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.ROOM_CHAT)) {
			// Check if in world
			Player online = client.getPlayer().getOnlinePlayerInstance();
			if (online != null && online.roomReady && online.room.equals(roomInstance.getRoomID())) {
				playerRoomSetup(client, roomInstance);
			}
		}
	}

	private static class RoomJoinMessageState {
		public boolean messagesSent = false;
	}

	private static void playerRoomSetup(ChatClient client, ChatRoom roomInstance) {
		ChatRoom localRoom = client.getLocalRoom(roomInstance.getRoomID());
		RoomJoinMessageState state = localRoom.getObject(RoomJoinMessageState.class);
		if (state == null) {
			state = new RoomJoinMessageState();
			localRoom.addObject(state);
		}
		if (!state.messagesSent) {
			state.messagesSent = true;

			// Send messages
			// Check if the room is set to heightened sensitivity
			HeightenedSensitivityFlags flags = roomInstance.getObject(HeightenedSensitivityFlags.class);
			if (flags == null) {
				flags = new HeightenedSensitivityFlags();
				flags.roomInst = roomInstance;
				roomInstance.addObject(flags);
			}
			if (flags.active) {
				// Enabled

				// Check moderator perms
				String permLevel = "member";
				if (client.getPlayer().getSaveSharedInventory().containsItem("permissions")) {
					permLevel = client.getPlayer().getSaveSharedInventory().getItem("permissions").getAsJsonObject()
							.get("permissionLevel").getAsString();
				}

				// Time format
				SimpleDateFormat fmt = new SimpleDateFormat("yyyy'-'MM'-'dd'T'HH':'mm':'ssXXX");
				fmt.setTimeZone(TimeZone.getTimeZone("UTC"));

				// Send message
				SendMessage res = new SendMessage();
				SimpleDateFormat fmt2 = new SimpleDateFormat("dd'-'MM'-'yyyy HH':'mm':'ss");
				fmt2.setTimeZone(TimeZone.getTimeZone("UTC"));
				res.roomType = roomInstance.getType();
				res.room = roomInstance.getRoomID();
				if (flags.chatDisabled)
					res.message = "Due to the large amount of filter triggers within this chat room, the chat has been temporarily disabled until a staff member can get online. We apologize about the inconvenience! The team has already been alerted about the chat being disabled and will get on to investigate and re-enable it as soon as possible!";
				else if (flags.wasAutoactivate)
					res.message = "Due to the large amount of filter triggers within this chat room without staff being present, the chat has been placed in heightened sensitivity mode, filters are temporarily more aggressive until staff disables this mode. Please avoid using swears and/or sensitive language until staff disables this mode.";
				else
					res.message = "This chat room has been placed in heightened sensitivity mode by the server staff, filters are temporarily more aggressive. Please avoid using swears and/or sensitive language until staff disables this mode.";
				res.sourceWriter = NIL_UUID;
				res.sentAtWriter = fmt.format(new Date());
				res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
				client.sendPacket(res);
			}
		}
	}

	private JsonArray messagePartsAsJson(TextPart[] textParts) {
		JsonArray parts = new JsonArray();
		for (TextPart part : textParts)
			parts.add(messagePartAsJson(part));
		return parts;
	}

	private JsonObject messagePartAsJson(TextPart part) {
		JsonObject partJson = new JsonObject();
		partJson.addProperty("text", part.getText());
		partJson.addProperty("censored", part.isFlagged());
		partJson.addProperty("severity", part.getSeverity().toString().toLowerCase());
		partJson.addProperty("reason", part.getPrimaryFilteringReason());
		partJson.add("matchedWords", matchedWordsAsJson(part.getMatchedWords()));
		partJson.add("filter", filterDefAsJson(part.getPhraseFilter()));
		return partJson;
	}

	private JsonObject filterSetAsJson(PhraseFilterSet set) {
		JsonObject res = new JsonObject();
		res.addProperty("setName", set.getSetName());
		res.addProperty("setDescription", set.getSetDescription());
		res.addProperty("filterReason", set.getFilteringReason());
		res.add("tags", stringArrayAsJson(set.getSetTags()));
		return res;
	}

	private JsonObject filterDefAsJson(PhraseFilter filter) {
		if (filter == null)
			return null;
		JsonObject filterDef = new JsonObject();
		filterDef.add("modes", filterModesAsJson(filter.getModes()));
		filterDef.addProperty("severity", filter.getSeverity().toString().toLowerCase());
		filterDef.addProperty("reason", filter.getReason());
		filterDef.addProperty("phrase", filter.getPhrase());
		filterDef.add("variants", stringArrayAsJson(filter.getVariants()));
		filterDef.add("set", filterSetAsJson(filter.getSet()));
		return filterDef;
	}

	private JsonArray matchedWordsAsJson(WordMatch[] matchedWords) {
		JsonArray words = new JsonArray();
		for (WordMatch word : matchedWords)
			words.add(matchedWordAsJson(word));
		return words;
	}

	private JsonArray stringArrayAsJson(String[] arr) {
		JsonArray arr2 = new JsonArray();
		for (String arrE : arr)
			arr2.add(arrE);
		return arr2;
	}

	private JsonArray filterModesAsJson(FilterMode[] arr) {
		JsonArray arr2 = new JsonArray();
		for (FilterMode arrE : arr)
			arr2.add(arrE.toString().toLowerCase());
		return arr2;
	}

	private JsonObject matchedWordAsJson(WordMatch matchedWord) {
		JsonObject word = new JsonObject();
		word.addProperty("reason", matchedWord.getReason());
		word.addProperty("severity", matchedWord.getSeverity().toString().toLowerCase());
		word.addProperty("matchedPhrase", matchedWord.getMatchedPhrase());
		word.add("phraseVariants", stringArrayAsJson(matchedWord.getVariants()));
		word.add("filter", filterDefAsJson(matchedWord.getPhraseFilter()));
		return word;
	}

	private String matchedWordsAsString(WordMatch[] words) {
		String matchedWords = "";
		for (WordMatch match : words) {
			// Add matched word
			if (matchedWords.isEmpty())
				matchedWords = match.getMatchedPhrase();
			else
				matchedWords += ", " + match.getMatchedPhrase();
		}
		return matchedWords;
	}

	@Override
	public boolean handle(ChatClient client) {
		DMManager manager = DMManager.getInstance();

		// Check moderator perms
		String permLevel = "member";
		if (client.getPlayer().getSaveSharedInventory().containsItem("permissions")) {
			permLevel = client.getPlayer().getSaveSharedInventory().getItem("permissions").getAsJsonObject()
					.get("permissionLevel").getAsString();
		}

		// Get room
		roomInstance = client.getServer().getRoom(room);
		if (roomInstance == null) {
			// Room doesnt exist
			return true;
		}

		// Security checks
		// Check moderator perms
		if (!GameServer.hasPerm(permLevel, "moderator")) {
			// Ignore 'limbo' players
			Player gameClient = client.getPlayer().getOnlinePlayerInstance();
			if (gameClient == null) {
				// Ok-
				// Bye bye, you're not ingame
				client.disconnect();
				return true;
			} else if (!gameClient.roomReady || gameClient.room == null) {
				// Limbo player
				return true;
			}

			// Check room type
			//
			// If its not a mod and its a room the player isnt in, they shouldnt receive the
			// messages
			if (roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.ROOM_CHAT)) {
				// Check if sanctuary
				if (room.startsWith("sanctuary_")) {
					if (!gameClient.room.equals(room)) {
						// Invalid
						return true;
					}
				} else {
					// Check level
					// FIXME: incompatible with instance management
					if (!room.equalsIgnoreCase("room_" + gameClient.levelID)) {
						// Invalid
						return true;
					}
				}
			}
		}

		// Clean message
		message = message.trim();

		// Check if containing illegal tags
		if (message.replace(" ", "").replace("\\", "").toLowerCase().contains("<noparse>")
				|| message.replace(" ", "").replace("\\", "").toLowerCase().contains("<noparse/>")) {
			// Message invalid, dont send it, we dont allow use of noparse tags this can be
			// otherwise bad

			// Time format
			SimpleDateFormat fmt = new SimpleDateFormat("yyyy'-'MM'-'dd'T'HH':'mm':'ssXXX");
			fmt.setTimeZone(TimeZone.getTimeZone("UTC"));

			// Send failure
			SendMessage res = new SendMessage();
			res.roomType = roomInstance.getType();
			res.room = room;
			res.message = "Whoah there! Your message contains invalid tags, as a precaution we do not allow the use of the noparse tags. Your message was not sent.";
			res.sourceWriter = NIL_UUID;
			res.sentAtWriter = fmt.format(new Date());
			client.sendPacket(res);
			return true;
		}

		// Check content
		if (message.isBlank()) {
			return true; // ignore chat
		}

		// Fire event
		ChatMessageReceivedEvent evt = new ChatMessageReceivedEvent(client.getServer(), client.getPlayer(), client,
				message, room);
		EventBus.getInstance().dispatchEvent(evt);
		if (evt.isCancelled())
			return true; // Cancelled

		// Chat commands
		if (message.startsWith(">")) {
			String cmd = message.substring(1).trim();
			if (handleCommand(cmd, client))
				return true;
		}

		// Check room
		if (client.isInRoom(room)) {
			// Get memory
			TextFilterContextMemory chatMemoryRoom = roomInstance.getObject(TextFilterContextMemory.class);
			if (chatMemoryRoom == null) {
				chatMemoryRoom = createChatMemoryObject();
				roomInstance.addObject(chatMemoryRoom);
			}
			TextFilterContextMemory chatMemoryClient = client.getObject(TextFilterContextMemory.class);
			if (chatMemoryClient == null) {
				chatMemoryClient = createChatMemoryObject();
				client.addObject(chatMemoryClient);
			}
			TextFilterContextMemory chatMemory = new WrappedTextFilterContextMemory(chatMemoryRoom, chatMemoryClient);

			// Log
			if (!roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT)) {
				Centuria.logger.info("Chat: " + client.getPlayer().getDisplayName() + ": " + message + " ["
						+ formatRoomName(client, roomInstance) + "]");

				// Log to chat log
				if (chatLogBinary != null) {
					try {
						// Create entry
						// Room: string
						// Type: string
						// User ID: string
						// Message: string
						// Timestamp: long
						ByteArrayOutputStream bO = new ByteArrayOutputStream();
						DataWriter writer = new DataWriter(bO);
						writer.writeString(room);
						writer.writeString(client.getPlayer().getAccountID());
						writer.writeString(message);
						writer.writeLong(System.currentTimeMillis());
						synchronized (chatLogBinary) {
							writer = new DataWriter(chatLogBinary);
							writer.writeBytes(bO.toByteArray());
							chatLogBinary.flush();
						}
					} catch (IOException e) {
					}
				}
			}

			// Load rate limit
			ChatRateLimit rateLimit = client.getObject(ChatRateLimit.class);
			if (rateLimit == null) {
				rateLimit = new ChatRateLimit();
				client.addObject(rateLimit);
			}

			// Check expiry of rate limit message counter
			if (System.currentTimeMillis() - rateLimit.rateLimitStart >= 3000) {
				// Reset rate limit
				rateLimit.chatMessageCount = 0;
			}

			// Check if we need to start the timer
			if (rateLimit.chatMessageCount == 0) {
				// Start timer, first message
				rateLimit.rateLimitStart = System.currentTimeMillis();
			}

			// Increase counter
			rateLimit.chatMessageCount++;

			// Check it
			if (rateLimit.chatMessageCount >= 7 && !rateLimit.isRateLimited()) {
				// Player sent 7 messages within the last 3 seconds, engage rate limit
				rateLimit.rateLimitEnableTime = System.currentTimeMillis();

				// Check if private
				if (roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT)) {
					// Private chat, need more details
					// And strip away the message
					EventBus.getInstance().dispatchEvent(new MiscModerationEvent("chatfilter.mute",
							"Chat anti-spam limit was triggered for " + client.getPlayer().getDisplayName() + "!",
							Map.of("Private chat room", formatRoomName(client, roomInstance), "Room", room,
									"Resulting action", "messages are being blocked for 15 seconds"),
							"SYSTEM", client.getPlayer()));
				} else {
					EventBus.getInstance().dispatchEvent(new MiscModerationEvent("chatfilter.mute",
							"Chat anti-spam limit was triggered for " + client.getPlayer().getDisplayName() + "!",
							Map.of("Chat message", message, "Room", formatRoomName(client, roomInstance),
									"Resulting action", "messages are being blocked for 15 seconds"),
							"SYSTEM", client.getPlayer()));
				}
			}

			// Heightened sensitivity config
			if (!heightenedSensitivityConfigInited) {
				try {
					heightenedSensitivityDeactivateTimer = Long.parseLong(Centuria.textFilterProperties
							.getOrDefault("heightened-sensitivity-deactivate-timer", "900000"));
					heightenedSensitivityChatReactivateTimer = Long.parseLong(Centuria.textFilterProperties
							.getOrDefault("heightened-sensitivity-chat-reactivate-timer", "900000"));
					heightenedSensitivityTriggerThreshold = Integer.parseInt(Centuria.textFilterProperties
							.getOrDefault("heightened-sensitivity-trigger-threshold", "15"));
					heightenedSensitivityTriggerMaxAge = Long.parseLong(Centuria.textFilterProperties
							.getOrDefault("heightened-sensitivity-trigger-max-age", "900000"));
					heightenedSensitivityChatDisableThreshold1 = Integer.parseInt(Centuria.textFilterProperties
							.getOrDefault("heightened-sensitivity-chatdisable-threshold-primary", "5"));
					heightenedSensitivityChatDisableThreshold2 = Integer.parseInt(Centuria.textFilterProperties
							.getOrDefault("heightened-sensitivity-chatdisable-threshold-secondary", "30"));
					heightenedSensitivityChatDisableMaxAge1 = Long.parseLong(Centuria.textFilterProperties
							.getOrDefault("heightened-sensitivity-chatdisable-max-age-primary", "300000"));
					heightenedSensitivityChatDisableMaxAge2 = Long.parseLong(Centuria.textFilterProperties
							.getOrDefault("heightened-sensitivity-chatdisable-max-age-secondary", "1800000"));
					heightenedSensitivityConfigInited = true;
				} catch (Exception e) {
					heightenedSensitivityDeactivateTimer = 900000;
					heightenedSensitivityChatReactivateTimer = 900000;
					heightenedSensitivityTriggerThreshold = 15;
					heightenedSensitivityTriggerMaxAge = 900000;
					heightenedSensitivityChatDisableThreshold1 = 5;
					heightenedSensitivityChatDisableThreshold2 = 30;
					heightenedSensitivityChatDisableMaxAge1 = 300000;
					heightenedSensitivityChatDisableMaxAge2 = 1800000;
					heightenedSensitivityConfigInited = true;
					Centuria.logger.error("Failed to load textfilter.conf! Please make sure the syntax is correct.", e);
				}
			}

			// Heightened sensitivity flags
			HeightenedSensitivityFlags flags = roomInstance.getObject(HeightenedSensitivityFlags.class);
			if (flags == null) {
				flags = new HeightenedSensitivityFlags();
				flags.roomInst = roomInstance;
				roomInstance.addObject(flags);
			}
			boolean roomHasStaff = false;
			for (ChatClient cl2 : client.getServer().getClients()) {
				if (cl2 != null) {
					// Check moderator perms
					String permLevel2 = "member";
					if (cl2.getPlayer().getSaveSharedInventory().containsItem("permissions")) {
						permLevel2 = cl2.getPlayer().getSaveSharedInventory().getItem("permissions").getAsJsonObject()
								.get("permissionLevel").getAsString();
					}
					if (GameServer.hasPerm(permLevel2, "moderator") && cl2.isInRoom(room)) {
						// Staff in room

						// Cap at zero
						roomHasStaff = true;
						flags.flagCountAutoactivate = 0;
						flags.lastFlagAutoactivate = System.currentTimeMillis();

						// Reactivate chat if needed
						if (flags.chatDisabled) {
							flags.enableChat(client.getServer());
						}

						// Break
						break;
					}
				}
			}

			// Reset autoactivate if needed, by checking if the last flag is too old
			if (!flags.active && heightenedSensitivityTriggerMaxAge != -1
					&& System.currentTimeMillis() - flags.lastFlagAutoactivate > heightenedSensitivityTriggerMaxAge) {
				// Reset
				flags.flagCountAutoactivate = 0;
				flags.lastFlagAutoactivate = System.currentTimeMillis();
			}

			// Reset chat disable counters if needed
			if (((heightenedSensitivityChatDisableMaxAge1 != -1 || heightenedSensitivityChatDisableMaxAge2 != -1)
					|| roomHasStaff)) {
				// Reset if needed
				if (roomHasStaff || (heightenedSensitivityChatDisableMaxAge1 != -1 && System.currentTimeMillis()
						- flags.lastFlagChatdisable > heightenedSensitivityChatDisableMaxAge1)) {
					flags.flagCountChatdisable = 0;
					flags.lastFlagChatdisable = System.currentTimeMillis();
				}
				if (roomHasStaff || (heightenedSensitivityChatDisableMaxAge2 != -1 && System.currentTimeMillis()
						- flags.lastFlagChatdisableSecondary > heightenedSensitivityChatDisableMaxAge2)) {
					flags.flagCountChatdisableSecondary = 0;
					flags.lastFlagChatdisableSecondary = System.currentTimeMillis();
				}
			}

			// Reenable chat if needed
			boolean reenabledChat = false;
			if (flags.chatDisabled && flags.renableChatAter != -1
					&& System.currentTimeMillis() >= flags.renableChatAter) {
				// Re-enable
				flags.enableChat(client.getServer());
				reenabledChat = true;
			}

			// Deactivate heightened sensitivity if needed
			if (flags.active && !flags.chatDisabled && flags.wasAutoactivate
					&& heightenedSensitivityDeactivateTimer != -1 && System.currentTimeMillis() >= flags.disableAfter) {
				// Disable
				flags.deactivateHeightenedSensitivity(client.getServer(), reenabledChat);

				// Check if private
				if (roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT)) {
					EventBus.getInstance()
							.dispatchEvent(
									new MiscModerationEvent("chatfilter.heightenedstrictness.deactivate",
											"Heightened sensitivity mode deactivated",
											Map.of("Private chat room", formatRoomName(client, roomInstance), "Room",
													room, "Action", "deactivated heightened sensitivity mode"),
											"SYSTEM", null));
				} else {
					EventBus.getInstance()
							.dispatchEvent(new MiscModerationEvent("chatfilter.heightenedstrictness.deactivate",
									"Heightened sensitivity mode deactivated",
									Map.of("Room", formatRoomName(client, roomInstance), "Action",
											"deactivated heightened sensitivity mode"),
									"SYSTEM", null));
				}
			}

			// Check heightened sensitivity state
			String[] tags = new String[0];
			String[] tagsHighlight = new String[] { "POTENTIALRISK" };
			if (flags.active) {
				tags = new String[] { "HEIGHTENEDSENSITIVITY" };
				tagsHighlight = new String[] { "HEIGHTENEDSENSITIVITY", "POTENTIALRISK" };
			}

			// Run filters
			FilterResult filterResultDefaultMod = TextFilterService.getInstance().filter(chatMemory, message,
					flags.active, tags);
			FilterResult filterResultDefaultOrig = TextFilterService.getInstance().filter(chatMemory, message, false,
					tags);
			FilterResult filterResultStrict = TextFilterService.getInstance().filter(chatMemory, message, true, tags);
			FilterResult filterResultStaffHighlight = TextFilterService.getInstance().filter(chatMemory,
					FilterSeverity.STAFF_HIGHLIGHT, message, false, tagsHighlight);
			FilterResult filterResultStaffHighlightStrict = TextFilterService.getInstance().filter(chatMemory,
					FilterSeverity.STAFF_HIGHLIGHT, message, true, tagsHighlight);
			String reasonResultDefault = filterResultDefaultMod.getPrimaryFilterReason();
			if (filterResultDefaultOrig.isMatch() && filterResultDefaultMod.isMatch())
				reasonResultDefault = filterResultDefaultOrig.getFilterResult();
			else if (filterResultDefaultMod.getSeverity().ordinal() == FilterSeverity.USER_STRICT_MODE.ordinal()
					&& filterResultDefaultMod.isMatch() && !filterResultDefaultOrig.isMatch())
				reasonResultDefault = "Filtered due to heightened sensitivity mode";

			// Load filter result fields
			boolean filteredUserStrictModeState = filterResultStrict.isMatch();
			boolean filteredDefaultState = filterResultDefaultMod.isMatch();
			boolean filteredDefaultOrigState = filterResultDefaultOrig.isMatch();
			boolean filteredUserStrictModeCensor = filterResultStrict.getSeverity()
					.ordinal() >= FilterSeverity.USER_STRICT_MODE.ordinal();
			boolean filteredDefaultCensor = filterResultDefaultMod.getSeverity()
					.ordinal() >= FilterSeverity.USER_STRICT_MODE.ordinal();
			boolean filteredDefaultOrigCensor = filterResultDefaultOrig.getSeverity()
					.ordinal() >= FilterSeverity.USER_STRICT_MODE.ordinal();
			boolean filteredFlaggedState = filterResultStaffHighlightStrict.isMatch();
			boolean filteredFlaggedWithoutStrictmodeState = filterResultStaffHighlight.isMatch();

			// Load user settings
			int filterSettingSelf = 0;
			UserVarValue valS = client.getPlayer().getSaveSpecificInventory().getUserVarAccesor()
					.getPlayerVarValue("9362", 0); // the setting for the filter ingame, if the user wishes to have a
													// stricter filter enabled.
			if (valS != null)
				filterSettingSelf = valS.value;

			// Select message
			String messageIn = filterResultDefaultMod.getFilterResult();
			if (filteredUserStrictModeState && filteredUserStrictModeCensor && filterSettingSelf != 0)
				messageIn = filterResultStrict.getFilterResult(); // Strict mode, source had strict enabled

			// Check rate limit
			if (rateLimit.isRateLimited()) {
				// Cancel message

				// Set timer
				rateLimit.rateLimitEnableTime = System.currentTimeMillis();

				// Time format
				SimpleDateFormat fmt = new SimpleDateFormat("yyyy'-'MM'-'dd'T'HH':'mm':'ssXXX");
				fmt.setTimeZone(TimeZone.getTimeZone("UTC"));

				// Send failure
				SendMessage res = new SendMessage();
				res.roomType = roomInstance.getType();
				res.room = room;
				res.message = "</noparse><color=red>[!] </color><color=orange><noparse>" + messageIn
						+ "</noparse></color><noparse>";
				res.messagePlain = "[!] " + messageIn;
				res.originalMessage = message;
				res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
				res.alertingMessage = true;
				res.criticalAlertingMessage = true;
				res.blockedMessage = true;
				res.sourceWriter = client.getPlayer().getAccountID();
				res.sentAtWriter = fmt.format(new Date());
				client.sendPacket(res);

				// Broadcast to moderators unless its a private chat
				res = new SendMessage();
				res.roomType = roomInstance.getType();
				res.room = room;
				res.message = "</noparse><color=red>[!] </color><color=orange><noparse>" + message
						+ "</noparse></color><noparse>";
				res.messagePlain = "[!] " + message;
				res.originalMessage = message;
				res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
				res.alertingMessage = true;
				res.criticalAlertingMessage = true;
				res.blockedMessage = true;
				res.sourceWriter = client.getPlayer().getAccountID();
				res.sentAtWriter = fmt.format(new Date());
				broadcastToModerators(client, res);

				// System message
				res = new SendMessage();
				res.roomType = roomInstance.getType();
				res.room = room;
				res.message = "Whoah there! You are sending too many messages in a short period, please slow down! Please wait 15 seconds before sending another message.";
				res.sourceWriter = NIL_UUID;
				res.sentAtWriter = fmt.format(new Date());
				res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
				client.sendPacket(res);

				// Exit
				return true;
			}

			// Check mute
			CenturiaAccount acc = client.getPlayer();
			if (acc.getSaveSharedInventory().containsItem("penalty") && acc.getSaveSharedInventory().getItem("penalty")
					.getAsJsonObject().get("type").getAsString().equals("mute")) {
				JsonObject muteInfo = acc.getSaveSharedInventory().getItem("penalty").getAsJsonObject();
				if (muteInfo.get("unmuteTimestamp").getAsLong() == -1
						|| muteInfo.get("unmuteTimestamp").getAsLong() > System.currentTimeMillis()) {
					// Time format
					SimpleDateFormat fmt = new SimpleDateFormat("yyyy'-'MM'-'dd'T'HH':'mm':'ssXXX");
					fmt.setTimeZone(TimeZone.getTimeZone("UTC"));

					// Get reason
					String reason = null;
					if (muteInfo.has("reason"))
						reason = muteInfo.get("reason").getAsString();

					// Send failure
					SendMessage res = new SendMessage();
					res.roomType = roomInstance.getType();
					res.room = room;
					res.message = "</noparse><color=red>[!] </color><color=orange><noparse>" + messageIn
							+ "</noparse></color><noparse>";
					res.messagePlain = "[!] " + messageIn;
					res.originalMessage = message;
					res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
					res.alertingMessage = true;
					res.criticalAlertingMessage = true;
					res.blockedMessage = true;
					res.sourceWriter = client.getPlayer().getAccountID();
					res.sentAtWriter = fmt.format(new Date());
					client.sendPacket(res);

					// Broadcast to moderators unless its a private chat
					res = new SendMessage();
					res.roomType = roomInstance.getType();
					res.room = room;
					res.message = "</noparse><color=red>[!] </color><color=orange><noparse>" + message
							+ "</noparse></color><noparse>";
					res.messagePlain = "[!] " + message;
					res.originalMessage = message;
					res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
					res.alertingMessage = true;
					res.criticalAlertingMessage = true;
					res.blockedMessage = true;
					res.sourceWriter = client.getPlayer().getAccountID();
					res.sentAtWriter = fmt.format(new Date());
					broadcastToModerators(client, res);

					// System message
					res = new SendMessage();
					res.roomType = roomInstance.getType();
					res.room = room;
					res.message = "You are muted and cannot send messages in chat."
							+ (reason != null ? "\nReason: " + reason : "");
					res.sourceWriter = NIL_UUID;
					res.sentAtWriter = fmt.format(new Date());
					res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
					client.sendPacket(res);

					return true; // ignore chat
				}
			}

			// Disable chat if needed
			if (flags.active && !flags.chatDisabled && !roomHasStaff && filterResultDefaultOrig.isMatch()
					&& filteredDefaultOrigCensor) {
				// Increase flag counter for chat disable
				flags.flagCountChatdisable++;
				flags.flagCountChatdisableSecondary++;
				if (flags.flagCountChatdisable >= heightenedSensitivityChatDisableThreshold1
						|| flags.flagCountChatdisableSecondary >= heightenedSensitivityChatDisableThreshold2) {
					// Format
					SimpleDateFormat fmt2 = new SimpleDateFormat("dd'-'MM'-'yyyy HH':'mm':'ss");
					fmt2.setTimeZone(TimeZone.getTimeZone("UTC"));

					// Get matched words
					String matchedWordsString = matchedWordsAsString(filterResultDefaultOrig.getMatches());

					// Get reason
					String filterReason = filterResultDefaultOrig.getPrimaryFilterReason();

					// Disable chat
					//
					// Check if theres a timer assigned, if not, in private chat this reverts to the
					// default timer
					if (roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.ROOM_CHAT)
							|| heightenedSensitivityChatReactivateTimer != -1)
						flags.renableChatAter = heightenedSensitivityChatReactivateTimer == -1 ? -1
								: System.currentTimeMillis() + heightenedSensitivityChatReactivateTimer;
					else
						flags.renableChatAter = System.currentTimeMillis() + 900000;

					// Disable the chat
					flags.disableChat(client.getServer());

					// Dispatch event
					if (roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT)) {
						EventBus.getInstance().dispatchEvent(new MiscModerationEvent(
								"chatfilter.heightenedstrictness.chatdisable",
								"Chat filter has flagged player " + client.getPlayer().getDisplayName() + "!",
								Map.of("Notice",
										"Chat has been disabled due to having passed the chat deactivation threshold, chat will re-enable when the chat reactivation timer is met",
										"Private chat room", formatRoomName(client, roomInstance), "Chat message",
										message, "Matched word(s)", matchedWordsString, "Primary reason for filtering",
										filterReason, "Room", room, "Resulting action", "chat disabled",
										"Chat re-enables at",
										fmt2.format(new Date(flags.renableChatAter)) + " UTC ("
												+ formatTimeRelative(flags.renableChatAter - System.currentTimeMillis())
												+ " from now)"),
								"SYSTEM", client.getPlayer()));
					} else {
						if (flags.renableChatAter != -1) {
							EventBus.getInstance().dispatchEvent(new MiscModerationEvent(
									"chatfilter.heightenedstrictness.chatdisable",
									"Chat filter has flagged player " + client.getPlayer().getDisplayName() + "!",
									Map.of("Notice",
											"Chat has been disabled due to having passed the chat deactivation threshold, chat will re-enable when staff logs on or if the chat reactivation timer is met",
											"Chat message", message, "Matched word(s)", matchedWordsString,
											"Primary reason for filtering", filterReason, "Room",
											formatRoomName(client, roomInstance), "Resulting action", "chat disabled",
											"Chat re-enables at",
											fmt2.format(new Date(flags.renableChatAter)) + " UTC ("
													+ formatTimeRelative(
															flags.renableChatAter - System.currentTimeMillis())
													+ " from now)" + " or whenever a staff member gets online."),
									"SYSTEM", client.getPlayer()));
						} else {
							EventBus.getInstance().dispatchEvent(new MiscModerationEvent(
									"chatfilter.heightenedstrictness.chatdisable",
									"Chat filter has flagged player " + client.getPlayer().getDisplayName() + "!",
									Map.of("Notice",
											"Chat has been disabled due to having passed the chat deactivation threshold, chat will re-enable when staff logs on or if the chat reactivation timer is met",
											"Chat message", message, "Matched word(s)", matchedWordsString,
											"Primary reason for filtering", filterReason, "Room",
											formatRoomName(client, roomInstance), "Resulting action", "chat disabled"),
									"SYSTEM", client.getPlayer()));
						}
					}
				}
			}

			// Check disabled
			if (flags.chatDisabled) {
				// Time format
				SimpleDateFormat fmt = new SimpleDateFormat("yyyy'-'MM'-'dd'T'HH':'mm':'ssXXX");
				fmt.setTimeZone(TimeZone.getTimeZone("UTC"));

				// Get reason
				String reason = flags.activationReason;

				// Send failure
				SendMessage res = new SendMessage();
				res.roomType = roomInstance.getType();
				res.room = room;
				res.message = "</noparse><color=red>[!] </color><color=orange><noparse>" + messageIn
						+ "</noparse></color><noparse>";
				res.messagePlain = "[!] " + messageIn;
				res.originalMessage = message;
				res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
				res.alertingMessage = true;
				res.criticalAlertingMessage = true;
				res.blockedMessage = true;
				res.sourceWriter = client.getPlayer().getAccountID();
				res.sentAtWriter = fmt.format(new Date());
				client.sendPacket(res);

				// Broadcast to moderators unless its a private chat
				res = new SendMessage();
				res.roomType = roomInstance.getType();
				res.room = room;
				res.message = "</noparse><color=red>[!] </color><color=orange><noparse>" + message
						+ "</noparse></color><noparse>";
				res.messagePlain = "[!] " + message;
				res.originalMessage = message;
				res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
				res.alertingMessage = true;
				res.criticalAlertingMessage = true;
				res.blockedMessage = true;
				res.sourceWriter = client.getPlayer().getAccountID();
				res.sentAtWriter = fmt.format(new Date());
				broadcastToModerators(client, res);

				// System message
				res = new SendMessage();
				SimpleDateFormat fmt2 = new SimpleDateFormat("dd'-'MM'-'yyyy HH':'mm':'ss");
				fmt2.setTimeZone(TimeZone.getTimeZone("UTC"));
				res.roomType = roomInstance.getType();
				res.room = room;
				if (roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.ROOM_CHAT))
					res.message = "Due to a large amount of filter triggers, the chat has currently been disabled for this world until a staff member can get on. We apologize about the inconvenience! The team has already been alerted about the chat being disabled."
							+ (flags.renableChatAter != -1
									? "\n\nChat re-enables at " + fmt2.format(new Date(flags.renableChatAter))
											+ " UTC ("
											+ formatTimeRelative(flags.renableChatAter - System.currentTimeMillis())
											+ " from now)" + " or whenever a staff member gets online."
									: "")
							+ (reason != null ? "\nReason of activation of heightened sensitivity mode: " + reason
									: "");
				else
					res.message = "Due to a large amount of filter triggers, chat has currently been disabled for this room until the timer lapses. We apologize about the inconvenience!"
							+ (flags.renableChatAter != -1
									? "\n\nChat re-enables at " + fmt2.format(new Date(flags.renableChatAter))
											+ " UTC ("
											+ formatTimeRelative(flags.renableChatAter - System.currentTimeMillis())
											+ " from now)"
									: "")
							+ (reason != null ? "\nReason of activation of heightened sensitivity mode: " + reason
									: "");
				res.sourceWriter = NIL_UUID;
				res.sentAtWriter = fmt.format(new Date());
				res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
				client.sendPacket(res);

				return true; // ignore chat
			}

			// Check trigger
			// Enable heightened sensitivity if needed
			if (!flags.active && filterResultDefaultOrig.isMatch() && filteredDefaultOrigCensor && !roomHasStaff) {
				// Increase counter
				flags.flagCountAutoactivate++;
				flags.lastFlagAutoactivate = System.currentTimeMillis();
				if (flags.flagCountAutoactivate >= heightenedSensitivityTriggerThreshold) {
					// Enable heightened sensitivity mode
					flags.activationReason = null;
					flags.disableAfter = heightenedSensitivityDeactivateTimer == -1 ? -1
							: System.currentTimeMillis() + heightenedSensitivityDeactivateTimer;
					flags.wasAutoactivate = true;
					flags.activateHeightenedSensitivity(client.getServer());

					// Get matched words
					String matchedWordsString = matchedWordsAsString(filterResultDefaultOrig.getMatches());

					// Get reason
					String filterReason = filterResultDefaultOrig.getPrimaryFilterReason();

					// Check if private
					if (roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT)) {
						EventBus.getInstance().dispatchEvent(new MiscModerationEvent(
								"chatfilter.heightenedstrictness.activate",
								"Chat filter has flagged player " + client.getPlayer().getDisplayName() + "!",
								Map.of("Notice",
										"Due to having pased the threshold of filter triggers, the chat room has been set to heightened sensitivity mode",
										"Private chat room", formatRoomName(client, roomInstance), "Chat message",
										message, "Matched word(s)", matchedWordsString, "Primary reason for filtering",
										filterReason, "Room", room, "Resulting action",
										"activated heightened sensitivity mode"),
								"SYSTEM", client.getPlayer()));
					} else {
						EventBus.getInstance().dispatchEvent(new MiscModerationEvent(
								"chatfilter.heightenedstrictness.activate",
								"Chat filter has flagged player " + client.getPlayer().getDisplayName() + "!",
								Map.of("Notice",
										"Due to having pased the threshold of filter triggers, and no staff being online, the chat room has been set to heightened sensitivity mode",
										"Chat message", message, "Matched word(s)", matchedWordsString,
										"Primary reason for filtering", filterReason, "Room",
										formatRoomName(client, roomInstance), "Resulting action",
										"activated heightened sensitivity mode"),
								"SYSTEM", client.getPlayer()));
					}
				}
			}

			// Check mute
			if (filterResultDefaultOrig.getSeverity().ordinal() >= FilterSeverity.INSTAMUTE.ordinal()) {
				// Should mute

				// Push to context for client
				chatMemoryClient.pushToContext(filterResultDefaultOrig);

				// Select filter
				FilterResult selectedFilterResult = filterResultDefaultOrig;
				if (filterSettingSelf != 0) {
					// Load strict mode
					selectedFilterResult = filterResultStrict;
				}

				// Format
				String highlightedMessage = moderatorMessageStringBuilderRed
						.buildOutputString(selectedFilterResult.getTextParts());
				String highlightedMessagePlain = moderatorMessageStringBuilderPlain
						.buildOutputString(selectedFilterResult.getTextParts());
				String matchedWordsString = matchedWordsAsString(selectedFilterResult.getMatches());

				// Get reason
				String filterReason = selectedFilterResult.getPrimaryFilterReason();

				// Check if private
				if (roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT)) {
					// Private chat, need more details
					EventBus.getInstance().dispatchEvent(new MiscModerationEvent("chatfilter.mute",
							"Chat filter has flagged player " + client.getPlayer().getDisplayName() + "!",
							Map.of("Private chat room", formatRoomName(client, roomInstance), "Chat message", message,
									"Matched word(s)", matchedWordsString, "Primary reason for filtering", filterReason,
									"Room", room, "Resulting action", "muted"),
							"SYSTEM", client.getPlayer()));
				} else {
					EventBus.getInstance()
							.dispatchEvent(new MiscModerationEvent("chatfilter.mute",
									"Chat filter has flagged player " + client.getPlayer().getDisplayName() + "!",
									Map.of("Chat message", message, "Matched word(s)", matchedWordsString,
											"Primary reason for filtering", filterReason, "Room",
											formatRoomName(client, roomInstance), "Resulting action", "muted"),
									"SYSTEM", client.getPlayer()));
				}

				// Send failure
				SimpleDateFormat fmt = new SimpleDateFormat("yyyy'-'MM'-'dd'T'HH':'mm':'ssXXX");
				fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
				SendMessage res = new SendMessage();
				res.roomType = roomInstance.getType();
				res.room = room;
				res.message = "</noparse><color=red>[!] </color><color=orange><noparse>"
						+ selectedFilterResult.getFilterResult() + "</noparse></color><noparse>";
				res.messagePlain = "[!] " + selectedFilterResult.getFilterResult();
				res.messageHighlighted = "</noparse><color=red>[!] </color><color=orange><noparse>" + highlightedMessage
						+ "</noparse></color><noparse>";
				res.messageHighlightedPlain = "[!] " + highlightedMessagePlain;
				res.messagePartsWriter = selectedFilterResult.getTextParts();
				res.filterResultWriter = selectedFilterResult;
				res.originalMessage = message;
				res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
				res.alertingMessage = true;
				res.criticalAlertingMessage = true;
				res.blockedMessage = true;
				res.sourceWriter = client.getPlayer().getAccountID();
				res.sentAtWriter = fmt.format(new Date());
				client.sendPacket(res);

				// Broadcast to moderators unless its a private chat
				res = new SendMessage();
				res.roomType = roomInstance.getType();
				res.room = room;
				res.message = "</noparse><color=red>[!] </color><color=orange><noparse>"
						+ selectedFilterResult.getFilterResult() + "</noparse></color><noparse>";
				res.messageHighlighted = "</noparse><color=red>[!] </color><color=orange><noparse>" + highlightedMessage
						+ "</noparse></color><noparse>";
				res.messagePartsWriter = selectedFilterResult.getTextParts();
				res.filterResultWriter = selectedFilterResult;
				res.originalMessage = message;
				res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
				res.alertingMessage = true;
				res.criticalAlertingMessage = true;
				res.blockedMessage = true;
				res.sourceWriter = client.getPlayer().getAccountID();
				res.sentAtWriter = fmt.format(new Date());
				broadcastToModerators(client, res);

				// Mute
				client.getPlayer().mute(0, 0, 30, "SYSTEM", filterReason);

				// Send system message
				res = new SendMessage();
				res.roomType = roomInstance.getType();
				res.room = room;
				res.message = "You have been automatically muted in public chat for violating server rules, mute will last 30 minutes.\nReason: "
						+ filterReason + "\nWe request you to keep your chat respectful, safe and clean!";
				res.sourceWriter = NIL_UUID;
				res.sentAtWriter = fmt.format(new Date());
				res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
				client.sendPacket(res);
				return true;
			}

			// Fire event
			ChatMessageBroadcastEvent evt2 = new ChatMessageBroadcastEvent(client.getServer(), client.getPlayer(),
					client, message, room);
			EventBus.getInstance().dispatchEvent(evt2);
			if (evt2.isCancelled())
				return true; // Cancelled

			// OC proxying
			String ocProxyName = null;

			// Get proxy session
			ProxySession session = client.getObject(ProxySession.class);
			if (session == null) {
				// Create if missing
				session = new ProxySession();
				client.addObject(session);
			}

			// Check sticky status
			ProxySession.RoomProxySession roomSes = null;
			if (session.roomSessions.containsKey(room)) {
				// Check sticky
				roomSes = session.roomSessions.get(room);
				if (roomSes.sticky) {
					// Update oc proxy thats being used
					ocProxyName = roomSes.lastUsedOcName;
				}
			}

			// Find proxy
			for (OcProxyMetadata md : client.getOcProxyMetadata()) {
				// Check message
				if (message.startsWith(md.prefix) && message.endsWith(md.suffix)) {
					// Found OC
					ocProxyName = md.name;

					// Update message
					message = message.substring(md.prefix.length());
					if (!md.suffix.isEmpty()) {
						// Remove suffix
						message = message.substring(0, message.lastIndexOf(md.suffix));
					}
					message = message.trim();

					// Check content
					if (message.isBlank()) {
						return true; // ignore chat
					}
					break;
				}
			}

			// Check result
			if (ocProxyName != null) {
				// Get proxy
				OcProxyInfo proxy = OcProxyInfo.ofUser(client.getPlayer(), ocProxyName);
				if (proxy != null) {
					// Update name string for it to be used in the chat itself
					ocProxyName = "<color=#00f7ff><noparse>" + proxy.displayName + "</noparse>"
							+ (proxy.characterPronouns.toLowerCase().equals("n/a")
									|| proxy.characterPronouns.toLowerCase().isEmpty() ? ""
											: " [<noparse>" + proxy.characterPronouns + "</noparse>]")
							+ "</color> <color=#daa520>[" + client.getPlayer().getDisplayName() + "]</color>";

					// Update sticky proxying
					if (roomSes != null)
						roomSes.lastUsedOcName = proxy.displayName;
				} else {
					// Refresh
					ocProxyName = null;
					client.reloadProxies();
				}
			}

			// Gather result
			// Check severity and if we need to mute
			if (filteredDefaultOrigState && filteredDefaultOrigCensor) {
				// Format
				String highlightedMessage = moderatorMessageStringBuilderRed
						.buildOutputString(filterResultDefaultOrig.getTextParts());
				String highlightedMessagePlain = moderatorMessageStringBuilderPlain
						.buildOutputString(filterResultDefaultOrig.getTextParts());
				String matchedWordsString = matchedWordsAsString(filterResultDefaultOrig.getMatches());

				// Get reason
				String filterReason = filterResultDefaultOrig.getPrimaryFilterReason();

				// Get/create memory
				ChatFilterMemory mem = client.getObject(ChatFilterMemory.class);
				if (mem == null) {
					mem = new ChatFilterMemory();
					client.addObject(mem);
				}

				// Update
				if (System.currentTimeMillis()
						- mem.lastFlag > (flags.active ? (12 * 60 * 60 * 1000) : (3 * 60 * 60 * 1000))) {
					mem.lastFlag = 0;
					mem.flagCount = 0;
				}
				mem.lastFlag = System.currentTimeMillis();
				mem.flagCount++;

				// Check count
				if (mem.flagCount >= 4) {
					// Mod log

					// Push to context
					chatMemory.pushToContext(filterResultDefaultOrig);

					// Check if private
					if (roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT)) {
						// Private chat, need more details
						EventBus.getInstance().dispatchEvent(new MiscModerationEvent("chatfilter.mute",
								"Chat filter has flagged player " + client.getPlayer().getDisplayName() + "!",
								Map.of("Private chat room", formatRoomName(client, roomInstance), "Chat message",
										message, "Matched word(s)", matchedWordsString, "Primary reason for filtering",
										filterReason, "Room", room, "Resulting action", "muted", "Reason for mute",
										"Continued breaches of chat rules after 2 warnings."),
								"SYSTEM", client.getPlayer()));
					} else {
						EventBus.getInstance().dispatchEvent(new MiscModerationEvent("chatfilter.mute",
								"Chat filter has flagged player " + client.getPlayer().getDisplayName() + "!",
								Map.of("Chat message", message, "Matched word(s)", matchedWordsString,
										"Primary reason for filtering", filterReason, "Room",
										formatRoomName(client, roomInstance), "Resulting action", "muted",
										"Reason for mute", "Continued breaches of chat rules after 2 warnings."),
								"SYSTEM", client.getPlayer()));
					}

					// Send failure
					SimpleDateFormat fmt = new SimpleDateFormat("yyyy'-'MM'-'dd'T'HH':'mm':'ssXXX");
					fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
					SendMessage res = new SendMessage();
					res.roomType = roomInstance.getType();
					res.room = room;
					res.message = "</noparse><color=red>[!] </color><color=orange><noparse>"
							+ filterResultDefaultOrig.getFilterResult() + "</noparse></color><noparse>";
					res.messagePlain = "[!] " + filterResultDefaultOrig.getFilterResult();
					res.messageHighlighted = "</noparse><color=red>[!] </color><color=orange><noparse>"
							+ highlightedMessage + "</noparse></color><noparse>";
					res.messageHighlightedPlain = "[!] " + highlightedMessagePlain;
					res.messagePartsWriter = filterResultDefaultOrig.getTextParts();
					res.filterResultWriter = filterResultDefaultOrig;
					res.originalMessage = message;
					res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
					res.alertingMessage = true;
					res.criticalAlertingMessage = true;
					res.blockedMessage = true;
					res.sourceWriter = client.getPlayer().getAccountID();
					res.sentAtWriter = fmt.format(new Date());
					client.sendPacket(res);

					// Broadcast to moderators unless its a private chat
					res = new SendMessage();
					res.roomType = roomInstance.getType();
					res.room = room;
					res.message = "</noparse><color=red>[!] </color><color=orange><noparse>"
							+ filterResultDefaultOrig.getFilterResult() + "</noparse></color><noparse>";
					res.messageHighlighted = "</noparse><color=red>[!] </color><color=orange><noparse>"
							+ highlightedMessage + "</noparse></color><noparse>";
					res.messagePartsWriter = filterResultDefaultOrig.getTextParts();
					res.filterResultWriter = filterResultDefaultOrig;
					res.originalMessage = message;
					res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
					res.alertingMessage = true;
					res.criticalAlertingMessage = true;
					res.blockedMessage = true;
					res.sourceWriter = client.getPlayer().getAccountID();
					res.sentAtWriter = fmt.format(new Date());
					broadcastToModerators(client, res);

					// Mute
					client.getPlayer().mute(0, 0, 30, "SYSTEM",
							"Due to your continued breaches of the chat rules, you have been muted for 30 minutes.");

					// Send system message
					res = new SendMessage();
					res.roomType = roomInstance.getType();
					res.room = room;
					res.message = "Your message was blocked because it may not be appropriate.\nReason: " + filterReason
							+ "\n\nDue to your continued breaches of the chat rules, you have been muted for 30 minutes.\nWe ask you to keep chat respectful, safe and clean!";
					res.sourceWriter = NIL_UUID;
					res.sentAtWriter = fmt.format(new Date());
					client.sendPacket(res);
					mem.lastFlag = 0;
					mem.flagCount = 0;
					res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
					return true;
				}
			}

			// Push to context
			chatMemory.pushToContext(filterResultDefaultMod);

			// Time format
			SimpleDateFormat fmt = new SimpleDateFormat("yyyy'-'MM'-'dd'T'HH':'mm':'ssXXX");
			fmt.setTimeZone(TimeZone.getTimeZone("UTC"));

			// Check dm
			if (roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT) && !room.equals("SYSTEM")) {
				// Check existence
				boolean exists = manager.dmExists(room);
				boolean wasBlockAccess = false;
				String participant = client.getPlayer().getAccountID();
				if (!GameServer.hasPerm(permLevel, "moderator")) {
					if (exists) {
						// Check if in DM
						boolean found = false;
						for (String id : manager.getDMParticipants(room)) {
							if (id.equals(participant)) {
								found = true;
								break;
							}
						}
						if (!found) {
							exists = false;
							wasBlockAccess = true;
						}
					}
					if (exists) {
						// Check if all the others blocked the member
						boolean hasNonBlocked = false;
						for (String p2 : manager.getDMParticipants(room)) {
							if (!p2.equals(participant) && !p2.startsWith("plaintext:")) {
								if (!SocialManager.getInstance().socialListExists(p2)
										|| !SocialManager.getInstance().getPlayerIsBlocked(p2, participant))
									hasNonBlocked = true;
							}
						}
						if (!hasNonBlocked) {
							// Block
							exists = false;
							wasBlockAccess = false;
						}
					}
				}

				// Check result
				if (!exists) {
					// Fail
					// Send message sent error

					// Send failure
					SendMessage res = new SendMessage();
					res.roomType = roomInstance.getType();
					res.room = room;
					res.message = "</noparse><color=red>[!] </color><color=orange><noparse>" + message
							+ "</noparse></color><noparse>";
					res.messagePlain = "[!] " + message;
					res.originalMessage = message;
					res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
					res.alertingMessage = true;
					res.criticalAlertingMessage = true;
					res.blockedMessage = true;
					res.sourceWriter = client.getPlayer().getAccountID();
					res.sentAtWriter = fmt.format(new Date());
					client.sendPacket(res);

					// Broadcast to moderators unless its a private chat
					res = new SendMessage();
					res.roomType = roomInstance.getType();
					res.room = room;
					res.message = "</noparse><color=red>[!] </color><color=orange><noparse>" + message
							+ "</noparse></color><noparse>";
					res.messagePlain = "[!] " + message;
					res.originalMessage = message;
					res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
					res.alertingMessage = true;
					res.criticalAlertingMessage = true;
					res.blockedMessage = true;
					res.sourceWriter = client.getPlayer().getAccountID();
					res.sentAtWriter = fmt.format(new Date());
					broadcastToModerators(client, res);

					// System message
					res = new SendMessage();
					res.roomType = roomInstance.getType();
					res.room = room;
					if (wasBlockAccess)
						res.message = "Error: the message could not be sent as the chat room you are sending your message to revoked your access";
					else
						res.message = "Error: the message could not be sent as the chat you are trying to send a message to was deleted";
					res.sourceWriter = NIL_UUID;
					res.sentAtWriter = fmt.format(new Date());
					res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
					client.sendPacket(res);

					// Return
					return true;
				}
			}

			// If it is a DM, save message
			if (roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT) && manager.dmExists(room)) {
				PrivateChatMessage msg = new PrivateChatMessage();
				String messageToUse = filterResultDefaultMod.getFilterResult();
				if (filterSettingSelf != 0) {
					// Use strict mode
					messageToUse = filterResultStrict.getFilterResult();
				}
				msg.content = messageToUse;
				msg.sentAt = fmt.format(new Date());
				msg.source = client.getPlayer().getAccountID();
				if (ocProxyName != null)
					msg.source = "plaintext:" + ocProxyName;
				manager.saveDMMessge(room, msg);
			}

			// Send to all in room
			SocialManager socialManager = SocialManager.getInstance();
			Player cPlayer = client.getPlayer().getOnlinePlayerInstance();
			for (ChatClient receiver : client.getServer().getClients()) {
				// Fetch receiver moderator perms
				String permLevel2 = "member";
				if (receiver.getPlayer().getSaveSharedInventory().containsItem("permissions")) {
					permLevel2 = receiver.getPlayer().getSaveSharedInventory().getItem("permissions").getAsJsonObject()
							.get("permissionLevel").getAsString();
				}

				// Check if in room
				if (receiver.isInRoom(room)) {
					// Check if the receiver has blocked the sender and that neither is a moderator
					if (!socialManager.socialListExists(receiver.getPlayer().getAccountID())
							|| !socialManager.getPlayerIsBlocked(receiver.getPlayer().getAccountID(),
									client.getPlayer().getAccountID())
							|| GameServer.hasPerm(permLevel2, "moderator")
							|| GameServer.hasPerm(permLevel, "moderator")) {
						// Check limbo player
						Player gameClient = receiver.getPlayer().getOnlinePlayerInstance();
						if ((gameClient != null && (!gameClient.roomReady || gameClient.room == null))
								|| (gameClient == null && !GameServer.hasPerm(permLevel2, "moderator")))
							continue;

						// Check ghost mode
						if (cPlayer != null && cPlayer.ghostMode && !GameServer.hasPerm(permLevel2, "moderator")
								&& roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.ROOM_CHAT))
							continue;

						// Check if the sender has blocked this receiver, if so, prevent the receiver
						// from receiving the message that was sent, also applies to moderator-sent
						// messages unless its a dm and the player is a moderator
						if (socialManager.getPlayerIsBlocked(client.getPlayer().getAccountID(),
								receiver.getPlayer().getAccountID())) {
							// Check mod perms and room type
							if (GameServer.hasPerm(permLevel, "moderator")) {
								if (client.isInRoom(room)
										&& !roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT)
										&& !GameServer.hasPerm(permLevel2, "moderator")) {
									continue; // Blocked
								}
							} else if (!GameServer.hasPerm(permLevel2, "moderator"))
								continue; // Blocked
						}

						// Load filter settings of the recipient
						int filterSetting = 0;
						UserVarValue val = receiver.getPlayer().getSaveSpecificInventory().getUserVarAccesor()
								.getPlayerVarValue("9362", 0);
						if (val != null)
							filterSetting = val.value;

						// Get filter result
						boolean filteredToRecipient = (filteredDefaultState && filteredDefaultCensor)
								|| ((filteredUserStrictModeState && filteredUserStrictModeCensor)
										&& filterSetting != 0);
						boolean filterUseStrictModeForRecipient = (filteredUserStrictModeState
								&& filteredUserStrictModeCensor) && filterSetting != 0;

						// Select message
						String filteredMessage = messageIn;
						if (filterUseStrictModeForRecipient)
							filteredMessage = filterResultStrict.getFilterResult();

						// Send response
						SendMessage res = new SendMessage();

						// Add properties
						res.moderatorMessage = GameServer.hasPerm(permLevel2, "moderator");
						res.roomType = roomInstance.getType();
						res.room = room;

						// Add properties based on staff rank
						if ((GameServer.hasPerm(permLevel2, "moderator")
								|| receiver.getPlayer().getAccountID().equals(client.getPlayer().getAccountID()))
								&& ((filteredDefaultState && filteredDefaultCensor)
										|| (filteredUserStrictModeState && filteredUserStrictModeCensor)
										|| filteredFlaggedState)) {
							// Is a moderator (or source) and a filter did trigger

							// Checks if moderator, we dont need to uncensor the message for non-mod
							boolean isModerator = GameServer.hasPerm(permLevel2, "moderator");
							res.moderatorMessage = isModerator;

							// Determines if the flag is critical, if true, the exclamation is red,
							// otherwise its yellow to signify strict-mode, the box around the exclamation
							// mark is orange
							//
							// The message will be orange in both cases
							boolean isCriticalFlagged = filteredDefaultOrigState && filteredDefaultOrigCensor;
							boolean flaggedDueToHeightenedSensitivity = !isCriticalFlagged && filteredDefaultState
									&& filteredDefaultCensor;

							// If this is true, the exclamation is green as its uncensored, but still
							// highlighted, the box around the exclamation mark is orange
							//
							// The message will be yellow
							boolean isAlertFlagged = filteredFlaggedState;

							// Format
							String highlightedMessageDefault = moderatorMessageStringBuilderRed
									.buildOutputString(filterResultDefaultOrig.getTextParts());
							String highlightedMessageDefaultPlain = moderatorMessageStringBuilderPlain
									.buildOutputString(filterResultDefaultOrig.getTextParts());
							String highlightedMessageMod = moderatorMessageStringBuilderRed
									.buildOutputString(filterResultDefaultMod.getTextParts());
							String highlightedMessageModPlain = moderatorMessageStringBuilderPlain
									.buildOutputString(filterResultDefaultMod.getTextParts());
							String highlightedMessageStrict = moderatorMessageStringBuilderRed
									.buildOutputString(filterResultStrict.getTextParts());
							String highlightedMessageStrictPlain = moderatorMessageStringBuilderPlain
									.buildOutputString(filterResultStrict.getTextParts());
							String highlightedMessageFlagged = moderatorMessageStringBuilderOrange
									.buildOutputString(filterResultStaffHighlightStrict.getTextParts());
							String highlightedMessageFlaggedPlain = moderatorMessageStringBuilderPlain
									.buildOutputString(filterResultStaffHighlightStrict.getTextParts());

							// Check moderator
							if (!isModerator) {
								// Not a moderator, just highlight
								if (filteredToRecipient) {
									// Recipient was filtered

									// Check flag
									if (isCriticalFlagged)
										res.message = "</noparse><color=orange>[<color=red>!</color>] </color><color=orange><noparse>"
												+ filteredMessage + "</noparse></color><noparse>";
									else
										res.message = "</noparse><color=orange>[<color=yellow>!</color>] </color><color=orange><noparse>"
												+ filteredMessage + "</noparse></color><noparse>";

									// Add remaining
									res.messagePlain = "[!] " + filteredMessage;
									res.alertingMessage = true;
									res.criticalAlertingMessage = true;
									res.blockedMessage = false;
								} else {
									// Default
									res.message = filteredMessage;
								}
							} else {
								// Is staff

								// Check flag
								if ((filteredDefaultState && filteredDefaultCensor)
										|| (filteredUserStrictModeState && filteredUserStrictModeCensor)
										|| flaggedDueToHeightenedSensitivity || filteredFlaggedState) {
									// Check filter trigger type
									if (flaggedDueToHeightenedSensitivity) {
										// Strict mode filter is used here as it includes non-strict during filtering,
										// it may catch more than the non-strict version
										res.message = "</noparse><color=orange>[<color=red>!</color>] </color><color=orange><noparse>"
												+ highlightedMessageMod + "</noparse></color><noparse>";
										res.messagePlain = "[!] " + highlightedMessageModPlain;
									} else if (isCriticalFlagged) {
										// Strict mode filter is used here as it includes non-strict during filtering,
										// it may catch more than the non-strict version
										res.message = "</noparse><color=orange>[<color=red>!</color>] </color><color=orange><noparse>"
												+ ((filteredUserStrictModeState && filteredUserStrictModeCensor)
														? highlightedMessageStrict
														: highlightedMessageDefault)
												+ "</noparse></color><noparse>";
										res.messagePlain = "[!] "
												+ ((filteredUserStrictModeState && filteredUserStrictModeCensor)
														? highlightedMessageStrictPlain
														: highlightedMessageDefaultPlain);
									} else if (filteredUserStrictModeState && filteredUserStrictModeCensor) {
										// Strict mode filter is used here as it includes non-strict during filtering,
										// it may catch more than the non-strict version
										res.message = "</noparse><color=orange>[<color=yellow>!</color>] </color><color=orange><noparse>"
												+ ((filteredUserStrictModeState && filteredUserStrictModeCensor)
														? highlightedMessageStrict
														: highlightedMessageDefault)
												+ "</noparse></color><noparse>";
										res.messagePlain = "[!] "
												+ ((filteredUserStrictModeState && filteredUserStrictModeCensor)
														? highlightedMessageStrictPlain
														: highlightedMessageDefaultPlain);
									} else if (isAlertFlagged) {
										res.message = "</noparse><color=orange>[<color=green>!</color>] </color><color=yellow><noparse>"
												+ highlightedMessageFlagged + "</noparse></color><noparse>";
										res.messagePlain = "[!] " + highlightedMessageFlaggedPlain;
									}

									// Add remaining fields
									res.originalMessage = message;
									if (flaggedDueToHeightenedSensitivity) {
										res.filterResultWriter = filterResultDefaultMod;
										res.messagePartsWriter = filterResultDefaultMod.getTextParts();
									} else {
										res.filterResultWriter = (filteredUserStrictModeState
												&& filteredUserStrictModeCensor) ? filterResultStrict
														: filterResultDefaultOrig;
										res.messagePartsWriter = (filteredUserStrictModeState
												&& filteredUserStrictModeCensor) ? filterResultStrict.getTextParts()
														: filterResultDefaultOrig.getTextParts();
									}
									res.alertingMessage = true;
									res.criticalAlertingMessage = isCriticalFlagged;
									res.blockedMessage = false;
								} else {
									// Default uncensored
									res.message = filteredMessage;
								}
							}
						} else {
							// Default
							res.message = filteredMessage;
						}

						// Add source and such
						res.sourceWriter = client.getPlayer().getAccountID();
						res.sentAtWriter = fmt.format(new Date());
						if (ocProxyName != null) {
							res.sourceWriter = "plaintext:" + ocProxyName;
							res.authorWriter = client.getPlayer().getAccountID();
						}

						// Send message
						receiver.sendPacket(res);
					}
				} else if (!roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT)) {
					// Moderator in other room
					if (receiver.getObject(ModeratorClient.class) != null) {
						// Check moderator perms
						String permLevel3 = "member";
						if (receiver.getPlayer().getSaveSharedInventory().containsItem("permissions")) {
							permLevel3 = receiver.getPlayer().getSaveSharedInventory().getItem("permissions")
									.getAsJsonObject().get("permissionLevel").getAsString();
						}
						if (GameServer.hasPerm(permLevel3, "moderator")) {
							// Send through centuria moderator protocol
							SendMessage res = new SendMessage();
							res.packetId = "centuria.moderatorclient.postedMessageInOtherRoom";
							res.roomType = roomInstance.getType();
							res.room = room;

							// Mark moderator
							res.moderatorMessage = true;

							// Determines if the flag is critical, if true, the exclamation is red,
							// otherwise its yellow to signify strict-mode, the box around the exclamation
							// mark is orange
							//
							// The message will be orange in both cases
							boolean isCriticalFlagged = filteredDefaultOrigState && filteredDefaultOrigCensor;
							boolean flaggedDueToHeightenedSensitivity = !isCriticalFlagged && filteredDefaultState
									&& filteredDefaultCensor;

							// If this is true, the exclamation is green as its uncensored, but still
							// highlighted, the box around the exclamation mark is orange
							//
							// The message will be yellow
							boolean isAlertFlagged = filteredFlaggedState;

							// Format
							String highlightedMessageDefault = moderatorMessageStringBuilderRed
									.buildOutputString(filterResultDefaultOrig.getTextParts());
							String highlightedMessageDefaultPlain = moderatorMessageStringBuilderPlain
									.buildOutputString(filterResultDefaultOrig.getTextParts());
							String highlightedMessageMod = moderatorMessageStringBuilderRed
									.buildOutputString(filterResultDefaultMod.getTextParts());
							String highlightedMessageModPlain = moderatorMessageStringBuilderPlain
									.buildOutputString(filterResultDefaultMod.getTextParts());
							String highlightedMessageStrict = moderatorMessageStringBuilderRed
									.buildOutputString(filterResultStrict.getTextParts());
							String highlightedMessageStrictPlain = moderatorMessageStringBuilderPlain
									.buildOutputString(filterResultStrict.getTextParts());
							String highlightedMessageFlagged = moderatorMessageStringBuilderOrange
									.buildOutputString(filterResultStaffHighlightStrict.getTextParts());
							String highlightedMessageFlaggedPlain = moderatorMessageStringBuilderPlain
									.buildOutputString(filterResultStaffHighlightStrict.getTextParts());

							// Check flag
							if ((filteredDefaultState && filteredDefaultCensor)
									|| (filteredUserStrictModeState && filteredUserStrictModeCensor)
									|| flaggedDueToHeightenedSensitivity || filteredFlaggedState) {
								// Check filter trigger type
								if (flaggedDueToHeightenedSensitivity) {
									// Strict mode filter is used here as it includes non-strict during filtering,
									// it may catch more than the non-strict version
									res.message = "</noparse><color=orange>[<color=red>!</color>] </color><color=orange><noparse>"
											+ highlightedMessageMod + "</noparse></color><noparse>";
									res.messagePlain = "[!] " + highlightedMessageModPlain;
								} else if (isCriticalFlagged) {
									// Strict mode filter is used here as it includes non-strict during filtering,
									// it may catch more than the non-strict version
									res.message = "</noparse><color=orange>[<color=red>!</color>] </color><color=orange><noparse>"
											+ ((filteredUserStrictModeState && filteredUserStrictModeCensor)
													? highlightedMessageStrict
													: highlightedMessageDefault)
											+ "</noparse></color><noparse>";
									res.messagePlain = "[!] "
											+ ((filteredUserStrictModeState && filteredUserStrictModeCensor)
													? highlightedMessageStrictPlain
													: highlightedMessageDefaultPlain);
								} else if (filteredUserStrictModeState && filteredUserStrictModeCensor) {
									// Strict mode filter is used here as it includes non-strict during filtering,
									// it may catch more than the non-strict version
									res.message = "</noparse><color=orange>[<color=yellow>!</color>] </color><color=orange><noparse>"
											+ ((filteredUserStrictModeState && filteredUserStrictModeCensor)
													? highlightedMessageStrict
													: highlightedMessageDefault)
											+ "</noparse></color><noparse>";
									res.messagePlain = "[!] "
											+ ((filteredUserStrictModeState && filteredUserStrictModeCensor)
													? highlightedMessageStrictPlain
													: highlightedMessageDefaultPlain);
								} else if (isAlertFlagged) {
									res.message = "</noparse><color=orange>[<color=green>!</color>] </color><color=yellow><noparse>"
											+ highlightedMessageFlagged + "</noparse></color><noparse>";
									res.messagePlain = "[!] " + highlightedMessageFlaggedPlain;
								}

								// Add remaining fields
								res.originalMessage = message;
								if (flaggedDueToHeightenedSensitivity) {
									res.filterResultWriter = filterResultDefaultMod;
									res.messagePartsWriter = filterResultDefaultMod.getTextParts();
								} else {
									res.filterResultWriter = (filteredUserStrictModeState
											&& filteredUserStrictModeCensor) ? filterResultStrict
													: filterResultDefaultOrig;
									res.messagePartsWriter = (filteredUserStrictModeState
											&& filteredUserStrictModeCensor) ? filterResultStrict.getTextParts()
													: filterResultDefaultOrig.getTextParts();
								}
								res.alertingMessage = true;
								res.criticalAlertingMessage = isCriticalFlagged;
								res.blockedMessage = false;
							} else {
								// Default uncensored
								res.message = filterResultDefaultMod.getFilterResult();
							}

							// Add source and such
							res.sourceWriter = client.getPlayer().getAccountID();
							res.sentAtWriter = fmt.format(new Date());
							if (ocProxyName != null) {
								res.sourceWriter = "plaintext:" + ocProxyName;
								res.authorWriter = client.getPlayer().getAccountID();
							}

							// Send message
							receiver.sendPacket(res);
						}
					}
				}
			}

			// Check censor
			if (filteredDefaultOrigState && filteredDefaultOrigCensor) {
				// Get/create memory
				ChatFilterMemory mem = client.getObject(ChatFilterMemory.class);
				String filterReason = filterResultDefaultOrig.getPrimaryFilterReason();
				String matchedWordsString = matchedWordsAsString(filterResultDefaultOrig.getMatches());

				// Check count
				if (mem.flagCount == 1) {
					// Send message
					SendMessage res = new SendMessage();
					res.roomType = roomInstance.getType();
					res.room = room;
					res.message = "Your message was censored because it may not be appropriate.\nReason: "
							+ filterReason + "\nWe ask you to keep chat respectful, safe and clean.";
					res.sourceWriter = NIL_UUID;
					res.sentAtWriter = fmt.format(new Date());
					res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
					client.sendPacket(res);

					// Mod log
					if (roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT)) {
						// Private chat, need more details
						EventBus.getInstance().dispatchEvent(new MiscModerationEvent("chatfilter.censored",
								"Chat filter has flagged player " + client.getPlayer().getDisplayName() + "!",
								Map.of("Private chat room", formatRoomName(client, roomInstance), "Chat message",
										message, "Matched word(s)", matchedWordsString, "Primary reason for filtering",
										filterReason, "Room", room, "Resulting action", "censored"),
								"SYSTEM", client.getPlayer()));
					} else {
						EventBus.getInstance()
								.dispatchEvent(new MiscModerationEvent("chatfilter.censored",
										"Chat filter has flagged player " + client.getPlayer().getDisplayName() + "!",
										Map.of("Chat message", message, "Matched word(s)", matchedWordsString,
												"Primary reason for filtering", filterReason, "Room",
												formatRoomName(client, roomInstance), "Resulting action", "censored"),
										"SYSTEM", client.getPlayer()));
					}
				} else if (mem.flagCount == 2) {
					// Send message
					SendMessage res = new SendMessage();
					res.roomType = roomInstance.getType();
					res.room = room;
					res.message = "Your message was censored because it may not be appropriate.\nReason: "
							+ filterReason
							+ "\n\nThis is your first warning, if you continue to breach the chat rules, your account will be muted.\nWe ask you to keep chat respectful, safe and clean.";
					res.sourceWriter = NIL_UUID;
					res.sentAtWriter = fmt.format(new Date());
					res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
					client.sendPacket(res);

					// Mod log
					if (roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT)) {
						// Private chat, need more details
						EventBus.getInstance().dispatchEvent(new MiscModerationEvent("chatfilter.censored",
								"Chat filter has flagged player " + client.getPlayer().getDisplayName() + "!",
								Map.of("Private chat room", formatRoomName(client, roomInstance), "Chat message",
										message, "Matched word(s)", matchedWordsString, "Primary reason for filtering",
										filterReason, "Room", room, "Resulting action", "first warning"),
								"SYSTEM", client.getPlayer()));
					} else {
						EventBus.getInstance().dispatchEvent(new MiscModerationEvent("chatfilter.censored",
								"Chat filter has flagged player " + client.getPlayer().getDisplayName() + "!",
								Map.of("Chat message", message, "Matched word(s)", matchedWordsString,
										"Primary reason for filtering", filterReason, "Room",
										formatRoomName(client, roomInstance), "Resulting action", "first warning"),
								"SYSTEM", client.getPlayer()));
					}
				} else if (mem.flagCount == 3) {
					// Send message
					SendMessage res = new SendMessage();
					res.roomType = roomInstance.getType();
					res.room = room;
					res.message = "Your message was censored because it may not be appropriate.\nReason: "
							+ filterReason
							+ "\n\nThis is your LAST warning, the next breach of chat rules will result in a mute.\nWe ask you to keep chat respectful, safe and clean.";
					res.sourceWriter = NIL_UUID;
					res.sentAtWriter = fmt.format(new Date());
					res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
					client.sendPacket(res);

					// Mod log
					if (roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT)) {
						// Private chat, need more details
						EventBus.getInstance().dispatchEvent(new MiscModerationEvent("chatfilter.censored",
								"Chat filter has flagged player " + client.getPlayer().getDisplayName() + "!",
								Map.of("Private chat room", formatRoomName(client, roomInstance), "Chat message",
										message, "Matched word(s)", matchedWordsString, "Primary reason for filtering",
										filterReason, "Room", room, "Resulting action", "final warning"),
								"SYSTEM", client.getPlayer()));
					} else {
						EventBus.getInstance().dispatchEvent(new MiscModerationEvent("chatfilter.censored",
								"Chat filter has flagged player " + client.getPlayer().getDisplayName() + "!",
								Map.of("Chat message", message, "Matched word(s)", matchedWordsString,
										"Primary reason for filtering", filterReason, "Room",
										formatRoomName(client, roomInstance), "Resulting action", "final warning"),
								"SYSTEM", client.getPlayer()));
					}
				}
			} else if (filteredDefaultState && filteredDefaultCensor) {
				// Send message
				SendMessage res = new SendMessage();
				res.roomType = roomInstance.getType();
				res.room = room;
				res.message = "The text filter is in heightened sensitivity mode, your message was censored as a result.\nReason: "
						+ reasonResultDefault;
				res.sourceWriter = NIL_UUID;
				res.sentAtWriter = fmt.format(new Date());
				res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
				client.sendPacket(res);
			} else if ((filteredUserStrictModeState && filteredUserStrictModeCensor) && filterSettingSelf != 0) {
				// Send message
				SendMessage res = new SendMessage();
				res.roomType = roomInstance.getType();
				res.room = room;
				res.message = "Your message was censored because of your current settings.\nIf you wish to not have this message flagged, please change your game's chat settings.";
				res.sourceWriter = NIL_UUID;
				res.sentAtWriter = fmt.format(new Date());
				res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
				client.sendPacket(res);
			}

			// Check if flagged
			if (filteredFlaggedWithoutStrictmodeState
					&& !roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT)) {
				// Alert staff if needed
				String filterReason = filterResultStaffHighlight.getPrimaryFilterReason();
				String matchedWordsString = matchedWordsAsString(filterResultStaffHighlight.getMatches());

				// Check if staff is present
				boolean hasStaffInRoom = false;
				for (ChatClient c : client.getServer().getClients()) {
					// Fetch receiver moderator perms
					String permLevel2 = "member";
					if (c.getPlayer().getSaveSharedInventory().containsItem("permissions")) {
						permLevel2 = c.getPlayer().getSaveSharedInventory().getItem("permissions").getAsJsonObject()
								.get("permissionLevel").getAsString();
					}
					if (GameServer.hasPerm(permLevel2, "moderator") && c.isInRoom(room)) {
						hasStaffInRoom = true;
						break;
					}
				}

				// Mod log
				EventBus.getInstance()
						.dispatchEvent(new MiscModerationEvent("chatfilter.alert",
								"Chat filter alert! Player " + client.getPlayer().getDisplayName()
										+ " sent a message that was flagged by the system!",
								Map.of("Chat message", message, "Matched word(s)", matchedWordsString,
										"Primary reason for alerting", filterReason, "Room",
										formatRoomName(client, roomInstance), "Resulting action",
										"no action taken, only alerting staff"),
								"SYSTEM", client.getPlayer(), !hasStaffInRoom));
			}
		}

		return true;
	}

	private static String formatTimeRelative(long time) {
		String out = "";
		if (time > 60 * 60 * 1000) {
			long hours = time / (60 * 60 * 1000);
			time = time - hours * 60 * 60 * 1000;
			out += hours + " hours";
		}
		if (time > 60 * 1000) {
			long mins = time / (60 * 1000);
			time = time - mins * 60 * 1000;
			if (!out.isEmpty())
				out += ", ";
			out += mins + " minutes";
		}
		if (time > 1000) {
			long secs = time / (1000);
			time = time - secs * 1000;
			if (!out.isEmpty())
				out += ", ";
			out += secs + " seconds";
		}
		return out;
	}

	private void broadcastToModerators(ChatClient client, SendMessage message) {
		for (ChatClient receiver : client.getServer().getClients()) {
			// Fetch receiver moderator perms
			String permLevel2 = "member";
			if (receiver.getPlayer().getSaveSharedInventory().containsItem("permissions")) {
				permLevel2 = receiver.getPlayer().getSaveSharedInventory().getItem("permissions").getAsJsonObject()
						.get("permissionLevel").getAsString();
			}

			// Check if in room
			if (receiver.isInRoom(message.room) && GameServer.hasPerm(permLevel2, "moderator")
					&& !receiver.getPlayer().getAccountID().equals(client.getPlayer().getAccountID())) {
				// Check limbo player
				Player gameClient = receiver.getPlayer().getOnlinePlayerInstance();
				if (gameClient != null && (!gameClient.roomReady || gameClient.room == null))
					continue;

				// Send to mod
				SendMessage res = new SendMessage();
				res.roomType = message.roomType;
				res.room = message.room;
				res.message = message.message;
				res.messagePlain = message.messagePlain;
				res.moderatorMessage = true;
				res.originalMessage = message.originalMessage;
				res.alertingMessage = message.alertingMessage;
				res.criticalAlertingMessage = message.criticalAlertingMessage;
				res.blockedMessage = message.blockedMessage;
				res.sourceWriter = message.sourceWriter;
				res.sentAtWriter = message.sentAtWriter;
				res.filterResultWriter = message.filterResultWriter;
				res.messagePartsWriter = message.messagePartsWriter;
				receiver.sendPacket(res);
			} else if (!receiver.isInRoom(message.room)
					&& !receiver.getPlayer().getAccountID().equals(client.getPlayer().getAccountID())
					&& !roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT)) {
				// Not in room

				// Check moderator client
				if (receiver.getObject(ModeratorClient.class) != null) {
					// Check moderator perms
					String permLevel = "member";
					if (receiver.getPlayer().getSaveSharedInventory().containsItem("permissions")) {
						permLevel = receiver.getPlayer().getSaveSharedInventory().getItem("permissions")
								.getAsJsonObject().get("permissionLevel").getAsString();
					}
					if (GameServer.hasPerm(permLevel, "moderator")) {
						// Send through centuria moderator protocol
						SendMessage res = new SendMessage();
						res.packetId = "centuria.moderatorclient.postedMessageInOtherRoom";
						res.roomType = message.roomType;
						res.room = message.room;
						res.message = message.message;
						res.messagePlain = message.messagePlain;
						res.moderatorMessage = true;
						res.originalMessage = message.originalMessage;
						res.alertingMessage = message.alertingMessage;
						res.criticalAlertingMessage = message.criticalAlertingMessage;
						res.blockedMessage = message.blockedMessage;
						res.sourceWriter = message.sourceWriter;
						res.sentAtWriter = message.sentAtWriter;
						res.filterResultWriter = message.filterResultWriter;
						res.messagePartsWriter = message.messagePartsWriter;
						receiver.sendPacket(res);
					}
				}
			}
		}
	}

	private TextFilterContextMemory createChatMemoryObject() {
		// Initialize
		try {
			return new TextFilterContextMemory(FilterSeverity.ALWAYS_FILTERED,
					Integer.parseInt(
							Centuria.textFilterProperties.getOrDefault("chat-message-memory-max-length", "10")),
					Integer.parseInt(
							Centuria.textFilterProperties.getOrDefault("chat-message-memory-max-triggers", "5")),
					Long.parseLong(Centuria.textFilterProperties.getOrDefault("chat-message-trigger-age-timems-limit",
							"1200000")),
					Integer.parseInt(Centuria.textFilterProperties
							.getOrDefault("chat-message-trigger-age-position-limit", "15")),
					Integer.parseInt(Centuria.textFilterProperties
							.getOrDefault("chat-message-trigger-retain-surrounding", "10")));
		} catch (Exception e) {
			TextFilterContextMemory chatMemory = new TextFilterContextMemory(FilterSeverity.ALWAYS_FILTERED, 10);
			Centuria.logger.error("Failed to load textfilter.conf! Please make sure the syntax is correct.", e);
			return chatMemory;
		}
	}

	// Command parser
	private ArrayList<String> parseCommand(String args) {
		ArrayList<String> args3 = new ArrayList<String>();
		char[] argarray = args.toCharArray();
		boolean ignorespaces = false;
		String last = "";
		int i = 0;
		for (char c : args.toCharArray()) {
			if (c == '"' && (i == 0 || argarray[i - 1] != '\\')) {
				ignorespaces = !ignorespaces;
			} else if (c == ' ' && !ignorespaces && (argarray[i - 1] != '\\')) {
				if (!last.isEmpty())
					args3.add(last);
				last = "";
			} else if (c != '\\' || (i + 1 < argarray.length && argarray[i + 1] != '"'
					&& (argarray[i + 1] != ' ' || ignorespaces))) {
				last += c;
			}

			i++;
		}

		if (!last.isEmpty())
			args3.add(last);

		return args3;
	}

	// Command handler
	private boolean handleCommand(String cmd, ChatClient client) {
		// Load permission level
		String permLevel = "member";
		if (client.getPlayer().getSaveSharedInventory().containsItem("permissions")) {
			permLevel = client.getPlayer().getSaveSharedInventory().getItem("permissions").getAsJsonObject()
					.get("permissionLevel").getAsString();
		}

		// Generate the command list
		ArrayList<String> commandMessages = new ArrayList<String>();

		if (client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemResources
				|| GameServer.hasPerm(permLevel, "moderator"))
			commandMessages.add("giveBasicMaterials");
		if (client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemCurrency
				|| GameServer.hasPerm(permLevel, "moderator"))
			commandMessages.add("giveBasicCurrency");
		if (client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemAvatars
				|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemClothes
				|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemCurrency
				|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemFurnitureItems
				|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemMods
				|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemResources
				|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemSanctuaryTypes)
			commandMessages.add("restockInventory");

		commandMessages.add("togglenameprefix");
		if (GameServer.hasPerm(permLevel, "moderator")) {
			commandMessages.add("toggleghostmode");
			commandMessages.add("toggletpoverride");
			commandMessages.add("kick \"<player>\" [\"<reason>\"]");
			commandMessages.add("ipban \"<player/address>\" [\"<reason>\"]");
			commandMessages.add("permban \"<player>\" [\"<reason>\"]");
			commandMessages.add("tempban \"<player>\" <days> [\"<reason>\"]");
			commandMessages.add("mute \"<player>\" [\"<reason>\"]");
			commandMessages.add("mute \"<player>\" <minutes> [\"<reason>\"]");
			commandMessages.add("mute \"<player>\" <hour> <minutes> [\"<reason>\"]");
			commandMessages.add("mute \"<player>\" <days> <hour> <minutes> [\"<reason>\"]");
			commandMessages.add("heightenedsensitivity enable [\"<reason>\"] [\"<room>\"]");
			commandMessages.add("forcenamechange \"<player>\"");
			commandMessages.add("changeothername \"<player>\" \"<new-name>\"");
			commandMessages.add("pardonip \"<ip>\"");
			commandMessages.add("pardon \"<player>\" [\"<reason>\"]");
			commandMessages.add("tradepermban \"<player>\" [\"<reason>\"]");
			commandMessages.add("tradetempban \"<player>\" <days> [\"<reason>\"]");
			commandMessages.add("tradepardon \"<player>\" [\"<reason>\"]");
			commandMessages.add("heightenedsensitivity disable [\"<room>\"]");
			commandMessages.add("xpinfo [\"<player>\"]");
			commandMessages.add("takexp <amount> [\"<player>\"]");
			commandMessages.add("resetxp [\"<player>\"]");
			commandMessages.add("takelevels <amount> [\"<player>\"]");
			commandMessages.add("questskip [<amount>] [<player>]");
			commandMessages.add("setplayertag \"<tag id>\" [\"<player>\"] [\"<escaped  tag json data>\"]");
			commandMessages.add("removeplayertag \"<tag id>\" [\"<player>\"]");
			commandMessages.add("addxp <amount> [\"<player>\"]");
			commandMessages.add("addlevels <amount> [\"<player>\"]");
			commandMessages.add("resetalllevels [confirm]");
			if (GameServer.hasPerm(permLevel, "admin")) {
				commandMessages.add("generateclearancecode");
				commandMessages.add("makeadmin \"<player>\"");
				commandMessages.add("makemoderator \"<player>\"");
				commandMessages.add("removeperms \"<player>\"");
				commandMessages.add("startmaintenance");
				commandMessages.add("endmaintenance");
				commandMessages.add("retryautomaticupdate");
				commandMessages.add("forceinstallupdate");
				commandMessages.add("shutdownserver [\"<reason>\"]");
				commandMessages.add("updatewarning <minutes-remaining>");
				commandMessages.add("updateshutdown [\"<reason>\"]");
				commandMessages.add("update <60|30|15|10|5|3|1>");
				commandMessages.add("cancelupdate");
			}
			if (GameServer.hasPerm(permLevel, "developer")) {
				commandMessages.add("makedeveloper \"<name>\"");
				commandMessages.add("srp \"<raw-packet>\" [<player>]");
			}
			commandMessages.add("staffroom");
			commandMessages.add("listplayers");
			commandMessages.add("listplayerpps");
			commandMessages.add("coordsof/coords [\"<player>\"]");
			commandMessages.add("tp [\"<player to teleport>\"] \"<target player>\"");
			commandMessages.add("tp [\"<player to teleport>\"] <x> <y> <z>");
			commandMessages.add("tpall \"<target player>\"");
			commandMessages.add("tpall <x> <y> <z>");
			commandMessages.add("tpserverto \"<target player>\"");
			commandMessages.add("tptosanctuary \"<sanctuary owner player name>\" [\"<target player>\"]");
			commandMessages.add("tpm <levelDefID> [<levelType>] [<target player>]");
		}
		if (client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowSkipTwiggleWork
				|| GameServer.hasPerm(permLevel, "moderator"))
			if (!GameServer.hasPerm(permLevel, "moderator"))
				commandMessages.add("skipsanctuaryupgrade");
			else
				commandMessages.add("skipsanctuaryupgrade [<player>]");
		if (client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemAvatars
				|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemClothes
				|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemCurrency
				|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemFurnitureItems
				|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemMods
				|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemResources
				|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemSanctuaryTypes
				|| GameServer.hasPerm(permLevel, "moderator"))
			if (GameServer.hasPerm(permLevel, "moderator"))
				commandMessages.add("giveitem <itemDefId> [<quantity>] [<player>]");
			else
				commandMessages.add("giveitem <itemDefId> [<quantity>]");
		if (GameServer.hasPerm(permLevel, "moderator"))
			commandMessages.add("takeitem <itemDefId> [<quantity>] [<player>]");
		if (client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemAvatars
				|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemClothes
				|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemCurrency
				|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemFurnitureItems
				|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemMods
				|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemResources
				|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemSanctuaryTypes
				|| GameServer.hasPerm(permLevel, "moderator"))
			if (GameServer.hasPerm(permLevel, "moderator"))
				commandMessages.add("removeallfiltereditems [<player>]");
			else
				commandMessages.add("removeallfiltereditems");
		commandMessages.add("questrewind <amount-of-quests-to-rewind>");

		// Dice
		commandMessages.add(
				"roll [<amount of rolls>][d<size>] (eg. roll d20 (1 roll of a d20 dice), roll 2d42 (2 rolls of a d42 dice), roll d20 (rolls a single d20 dice), roll 10 (rolls 10 d20 dices))");

		// OC proxying
		commandMessages.add(
				"oc register \"<name>\" \"[<trigger prefix>]message[<trigger suffix>]\" (eg. oc register \"Alice\" \"alice: message\")");
		commandMessages.add(
				"oc settrigger \"<name>\" \"[<trigger prefix>]message[<trigger suffix>]\" (eg. oc settrigger \"Alice\" \"alice: message\")");
		if (!GameServer.hasPerm(permLevel, "moderator"))
			commandMessages.add("oc rename \"<name>\" \"<new name>\"");
		else
			commandMessages.add("oc rename \"<name>\" \"<new name>\" [\"<player>\"]");
		if (!GameServer.hasPerm(permLevel, "moderator"))
			commandMessages.add("oc delete \"<name>\"");
		else
			commandMessages.add("oc delete \"<name>\" [\"<player>\"]");
		if (!GameServer.hasPerm(permLevel, "moderator"))
			commandMessages.add("oc bio \"<name>\" \"<new bio>\"");
		else
			commandMessages.add("oc bio \"<name>\" \"<new bio>\" [\"<player>\"]");
		if (!GameServer.hasPerm(permLevel, "moderator"))
			commandMessages.add("oc pronouns \"<name>\" \"<new pronouns>\"");
		else
			commandMessages.add("oc pronouns \"<name>\" \"<new pronouns>\" [\"<player>\"]");
		commandMessages.add("oc toggleprivate \"<name>\"");
		commandMessages.add("oc stickyproxy \"<name>\"");
		commandMessages.add("oc stickyoff");
		commandMessages.add("oc show \"<name>\" [\"<player>\"]");
		commandMessages.add("oc list [\"<player>\"]");
		if (!GameServer.hasPerm(permLevel, "moderator")) {
			commandMessages.add("listplayers");
		}

		// Add module commands
		ModuleCommandSyntaxListEvent evMCSL = new ModuleCommandSyntaxListEvent(commandMessages, client,
				client.getPlayer(), permLevel);
		EventBus.getInstance().dispatchEvent(evMCSL);

		// Add help if not empty
		if (!commandMessages.isEmpty())
			commandMessages.add("help");

		// Run command
		if (!commandMessages.isEmpty()) {
			// Parse command
			ArrayList<String> args = parseCommand(cmd);
			String cmdId = "";
			if (args.size() > 0) {
				cmdId = args.remove(0).toLowerCase();
				cmd = cmdId;

				// Run module command
				final String cmdIdentifier = cmd;
				ChatCommandEvent ev = new ChatCommandEvent(cmdId, args, client, client.getPlayer(), permLevel, t -> {
					systemMessage(t, cmdIdentifier, client);
				});
				EventBus.getInstance().dispatchEvent(ev);
				if (ev.isHandled())
					return true;

				if (cmdId.equals("givebasicmaterials")) {
					if (client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemResources
							|| GameServer.hasPerm(permLevel, "moderator")) {
						var onlinePlayer = client.getPlayer().getOnlinePlayerInstance();

						if (onlinePlayer != null) {
							var accessor = client.getPlayer().getSaveSpecificInventory().getItemAccessor(onlinePlayer);

							accessor.add("6691", 1000);
							accessor.add("6692", 1000);
							accessor.add("6693", 1000);
							accessor.add("6694", 1000);
							accessor.add("6695", 1000);
							accessor.add("6696", 1000);
							accessor.add("6697", 1000);
							accessor.add("6698", 1000);
							accessor.add("6699", 1000);
							accessor.add("6700", 1000);
							accessor.add("6701", 1000);
							accessor.add("6702", 1000);
							accessor.add("6703", 1000);
							accessor.add("6704", 1000);
							accessor.add("6705", 1000);

							// TODO: Check result
							systemMessage("You have been given 1000 of every basic material. Have fun!", cmd, client);
						}
						return true;
					}
				} else if (cmdId.equals("togglenameprefix")) {
					// Name prefix
					if (client.getPlayer().getSaveSharedInventory().containsItem("prefixdisabled")) {
						// Enable prefix
						client.getPlayer().getSaveSharedInventory().deleteItem("prefixdisabled");
						systemMessage(
								"Name prefix and color re-enabled, note this does not fully apply until you and other players relog.",
								cmd, client);
					} else {
						// Disable prefix
						client.getPlayer().getSaveSharedInventory().setItem("prefixdisabled", new JsonObject());
						systemMessage(
								"Name prefix and color disabled, please note this does not fully apply until you and other players relog.",
								cmd, client);
					}
					return true;
				} else if (cmdId.equals("givebasiccurrency")) {
					if (client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemCurrency
							|| GameServer.hasPerm(permLevel, "moderator")) {
						var onlinePlayer = client.getPlayer().getOnlinePlayerInstance();

						if (onlinePlayer != null) {
							var accessor = client.getPlayer().getSaveSpecificInventory().getCurrencyAccessor();

							accessor.addLikes(onlinePlayer.client, 1000);
							accessor.addStarFragments(onlinePlayer.client, 1000);

							// TODO: Check result
							systemMessage("You have been given 1000 star fragments and likes. Have fun!", cmd, client);
						}
						return true;
					}
				} else if (cmdId.equals("restockinventory")) {
					if (client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemAvatars
							|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemClothes
							|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemCurrency
							|| client.getPlayer().getSaveSpecificInventory()
									.getSaveSettings().allowGiveItemFurnitureItems
							|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemMods
							|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemResources
							|| client.getPlayer().getSaveSpecificInventory()
									.getSaveSettings().allowGiveItemSanctuaryTypes) {
						var onlinePlayer = client.getPlayer().getOnlinePlayerInstance();
						String cmdF = cmd;
						AsyncTaskManager.runAsync(() -> {
							systemMessage("Restocking inventory... Please be patient, your game may lag for a bit...",
									cmdF, client);
							Centuria.gameServer.stockInventory(onlinePlayer, client.getPlayer(),
									client.getPlayer().getSaveSpecificInventory(), true);
							systemMessage("Your inventory has been restocked! Have fun!", cmdF, client);
						});
						return true;
					}
				} else if (cmdId.equals("questrewind")) {
					if (args.size() < 1) {
						// Missing argument
						systemMessage("Missing argument: amount of quests to rewind", cmd, client);
						return true;
					}
					int questsToRewind = 0;
					try {
						questsToRewind = Integer.parseInt(args.get(0));
					} catch (NumberFormatException e) {
						// Missing argument
						systemMessage("Invalid argument: amount of quests to rewind: expected number", cmd, client);
						return true;
					}
					if (questsToRewind < 1) {
						// Missing argument
						systemMessage(
								"Invalid argument: amount of quests to rewind: expected a number greater or equal to one.\n\nTo restart the quest specify 1, 2 restarts the quest before the current one.",
								cmd, client);
						return true;
					}

					// Get current quest position
					String quest = QuestManager.getActiveQuest(client.getPlayer());
					int pos = QuestManager.getQuestPosition(quest);
					// FIXME: patch issue where this will break after completing all quests
					if (pos < questsToRewind) {
						// Missing argument
						systemMessage(
								"Invalid argument: amount of quests to rewind: number exceeds the amount of your completed quests.",
								cmd, client);
						return true;
					}

					// Rewind quests
					JsonObject obj = client.getPlayer().getSaveSpecificInventory().getAccessor()
							.findInventoryObjectByDefId("311", "22781");
					JsonObject progressionMap = obj.get("components").getAsJsonObject()
							.get("SocialExpanseLinearGenericQuestsCompletion").getAsJsonObject();
					JsonArray arr = progressionMap.get("completedQuests").getAsJsonArray();
					for (int i = 0; i < questsToRewind; i++) {
						arr.remove(arr.get(arr.size() - 1));
					}

					// Save
					client.getPlayer().getSaveSpecificInventory().setItem("311",
							client.getPlayer().getSaveSpecificInventory().getItem("311"));
					systemMessage("Success! Rewinded your quest log, '"
							+ QuestManager.getQuest(QuestManager.getActiveQuest(client.getPlayer())).name
							+ "' is now your active quest! Please log out and log back in to complete the process.",
							cmd, client);

					return true;
				} else if (cmdId.equals("roll")) {
					// Check size
					int amount = 1;
					int size = 20;
					if (args.size() >= 1) {
						String sizeStr = args.get(0);
						if (sizeStr.toLowerCase().contains("d")) {
							String amountStr = sizeStr.substring(0, sizeStr.toLowerCase().indexOf("d"));
							String maxStr = sizeStr.substring(sizeStr.toLowerCase().indexOf("d") + 1);
							if (!maxStr.matches("^-?[0-9]+$")) {
								// Invalid argument
								systemMessage("Invalid argument: size: not a valid number", cmd, client);
								return true;
							}
							if (!amountStr.isEmpty() && !amountStr.matches("^-?[0-9]+$")) {
								// Invalid argument
								systemMessage("Invalid argument: amount: not a valid number", cmd, client);
								return true;
							}
							if (!amountStr.isEmpty()) {
								try {
									amount = Integer.parseInt(amountStr);
								} catch (Exception e) {
									// Invalid argument
									systemMessage("Invalid argument: amount: not a valid number", cmd, client);
									return true;
								}
							}
							try {
								size = Integer.parseInt(maxStr);
							} catch (Exception e) {
								// Invalid argument
								systemMessage("Invalid argument: size: not a valid number", cmd, client);
								return true;
							}

						} else {
							try {
								amount = Integer.parseInt(sizeStr);
							} catch (Exception e) {
								// Invalid argument
								systemMessage("Invalid argument: amount: not a valid number", cmd, client);
								return true;
							}
						}
						if (size < 1) {
							// Invalid argument
							systemMessage("Invalid argument: size: cannot be less than one", cmd, client);
							return true;
						}
						if (amount < 1) {
							// Invalid argument
							systemMessage("Invalid argument: amount: cannot roll less than one dice", cmd, client);
							return true;
						}
						if (amount > 10) {
							// Invalid argument
							systemMessage("Invalid argument: amount: cannot roll more than 10 dices at once", cmd,
									client);
							return true;
						}
					}

					// Roll
					String result = "";
					if (amount != 1) {
						for (int i = 0; i < amount; i++) {
							if (!result.isEmpty())
								result += "\n";
							result += "Dice roll #" + (i + 1) + ": rolled " + rnd.nextInt(1, size + 1);
						}
					} else
						result = "Rolled " + rnd.nextInt(1, size + 1);

					// Send result
					systemMessage(result, cmd, client);
					return true;
				} else if (cmdId.equals("oc") && args.size() >= 1) {
					String task = args.get(0).toLowerCase();
					switch (task) {

					// Register
					case "register": {
						// Remove task argument
						args.remove(0);

						// Check arguments
						if (args.size() < 1) {
							// Missing argument
							systemMessage("Missing argument: name: requiring a OC name to register a OC",
									cmd + " " + task, client);
							return true;
						}

						// Get name
						String name = args.get(0);
						name = name.trim();
						if (name.isEmpty()) {
							// Missing argument
							systemMessage("Missing argument: name: requiring a OC name to register a OC",
									cmd + " " + task, client);
							return true;
						}
						if (!name.matches("^[A-Za-z0-9_\\-. ]+")) {
							// Invalid argument
							systemMessage(
									"Invalid argument: name: contains invalid characters, can only be alphanumeric, contain spaces, dots, dashes and underscores",
									cmd + " " + task, client);
							return true;
						}

						// Verify name with blacklist
						FilterResult res = TextFilterService.getInstance().filter(name, false, "USERNAMEFILTER");
						if (res.isMatch() && res.getSeverity().ordinal() >= FilterSeverity.USER_STRICT_MODE.ordinal()) {
							// Reply with error
							systemMessage("Invalid argument: name: this name was blocked as it may be inappropriate",
									cmd + " " + task, client);
							return true;
						}

						// Check arguments
						if (args.size() < 2) {
							// Missing argument
							systemMessage("Missing argument: trigger: the game needs to know when to use this OC.\n"
									+ "\n"
									+ "For a trigger, you need to create a template message, with the word 'message' to describe what the game must use as message content.\n"
									+ "\n"
									+ "Example: \"Alice: message\", usage example: \"alice: hi\", the chat would say hi as alice\n"
									+ "Another example: \"[[message]]\", usage example: \"[[some message]]\", the chat say \"some message\" as the OC tied to the trigger",
									cmd + " " + task, client);
							return true;
						}

						// Get trigger string
						String trigger = args.get(1);

						// Verify trigger
						if (!trigger.contains("message")) {
							// Invalid argument
							systemMessage(
									"Invalid argument: trigger: missing the word 'message', the game needs to know when to use this OC.\n"
											+ "\n"
											+ "For a trigger, you need to create a template message, with the word 'message' to describe what the game must use as message content.\n"
											+ "\n"
											+ "Example: \"Alice: message\", usage example: \"alice: hi\", the chat would say hi as alice\n"
											+ "Another example: \"[[message]]\", usage example: \"[[some message]]\", the chat say \"some message\" as the OC tied to the trigger",
									cmd + " " + task, client);
							return true;
						}

						// Create the trigger
						String prefix = trigger.substring(0, trigger.indexOf("message"));
						String suffix = trigger.substring(trigger.indexOf("message") + "message".length());
						if (suffix.contains("message")) {
							// Invalid argument
							systemMessage(
									"Invalid argument: trigger: unable to determine what instance of 'message' to use as trigger delimiter! Please use the word only once in a trigger text!",
									cmd + " " + task, client);
							return true;
						}
						if (prefix.isEmpty() && suffix.isEmpty()) {
							// Invalid argument
							systemMessage(
									"Invalid argument: trigger: please make sure to not only have 'message' in your trigger text",
									cmd + " " + task, client);
							return true;
						}

						// Verify OC existence
						if (OcProxyInfo.ocExists(client.getPlayer(), name)) {
							// Already exists
							systemMessage("Invalid argument: name: you already have a OC named " + name
									+ ", use `oc show` to look it up", cmd + " " + task, client);
							return true;
						}

						// Create OC
						OcProxyInfo.saveOc(client.getPlayer(), name, prefix, suffix);

						// Reload
						client.reloadProxies();

						// Success!
						systemMessage("Successfully created the OC " + name + "!", cmd + " " + task, client);

						// Return
						return true;
					}

					// Trigger update
					case "settrigger": {
						// Remove task argument
						args.remove(0);

						// Check arguments
						if (args.size() < 1) {
							// Missing argument
							systemMessage("Missing argument: name: requiring a OC name to update a OC",
									cmd + " " + task, client);
							return true;
						}

						// Get name
						String name = args.get(0);
						name = name.trim();
						if (name.isEmpty()) {
							// Missing argument
							systemMessage("Missing argument: name: requiring a OC name to update a OC",
									cmd + " " + task, client);
							return true;
						}
						if (!name.matches("^[A-Za-z0-9_\\-. ]+")) {
							// Invalid argument
							systemMessage(
									"Invalid argument: name: contains invalid characters, can only be alphanumeric, contain spaces, dots, dashes and underscores",
									cmd + " " + task, client);
							return true;
						}

						// Check arguments
						if (args.size() < 2) {
							// Missing argument
							systemMessage("Missing argument: trigger: the game needs to know when to use this OC.\n"
									+ "\n"
									+ "For a trigger, you need to create a template message, with the word 'message' to describe what the game must use as message content.\n"
									+ "\n"
									+ "Example: \"Alice: message\", usage example: \"alice: hi\", the chat would say hi as alice\n"
									+ "Another example: \"[[message]]\", usage example: \"[[some message]]\", the chat say \"some message\" as the OC tied to the trigger",
									cmd + " " + task, client);
							return true;
						}

						// Get trigger string
						String trigger = args.get(1);

						// Verify trigger
						if (!trigger.contains("message")) {
							// Invalid argument
							systemMessage(
									"Invalid argument: trigger: missing the word 'message', the game needs to know when to use this OC.\n"
											+ "\n"
											+ "For a trigger, you need to create a template message, with the word 'message' to describe what the game must use as message content.\n"
											+ "\n"
											+ "Example: \"Alice: message\", usage example: \"alice: hi\", the chat would say hi as alice\n"
											+ "Another example: \"[[message]]\", usage example: \"[[some message]]\", the chat say \"some message\" as the OC tied to the trigger",
									cmd + " " + task, client);
							return true;
						}

						// Create the trigger
						String prefix = trigger.substring(0, trigger.indexOf("message"));
						String suffix = trigger.substring(trigger.indexOf("message") + "message".length());
						if (suffix.contains("message")) {
							// Invalid argument
							systemMessage(
									"Invalid argument: trigger: unable to determine what instance of 'message' to use as trigger delimiter! Please use the word only once in a trigger text!",
									cmd + " " + task, client);
							return true;
						}
						if (prefix.isEmpty() && suffix.isEmpty()) {
							// Invalid argument
							systemMessage(
									"Invalid argument: trigger: please make sure to not only have 'message' in your trigger text",
									cmd + " " + task, client);
							return true;
						}

						// Verify OC existence
						if (!OcProxyInfo.ocExists(client.getPlayer(), name)) {
							// Already exists
							systemMessage("Invalid argument: name: could not find the OC", cmd + " " + task, client);
							return true;
						}

						// Update OC
						OcProxyInfo oc = OcProxyInfo.ofUser(client.getPlayer(), name);
						oc.triggerPrefix = prefix;
						oc.triggerSuffix = suffix;
						OcProxyInfo.saveOc(client.getPlayer(), oc);

						// Reload
						client.reloadProxies();

						// Success!
						systemMessage("Successfully updated trigger of OC " + name + "!", cmd + " " + task, client);

						// Return
						return true;
					}

					// Rename
					case "rename": {
						// Remove task argument
						args.remove(0);

						// Check arguments
						if (args.size() < 1) {
							// Missing argument
							systemMessage("Missing argument: name: requiring a OC name to update a OC",
									cmd + " " + task, client);
							return true;
						}

						// Get name
						String name = args.get(0);
						name = name.trim();
						if (name.isEmpty()) {
							// Missing argument
							systemMessage("Missing argument: name: requiring a OC name to update a OC",
									cmd + " " + task, client);
							return true;
						}
						if (!name.matches("^[A-Za-z0-9_\\-. ]+")) {
							// Invalid argument
							systemMessage(
									"Invalid argument: name: contains invalid characters, can only be alphanumeric, contain spaces, dots, dashes and underscores",
									cmd + " " + task, client);
							return true;
						}

						// Check arguments
						if (args.size() < 2) {
							// Missing argument
							systemMessage("Missing argument: new name", cmd + " " + task, client);
							return true;
						}

						// Get new name
						String newName = args.get(1);
						newName = newName.trim();
						if (newName.isEmpty()) {
							// Missing argument
							systemMessage("Missing argument: new name", cmd + " " + task, client);
							return true;
						}
						if (!newName.matches("^[A-Za-z0-9_\\-. ]+")) {
							// Invalid argument
							systemMessage(
									"Invalid argument: new name: contains invalid characters, can only be alphanumeric, contain spaces, dots, dashes and underscores",
									cmd + " " + task, client);
							return true;
						}

						// Verify name with blacklist
						FilterResult res = TextFilterService.getInstance().filter(newName, false, "USERNAMEFILTER");
						if (res.isMatch() && res.getSeverity().ordinal() >= FilterSeverity.USER_STRICT_MODE.ordinal()) {
							// Reply with error
							systemMessage(
									"Invalid argument: new name: this name was blocked as it may be inappropriate",
									cmd + " " + task, client);
							return true;
						}

						// Find ID
						String player = client.getPlayer().getDisplayName();
						if (args.size() >= 3 && GameServer.hasPerm(permLevel, "moderator"))
							player = args.get(2);
						String uuid = AccountManager.getInstance().getUserByDisplayName(player);
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}

						// Find account
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);
						if (acc == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}

						// Verify OC existence
						if (!OcProxyInfo.ocExists(acc, name)) {
							// Already exists
							systemMessage("Invalid argument: name: could not find the OC", cmd + " " + task, client);
							return true;
						}
						if (OcProxyInfo.ocExists(acc, newName)) {
							// Already exists
							systemMessage("Invalid argument: new name: name already in use by another OC",
									cmd + " " + task, client);
							return true;
						}

						// Update OC
						OcProxyInfo oc = OcProxyInfo.ofUser(acc, name);
						OcProxyInfo.deleteOc(acc, name);
						oc.displayName = newName;
						OcProxyInfo.saveOc(acc, oc);

						// Reload
						client.reloadProxies();

						// Success!
						systemMessage("Successfully updated the name of OC " + name + "!", cmd + " " + task, client);

						// Return
						return true;
					}

					// Delete
					case "delete": {
						// Remove task argument
						args.remove(0);

						// Check arguments
						if (args.size() < 1) {
							// Missing argument
							systemMessage("Missing argument: name: requiring a OC name to delete a OC",
									cmd + " " + task, client);
							return true;
						}

						// Get name
						String name = args.get(0);
						name = name.trim();
						if (name.isEmpty()) {
							// Missing argument
							systemMessage("Missing argument: name: requiring a OC name to delete a OC",
									cmd + " " + task, client);
							return true;
						}
						if (!name.matches("^[A-Za-z0-9_\\-. ]+")) {
							// Invalid argument
							systemMessage(
									"Invalid argument: name: contains invalid characters, can only be alphanumeric, contain spaces, dots, dashes and underscores",
									cmd + " " + task, client);
							return true;
						}

						// Find ID
						String player = client.getPlayer().getDisplayName();
						if (args.size() >= 2 && GameServer.hasPerm(permLevel, "moderator"))
							player = args.get(1);
						String uuid = AccountManager.getInstance().getUserByDisplayName(player);
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}

						// Find account
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);
						if (acc == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}

						// Verify OC existence
						if (!OcProxyInfo.ocExists(acc, name)) {
							// Already exists
							systemMessage("Invalid argument: name: could not find the OC", cmd + " " + task, client);
							return true;
						}

						// Confirm
						if (!GameServer.hasPerm(permLevel, "moderator")
								&& (args.size() < 2 || !args.get(1).equals("confirm"))) {
							systemMessage("This command will delete the character " + name
									+ "!\nAre you sure you want to continue?\nAdd 'confirm' to the command to confirm your action.",
									cmd, client);
							return true;
						}

						// Delete OC
						OcProxyInfo.deleteOc(acc, name);

						// Reload
						client.reloadProxies();

						// Success!
						systemMessage("Successfully deleted the OC " + name + "!", cmd + " " + task, client);

						// Return
						return true;
					}

					// Bio
					case "bio": {
						// Remove task argument
						args.remove(0);

						// Check arguments
						if (args.size() < 1) {
							// Missing argument
							systemMessage("Missing argument: name: requiring a OC name to update a OC",
									cmd + " " + task, client);
							return true;
						}

						// Get name
						String name = args.get(0);
						name = name.trim();
						if (name.isEmpty()) {
							// Missing argument
							systemMessage("Missing argument: name: requiring a OC name to update a OC",
									cmd + " " + task, client);
							return true;
						}
						if (!name.matches("^[A-Za-z0-9_\\-. ]+")) {
							// Invalid argument
							systemMessage(
									"Invalid argument: name: contains invalid characters, can only be alphanumeric, contain spaces, dots, dashes and underscores",
									cmd + " " + task, client);
							return true;
						}

						// Bio argument
						// Check arguments
						if (args.size() < 2) {
							// Missing argument
							systemMessage("Missing argument: bio", cmd + " " + task, client);
							return true;
						}

						// Get bio
						String bio = args.get(1);
						bio = bio.trim();

						// Verify bio with filters
						FilterResult res = TextFilterService.getInstance().filter(bio, false);
						if (res.isMatch() && res.getSeverity().ordinal() >= FilterSeverity.USER_STRICT_MODE.ordinal()) {
							// Reply with error
							systemMessage("Invalid argument: bio: this bio was blocked as it may be inappropriate",
									cmd + " " + task, client);
							return true;
						}

						// Find ID
						String player = client.getPlayer().getDisplayName();
						if (args.size() >= 3 && GameServer.hasPerm(permLevel, "moderator"))
							player = args.get(2);
						String uuid = AccountManager.getInstance().getUserByDisplayName(player);
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}

						// Find account
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);
						if (acc == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}

						// Verify OC existence
						if (!OcProxyInfo.ocExists(acc, name)) {
							// Already exists
							systemMessage("Invalid argument: name: could not find the OC", cmd + " " + task, client);
							return true;
						}

						// Update OC
						OcProxyInfo oc = OcProxyInfo.ofUser(acc, name);
						oc.characterBio = bio;
						OcProxyInfo.saveOc(acc, oc);

						// Reload
						client.reloadProxies();

						// Success!
						systemMessage("Successfully updated the bio of " + name + "!", cmd + " " + task, client);

						// Return
						return true;
					}

					// Pronouns
					case "pronouns": {
						// Remove task argument
						args.remove(0);

						// Check arguments
						if (args.size() < 1) {
							// Missing argument
							systemMessage("Missing argument: name: requiring a OC name to update a OC",
									cmd + " " + task, client);
							return true;
						}

						// Get name
						String name = args.get(0);
						name = name.trim();
						if (name.isEmpty()) {
							// Missing argument
							systemMessage("Missing argument: name: requiring a OC name to update a OC",
									cmd + " " + task, client);
							return true;
						}
						if (!name.matches("^[A-Za-z0-9_\\-. ]+")) {
							// Invalid argument
							systemMessage(
									"Invalid argument: name: contains invalid characters, can only be alphanumeric, contain spaces, dots, dashes and underscores",
									cmd + " " + task, client);
							return true;
						}

						// Pronouns argument
						// Check arguments
						if (args.size() < 2) {
							// Missing argument
							systemMessage("Missing argument: pronouns", cmd + " " + task, client);
							return true;
						}

						// Get pronouns
						String pronouns = args.get(1);
						pronouns = pronouns.trim();

						// Verify pronouns with filters
						FilterResult res = TextFilterService.getInstance().filter(pronouns, false);
						if (res.isMatch() && res.getSeverity().ordinal() >= FilterSeverity.USER_STRICT_MODE.ordinal()) {
							// Reply with error
							systemMessage(
									"Invalid argument: pronouns: these pronouns were blocked as they may be inappropriate",
									cmd + " " + task, client);
							return true;
						}

						// Find ID
						String player = client.getPlayer().getDisplayName();
						if (args.size() >= 3 && GameServer.hasPerm(permLevel, "moderator"))
							player = args.get(2);
						String uuid = AccountManager.getInstance().getUserByDisplayName(player);
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}

						// Find account
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);
						if (acc == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}

						// Verify OC existence
						if (!OcProxyInfo.ocExists(acc, name)) {
							// Already exists
							systemMessage("Invalid argument: name: could not find the OC", cmd + " " + task, client);
							return true;
						}

						// Update OC
						OcProxyInfo oc = OcProxyInfo.ofUser(acc, name);
						oc.characterPronouns = pronouns;
						OcProxyInfo.saveOc(acc, oc);

						// Reload
						client.reloadProxies();

						// Success!
						systemMessage("Successfully updated the pronouns of " + name + "!", cmd + " " + task, client);

						// Return
						return true;
					}

					// Sticky proxy
					case "stickyproxy": {
						// Remove task argument
						args.remove(0);

						// Check arguments
						if (args.size() < 1) {
							// Missing argument
							systemMessage("Missing argument: name: requiring a OC name to enable sticky proxy mode",
									cmd + " " + task, client);
							return true;
						}

						// Get name
						String name = args.get(0);
						name = name.trim();
						if (name.isEmpty()) {
							// Missing argument
							systemMessage("Missing argument: name: requiring a OC name toenable sticky proxy mode",
									cmd + " " + task, client);
							return true;
						}
						if (!name.matches("^[A-Za-z0-9_\\-. ]+")) {
							// Invalid argument
							systemMessage(
									"Invalid argument: name: contains invalid characters, can only be alphanumeric, contain spaces, dots, dashes and underscores",
									cmd + " " + task, client);
							return true;
						}

						// Verify OC existence
						if (!OcProxyInfo.ocExists(client.getPlayer(), name)) {
							// Already exists
							systemMessage("Invalid argument: name: could not find the OC", cmd + " " + task, client);
							return true;
						}

						// Enable
						ProxySession session = client.getObject(ProxySession.class);
						if (session == null) {
							// Create if missing
							session = new ProxySession();
							client.addObject(session);
						}
						ProxySession.RoomProxySession roomSes = session.roomSessions.get(room);
						if (roomSes == null) {
							// Create session
							roomSes = new ProxySession.RoomProxySession();
							session.roomSessions.put(room, roomSes);
						}
						roomSes.sticky = true;
						roomSes.lastUsedOcName = name;

						// Reload
						client.reloadProxies();

						// Success!
						systemMessage("Successfully enabled sticky proxying mode!", cmd + " " + task, client);

						// Return
						return true;
					}

					// Sticky proxy
					case "stickyoff": {
						// Remove task argument
						args.remove(0);

						// Disable
						ProxySession session = client.getObject(ProxySession.class);
						if (session == null) {
							// Create if missing
							session = new ProxySession();
							client.addObject(session);
						}
						ProxySession.RoomProxySession roomSes = session.roomSessions.get(room);
						if (roomSes == null) {
							// Create session
							roomSes = new ProxySession.RoomProxySession();
							session.roomSessions.put(room, roomSes);
						}
						roomSes.sticky = false;

						// Reload
						client.reloadProxies();

						// Success!
						systemMessage("Successfully disabled sticky proxying mode!", cmd + " " + task, client);

						// Return
						return true;
					}

					// Privacy
					case "toggleprivate": {
						// Remove task argument
						args.remove(0);

						// Check arguments
						if (args.size() < 1) {
							// Missing argument
							systemMessage("Missing argument: name: requiring a OC name to update a OC",
									cmd + " " + task, client);
							return true;
						}

						// Get name
						String name = args.get(0);
						name = name.trim();
						if (name.isEmpty()) {
							// Missing argument
							systemMessage("Missing argument: name: requiring a OC name to update a OC",
									cmd + " " + task, client);
							return true;
						}
						if (!name.matches("^[A-Za-z0-9_\\-. ]+")) {
							// Invalid argument
							systemMessage(
									"Invalid argument: name: contains invalid characters, can only be alphanumeric, contain spaces, dots, dashes and underscores",
									cmd + " " + task, client);
							return true;
						}

						// Verify OC existence
						if (!OcProxyInfo.ocExists(client.getPlayer(), name)) {
							// Already exists
							systemMessage("Invalid argument: name: could not find the OC", cmd + " " + task, client);
							return true;
						}

						// Toggle
						OcProxyInfo oc = OcProxyInfo.ofUser(client.getPlayer(), name);
						oc.publiclyVisible = !oc.publiclyVisible;
						OcProxyInfo.saveOc(client.getPlayer(), oc);

						// Reload
						client.reloadProxies();

						// Success!
						systemMessage(
								"Privacy status of OC " + oc.displayName + ": "
										+ (oc.publiclyVisible ? "publicly visible" : "private"),
								cmd + " " + task, client);

						// Return
						return true;
					}

					// Show
					case "show": {
						// Remove task argument
						args.remove(0);

						// Check arguments
						if (args.size() < 1) {
							// Missing argument
							systemMessage("Missing argument: name: requiring a OC name to display OCs",
									cmd + " " + task, client);
							return true;
						}

						// Get name
						String name = args.get(0);
						name = name.trim();
						if (name.isEmpty()) {
							// Missing argument
							systemMessage("Missing argument: name: requiring a OC name to display OCs",
									cmd + " " + task, client);
							return true;
						}
						if (!name.matches("^[A-Za-z0-9_\\-. ]+")) {
							// Invalid argument
							systemMessage(
									"Invalid argument: name: contains invalid characters, can only be alphanumeric, contain spaces, dots, dashes and underscores",
									cmd + " " + task, client);
							return true;
						}

						// Find ID
						String player = client.getPlayer().getDisplayName();
						if (args.size() >= 2)
							player = args.get(1);
						String uuid = AccountManager.getInstance().getUserByDisplayName(player);
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}

						// Find account
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);
						if (acc == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}

						// Verify OC existence
						if (!OcProxyInfo.ocExists(acc, name) || (!OcProxyInfo.ofUser(acc, name).publiclyVisible
								&& !GameServer.hasPerm(permLevel, "moderator"))) {
							// Already exists
							systemMessage("Invalid argument: name: could not find the OC", cmd + " " + task, client);
							return true;
						}

						// Get OC
						OcProxyInfo oc = OcProxyInfo.ofUser(acc, name);

						// Load filter settings
						int filterSetting = 0;
						UserVarValue val = client.getPlayer().getSaveSpecificInventory().getUserVarAccesor()
								.getPlayerVarValue("9362", 0);
						if (val != null)
							filterSetting = val.value;

						// Filter bio
						FilterResult res = TextFilterService.getInstance().filter(oc.characterBio, filterSetting != 0);

						// Show overview
						systemMessage(
								"Overview of " + name + ":\n" + "\nName: " + oc.displayName + "\nPronouns: "
										+ oc.characterPronouns
										+ (uuid.equals(client.getPlayer().getAccountID())
												? "\nTrigger: </noparse><noparse>" + oc.triggerPrefix + "message"
														+ oc.triggerSuffix + "</noparse><noparse>"
												: "")
										+ "\n" + "\nBio:" + "\n" + res.getFilterResult(),
								cmd + " " + task, client);

						// Return
						return true;
					}

					// List
					case "list": {
						// Remove task argument
						args.remove(0);

						// Find ID
						String player = client.getPlayer().getDisplayName();
						if (args.size() >= 1)
							player = args.get(0);
						String uuid = AccountManager.getInstance().getUserByDisplayName(player);
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}

						// Find account
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);
						if (acc == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}

						// List ocs
						String msg = "List of OCs:";
						for (OcProxyInfo oc : OcProxyInfo.allOfUser(acc)) {
							// Check privacy
							if (oc.publiclyVisible || GameServer.hasPerm(permLevel, "moderator"))
								msg += "\n - " + oc.displayName;
						}
						systemMessage(msg, cmd, client);

						// Return
						return true;
					}

					default: {
						cmd = cmd + " " + task;
						break;
					}

					}
				}

				// Run system command
				if (GameServer.hasPerm(permLevel, "moderator")) {
					switch (cmdId) {

					//
					// Moderator commands below
					case "listplayerpps": {
						// Player packet-per-second rates of game
						String response = "Player packet-per-second rates for game clients:";
						for (Player plr : Centuria.gameServer.getPlayers())
							response += "\n - " + plr.account.getDisplayName() + " - current: "
									+ plr.client.getPacketsPerSecondRate() + " - peak: "
									+ plr.client.getHighestPacketsPerSecondRate();

						// Player packet-per-second rates of chat
						response += "\n\nChat packet-per-second rates:";
						for (ChatClient plr : Centuria.chatServer.getClients())
							response += "\n - " + plr.getPlayer().getDisplayName() + " - current: "
									+ plr.getPacketsPerSecondRate() + " - peak: "
									+ plr.getHighestPacketsPerSecondRate();

						// Player packet-per-second rates of voice chat
						response += "\n\nVoice chat packet-per-second rates:";
						for (VoiceChatClient plr : Centuria.voiceChatServer.getClients())
							response += "\n - " + plr.getPlayer().getDisplayName() + " - current: "
									+ plr.getPacketsPerSecondRate() + " - peak: "
									+ plr.getHighestPacketsPerSecondRate();

						// Send response
						systemMessage(response, cmd, client);
						return true;
					}

					case "coords":
					case "coordsof": {
						// Coordinate tool

						// Find player
						String player = client.getPlayer().getDisplayName();
						if (args.size() >= 1)
							player = args.get(0);
						String uuid = AccountManager.getInstance().getUserByDisplayName(player);
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);
						Player plr = acc.getOnlinePlayerInstance();
						if (plr == null || !plr.roomReady) {
							// Player not found
							systemMessage("Specified player is not online or not fully in world yet.", cmd, client);
							return true;
						}

						// Show
						systemMessage("Coordinates of " + acc.getDisplayName() + ":" //
								+ "\n - Room ID: " + plr.room //
								+ "\n - Level ID: " + plr.levelID //
								+ "\n - Level type: " + plr.levelType //
								+ "\n - Position XYZ: " + plr.lastPos.x + " " + plr.lastPos.y + " " + plr.lastPos.z //
								+ "\n - Rotation XYZW: " + plr.lastRot.x + " " + plr.lastRot.y + " " + plr.lastRot.z
								+ " " + plr.lastRot.w + " " //
								, cmd, client);
						return true;
					}

					case "tp": {
						// Teleport tool

						// Find player
						String player = client.getPlayer().getDisplayName();
						if (args.size() == 2 || args.size() >= 4) {
							player = args.get(0);
							args.remove(0);
						}
						String uuid = AccountManager.getInstance().getUserByDisplayName(player);
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);
						Player plr = acc.getOnlinePlayerInstance();
						if (plr == null || !plr.roomReady) {
							// Player not found
							systemMessage("Specified player is not online or not fully in world yet.", cmd, client);
							return true;
						}

						// Determine mode
						if (args.size() >= 3) {
							try {
								// Coordinates
								if (!args.get(0).matches("^[\\-0-9\\.]+$")) {
									systemMessage("Invalid argument: X: invalid value", cmd, client);
									return true;
								}
								if (!args.get(1).matches("^[\\-0-9\\.]+$")) {
									systemMessage("Invalid argument: Y: invalid value", cmd, client);
									return true;
								}
								if (!args.get(2).matches("^[\\-0-9\\.]+$")) {
									systemMessage("Invalid argument: Z: invalid value", cmd, client);
									return true;
								}
								plr.teleportDestination = null;
								plr.targetPos = new Vector3(Double.parseDouble(args.get(0)),
										Double.parseDouble(args.get(1)), Double.parseDouble(args.get(2)));
								plr.targetRot = plr.lastRot;
								plr.teleportToRoom(plr.levelID, plr.levelType, 0, plr.room,
										plr.room.startsWith("sanctuary_") ? plr.room.substring("sanctuary_".length())
												: "");
								systemMessage("Teleported " + plr.account.getDisplayName() + " to " + plr.targetPos.x
										+ " " + plr.targetPos.y + " " + plr.targetPos.z, cmd, client);
							} catch (Exception e) {
								e.printStackTrace();
								systemMessage("Error: " + e, cmd, client);
							}
						} else {
							// Player
							if (args.size() < 1) {
								systemMessage("Missing argument: target player or XYZ coordinates", cmd, client);
								return true;
							}
							String target = args.get(0);
							uuid = AccountManager.getInstance().getUserByDisplayName(target);
							if (uuid == null) {
								// Player not found
								systemMessage("Specified target account could not be located.", cmd, client);
								return true;
							}
							acc = AccountManager.getInstance().getAccount(uuid);
							Player plrTarget = acc.getOnlinePlayerInstance();
							if (plrTarget == null || !plrTarget.roomReady) {
								// Player not found
								systemMessage("Specified target player is not online or not fully in world yet.", cmd,
										client);
								return true;
							}

							// Handle teleport
							plr.teleportDestination = plrTarget.account.getAccountID();
							plr.targetPos = plrTarget.lastPos;
							plr.targetRot = plrTarget.lastRot;
							plr.teleportToRoom(plrTarget.levelID, plrTarget.levelType, 0, plrTarget.room,
									plrTarget.room.startsWith("sanctuary_")
											? plrTarget.room.substring("sanctuary_".length())
											: "");
							systemMessage("Teleported " + plr.account.getDisplayName() + " to "
									+ plrTarget.account.getDisplayName(), cmd, client);
						}
						return true;
					}

					case "tpserverto": {
						// Teleport tool

						// Player
						if (args.size() < 1) {
							systemMessage("Missing argument: target player", cmd, client);
							return true;
						}
						String target = args.get(0);
						String uuid = AccountManager.getInstance().getUserByDisplayName(target);
						if (uuid == null) {
							// Player not found
							systemMessage("Specified target account could not be located.", cmd, client);
							return true;
						}
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);
						Player plrTarget = acc.getOnlinePlayerInstance();
						if (plrTarget == null || !plrTarget.roomReady) {
							// Player not found
							systemMessage("Specified target player is not online or not fully in world yet.", cmd,
									client);
							return true;
						}

						// Find players in room
						for (Player plr : Centuria.gameServer.getPlayers()) {
							// Check ready
							if (!plr.roomReady || plr.account.getAccountID().equals(plrTarget.account.getAccountID()))
								continue;

							// Handle teleport
							plr.teleportDestination = plrTarget.account.getAccountID();
							plr.targetPos = plrTarget.lastPos;
							plr.targetRot = plrTarget.lastRot;
							plr.teleportToRoom(plrTarget.levelID, plrTarget.levelType, 0, plrTarget.room,
									plrTarget.room.startsWith("sanctuary_")
											? plrTarget.room.substring("sanctuary_".length())
											: "");
							systemMessage("Teleported " + plr.account.getDisplayName() + " to "
									+ plrTarget.account.getDisplayName(), cmd, client);
						}

						// Done
						systemMessage("Bulk-teleport completed!", cmd, client);
						return true;
					}

					case "tptosanctuary": {
						// Sanctuary teleport

						// Player
						if (args.size() < 1) {
							systemMessage("Missing argument: owner player name", cmd, client);
							return true;
						}
						String player = args.get(0);
						String uuid = AccountManager.getInstance().getUserByDisplayName(player);
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

						// Get current
						Player plrS = client.getPlayer().getOnlinePlayerInstance();

						// Check
						if (args.size() >= 2) {
							String target = args.get(1);
							uuid = AccountManager.getInstance().getUserByDisplayName(target);
							if (uuid == null) {
								// Player not found
								systemMessage("Specified target account could not be located.", cmd, client);
								return true;
							}
							CenturiaAccount acc2 = AccountManager.getInstance().getAccount(uuid);
							plrS = acc2.getOnlinePlayerInstance();
						}

						// Check
						if (plrS == null || !plrS.roomReady) {
							// Player not found
							systemMessage("Player to teleport is not online or not fully in world yet.", cmd, client);
							return true;
						}

						// Teleport
						plrS.teleportToSanctuary(acc.getAccountID(), true);

						// Done
						systemMessage("Successfully teleported " + plrS.account.getDisplayName() + " to "
								+ acc.getDisplayName() + "'s sanctuary", cmd, client);
						return true;
					}

					case "tpall": {
						// Teleport tool

						// Get current
						Player plrS = client.getPlayer().getOnlinePlayerInstance();
						if (plrS == null || !plrS.roomReady) {
							// Player not found
							systemMessage("Your player is not online or not fully in world yet.", cmd, client);
							return true;
						}

						// Find players in room
						for (Player plr : Centuria.gameServer.getPlayers()) {
							// Check ready
							if (!plr.roomReady)
								continue;

							// Determine mode
							if (args.size() >= 3) {
								try {
									// Verify room
									if (!plr.room.equals(plrS.room))
										continue;

									// Coordinates
									if (!args.get(0).matches("^[\\-0-9\\.]+$")) {
										systemMessage("Invalid argument: X: invalid value", cmd, client);
										return true;
									}
									if (!args.get(1).matches("^[\\-0-9\\.]+$")) {
										systemMessage("Invalid argument: Y: invalid value", cmd, client);
										return true;
									}
									if (!args.get(2).matches("^[\\-0-9\\.]+$")) {
										systemMessage("Invalid argument: Z: invalid value", cmd, client);
										return true;
									}
									plr.teleportDestination = null;
									plr.targetPos = new Vector3(Double.parseDouble(args.get(0)),
											Double.parseDouble(args.get(1)), Double.parseDouble(args.get(2)));
									plr.targetRot = plr.lastRot;
									plr.teleportToRoom(plr.levelID, plr.levelType, 0, plr.room,
											plr.room.startsWith("sanctuary_")
													? plr.room.substring("sanctuary_".length())
													: "");
									systemMessage("Teleported " + plr.account.getDisplayName() + " to "
											+ plr.targetPos.x + " " + plr.targetPos.y + " " + plr.targetPos.z, cmd,
											client);
								} catch (Exception e) {
									e.printStackTrace();
									systemMessage("Error: " + e, cmd, client);
								}
							} else {
								// Player
								if (args.size() < 1) {
									systemMessage("Missing argument: target player or XYZ coordinates", cmd, client);
									return true;
								}
								String target = args.get(0);
								String uuid = AccountManager.getInstance().getUserByDisplayName(target);
								if (uuid == null) {
									// Player not found
									systemMessage("Specified target account could not be located.", cmd, client);
									return true;
								}
								CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);
								Player plrTarget = acc.getOnlinePlayerInstance();
								if (plrTarget == null || !plrTarget.roomReady) {
									// Player not found
									systemMessage("Specified target player is not online or not fully in world yet.",
											cmd, client);
									return true;
								}

								// Verify room
								if (plr.account.getAccountID().equals(plrTarget.account.getAccountID())
										|| !plr.room.equals(plrS.room))
									continue;

								// Handle teleport
								plr.teleportDestination = plrTarget.account.getAccountID();
								plr.targetPos = plrTarget.lastPos;
								plr.targetRot = plrTarget.lastRot;
								plr.teleportToRoom(plrTarget.levelID, plrTarget.levelType, 0, plrTarget.room,
										plrTarget.room.startsWith("sanctuary_")
												? plrTarget.room.substring("sanctuary_".length())
												: "");
								systemMessage("Teleported " + plr.account.getDisplayName() + " to "
										+ plrTarget.account.getDisplayName(), cmd, client);
							}
						}

						// Done
						systemMessage("Bulk-teleport completed!", cmd, client);
						return true;
					}

					case "listplayers": {
						// Load spawn helper
						JsonObject helper = null;
						try {
							// Load helper
							InputStream strm = InventoryItemDownloadPacket.class.getClassLoader()
									.getResourceAsStream("spawns.json");
							helper = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8")).getAsJsonObject()
									.get("Maps").getAsJsonObject();
							strm.close();
						} catch (Exception e) {
						}

						// Locate suspicious clients from chat server
						ArrayList<String> mapLessClients = new ArrayList<String>();
						HashMap<CenturiaAccount, String> suspiciousClients = new HashMap<CenturiaAccount, String>();
						for (ChatClient cl : client.getServer().getClients()) {
							if (!mapLessClients.contains(cl.getPlayer().getAccountID())) {
								Player plr = cl.getPlayer().getOnlinePlayerInstance();
								if (plr == null) {
									// Check perms
									String permLevel2 = "member";
									if (cl.getPlayer().getSaveSharedInventory().containsItem("permissions")) {
										permLevel2 = cl.getPlayer().getSaveSharedInventory().getItem("permissions")
												.getAsJsonObject().get("permissionLevel").getAsString();
									}
									if (GameServer.hasPerm(permLevel2, "moderator"))
										continue;

									// No game server
									mapLessClients.add(cl.getPlayer().getAccountID());
									suspiciousClients.put(cl.getPlayer(), "no gameserver connection");
								} else if ((!plr.roomReady || plr.room == null) && !plr.levelID.equals("25280")) {
									// In limbo
									mapLessClients.add(cl.getPlayer().getAccountID());
									suspiciousClients.put(cl.getPlayer(), "limbo");
								}
							}
						}

						// Limbo clients from game server
						for (Player plr : Centuria.gameServer.getPlayers()) {
							if (!mapLessClients.contains(plr.account.getAccountID())) {
								if ((!plr.roomReady || plr.room == null) && !plr.levelID.equals("25280")) {
									// In limbo
									mapLessClients.add(plr.account.getAccountID());
									suspiciousClients.put(plr.account, "limbo");
								}
							}
						}

						// Find level IDs
						int ingame = 0;
						ArrayList<String> levelIDs = new ArrayList<String>();
						HashMap<Player, String> playersInRooms = new HashMap<Player, String>();
						for (Player plr : Centuria.gameServer.getPlayers()) {
							if (!mapLessClients.contains(plr.account.getAccountID())
									&& (plr.roomReady || plr.levelID.equals("25280"))) {
								// Increase count
								ingame++;

								// Add level if missing
								if (!levelIDs.contains(plr.levelID)) {
									levelIDs.add(plr.levelID);
								}

								// Add to room map
								if (plr.room != null)
									playersInRooms.put(plr, plr.room);
							}
						}

						// Build message
						String response = Centuria.gameServer.getPlayers().length + " player(s) connected, " + ingame
								+ " player(s) in world:";

						// Add each level
						ArrayList<String> playerIDs = new ArrayList<String>();
						for (String levelID : levelIDs) {
							// Determine map name
							String map = "UNKNOWN: " + levelID;
							if (levelID.equals("25280"))
								map = "Tutorial [" + levelID + "]";
							else if (helper.has(levelID))
								map = helper.get(levelID).getAsString() + " [" + levelID + "]";

							// Players
							for (Player plr : playersInRooms.keySet()) {
								if (!mapLessClients.contains(plr.account.getAccountID())
										&& !playerIDs.contains(plr.account.getAccountID())) {
									// Check
									if (plr.levelID.equals(levelID)) {
										// Add to response
										response += "\n - " + plr.account.getDisplayName() + " - " + map
												+ (plr.ghostMode ? " [GHOSTING]" : "");

										// Check suspicious
										Optional<CenturiaAccount> susAcc = suspiciousClients.keySet().stream()
												.filter(t -> t.getAccountID().equals(plr.account.getAccountID()))
												.findFirst();
										if (susAcc.isPresent()) {
											// Note it
											response += " [ WARNING: " + suspiciousClients.get(susAcc.get()) + " ]";
										}
									}
								}
							}
						}

						// Add suspicious clients
						if (suspiciousClients.size() != 0) {
							String susClientsStr = "";
							susClientsStr += "\n";
							susClientsStr += "\nSuspicious clients:";
							for (CenturiaAccount acc : suspiciousClients.keySet()) {
								// Add
								susClientsStr += "\n - " + acc.getDisplayName() + " [" + suspiciousClients.get(acc)
										+ "]";
							}
							response += susClientsStr;
						}

						// Send response
						systemMessage(response, cmd, client);
						return true;
					}
					case "heightenedsensitivity": {
						// Mute
						if (args.size() < 1) {
							systemMessage("Missing argument: enable/disable", cmd, client);
							return true;
						}

						switch (args.get(0).toLowerCase()) {

						case "enable": {
							String roomToAdjust = room;
							String reason = null;
							if (args.size() >= 2)
								reason = args.get(1);
							if (args.size() >= 3)
								roomToAdjust = args.get(2);
							ChatRoom roomInstance = client.getServer().getRoom(roomToAdjust);

							// Check room
							if (roomInstance == null) {
								systemMessage("Invalid argument: " + roomToAdjust
										+ ": room not recognized, please make sure the room actually has players in it",
										cmd, client);
								return true;
							}

							// Check
							HeightenedSensitivityFlags flags = roomInstance.getObject(HeightenedSensitivityFlags.class);
							if (flags == null) {
								flags = new HeightenedSensitivityFlags();
								flags.roomInst = roomInstance;
								roomInstance.addObject(flags);
							}
							if (flags.active) {
								systemMessage("Heightened sensitivity is already enabled for that room", cmd, client);
								return true;
							}

							// Activate
							flags.activationReason = reason;
							flags.disableAfter = -1;
							flags.wasAutoactivate = false;
							flags.activateHeightenedSensitivity(client.getServer());

							// Check if private
							if (roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT)) {
								EventBus.getInstance()
										.dispatchEvent(new MiscModerationEvent(
												"chatfilter.heightenedstrictness.activate",
												"Heightened sensitivity mode activated",
												Map.of("Private chat room", formatRoomName(client, roomInstance),
														"Room", roomInstance.getRoomID(), "Action",
														"activated heightened sensitivity mode"),
												client.getPlayer().getAccountID(), null));
							} else {
								EventBus.getInstance().dispatchEvent(
										new MiscModerationEvent("chatfilter.heightenedstrictness.activate",
												"Heightened sensitivity mode activated",
												Map.of("Room", formatRoomName(client, roomInstance), "Action",
														"activated heightened sensitivity mode"),
												client.getPlayer().getAccountID(), null));
							}
							systemMessage("Heightened sensitivity enabled for room " + roomInstance.getRoomID(), cmd,
									client);
							return true;
						}

						case "disable": {
							String roomToAdjust = room;
							if (args.size() >= 2)
								roomToAdjust = args.get(1);
							ChatRoom roomInstance = client.getServer().getRoom(roomToAdjust);

							// Check room
							if (roomInstance == null) {
								systemMessage("Invalid argument: " + roomToAdjust
										+ ": room not recognized, make sure the room actually has players in it", cmd,
										client);
								return true;
							}

							// Check
							HeightenedSensitivityFlags flags = roomInstance.getObject(HeightenedSensitivityFlags.class);
							if (flags == null) {
								flags = new HeightenedSensitivityFlags();
								flags.roomInst = roomInstance;
								roomInstance.addObject(flags);
							}
							if (!flags.active) {
								systemMessage("Heightened sensitivity is already disabled for that room", cmd, client);
								return true;
							}

							// Deactivate
							flags.deactivateHeightenedSensitivity(client.getServer(), false);

							// Check if private
							if (roomInstance.getType().equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT)) {
								EventBus.getInstance()
										.dispatchEvent(new MiscModerationEvent(
												"chatfilter.heightenedstrictness.deactivate",
												"Heightened sensitivity mode deactivated",
												Map.of("Private chat room", formatRoomName(client, roomInstance),
														"Room", roomInstance.getRoomID(), "Action",
														"deactivated heightened sensitivity mode"),
												client.getPlayer().getAccountID(), null));
							} else {
								EventBus.getInstance().dispatchEvent(
										new MiscModerationEvent("chatfilter.heightenedstrictness.deactivate",
												"Heightened sensitivity mode deactivated",
												Map.of("Room", formatRoomName(client, roomInstance), "Action",
														"deactivated heightened sensitivity mode"),
												client.getPlayer().getAccountID(), null));
							}
							systemMessage("Heightened sensitivity disabled for room " + roomInstance.getRoomID(), cmd,
									client);
							return true;
						}

						default: {
							systemMessage("Invalid argument: " + args.get(0) + ": expected enable or disable", cmd,
									client);
							return true;
						}
						}
					}
					case "mute": {
						// Mute
						if (args.size() < 1) {
							systemMessage("Missing argument: player", cmd, client);
							return true;
						}

						String reason = null;
						if (args.size() >= 2 && !args.get(1).matches("[0-9]+"))
							reason = args.get(1);
						else if (args.size() >= 3 && !args.get(2).matches("[0-9]+"))
							reason = args.get(2);
						else if (args.size() >= 4 && !args.get(3).matches("[0-9]+"))
							reason = args.get(3);
						else if (args.size() >= 5)
							reason = args.get(4);

						// Find player
						String uuid = AccountManager.getInstance().getUserByDisplayName(args.get(0));
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

						// Check rank
						if (acc.getSaveSharedInventory().containsItem("permissions")) {
							if ((GameServer
									.hasPerm(acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
											.get("permissionLevel").getAsString(), "developer")
									&& !GameServer.hasPerm(permLevel, "developer"))
									|| GameServer.hasPerm(acc.getSaveSharedInventory().getItem("permissions")
											.getAsJsonObject().get("permissionLevel").getAsString(), "admin")
											&& !GameServer.hasPerm(permLevel, "admin")) {
								systemMessage("Unable to mute higher-ranking users.", cmd, client);
								return true;
							}
						}

						// Check if banned
						if (acc.getSaveSharedInventory().containsItem("penalty") && acc.getSaveSharedInventory()
								.getItem("penalty").getAsJsonObject().get("type").getAsString().equals("ban")) {
							// Check ban
							systemMessage("Specified account is banned.", cmd, client);
							return true;
						}

						// Tempmute
						if (args.size() >= 2 && args.get(1).matches("[0-9]+")) {
							int minutes;
							int days = 0;
							int hours = 0;
							try {
								minutes = Integer.valueOf(args.get(1));
							} catch (Exception e) {
								systemMessage("Invalid value for argument: minutes", cmd, client);
								return true;
							}
							try {
								if (args.size() >= 3 && args.get(2).matches("[0-9]+")) {
									hours = Integer.valueOf(args.get(1));
									minutes = Integer.valueOf(args.get(2));
								}
							} catch (Exception e) {
								systemMessage("Invalid value for argument: hours", cmd, client);
								return true;
							}
							try {
								if (args.size() >= 4 && args.get(2).matches("[0-9]+")
										&& args.get(3).matches("[0-9]+")) {
									days = Integer.valueOf(args.get(1));
									hours = Integer.valueOf(args.get(2));
									minutes = Integer.valueOf(args.get(3));
								}
							} catch (Exception e) {
								systemMessage("Invalid value for argument: days", cmd, client);
								return true;
							}

							// Mute
							acc.mute(days, hours, minutes, client.getPlayer().getAccountID(), reason);
							systemMessage("Muted " + acc.getDisplayName() + ".", cmd, client);
						} else {
							// Permanent mute

							// Mute
							acc.permmute(client.getPlayer().getAccountID(), reason);
							systemMessage("Muted " + acc.getDisplayName() + ".", cmd, client);
						}

						return true;
					}
					case "tempban": {
						// Temporary ban
						if (args.size() < 1) {
							systemMessage("Missing argument: player", cmd, client);
							return true;
						} else if (args.size() < 2) {
							systemMessage("Missing argument: days", cmd, client);
							return true;
						}
						int days;
						try {
							days = Integer.valueOf(args.get(1));
						} catch (Exception e) {
							systemMessage("Invalid value for argument: days", cmd, client);
							return true;
						}

						String reason = null;
						if (args.size() >= 3)
							reason = args.get(2);

						// Find player
						String uuid = AccountManager.getInstance().getUserByDisplayName(args.get(0));
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

						// Check rank
						if (acc.getSaveSharedInventory().containsItem("permissions")) {
							if ((GameServer
									.hasPerm(acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
											.get("permissionLevel").getAsString(), "developer")
									&& !GameServer.hasPerm(permLevel, "developer"))
									|| GameServer.hasPerm(acc.getSaveSharedInventory().getItem("permissions")
											.getAsJsonObject().get("permissionLevel").getAsString(), "admin")
											&& !GameServer.hasPerm(permLevel, "admin")) {
								systemMessage("Unable to ban higher-ranking users.", cmd, client);
								return true;
							}
						}

						// Ban temporarily
						acc.tempban(days, client.getPlayer().getAccountID(), reason);
						systemMessage("Temporarily banned " + acc.getDisplayName() + ".", cmd, client);
						return true;
					}
					case "permban": {
						// Temporary ban
						if (args.size() < 1) {
							systemMessage("Missing argument: player", cmd, client);
							return true;
						}

						// Find player
						String uuid = AccountManager.getInstance().getUserByDisplayName(args.get(0));
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

						String reason = null;
						if (args.size() >= 2)
							reason = args.get(1);

						// Check rank
						if (acc.getSaveSharedInventory().containsItem("permissions")) {
							if ((GameServer
									.hasPerm(acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
											.get("permissionLevel").getAsString(), "developer")
									&& !GameServer.hasPerm(permLevel, "developer"))
									|| GameServer.hasPerm(acc.getSaveSharedInventory().getItem("permissions")
											.getAsJsonObject().get("permissionLevel").getAsString(), "admin")
											&& !GameServer.hasPerm(permLevel, "admin")) {
								systemMessage("Unable to ban higher-ranking users.", cmd, client);
								return true;
							}
						}

						// Ban permanently
						acc.ban(client.getPlayer().getAccountID(), reason);
						systemMessage("Permanently banned " + acc.getDisplayName() + ".", cmd, client);
						return true;
					}
					case "ipban": {
						// IP-ban command
						if (args.size() < 1) {
							systemMessage("Missing argument: player or address", cmd, client);
							return true;
						}

						String reason = null;
						if (args.size() >= 2)
							reason = args.get(1);

						// Check clearance
						if (!GameServer.hasPerm(permLevel, "admin")) {
							// Check arguments
							if (args.size() < 3) {
								systemMessage(
										"Error: clearance code required, please add a admin-issued clearance code to the command AFTER the reason for the IP ban.",
										cmd, client);
								return true;
							}

							// Check code
							while (true) {
								try {
									if (clearanceCodes.contains(args.get(2))) {
										clearanceCodes.remove(args.get(2));
									} else {
										systemMessage("Error: invalid clearance code.", cmd, client);
										return true;
									}
									break;
								} catch (ConcurrentModificationException e) {
								}
							}
						}

						// Find player
						for (Player plr : Centuria.gameServer.getPlayers()) {
							if (plr.account.getDisplayName().equals(args.get(0))) {
								// Check rank
								if (plr.account.getSaveSharedInventory().containsItem("permissions")) {
									if ((GameServer.hasPerm(
											plr.account.getSaveSharedInventory().getItem("permissions")
													.getAsJsonObject().get("permissionLevel").getAsString(),
											"developer") && !GameServer.hasPerm(permLevel, "developer"))
											|| GameServer.hasPerm(
													plr.account.getSaveSharedInventory().getItem("permissions")
															.getAsJsonObject().get("permissionLevel").getAsString(),
													"admin") && !GameServer.hasPerm(permLevel, "admin")) {
										systemMessage("Unable to ban higher-ranking users.", cmd, client);
										return true;
									}
								}

								// Ban IP
								plr.account.ipban(client.getPlayer().getAccountID(), reason);
								systemMessage("IP-banned " + plr.account.getDisplayName() + ".", cmd, client);
								return true;
							}
						}

						// Check if the inputted address is a IP addres
						try {
							InetAddress.getByName(args.get(0));

							// Ban the IP
							IpBanManager.getInstance().banIP(args.get(0));

							// Disconnect all with the given IP address (or attempt to)
							for (Player plr : Centuria.gameServer.getPlayers()) {
								// Get IP of player
								if (plr.client.getAddress().equals(args.get(0))) {
									// Ban player
									plr.account.ban(client.getPlayer().getAccountID(), reason);
								}
							}

							// Log completion
							systemMessage("Banned IP: " + args.get(0), cmd, client);

							return true;
						} catch (Exception e) {
						}

						// Player not found
						systemMessage("Player is not online.", cmd, client);
						return true;
					}
					case "staffroom": {
						// Teleport to staff room

						// Find online player
						for (Player plr : Centuria.gameServer.getPlayers()) {
							if (plr.account.getAccountID().equals(client.getPlayer().getAccountID())) {
								// Load the requested room
								RoomJoinPacket join = new RoomJoinPacket();
								join.levelType = 0; // World
								join.levelID = "1718";

								// Sync
								GameServer srv = (GameServer) plr.client.getServer();
								for (Player player : srv.getPlayers()) {
									if (plr.room != null && player.room != null && player.room.equals(plr.room)
											&& player != plr) {
										plr.destroyAt(player);
									}
								}

								// Assign room
								plr.roomReady = false;
								plr.pendingLevelID = "1718";
								plr.pendingRoom = "room_STAFFROOM";
								join.roomIdentifier = "room_STAFFROOM";

								// Send response
								plr.client.sendPacket(join);

								break;
							}
						}

						return true;
					}
					case "pardonip": {
						// Remove IP ban
						if (args.size() < 1) {
							systemMessage("Missing argument: ip", cmd, client);
							return true;
						}

						// Check clearance
						if (!GameServer.hasPerm(permLevel, "admin")) {
							// Check arguments
							if (args.size() < 2) {
								systemMessage(
										"Error: clearance code required, please add a admin-issued clearance code to the command.",
										cmd, client);
								return true;
							}

							// Check code
							while (true) {
								try {
									if (clearanceCodes.contains(args.get(1))) {
										clearanceCodes.remove(args.get(1));
									} else {
										systemMessage("Error: invalid clearance code.", cmd, client);
										return true;
									}
									break;
								} catch (ConcurrentModificationException e) {
								}
							}
						}

						// Check ip ban
						IpBanManager manager = IpBanManager.getInstance();
						if (manager.isIPBanned(args.get(0)))
							manager.unbanIP(args.get(0));

						systemMessage("Removed IP ban: " + args.get(0) + ".", cmd, client);
						return true;
					}
					case "tradetempban": {
						// Temporary trade-ban
						if (args.size() < 1) {
							systemMessage("Missing argument: player", cmd, client);
							return true;
						} else if (args.size() < 2) {
							systemMessage("Missing argument: days", cmd, client);
							return true;
						}
						int days;
						try {
							days = Integer.valueOf(args.get(1));
						} catch (Exception e) {
							systemMessage("Invalid value for argument: days", cmd, client);
							return true;
						}

						String reason = null;
						if (args.size() >= 3)
							reason = args.get(2);

						// Find player
						String uuid = AccountManager.getInstance().getUserByDisplayName(args.get(0));
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

						// Check rank
						if (acc.getSaveSharedInventory().containsItem("permissions")) {
							if ((GameServer
									.hasPerm(acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
											.get("permissionLevel").getAsString(), "developer")
									&& !GameServer.hasPerm(permLevel, "developer"))
									|| GameServer.hasPerm(acc.getSaveSharedInventory().getItem("permissions")
											.getAsJsonObject().get("permissionLevel").getAsString(), "admin")
											&& !GameServer.hasPerm(permLevel, "admin")) {
								systemMessage("Unable to trade-ban higher-ranking users.", cmd, client);
								return true;
							}
						}

						// Ban temporarily
						Trade.tradeBanTemp(acc, days, client.getPlayer().getAccountID(), reason);
						systemMessage("Temporarily trade-banned " + acc.getDisplayName() + ".", cmd, client);
						return true;
					}
					case "tradepermban": {
						// Temporary trade-ban
						if (args.size() < 1) {
							systemMessage("Missing argument: player", cmd, client);
							return true;
						}

						// Find player
						String uuid = AccountManager.getInstance().getUserByDisplayName(args.get(0));
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

						String reason = null;
						if (args.size() >= 2)
							reason = args.get(1);

						// Check rank
						if (acc.getSaveSharedInventory().containsItem("permissions")) {
							if ((GameServer
									.hasPerm(acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
											.get("permissionLevel").getAsString(), "developer")
									&& !GameServer.hasPerm(permLevel, "developer"))
									|| GameServer.hasPerm(acc.getSaveSharedInventory().getItem("permissions")
											.getAsJsonObject().get("permissionLevel").getAsString(), "admin")
											&& !GameServer.hasPerm(permLevel, "admin")) {
								systemMessage("Unable to trade-ban higher-ranking users.", cmd, client);
								return true;
							}
						}

						// Ban permanently
						Trade.tradeBanPermanent(acc, client.getPlayer().getAccountID(), reason);
						systemMessage("Permanently trade-banned " + acc.getDisplayName() + ".", cmd, client);
						return true;
					}
					case "tradepardon": {
						// Remove trade ban
						if (args.size() < 1) {
							systemMessage("Missing argument: player", cmd, client);
							return true;
						}

						// Find player
						String uuid = AccountManager.getInstance().getUserByDisplayName(args.get(0));
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

						// Reason
						String reason = null;
						if (args.size() >= 2) {
							reason = args.get(1);
						}

						// Check rank
						if (acc.getSaveSharedInventory().containsItem("permissions")) {
							if ((GameServer
									.hasPerm(acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
											.get("permissionLevel").getAsString(), "developer")
									&& !GameServer.hasPerm(permLevel, "developer"))
									|| GameServer.hasPerm(acc.getSaveSharedInventory().getItem("permissions")
											.getAsJsonObject().get("permissionLevel").getAsString(), "admin")
											&& !GameServer.hasPerm(permLevel, "admin")) {
								systemMessage("Unable to pardon higher-ranking users.", cmd, client);
								return true;
							}
						}

						// Pardon player
						Trade.tradeBanPardon(acc, client.getPlayer().getAccountID(), reason);
						systemMessage("Penalties removed from " + acc.getDisplayName() + ".", cmd, client);
						return true;
					}
					case "pardon": {
						// Remove all penalties
						if (args.size() < 1) {
							systemMessage("Missing argument: player", cmd, client);
							return true;
						}

						// Find player
						String uuid = AccountManager.getInstance().getUserByDisplayName(args.get(0));
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

						// Reason
						String reason = null;
						if (args.size() >= 2) {
							reason = args.get(1);
						}

						// Check rank
						if (acc.getSaveSharedInventory().containsItem("permissions")) {
							if ((GameServer
									.hasPerm(acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
											.get("permissionLevel").getAsString(), "developer")
									&& !GameServer.hasPerm(permLevel, "developer"))
									|| GameServer.hasPerm(acc.getSaveSharedInventory().getItem("permissions")
											.getAsJsonObject().get("permissionLevel").getAsString(), "admin")
											&& !GameServer.hasPerm(permLevel, "admin")) {
								systemMessage("Unable to pardon higher-ranking users.", cmd, client);
								return true;
							}
						}

						// Pardon player
						acc.pardon(client.getPlayer().getAccountID(), reason);
						systemMessage("Penalties removed from " + acc.getDisplayName() + ".", cmd, client);
						return true;
					}
					case "forcenamechange": {
						// Force name change command
						if (args.size() < 1) {
							systemMessage("Missing argument: player", cmd, client);
							return true;
						}

						// Find player
						String uuid = AccountManager.getInstance().getUserByDisplayName(args.get(0));
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);
						acc.forceNameChange();

						// Player found
						systemMessage(
								"Applied a name change requirement to the next login of " + acc.getDisplayName() + ".",
								cmd, client);
						return true;
					}
					case "changeothername": {
						// Name change command
						if (args.size() < 1) {
							systemMessage("Missing argument: player", cmd, client);
							return true;
						} else if (args.size() < 1) {
							systemMessage("Missing argument: new-name", cmd, client);
							return true;
						}

						// Find player
						String uuid = AccountManager.getInstance().getUserByDisplayName(args.get(0));
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}

						// Load info
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);
						String oldName = acc.getDisplayName();

						// Check rank
						if (acc.getSaveSharedInventory().containsItem("permissions")) {
							if ((GameServer
									.hasPerm(acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
											.get("permissionLevel").getAsString(), "developer")
									&& !GameServer.hasPerm(permLevel, "developer"))
									|| GameServer.hasPerm(acc.getSaveSharedInventory().getItem("permissions")
											.getAsJsonObject().get("permissionLevel").getAsString(), "admin")
											&& !GameServer.hasPerm(permLevel, "admin")) {
								systemMessage("Unable to rename higher-ranking users.", cmd, client);
								return true;
							}
						}

						// Check name lock
						if (AccountManager.getInstance().isDisplayNameInUse(args.get(1))) {
							// Failure
							systemMessage("Invalid value for argument: new-name: display name is in use", cmd, client);
							return true;
						}

						// Change name
						if (!acc.updateDisplayName(args.get(1))) {
							// Failure
							systemMessage("Invalid value for argument: new-name: invalid characters", cmd, client);
							return true;
						}

						// Prevent old name from being used
						AccountManager.getInstance().releaseDisplayName(oldName);
						AccountManager.getInstance().lockDisplayName(oldName, "-1");

						// Lock new name
						AccountManager.getInstance().lockDisplayName(args.get(1), acc.getAccountID());

						// Kick online player
						acc.kickDirect("SYSTEM", "Display name changed");
						systemMessage("Renamed " + oldName + " " + args.get(1) + ".", cmd, client);
						return true;
					}
					case "kick": {
						// Kick command
						if (args.size() < 1) {
							systemMessage("Missing argument: player", cmd, client);
							return true;
						}

						String reason = null;
						if (args.size() >= 2)
							reason = args.get(1);

						// Find player
						for (Player plr : Centuria.gameServer.getPlayers()) {
							if (plr.account.getDisplayName().equals(args.get(0))) {
								// Check rank
								if (plr.account.getSaveSharedInventory().containsItem("permissions")) {
									if ((GameServer.hasPerm(
											plr.account.getSaveSharedInventory().getItem("permissions")
													.getAsJsonObject().get("permissionLevel").getAsString(),
											"developer") && !GameServer.hasPerm(permLevel, "developer"))
											|| GameServer.hasPerm(
													plr.account.getSaveSharedInventory().getItem("permissions")
															.getAsJsonObject().get("permissionLevel").getAsString(),
													"admin") && !GameServer.hasPerm(permLevel, "admin")) {
										systemMessage("Unable to kick higher-ranking users.", cmd, client);
										return true;
									}
								}

								// Kick the player
								systemMessage("Kicked " + plr.account.getDisplayName() + ".", cmd, client);
								plr.account.kick(client.getPlayer().getAccountID(), reason);
								return true;
							}
						}

						// Find chat-only connection
						for (ChatClient cl : client.getServer().getClients())
							if (cl.getPlayer().getDisplayName().equals(args.get(0))) {
								// Check rank
								if (cl.getPlayer().getSaveSharedInventory().containsItem("permissions")) {
									if ((GameServer.hasPerm(
											cl.getPlayer().getSaveSharedInventory().getItem("permissions")
													.getAsJsonObject().get("permissionLevel").getAsString(),
											"developer") && !GameServer.hasPerm(permLevel, "developer"))
											|| GameServer.hasPerm(
													cl.getPlayer().getSaveSharedInventory().getItem("permissions")
															.getAsJsonObject().get("permissionLevel").getAsString(),
													"admin") && !GameServer.hasPerm(permLevel, "admin")) {
										systemMessage("Unable to kick higher-ranking users.", cmd, client);
										return true;
									}
								}

								// Disconnect
								cl.disconnect();
								systemMessage("Kicked " + cl.getPlayer().getDisplayName() + " from the chat server.",
										cmd, client);
								return true;
							}

						// Player not found
						systemMessage("Player is not online.", cmd, client);
						return true;
					}
					case "toggletpoverride": {
						// Override tp locks
						Player plr = client.getPlayer().getOnlinePlayerInstance();
						if (plr.overrideTpLocks) {
							plr.overrideTpLocks = false;
							systemMessage(
									"Teleport override disabled. The system will no longer ignore follower settings.",
									cmd, client);
							EventBus.getInstance().dispatchEvent(new MiscModerationEvent("tpoverride.disabled",
									"Teleport Override Disabled", Map.of("Teleport override status", "Disabled"),
									plr.account.getAccountID(), null));
						} else {
							// Check clearance
							if (!GameServer.hasPerm(permLevel, "admin")) {
								// Check arguments
								if (args.size() < 1) {
									systemMessage(
											"Error: clearance code required, please add a admin-issued clearance code to the command.",
											cmd, client);
									return true;
								}

								// Check code
								while (true) {
									try {
										if (clearanceCodes.contains(args.get(0))) {
											clearanceCodes.remove(args.get(0));
										} else {
											systemMessage("Error: invalid clearance code.", cmd, client);
											return true;
										}
										break;
									} catch (ConcurrentModificationException e) {
									}
								}
							}

							plr.overrideTpLocks = true;
							systemMessage("Teleport override enabled. The system will ignore follower settings.", cmd,
									client);
							EventBus.getInstance()
									.dispatchEvent(new MiscModerationEvent("tpoverride.enabled",
											"Teleport Override Enabled", Map.of("Teleport override status", "Enabled"),
											plr.account.getAccountID(), null));
						}
						return true;
					}
					case "toggleghostmode": {
						// Ghost mode
						Player plr = client.getPlayer().getOnlinePlayerInstance();
						if (plr.ghostMode) {
							plr.ghostMode = false;

							// Spawn for everyone in room
							GameServer server = (GameServer) plr.client.getServer();
							for (Player player : server.getPlayers()) {
								if (plr.room != null && player.room != null && player.room.equals(plr.room)
										&& player != plr) {
									plr.syncTo(player, WorldObjectMoverNodeType.InitPosition);
									Centuria.logger.debug(MarkerManager.getMarker("WorldReadyPacket"), "Syncing player "
											+ player.account.getDisplayName() + " to " + plr.account.getDisplayName());
								}
							}

							systemMessage("Ghost mode disabled. You are visible to everyone.", cmd, client);
							EventBus.getInstance()
									.dispatchEvent(new MiscModerationEvent("ghostmode.disabled", "Ghost Mode Disabled",
											Map.of("Ghost mode status", "Disabled"), plr.account.getAccountID(), null));
						} else {
							// Enable ghost mode
							plr.ghostMode = true;

							// Despawn for everyone in room
							GameServer server = (GameServer) plr.client.getServer();
							for (Player player : server.getPlayers()) {
								if (plr.room != null && player.room != null && player.room.equals(plr.room)
										&& player != plr && !player.hasModPerms) {
									plr.destroyAt(player);
									Centuria.logger.debug(MarkerManager.getMarker("WorldReadyPacket"),
											"Removing player " + player.account.getDisplayName() + " from "
													+ plr.account.getDisplayName());
								}
							}

							systemMessage("Ghost mode enabled. You are now invisible to non-moderators.", cmd, client);
							EventBus.getInstance()
									.dispatchEvent(new MiscModerationEvent("ghostmode.enabled", "Ghost Mode Enabled",
											Map.of("Ghost mode status", "Enabled"), plr.account.getAccountID(), null));
						}

						return true;
					}

					//
					// Admin commands below
					case "generateclearancecode": {
						// Check perms
						if (GameServer.hasPerm(permLevel, "admin")) {
							long codeLong = rnd.nextLong();
							String code = "";
							while (true) {
								while (codeLong < 10000)
									codeLong = rnd.nextLong();
								code = Long.toString(codeLong, 16);
								try {
									if (!clearanceCodes.contains(code))
										break;
								} catch (ConcurrentModificationException e) {
								}
								code = Long.toString(rnd.nextLong(), 16);
							}
							clearanceCodes.add(code);
							EventBus.getInstance()
									.dispatchEvent(new MiscModerationEvent("clearancecode.generated",
											"Admin Clearance Code Generated", Map.of(),
											client.getPlayer().getAccountID(), null));
							systemMessage("Clearance code generated: " + code + "\nIt will expire in 2 minutes.", cmd,
									client);
							final String cFinal = code;
							Thread th = new Thread(() -> {
								for (int i = 0; i < 12000; i++) {
									try {
										if (!clearanceCodes.contains(cFinal))
											return;
									} catch (ConcurrentModificationException e) {
									}
									try {
										Thread.sleep(10);
									} catch (InterruptedException e) {
									}
								}
								clearanceCodes.remove(cFinal);
							}, "Clearance code expiry");
							th.setDaemon(true);
							th.start();
							return true;
						}
					}
					case "makeadmin": {
						// Check perms
						if (GameServer.hasPerm(permLevel, "admin")) {
							// Permanent ban
							if (args.size() < 1) {
								systemMessage("Missing argument: player", cmd, client);
								return true;
							}

							// Find player
							String uuid = AccountManager.getInstance().getUserByDisplayName(args.get(0));
							if (uuid == null) {
								// Player not found
								systemMessage("Specified account could not be located.", cmd, client);
								return true;
							}
							CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

							// Get permissions
							String permLevel2 = "member";
							if (acc.getSaveSharedInventory().containsItem("permissions")) {
								permLevel2 = acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
										.get("permissionLevel").getAsString();
							}

							// Check
							if (acc.getSaveSharedInventory().containsItem("permissions")) {
								if (GameServer
										.hasPerm(acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
												.get("permissionLevel").getAsString(), "developer")
										&& !GameServer.hasPerm(permLevel, "developer")) {
									systemMessage("Unable to demote higher-ranking users.", cmd, client);
									return true;
								}
							}

							// Make admin
							if (!acc.getSaveSharedInventory().containsItem("permissions"))
								acc.getSaveSharedInventory().setItem("permissions", new JsonObject());
							if (!acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
									.has("permissionLevel"))
								acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
										.remove("permissionLevel");
							acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
									.addProperty("permissionLevel", "admin");
							acc.getSaveSharedInventory().setItem("permissions",
									acc.getSaveSharedInventory().getItem("permissions"));

							// Find online player
							for (ChatClient plr : client.getServer().getClients()) {
								if (plr.getPlayer().getDisplayName().equals(args.get(0))) {
									// Update inventory
									plr.getPlayer().getSaveSharedInventory().setItem("permissions",
											acc.getSaveSharedInventory().getItem("permissions"));
									break;
								}
							}

							// Log
							EventBus.getInstance().dispatchEvent(new MiscModerationEvent("permissions.update",
									"Made " + acc.getDisplayName() + " administrator!",
									Map.of("Former permission level", permLevel2, "New permission level", "admin"),
									client.getPlayer().getAccountID(), acc));

							// Completed
							systemMessage("Made " + acc.getDisplayName() + " administrator.", cmd, client);
							return true;
						} else {
							break;
						}
					}
					case "makemoderator": {
						// Check perms
						if (GameServer.hasPerm(permLevel, "admin")) {
							// Permanent ban
							if (args.size() < 1) {
								systemMessage("Missing argument: player", cmd, client);
								return true;
							}

							// Find player
							String uuid = AccountManager.getInstance().getUserByDisplayName(args.get(0));
							if (uuid == null) {
								// Player not found
								systemMessage("Specified account could not be located.", cmd, client);
								return true;
							}
							CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

							// Get permissions
							String permLevel2 = "member";
							if (acc.getSaveSharedInventory().containsItem("permissions")) {
								permLevel2 = acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
										.get("permissionLevel").getAsString();
							}

							// Check
							if (acc.getSaveSharedInventory().containsItem("permissions")) {
								if (GameServer
										.hasPerm(acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
												.get("permissionLevel").getAsString(), "developer")
										&& !GameServer.hasPerm(permLevel, "developer")) {
									systemMessage("Unable to demote higher-ranking users.", cmd, client);
									return true;
								}
							}

							// Make moderator
							if (!acc.getSaveSharedInventory().containsItem("permissions"))
								acc.getSaveSharedInventory().setItem("permissions", new JsonObject());
							if (!acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
									.has("permissionLevel"))
								acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
										.remove("permissionLevel");
							acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
									.addProperty("permissionLevel", "moderator");
							acc.getSaveSharedInventory().setItem("permissions",
									acc.getSaveSharedInventory().getItem("permissions"));

							// Find online player
							for (ChatClient plr : client.getServer().getClients()) {
								if (plr.getPlayer().getDisplayName().equals(args.get(0))) {
									// Update inventory
									plr.getPlayer().getSaveSharedInventory().setItem("permissions",
											acc.getSaveSharedInventory().getItem("permissions"));
									break;
								}
							}

							// Log
							EventBus.getInstance().dispatchEvent(new MiscModerationEvent("permissions.update",
									"Made " + acc.getDisplayName() + " moderator!",
									Map.of("Former permission level", permLevel2, "New permission level", "moderator"),
									client.getPlayer().getAccountID(), acc));

							// Completed
							systemMessage("Made " + acc.getDisplayName() + " moderator.", cmd, client);
							return true;
						} else {
							break;
						}
					}
					case "removeperms": {
						// Check perms
						if (GameServer.hasPerm(permLevel, "admin")) {
							// Permanent ban
							if (args.size() < 1) {
								systemMessage("Missing argument: player", cmd, client);
								return true;
							}

							// Find player
							String uuid = AccountManager.getInstance().getUserByDisplayName(args.get(0));
							if (uuid == null) {
								// Player not found
								systemMessage("Specified account could not be located.", cmd, client);
								return true;
							}
							CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

							// Get permissions
							String permLevel2 = "member";
							if (acc.getSaveSharedInventory().containsItem("permissions")) {
								permLevel2 = acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
										.get("permissionLevel").getAsString();
							}

							// Take permissions away
							if (acc.getSaveSharedInventory().containsItem("permissions")) {
								if (GameServer
										.hasPerm(acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
												.get("permissionLevel").getAsString(), "developer")
										&& !GameServer.hasPerm(permLevel, "developer")) {
									systemMessage("Unable to remove permissions from higher-ranking users.", cmd,
											client);
									return true;
								}
								acc.getSaveSharedInventory().deleteItem("permissions");
							}

							// Find online player
							for (ChatClient plr : client.getServer().getClients()) {
								if (plr.getPlayer().getDisplayName().equals(args.get(0))) {
									// Update inventory
									plr.getPlayer().getSaveSharedInventory().deleteItem("permissions");
									break;
								}
							}

							// Find online player
							for (Player plr : Centuria.gameServer.getPlayers()) {
								if (plr.account.getDisplayName().equals(args.get(0))) {
									// Update inventory
									plr.account.getSaveSharedInventory().deleteItem("permissions");
									plr.hasModPerms = false;
									break;
								}
							}

							// Log
							EventBus.getInstance().dispatchEvent(new MiscModerationEvent("permissions.update",
									"Removed all permissions from " + acc.getDisplayName() + "!",
									Map.of("Former permission level", permLevel2, "New permission level", "member"),
									client.getPlayer().getAccountID(), acc));

							// Completed
							systemMessage("Removed all permissions from " + acc.getDisplayName() + ".", cmd, client);
							return true;
						} else {
							break;
						}
					}
					case "updatewarning": {
						// Check perms
						if (GameServer.hasPerm(permLevel, "admin")) {
							if (args.size() < 1) {
								systemMessage("Missing argument: minutes-remaining", cmd, client);
								return true;
							}

							// Parse arguments
							int mins = 0;
							try {
								mins = Integer.valueOf(args.get(0));
							} catch (Exception e) {
								systemMessage("Invalid value for argument: minutes-remaining", cmd, client);
								return true;
							}

							// Warn everyone
							for (Player plr : Centuria.gameServer.getPlayers()) {
								if (mins == 1)
									plr.client.sendPacket("%xt%ua%-1%7390|1%");
								else
									plr.client.sendPacket("%xt%ua%-1%7391|" + mins + "%");
							}

							return true;
						} else {
							break;
						}
					}
					case "update": {
						// Check perms
						if (GameServer.hasPerm(permLevel, "admin")) {
							if (args.size() < 1) {
								systemMessage("Missing argument: minutes", cmd, client);
								return true;
							}

							// Parse arguments
							int mins = 0;
							switch (args.get(0)) {
							case "120":
								mins = 120;
								break;
							case "60":
								mins = 60;
								break;
							case "30":
								mins = 30;
								break;
							case "15":
								mins = 15;
								break;
							case "10":
								mins = 10;
								break;
							case "5":
								mins = 5;
								break;
							case "3":
								mins = 3;
								break;
							case "1":
								mins = 1;
								break;
							default:
								systemMessage(
										"Invalid value for argument: minutes-remaining: expected: 1, 3, 5, 10, 15, 30, 60 or 120",
										cmd, client);
								return true;
							}

							// Run timer
							if (Centuria.runUpdater(mins)) {
								systemMessage("Update timer has been started.", cmd, client);
							} else {
								systemMessage("Update timer is already running.", cmd, client);
							}

							return true;
						} else {
							break;
						}
					}
					case "retryautomaticupdate": {
						// Check perms
						if (GameServer.hasPerm(permLevel, "admin")) {
							// Retry update
							if (Centuria.hasUpdaterFailed() && !Centuria.staffFixedUpdateError) {
								systemMessage("Signalled the automatic updater to retry the update process!", cmd,
										client);
								Centuria.staffFixedUpdateError = true;
							} else
								systemMessage("The automatic updater has not encountered any errors. Nothing to do.",
										cmd, client);
							return true;
						} else {
							break;
						}
					}
					case "forceinstallupdate": {
						// Check perms
						if (GameServer.hasPerm(permLevel, "admin")) {
							// Retry update
							if (Centuria.hasUpdaterFailed() && !Centuria.staffFixedUpdateError) {
								systemMessage(
										"Signalled the automatic updater to retry the update process with conflict detection disabled!",
										cmd, client);
								Centuria.staffFixedUpdateError = true;
								Centuria.forceInstallUpdate = true;
							} else
								systemMessage("The automatic updater has not encountered any errors. Nothing to do.",
										cmd, client);
							return true;
						} else {
							break;
						}
					}
					case "cancelupdate": {
						// Check perms
						if (GameServer.hasPerm(permLevel, "admin")) {
							// Cancel update
							if (Centuria.cancelUpdate())
								systemMessage("Update restart cancelled.", cmd, client);
							else
								systemMessage("Update timer is not running.", cmd, client);
							return true;
						} else {
							break;
						}
					}
					case "updateshutdown": {
						// Check perms
						if (GameServer.hasPerm(permLevel, "admin")) {
							// Shut down the server
							Centuria.gameServer.shutdown = true;
							Centuria.gameServer.maintenance = true;
							PolyUpdaterClient.scheduleUpdate();
							EventBus.getInstance()
									.dispatchEvent(new ServerUpdateEvent(null, -1, new PolyCollection[0]));
							for (Player plr : Centuria.gameServer.getPlayers()) {
								// Dispatch event
								EventBus.getInstance().dispatchEvent(new AccountDisconnectEvent(plr.account,
										args.size() >= 1 ? args.get(0) : null, DisconnectType.SERVER_SHUTDOWN));
							}
							Centuria.updateShutdown(args.size() >= 1 ? args.get(0) : null);
							return true;
						} else {
							break;
						}
					}
					case "shutdownserver": {
						// Check perms
						if (GameServer.hasPerm(permLevel, "admin")) {
							// Shut down the server
							Centuria.logger.info("Shutting down server...");
							Centuria.gameServer.shutdown = true;
							Centuria.gameServer.maintenance = true;
							for (Player plr : Centuria.gameServer.getPlayers()) {
								// Dispatch event
								EventBus.getInstance().dispatchEvent(new AccountDisconnectEvent(plr.account,
										args.size() >= 1 ? args.get(0) : null, DisconnectType.SERVER_SHUTDOWN));
							}
							Centuria.disconnectPlayersForShutdown(args.size() >= 1 ? args.get(0) : null);
							System.exit(0);
							return true;
						} else {
							break;
						}
					}
					case "startmaintenance": {
						// Check perms
						if (GameServer.hasPerm(permLevel, "admin")) {
							// Enable maintenance mode
							Centuria.gameServer.maintenance = true;

							// Dispatch maintenance event
							EventBus.getInstance().dispatchEvent(new MaintenanceStartEvent());
							// Cancel if maintenance is disabled
							if (!Centuria.gameServer.maintenance)
								return true;

							// Disconnect everyone but the staff
							for (Player plr : Centuria.gameServer.getPlayers()) {
								if (!plr.account.getSaveSharedInventory().containsItem("permissions")
										|| !GameServer.hasPerm(
												plr.account.getSaveSharedInventory().getItem("permissions")
														.getAsJsonObject().get("permissionLevel").getAsString(),
												"admin")) {
									// Dispatch event
									EventBus.getInstance().dispatchEvent(
											new AccountDisconnectEvent(plr.account, null, DisconnectType.MAINTENANCE));

									plr.client.sendPacket("%xt%ua%-1%__FORCE_RELOGIN__%");
								}
							}

							// Wait a bit
							int i = 0;
							while (Stream.of(Centuria.gameServer.getPlayers())
									.filter(plr -> !plr.account.getSaveSharedInventory().containsItem("permissions")
											|| !GameServer.hasPerm(
													plr.account.getSaveSharedInventory().getItem("permissions")
															.getAsJsonObject().get("permissionLevel").getAsString(),
													"admin"))
									.findFirst().isPresent()) {
								i++;
								if (i == 30)
									break;

								try {
									Thread.sleep(1000);
								} catch (InterruptedException e) {
								}
							}
							for (Player plr : Centuria.gameServer.getPlayers()) {
								if (!plr.account.getSaveSharedInventory().containsItem("permissions")
										|| !GameServer.hasPerm(
												plr.account.getSaveSharedInventory().getItem("permissions")
														.getAsJsonObject().get("permissionLevel").getAsString(),
												"admin")) {
									// Disconnect from the game server
									plr.client.disconnect();

									// Disconnect it from the chat server
									for (ChatClient cl : client.getServer().getClients()) {
										if (cl.getPlayer().getAccountID().equals(plr.account.getAccountID())) {
											cl.disconnect();
										}
									}
								}
							}

							// Send message
							systemMessage("Maintenance mode enabled.", cmd, client);
							return true;
						} else {
							break;
						}
					}
					case "endmaintenance": {
						// Check perms
						if (GameServer.hasPerm(permLevel, "admin")) {
							// Disable maintenance mode
							Centuria.gameServer.maintenance = false;

							// Dispatch maintenance end event
							EventBus.getInstance().dispatchEvent(new MaintenanceEndEvent());

							// Cancel if maintenance is enabled
							if (Centuria.gameServer.maintenance)
								return true;

							systemMessage("Maintenance mode disabled.", cmd, client);
							return true;
						} else {
							break;
						}
					}

					//
					// Developer commands below..
					case "makedeveloper": {
						// Check perms
						if (GameServer.hasPerm(permLevel, "developer")) {
							// Permanent ban
							if (args.size() < 1) {
								systemMessage("Missing argument: player", cmd, client);
								return true;
							}

							// Find player
							String uuid = AccountManager.getInstance().getUserByDisplayName(args.get(0));
							if (uuid == null) {
								// Player not found
								systemMessage("Specified account could not be located.", cmd, client);
								return true;
							}
							CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

							// Get permissions
							String permLevel2 = "member";
							if (acc.getSaveSharedInventory().containsItem("permissions")) {
								permLevel2 = acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
										.get("permissionLevel").getAsString();
							}

							// Make developer
							if (!acc.getSaveSharedInventory().containsItem("permissions"))
								acc.getSaveSharedInventory().setItem("permissions", new JsonObject());
							if (!acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
									.has("permissionLevel"))
								acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
										.remove("permissionLevel");
							acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
									.addProperty("permissionLevel", "developer");
							acc.getSaveSharedInventory().setItem("permissions",
									acc.getSaveSharedInventory().getItem("permissions"));

							// Find online player
							for (ChatClient plr : client.getServer().getClients()) {
								if (plr.getPlayer().getDisplayName().equals(args.get(0))) {
									// Update inventory
									plr.getPlayer().getSaveSharedInventory().setItem("permissions",
											acc.getSaveSharedInventory().getItem("permissions"));
									break;
								}
							}

							// Log
							EventBus.getInstance().dispatchEvent(new MiscModerationEvent("permissions.update",
									"Made " + acc.getDisplayName() + " developer!",
									Map.of("Former permission level", permLevel2, "New permission level", "developer"),
									client.getPlayer().getAccountID(), acc));

							// Completed
							systemMessage("Made " + acc.getDisplayName() + " developer.", cmd, client);
							return true;
						} else {
							break;
						}
					}
					case "setplayertag": {
						// Tag management
						String id = "";
						if (args.size() < 1) {
							systemMessage("Missing argument: tag ID", cmd, client);
							return true;
						}

						// Parse arguments
						id = args.get(0);
						if (!id.matches("^[A-Za-z0-9_\\-. ]+$")) {
							// Invalid ID
							systemMessage("Invalid argument: ID: invalid tag ID", cmd, client);
							return true;
						}

						// Tag value
						JsonObject value = new JsonObject();
						if (args.size() >= 3) {
							try {
								value = JsonParser.parseString(args.get(2)).getAsJsonObject();
							} catch (Exception e) {
								// Invalid value
								systemMessage("Invalid argument: value: invalid JSON data", cmd, client);
								return true;
							}
						}

						// Find player
						String player = client.getPlayer().getDisplayName();
						if (args.size() >= 2)
							player = args.get(1);
						String uuid = AccountManager.getInstance().getUserByDisplayName(player);
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

						// Update
						acc.setAccountTag(id, value);
						systemMessage("Tag updated successfully.", cmd, client);
						return true;
					}
					case "removeplayertag": {
						// Tag management
						String id = "";
						if (args.size() < 1) {
							systemMessage("Missing argument: tag ID", cmd, client);
							return true;
						}

						// Parse arguments
						id = args.get(0);
						if (!id.matches("^[A-Za-z0-9_\\-. ]+$")) {
							// Invalid ID
							systemMessage("Invalid argument: ID: invalid tag ID", cmd, client);
							return true;
						}

						// Find player
						String player = client.getPlayer().getDisplayName();
						if (args.size() >= 2)
							player = args.get(1);
						String uuid = AccountManager.getInstance().getUserByDisplayName(player);
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

						// Update
						if (acc.getAccountTag(id) == null) {
							// Tag not found
							systemMessage("Specified tag could not be found for this player.", cmd, client);
							return true;
						}
						acc.deleteAccountTag(id);
						systemMessage("Tag removed successfully.", cmd, client);
						return true;
					}
					case "tpm": {
						try {
							// Teleports a player to a map.
							String defID = "";
							if (args.size() < 1) {
								systemMessage("Missing argument: teleport defID", cmd, client);
								return true;
							}

							// Parse arguments
							defID = args.get(0);
							String type = "0";
							if (args.size() > 1) {
								type = args.get(1);
							}

							// Teleport

							// Find player
							String player = client.getPlayer().getDisplayName();
							if (args.size() >= 3) {
								player = args.get(2);
							}
							String uuid = AccountManager.getInstance().getUserByDisplayName(player);
							if (uuid == null) {
								// Player not found
								systemMessage("Specified account could not be located.", cmd, client);
								return true;
							}
							CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);
							Player plr = acc.getOnlinePlayerInstance();
							if (plr != null)
								plr.teleportToRoom(defID, Integer.valueOf(type), -1, "room_" + defID, "");
							else {
								// Player not found
								systemMessage("Specified player is not online.", cmd, client);
								return true;
							}
						} catch (Exception e) {
							e.printStackTrace();
							systemMessage("Error: " + e, cmd, client);
						}

						return true;
					}
					case "xpinfo": {
						// XP info
						// Parse arguments
						String player = client.getPlayer().getDisplayName();
						if (args.size() > 0) {
							player = args.get(0);
						}
						String uuid = AccountManager.getInstance().getUserByDisplayName(player);
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

						// Display info
						try {
							systemMessage("XP details:\n" + "Level: " + acc.getLevel().getLevel() + "\nXP: "
									+ acc.getLevel().getCurrentXP() + " / " + acc.getLevel().getLevelupXPCount()
									+ "\nTotal XP: " + acc.getLevel().getTotalXP(), cmd, client);
						} catch (Exception e) {
							systemMessage("Error: " + e, cmd, client);
						}

						return true;
					}
					case "takexp": {
						// Take XP
						// Parse arguments
						String player = client.getPlayer().getDisplayName();
						if (args.size() < 1) {
							systemMessage("Missing argument: xp amount", cmd, client);
							return true;
						}
						if (args.size() > 1) {
							player = args.get(1);
						}
						String uuid = AccountManager.getInstance().getUserByDisplayName(player);
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

						// Take xp
						try {
							int xp = Integer.parseInt(args.get(0));
							if (xp < 0) {
								systemMessage("Invalid XP amount: " + xp, cmd, client);
								return true;
							}
							acc.getLevel().removeXP(xp);
							systemMessage("Removed " + xp + " XP from " + acc.getDisplayName() + ".", cmd, client);
						} catch (Exception e) {
							systemMessage("Error: " + e, cmd, client);
						}

						return true;
					}
					case "resetxp": {
						// Reset XP
						// Parse arguments
						String player = client.getPlayer().getDisplayName();
						if (args.size() > 0) {
							player = args.get(0);
						}
						String uuid = AccountManager.getInstance().getUserByDisplayName(player);
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

						// Take xp
						try {
							acc.getLevel().resetLevelXP();
							systemMessage("Resetted level XP of " + acc.getDisplayName() + ".", cmd, client);
						} catch (Exception e) {
							systemMessage("Error: " + e, cmd, client);
						}

						return true;
					}
					case "takelevels": {
						// Take levels
						// Parse arguments
						String player = client.getPlayer().getDisplayName();
						if (args.size() < 1) {
							systemMessage("Missing argument: levels to remove", cmd, client);
							return true;
						}
						if (args.size() > 1) {
							player = args.get(1);
						}
						String uuid = AccountManager.getInstance().getUserByDisplayName(player);
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

						// Take xp
						try {
							int levels = Integer.parseInt(args.get(0));
							if (acc.getLevel().getLevel() <= levels) {
								systemMessage("Invalid amount of levels to remove: " + levels + " (user is at "
										+ acc.getLevel().getLevel() + ")", cmd, client);
								return true;
							}
							acc.getLevel().setLevel(acc.getLevel().getLevel() - levels);
							systemMessage("Removed " + levels + " levels from " + acc.getDisplayName() + ".", cmd,
									client);
							acc.kickDirect(uuid, "Levels were changed, relog required.");
						} catch (Exception e) {
							systemMessage("Error: " + e, cmd, client);
						}

						return true;
					}
					case "addxp": {
						// Add XP
						// Parse arguments
						String player = client.getPlayer().getDisplayName();
						if (args.size() < 1) {
							systemMessage("Missing argument: xp amount", cmd, client);
							return true;
						}
						if (args.size() > 1) {
							player = args.get(1);
						}
						if (!player.equals("*")) {
							String uuid = AccountManager.getInstance().getUserByDisplayName(player);
							if (uuid == null) {
								// Player not found
								systemMessage("Specified account could not be located.", cmd, client);
								return true;
							}
							CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

							// Add xp
							try {
								int xp = Integer.parseInt(args.get(0));
								if (xp < 0) {
									systemMessage("Invalid XP amount: " + xp, cmd, client);
									return true;
								}
								acc.getLevel().addXP(xp);
								systemMessage("Given " + xp + " XP to " + acc.getDisplayName() + ".", cmd, client);
							} catch (Exception e) {
								systemMessage("Error: " + e, cmd, client);
								e.printStackTrace();
							}
						} else {
							final String cmdF = cmd;
							AccountManager.getInstance().runForAllAccounts(acc -> {
								// Add xp
								try {
									int xp = Integer.parseInt(args.get(0));
									if (xp < 0) {
										systemMessage("Invalid XP amount: " + xp, cmdF, client);
									}
									acc.getLevel().addXP(xp);
									systemMessage("Given " + xp + " XP to " + acc.getDisplayName() + ".", cmdF, client);
								} catch (Exception e) {
									systemMessage("Error: " + e, cmdF, client);
									e.printStackTrace();
								}
							});
							return true;
						}

						return true;
					}
					case "addlevels": {
						// Add levels
						// Parse arguments
						String player = client.getPlayer().getDisplayName();
						if (args.size() < 1) {
							systemMessage("Missing argument: levels to add", cmd, client);
							return true;
						}
						if (args.size() > 1) {
							player = args.get(1);
						}
						if (!player.equals("*")) {
							String uuid = AccountManager.getInstance().getUserByDisplayName(player);
							if (uuid == null) {
								// Player not found
								systemMessage("Specified account could not be located.", cmd, client);
								return true;
							}
							CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

							// Add levels
							try {
								int levels = Integer.parseInt(args.get(0));
								if (levels < 0) {
									systemMessage("Invalid XP amount: " + levels, cmd, client);
									return true;
								}
								acc.getLevel().addLevel(levels);
								systemMessage("Given " + levels + " levels to " + acc.getDisplayName() + ".", cmd,
										client);
							} catch (Exception e) {
								systemMessage("Error: " + e, cmd, client);
							}
						} else {
							final String cmdF = cmd;
							AccountManager.getInstance().runForAllAccounts(acc -> {
								// Add levels
								try {
									int levels = Integer.parseInt(args.get(0));
									if (levels < 0) {
										systemMessage("Invalid XP amount: " + levels, cmdF, client);
									}
									acc.getLevel().addLevel(levels);
									systemMessage("Given " + levels + " levels to " + acc.getDisplayName() + ".", cmdF,
											client);
								} catch (Exception e) {
									systemMessage("Error: " + e, cmdF, client);
								}
							});
							return true;
						}

						return true;
					}
					case "resetalllevels": {
						// Reset all levels
						// Parse arguments
						if (args.size() < 1 || !args.get(0).equals("confirm")) {
							systemMessage(
									"This command will wipe all xp of all players, are you sure you want to continue?\nAdd 'confirm' to the command to confirm your action.",
									cmd, client);
							return true;
						}

						final String cmdF = cmd;
						AccountManager.getInstance().runForAllAccounts(acc -> {
							// Reset level
							try {
								acc.getLevel().resetLevelXP();
								systemMessage("Resetted level XP of " + acc.getDisplayName() + ".", cmdF, client);
							} catch (Exception e) {
								systemMessage("Error: " + e, cmdF, client);
							}
						});

						return true;
					}
					case "srp": {
						// Sends a raw packet
						if (GameServer.hasPerm(permLevel, "developer")) {
							String packet = "";
							if (args.size() < 1) {
								systemMessage("Missing argument: raw-packet", cmd, client);
								return true;
							}

							// Parse arguments
							packet = args.get(0);
							String player = client.getPlayer().getDisplayName();
							if (args.size() > 1) {
								player = args.get(1);
							}
							String uuid = AccountManager.getInstance().getUserByDisplayName(player);
							if (uuid == null) {
								// Player not found
								systemMessage("Specified account could not be located.", cmd, client);
								return true;
							}
							CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

							// Send packet
							try {
								if (acc.getOnlinePlayerInstance() == null)
									systemMessage("Error: player not online", cmd, client);
								acc.getOnlinePlayerInstance().client.sendPacket(packet);
								systemMessage("Packet has been sent.", cmd, client);
							} catch (Exception e) {
								systemMessage("Error: " + e, cmd, client);
							}

							return true;
						}
					}
					case "questskip": {
						// Skips quests
						int count = 1;
						if (args.size() > 0)
							try {
								count = Integer.parseInt(args.get(0));
							} catch (Exception e) {
								return true;
							}

						// Parse arguments
						String player = client.getPlayer().getDisplayName();
						if (args.size() > 1) {
							player = args.get(1);
						}
						String uuid = AccountManager.getInstance().getUserByDisplayName(player);
						if (uuid == null) {
							// Player not found
							systemMessage("Specified account could not be located.", cmd, client);
							return true;
						}
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

						// Send packet
						try {
							if (acc.getOnlinePlayerInstance() == null) {
								systemMessage("Error: player not online", cmd, client);
								return true;
							}
							if (QuestManager.getActiveQuest(acc) == null) {
								systemMessage("Error: no further quests", cmd, client);
								return true;
							}
							int c = 0;
							for (int i = 0; i < count; i++) {
								c++;
								if (!QuestManager.finishQuest(acc.getOnlinePlayerInstance(),
										QuestManager.getActiveQuest(acc)))
									break;
							}
							systemMessage(
									"Skipped " + c + " quests, now at: "
											+ QuestManager.getQuest(QuestManager.getActiveQuest(acc)).name,
									cmd, client);
						} catch (Exception e) {
							systemMessage("Error: " + e, cmd, client);
						}

						return true;
					}
					case "takeitem": {
						try {
							String defID = "0";
							int quantity = 1;
							String player = "";
							String uuid = client.getPlayer().getAccountID();

							if (args.size() < 1) {
								systemMessage("Missing argument: itemDefId", cmd, client);
								return true;
							}

							defID = args.get(0);
							if (args.size() >= 2) {
								quantity = Integer.valueOf(args.get(1));
							}

							if (args.size() >= 3) {
								player = args.get(2);

								// check existence of player

								uuid = AccountManager.getInstance().getUserByDisplayName(player);
								if (uuid == null) {
									// Player not found
									systemMessage("Specified account could not be located.", cmd, client);
									return true;
								}
							}

							// funny stuff check
							if (quantity <= 0 || defID.equals("0")) {
								systemMessage("You cannot remove 0 or less quantity.", cmd, client);
								return true;
							}

							// find account
							CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

							// give item to the command sender..
							var onlinePlayer = acc.getOnlinePlayerInstance();
							var result = acc.getSaveSpecificInventory().getItemAccessor(onlinePlayer).remove(defID,
									quantity);

							if (result)
								systemMessage(
										"Removed " + acc.getDisplayName() + " " + quantity + " of item " + defID
												+ ", remaining: " + acc.getSaveSpecificInventory()
														.getItemAccessor(onlinePlayer).getCountOfItem(defID),
										cmd, client);
							else
								systemMessage("Failed to remove item.", cmd, client);
							return true;
						} catch (Exception e) {
							systemMessage("Error: " + e, cmd, client);
							return true;
						}
					}
					case "giveitem":
						try {
							String defID = "0";
							int quantity = 1;
							String player = "";
							String uuid = client.getPlayer().getAccountID();

							if (args.size() < 1) {
								systemMessage("Missing argument: itemDefId", cmd, client);
								return true;
							}

							defID = args.get(0);
							if (args.size() >= 2) {
								quantity = Integer.valueOf(args.get(1));
							}

							if (args.size() >= 3) {
								player = args.get(2);

								// check existence of player

								uuid = AccountManager.getInstance().getUserByDisplayName(player);
								if (uuid == null) {
									// Player not found
									systemMessage("Specified account could not be located.", cmd, client);
									return true;
								}
							}

							// funny stuff check
							if (quantity <= 0) {
								systemMessage("You cannot give 0 or less quantity.", cmd, client);
								return true;
							}

							// find account
							CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

							// give item to the command sender..
							var onlinePlayer = acc.getOnlinePlayerInstance();
							var result = acc.getSaveSpecificInventory().getItemAccessor(onlinePlayer).add(defID,
									quantity);

							if (result.length > 0)
								systemMessage(
										"Gave " + acc.getDisplayName() + " " + quantity + " of item " + defID + ".",
										cmd, client);
							else
								systemMessage("Failed to add item.", cmd, client);
							return true;
						} catch (Exception e) {
							systemMessage("Error: " + e, cmd, client);
							return true;
						}
					}
				}

				//
				// Remove filtered items command
				if (cmd.equals("removeallfiltereditems")) {
					// Check mode and permissions
					if (client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemAvatars
							|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemClothes
							|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemCurrency
							|| client.getPlayer().getSaveSpecificInventory()
									.getSaveSettings().allowGiveItemFurnitureItems
							|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemMods
							|| client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowGiveItemResources
							|| client.getPlayer().getSaveSpecificInventory()
									.getSaveSettings().allowGiveItemSanctuaryTypes
							|| GameServer.hasPerm(permLevel, "moderator")) {
						// Run command
						try {
							// Parse arguments if any and check perms
							String player = client.getPlayer().getDisplayName();
							if (args.size() > 1 && GameServer.hasPerm(permLevel, "moderator")) {
								player = args.get(1);
							}

							// Find ID
							String uuid = AccountManager.getInstance().getUserByDisplayName(player);
							if (uuid == null) {
								// Player not found
								systemMessage("Specified account could not be located.", cmd, client);
								return true;
							}

							// Find account
							CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);
							if (acc == null) {
								// Player not found
								systemMessage("Specified account could not be located.", cmd, client);
								return true;
							}

							// Load filter
							InputStream strm = InventoryItemDownloadPacket.class.getClassLoader()
									.getResourceAsStream("creativeitemfilter.json");
							JsonObject helper = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8"))
									.getAsJsonObject().get("Items").getAsJsonObject();
							strm.close();

							// Remove items
							int removedItems = 0;
							for (String id : helper.keySet()) {
								// Find how many exist in the inventory
								ItemAccessor accessor = acc.getSaveSpecificInventory()
										.getItemAccessor(acc.getOnlinePlayerInstance());
								String defID = id;
								int currentCount = accessor.getCountOfItem(defID);

								if (currentCount > 0) {
									// Remove items
									accessor.remove(defID, currentCount);
									removedItems += currentCount;
								}
							}

							// Show result
							systemMessage("Removed " + removedItems + " item" + (removedItems == 1 ? "" : "s")
									+ " from the inventory.\n\nNote: a relog may be required for this to take effect.",
									cmd, client);

							return true;
						} catch (Exception e) {
							systemMessage("Error: " + e, cmd, client);
							return true;
						}
					}
				}

				//
				// User giveitem command
				if (cmd.equals("giveitem")) {
					try {
						String defID = "0";
						int quantity = 1;
						String uuid = client.getPlayer().getAccountID();

						if (args.size() < 1) {
							systemMessage("Missing argument: itemDefId", cmd, client);
							return true;
						}

						defID = args.get(0);
						if (args.size() >= 2) {
							quantity = Integer.valueOf(args.get(1));
						}

						// funny stuff check
						if (quantity <= 0) {
							systemMessage("You cannot give 0 or less quantity of/or an item ID of 0 or below.", cmd,
									client);
							return true;
						}

						// check max item limit (hardcoded 100 for creative mode)
						int current = client.getPlayer().getSaveSpecificInventory().getItemAccessor(null)
								.getCountOfItem(defID);
						if (!client.getPlayer().getSaveSpecificInventory().getItemAccessor(null).isQuantityBased(defID)
								&& quantity + current > 100) {
							systemMessage("You cannot have more than 100 of that item via commands.", cmd, client);
							return true;
						}

						// check item
						if (ItemAccessor.getInventoryTypeOf(defID) == null
								|| (!ItemAccessor.getInventoryTypeOf(defID).equals("100")
										&& !ItemAccessor.getInventoryTypeOf(defID).equals("104")
										&& !ItemAccessor.getInventoryTypeOf(defID).equals("111")
										&& !ItemAccessor.getInventoryTypeOf(defID).equals("103")
										&& !ItemAccessor.getInventoryTypeOf(defID).equals("102")
										&& !ItemAccessor.getInventoryTypeOf(defID).equals("10")
										&& !ItemAccessor.getInventoryTypeOf(defID).equals("2")
										&& !ItemAccessor.getInventoryTypeOf(defID).equals("1")
										&& !ItemAccessor.getInventoryTypeOf(defID).equals("7"))) {
							systemMessage("Invalid item defID. Please make sure you can actually obtain this item.",
									cmd, client);
							return true;
						}

						// check perms and item type
						if ((ItemAccessor.getInventoryTypeOf(defID).equals("100") && !client.getPlayer()
								.getSaveSpecificInventory().getSaveSettings().allowGiveItemClothes)
								|| (ItemAccessor.getInventoryTypeOf(defID).equals("104") && !client.getPlayer()
										.getSaveSpecificInventory().getSaveSettings().allowGiveItemCurrency)
								|| (ItemAccessor.getInventoryTypeOf(defID).equals("111") && !client.getPlayer()
										.getSaveSpecificInventory().getSaveSettings().allowGiveItemClothes)
								|| (ItemAccessor.getInventoryTypeOf(defID).equals("103") && !client.getPlayer()
										.getSaveSpecificInventory().getSaveSettings().allowGiveItemResources)
								|| (ItemAccessor.getInventoryTypeOf(defID).equals("102") && !client.getPlayer()
										.getSaveSpecificInventory().getSaveSettings().allowGiveItemFurnitureItems)
								|| (ItemAccessor.getInventoryTypeOf(defID).equals("10") && !client.getPlayer()
										.getSaveSpecificInventory().getSaveSettings().allowGiveItemSanctuaryTypes)
								|| (ItemAccessor.getInventoryTypeOf(defID).equals("2") && !client.getPlayer()
										.getSaveSpecificInventory().getSaveSettings().allowGiveItemMods)
								|| (ItemAccessor.getInventoryTypeOf(defID).equals("1") && !client.getPlayer()
										.getSaveSpecificInventory().getSaveSettings().allowGiveItemAvatars)
								|| (ItemAccessor.getInventoryTypeOf(defID).equals("7") && !client.getPlayer()
										.getSaveSpecificInventory().getSaveSettings().allowGiveItemEnigmas)) {
							systemMessage("Invalid item defID. Please make sure you can actually obtain this item.",
									cmd, client);
							return true;
						}

						// find account
						CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);

						// give item to the command sender..
						var onlinePlayer = acc.getOnlinePlayerInstance();
						var result = acc.getSaveSpecificInventory().getItemAccessor(onlinePlayer).add(defID, quantity);

						if (result.length > 0)
							systemMessage("Gave " + acc.getDisplayName() + " " + quantity + " of item " + defID + ".",
									cmd, client);
						else
							systemMessage("Failed to add item.", cmd, client);
						return true;
					} catch (Exception e) {
						systemMessage("Error: " + e, cmd, client);
						return true;
					}
				}

				//
				// User
				if (cmd.equals("skipsanctuaryupgrade")) {
					if (client.getPlayer().getSaveSpecificInventory().getSaveSettings().allowSkipTwiggleWork
							|| GameServer.hasPerm(permLevel, "moderator")) {
						// Run command
						try {
							// Parse arguments if any and check perms
							String player = client.getPlayer().getDisplayName();
							if (args.size() > 1 && GameServer.hasPerm(permLevel, "moderator")) {
								player = args.get(1);
							}

							// Find ID
							String uuid = AccountManager.getInstance().getUserByDisplayName(player);
							if (uuid == null) {
								// Player not found
								systemMessage("Specified account could not be located.", cmd, client);
								return true;
							}

							// Find account
							CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);
							if (acc == null) {
								// Player not found
								systemMessage("Specified account could not be located.", cmd, client);
								return true;
							}

							// Find twiggles
							ArrayList<TwiggleItem> twiggles = new ArrayList<TwiggleItem>();
							var twiggleAccessor = acc.getSaveSpecificInventory().getTwiggleAccesor();
							for (TwiggleItem twiggle : twiggleAccessor.getAllTwiggles()) {
								if (twiggle.getTwiggleComponent().workType != TwiggleState.None
										&& (twiggle.getTwiggleComponent().workEndTime + (60 * 1000)) > System
												.currentTimeMillis()) {
									// We add one minute to the work time limit as
									// the game has a small desync issue with the
									// jamaaTime system, making it at most a minute
									// off of the actual server time, the client
									// would still display work is busy while its
									// not
									twiggles.add(twiggle);
								}
							}
							if (twiggles.size() == 0) {
								// No busy twiggles
								systemMessage("There are no in progress upgrades currently active.", cmd, client);
								return true;
							}

							// Apply
							var twiggleInv = acc.getSaveSpecificInventory().getItem("110").getAsJsonArray();
							for (TwiggleItem twiggle : twiggles) {
								TwiggleItem selectedTwiggle = null;
								int index = 0;
								for (var tw : twiggleInv) {
									TwiggleItem twiggleItem = new TwiggleItem();
									twiggleItem.fromJsonObject(tw.getAsJsonObject());
									if (twiggleItem.uuid.equals(twiggle.uuid)) {
										selectedTwiggle = twiggleItem;
										break;
									}
									index++;
								}
								if (selectedTwiggle == null)
									continue;

								// Set timer
								selectedTwiggle.getTwiggleComponent().workEndTime = 1;
								selectedTwiggle.getTimeStampComponent().stamp();

								// remove old twiggle item
								twiggleInv.remove(index);

								// add new twiggle item
								twiggleInv.add(selectedTwiggle.toJsonObject());

								// save to disk
								acc.getSaveSpecificInventory().setItem("110",
										acc.getSaveSpecificInventory().getItem("110"));
								acc.getSaveSpecificInventory().getAccessor().markChanged("110", selectedTwiggle.uuid);
							}

							// Send ILs
							for (String change : acc.getSaveSpecificInventory().getAccessor().getChangedInventories())
								acc.getSaveSpecificInventory().getAccessor()
										.transferUpdatedItemsToPlayer(acc.getOnlinePlayerInstance(), change);

							// Success
							systemMessage("Skipped sanctuary upgrade timers successfully!", cmd, client);
							return true;
						} catch (Exception e) {
							systemMessage("Error: " + e, cmd, client);
							return true;
						}
					}
				}

				//
				// User listplayers command
				if (cmd.equals("listplayers")) {
					// Load spawn helper
					JsonObject helper = null;
					try {
						// Load helper
						InputStream strm = InventoryItemDownloadPacket.class.getClassLoader()
								.getResourceAsStream("spawns.json");
						helper = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8")).getAsJsonObject()
								.get("Maps").getAsJsonObject();
						strm.close();
					} catch (Exception e) {
					}

					// Find level IDs
					int ingame = 0;
					HashMap<String, Integer> levelIDs = new HashMap<String, Integer>();
					for (Player plr : Centuria.gameServer.getPlayers()) {
						if (plr.roomReady || plr.levelID.equals("25280")) {
							// Increase count
							ingame++;

							// Add
							levelIDs.put(plr.levelID, levelIDs.getOrDefault(plr.levelID, 0) + 1);
						}
					}

					// Build message
					String message = "There are " + Centuria.gameServer.getPlayers().length
							+ " player(s) connected and " + ingame + " player(s) in world.";
					if (levelIDs.size() != 0) {
						message += "\n";
						message += "\n";
						for (String levelID : levelIDs.keySet()) {
							// Determine map name
							String map = "UNKNOWN: " + levelID;
							if (levelID.equals("25280"))
								map = "Tutorial";
							else if (helper.has(levelID))
								map = helper.get(levelID).getAsString();
							message += map + ": " + levelIDs.get(levelID) + " players.";
							message += "\n";
						}
					}
					systemMessage(message, cmdId, client);
					return true;
				}

				//
				// Help command
				if (cmd.equals("help")) {
					String message = "List of commands:";
					for (String commandMessage : commandMessages) {
						message += "\n - " + commandMessage;
					}
					message += "\n\nSymbol guide:";
					message += "\n[] = optional arguement";
					message += "\n<> = replace with arguement";
					message += "\nYou do not need the symbols on the command itself, its only for informational purposes.";
					systemMessage(message, cmdId, client);
					return true;
				}
			}

			// Command not found
			systemMessage("Command not recognized, use help for a list of commands", cmd, client);
			return true;
		}
		return false;
	}

	private static String formatRoomName(ChatClient client, ChatRoom roomObj) {
		if (client != null && roomObj.getType().equalsIgnoreCase(ChatRoomTypes.PRIVATE_CHAT)) {
			// Find recipient
			String recipient = client.getPlayer().getDisplayName();
			String[] participants = DMManager.getInstance().getDMParticipants(roomObj.getRoomID());
			for (String p : participants) {
				if (!p.equals(client.getPlayer().getAccountID())) {
					// Check type
					if (p.startsWith("plaintext:")) {
						recipient = p.substring("plaintext:".length());
						break;
					} else {
						CenturiaAccount a = AccountManager.getInstance().getAccount(p);
						if (a != null)
							recipient = "PM to " + a.getDisplayName();
					}
				}
			}
			return recipient;
		} else {
			// Check room format
			if (roomObj.getRoomID().startsWith("room_")) {
				// Public room

				// Load spawn helper
				JsonObject helper = null;
				try {
					// Load helper
					InputStream strm = InventoryItemDownloadPacket.class.getClassLoader()
							.getResourceAsStream("spawns.json");
					helper = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8")).getAsJsonObject()
							.get("Maps").getAsJsonObject();
					strm.close();
				} catch (Exception e) {
				}

				String levelId = roomObj.getRoomID().substring("room_".length());
				String map = "UNKNOWN: " + levelId;
				if (levelId.equals("25280"))
					map = "Tutorial";
				else if (helper.has(levelId))
					map = helper.get(levelId).getAsString() + " [" + levelId + "]";
				return map;
			} else if (roomObj.getRoomID().startsWith("sanctuary_")) {
				// Sanctuary
				String owner = roomObj.getRoomID().substring("sanctuary_".length());
				CenturiaAccount a = AccountManager.getInstance().getAccount(owner);
				if (a != null)
					owner = a.getDisplayName();
				return "Sanctuary of " + owner;
			}
			return roomObj.getRoomID();
		}
	}

	private void systemMessage(String message, String cmd, ChatClient client) {
		// Check moderator perms
		String permLevel = "member";
		if (client.getPlayer().getSaveSharedInventory().containsItem("permissions")) {
			permLevel = client.getPlayer().getSaveSharedInventory().getItem("permissions").getAsJsonObject()
					.get("permissionLevel").getAsString();
		}

		// Send response
		SendMessage res = new SendMessage();
		res.roomType = roomInstance.getType();
		res.room = roomInstance.getRoomID();
		res.message = "Issued chat command: " + cmd + ":\n[system] " + message;
		res.sourceWriter = client.getPlayer().getAccountID();
		res.sentAtWriter = LocalDateTime.now().toString();
		res.moderatorMessage = GameServer.hasPerm(permLevel, "moderator");
		client.sendPacket(res);

		// Log
		Centuria.logger.info(client.getPlayer().getDisplayName() + " executed chat command: " + cmd + ": " + message);
	}
}