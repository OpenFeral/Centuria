package org.asf.centuria.modules.eventbus.impl.asm;

import org.asf.centuria.modules.eventbus.SupplierEventObject;

public interface IStaticSupplierEventDispatcher {

	public Object dispatch(SupplierEventObject<?> event);

}
