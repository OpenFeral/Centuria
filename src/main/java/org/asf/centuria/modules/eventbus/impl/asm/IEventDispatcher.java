package org.asf.centuria.modules.eventbus.impl.asm;

import org.asf.centuria.modules.eventbus.EventObject;
import org.asf.centuria.modules.eventbus.IEventReceiver;

public interface IEventDispatcher {

	public void dispatch(IEventReceiver receiver, EventObject event);

}
