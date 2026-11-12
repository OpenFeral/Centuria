package org.asf.centuria.packets.xt.gameserver.object;

import java.io.IOException;
import org.asf.centuria.Centuria;
import org.asf.centuria.data.XtReader;
import org.asf.centuria.data.XtWriter;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.interactions.InteractionManager;
import org.asf.centuria.interactions.NetworkedObjects;
import org.asf.centuria.interactions.dataobjects.NetworkedObject;
import org.asf.centuria.modules.eventbus.EventBus;
import org.asf.centuria.modules.events.interactions.InteractionDataRequestEvent;
import org.asf.centuria.networking.smartfox.SmartfoxClient;
import org.asf.centuria.packets.xt.IXtPacket;

public class ObjectAskResponsePacket implements IXtPacket<ObjectAskResponsePacket> {

	private static final String PACKET_ID = "oaskr";

	private String target;
	private int currentState;

	@Override
	public ObjectAskResponsePacket instantiate() {
		return new ObjectAskResponsePacket();
	}

	@Override
	public String id() {
		return PACKET_ID;
	}

	@Override
	public void parse(XtReader reader) throws IOException {
		target = reader.read();
		currentState = reader.readInt();
	}

	@Override
	public void build(XtWriter writer) throws IOException {
	}

	@Override
	public boolean handle(SmartfoxClient client) throws IOException {
		// Interaction data request
		Player plr = (Player) client.container;
		if (!plr.interactions.contains(target))
			return true; // Invalid interaction

		if (Centuria.debugMode) {
			Centuria.logger.debug("[INTERACTION] [ASKRESPONSE] Client to server (target: " + target + ", state: "
					+ currentState + ")");
		}

		// Find object
		NetworkedObject obj = NetworkedObjects.getObject(target);
		if (obj == null)
			return true;

		// Dispatch event
		InteractionDataRequestEvent req = new InteractionDataRequestEvent(plr, target, obj, currentState);
		EventBus.getInstance().dispatchEvent(req);
		if (req.isHandled())
			return true;

		// Handle request
		InteractionManager.handleInteractionDataRequest(plr, target, obj, currentState);
		return true;
	}

}
