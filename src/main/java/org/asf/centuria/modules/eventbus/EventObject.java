package org.asf.centuria.modules.eventbus;

/**
 * 
 * Abstract event object
 * 
 * @author Sky Swimmer
 *
 */
public abstract class EventObject {

	private boolean handled = false;

	/**
	 * Checks if the event has been marked as handled
	 * 
	 * @return True if the event has been handled, false otherwise
	 */
	public boolean isHandled() {
		return handled;
	}

	/**
	 * Marks the event as handled
	 */
	public void setHandled() {
		handled = true;
	}

	/**
	 * Called when the event object has been registered
	 * 
	 * @param bus Event bus that the event was registered to
	 * @since Beta 1.7.4
	 */
	public void onRegister(EventBus bus) {
	}

}
