package org.asf.centuria.modules.eventbus.impl.asm;

import org.asf.centuria.modules.eventbus.IEventReceiver;
import org.asf.centuria.modules.eventbus.SupplierEventObject;

public interface ISupplierEventDispatcher {

	public Object dispatch(IEventReceiver receiver, SupplierEventObject<?> event);

}
