package org.asf.centuria.packets.xt.gameserver.relationship;

import java.io.IOException;

import org.asf.centuria.Centuria;
import org.asf.centuria.accounts.AccountManager;
import org.asf.centuria.data.XtReader;
import org.asf.centuria.data.XtWriter;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.networking.gameserver.GameServer;
import org.asf.centuria.networking.smartfox.SmartfoxClient;
import org.asf.centuria.packets.xt.IXtPacket;
import org.asf.centuria.social.SocialManager;

public class RelationshipFindPlayerPacket implements IXtPacket<RelationshipFindPlayerPacket> {

	private static final String PACKET_ID = "rffpu";

	private String name;
	private String accountId = "";
	private boolean success = false;

	@Override
	public RelationshipFindPlayerPacket instantiate() {
		return new RelationshipFindPlayerPacket();
	}

	@Override
	public String id() {
		return PACKET_ID;
	}

	@Override
	public void parse(XtReader reader) throws IOException {
		name = reader.readRemaining();
	}

	@Override
	public void build(XtWriter writer) throws IOException {
		writer.writeInt(DATA_PREFIX); // data prefix

		writer.writeBoolean(success); // success
		writer.writeString(accountId); // account ID

		writer.writeString(DATA_SUFFIX); // data suffix
	}

	@Override
	public boolean handle(SmartfoxClient client) throws IOException {
		// Find player

		if (Centuria.debugMode) {
			Centuria.logger.debug("[SOCIAL] [FindPlayer] Client to server ( playerName: " + name + " )");
		}

		boolean moderator = false;
		if (((Player) client.container).account.getSaveSharedInventory().containsItem("permissions")) {
			String permLevel = ((Player) client.container).account.getSaveSharedInventory().getItem("permissions")
					.getAsJsonObject().get("permissionLevel").getAsString();
			moderator = GameServer.hasPerm(permLevel, "moderator");
		}
		String id = AccountManager.getInstance().getUserByDisplayName(name);
		if (id == null || (!moderator && (AccountManager.getInstance().getAccount(id).isBanned() || SocialManager
				.getInstance().getPlayerIsBlocked(id, ((Player) client.container).account.getAccountID())))) {
			client.sendPacket(this);

			// log interaction details
			if (Centuria.debugMode) {
				Centuria.logger.debug("[SOCIAL] [FindPlayer] Server to client ( " + this.build() + " )");
			}

			return true; // Account not found
		}

		// Send response

		this.accountId = id;
		this.success = true;
		client.sendPacket(this);

		if (Centuria.debugMode) {
			Centuria.logger.debug("[SOCIAL] [FindPlayer] Server to client ( " + this.build() + " )");
		}

		return true;
	}

}
