package org.asf.centuria.packets.xt.gameserver.object;

import java.io.IOException;
import java.util.ArrayList;

import org.asf.centuria.Centuria;
import org.asf.centuria.data.XtReader;
import org.asf.centuria.data.XtWriter;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.interactions.InteractionManager;
import org.asf.centuria.interactions.NetworkedObjects;
import org.asf.centuria.interactions.dataobjects.NetworkedObject;
import org.asf.centuria.interactions.dataobjects.StateInfo;
import org.asf.centuria.modules.eventbus.EventBus;
import org.asf.centuria.modules.events.interactions.InteractionSuccessEvent;
import org.asf.centuria.networking.smartfox.SmartfoxClient;
import org.asf.centuria.packets.xt.IXtPacket;

public class ObjectActionFinishPacket implements IXtPacket<ObjectActionFinishPacket> {

	private static final String PACKET_ID = "oaf";

	private String target;
	private int currentState;

	@Override
	public ObjectActionFinishPacket instantiate() {
		return new ObjectActionFinishPacket();
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
		// Interaction finish
		Player plr = (Player) client.container;
		if (!plr.interactions.contains(target))
			return true; // Invalid interaction

		if (Centuria.debugMode) {
			Centuria.logger.debug("[INTERACTION] [FINISH] Client to server (target: " + target + ")");
		}

		// Find object
		NetworkedObject obj = NetworkedObjects.getObject(target);
		if (obj == null)
			return true;

		// Controls if the resource will be destroyed
		boolean destroy = true;

		// Dispatch event
		InteractionSuccessEvent ev = new InteractionSuccessEvent(plr, target, obj, currentState);
		ev.setDestroyResource(destroy);
		EventBus.getInstance().dispatchEvent(ev);
		destroy = ev.shouldDestroyResource();
		if (!ev.isHandled()) {
			// Handle interaction
			destroy = InteractionManager.handleInteraction(plr, target, obj, currentState, destroy);
		}

		// Find state
		int nState = plr.states.getOrDefault(target, currentState);
		if (obj.stateInfo.containsKey(Integer.toString(nState)) || nState == -1)
			currentState = nState;

		// Send qcmd
		if (obj.stateInfo.containsKey(Integer.toString(currentState))) {
			ArrayList<StateInfo> states = obj.stateInfo.get(Integer.toString(currentState));
			plr.stateObjects.put(target, states);
			InteractionManager.runStates(states, plr, obj, target);
		}

		// Build response
		XtWriter pk = new XtWriter();
		pk.writeString("oaf");
		pk.writeInt(DATA_PREFIX); // Data prefix
		pk.writeString(target); // Interactable
		pk.writeInt(obj.primaryObjectInfo.type); // Type
		pk.writeString(destroy ? "2" : "0");
		pk.writeString(DATA_SUFFIX); // Data suffix
		client.sendPacket(pk.encode());

		return true;
	}
}
