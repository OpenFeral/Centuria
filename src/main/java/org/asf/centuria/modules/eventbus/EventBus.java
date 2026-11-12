package org.asf.centuria.modules.eventbus;

import java.util.HashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import org.asf.centuria.modules.eventbus.impl.EventBusImpl;

/**
 * 
 * The EventBus system, used to register and dispatch events
 * 
 * @author Sky Swimmer
 *
 */
public abstract class EventBus {

	protected static EventBus instance = new EventBusImpl();
	private HashMap<Function<? extends SupplierEventObject<?>, ?>, Consumer<? extends EventObject>> handlers = new HashMap<Function<? extends SupplierEventObject<?>, ?>, Consumer<? extends EventObject>>();

	/**
	 * Retrieves the active event bus
	 * 
	 * @return EventBus instance
	 */
	public static EventBus getInstance() {
		return instance;
	}

	/**
	 * Adds event handlers
	 * 
	 * @param <T>          Event type
	 * @param eventClass   Event class
	 * @param eventHandler Event handler to add
	 * @since Beta 1.7.4
	 */
	public abstract <T extends EventObject> void addEventHandler(Class<T> eventClass, Consumer<T> eventHandler);

	/**
	 * Removes event handlers
	 * 
	 * @param <T>          Event type
	 * @param eventClass   Event class
	 * @param eventHandler Event handler to add
	 * @since Beta 1.7.4
	 */
	public abstract <T extends EventObject> void removeEventHandler(Class<T> eventClass, Consumer<T> eventHandler);

	/**
	 * Adds event handlers
	 * 
	 * @param <T>          Event type
	 * @param <T2>         Event result type
	 * @param eventClass   Event class
	 * @param eventHandler Event handler to add
	 * @since Beta 1.7.4
	 */
	public <T2, T extends SupplierEventObject<T2>> void addEventHandler(Class<T> eventClass,
			Function<T, T2> eventHandler) {
		Consumer<T> handler = ev -> {
			T2 ret = eventHandler.apply(ev);
			if (ret != null)
				ev.setResult(ret);
		};
		handlers.put(eventHandler, handler);
		addEventHandler(eventClass, handler);
	}

	/**
	 * Removes event handlers
	 * 
	 * @param <T>          Event type
	 * @param <T2>         Event result type
	 * @param eventClass   Event class
	 * @param eventHandler Event handler to add
	 * @since Beta 1.7.4
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public <T2, T extends SupplierEventObject<T2>> void removeEventHandler(Class<T> eventClass,
			Function<T, T2> eventHandler) {
		if (handlers.containsKey(eventHandler)) {
			removeEventHandler(eventClass, (Consumer) handlers.get(eventHandler));
			handlers.remove(eventHandler);
		}
	}

	/**
	 * Subscribes all events in a IEventReceiver object
	 * 
	 * @param receiver IEventReceiver to add
	 * @since Beta 1.7.4
	 */
	public abstract void addAllEventsFromReceiver(IEventReceiver receiver);

	/**
	 * Removes all subscribed events from a IEventReceiver object
	 * 
	 * @param receiver IEventReceiver to add
	 * @since Beta 1.7.4
	 */
	public abstract void removeAllEventsFromReceiver(IEventReceiver receiver);

	/**
	 * Subscribes all events in a IEventReceiver object, deprecated alias of Beta
	 * 1.7.4's addAllEventsFromReceiver
	 * 
	 * @param receiver IEventReceiver to add
	 * @deprecated Deprecated since Beta 1.7.4 EventBus overhaul update, use
	 *             addAllEventsFromReceiver(receiver) instead
	 */
	@Deprecated
	public void addEventReceiver(IEventReceiver receiver) {
		addAllEventsFromReceiver(receiver);
	}

	/**
	 * Dispatches an event
	 * 
	 * @param event Event to dispatch
	 */
	public abstract void dispatchEvent(EventObject event);

	/**
	 * Dispatches an event
	 * 
	 * @param event Event to dispatch
	 * @since Beta 1.7.4
	 */
	public <T2, T extends SupplierEventObject<T2>> T2 dispatchEvent(T event) {
		dispatchEvent((EventObject) event);
		return event.getResult();
	}

	/**
	 * Dispatches an event
	 * 
	 * @param event    Event to dispatch
	 * @param callback Method to call once dispatching finishes
	 * @since Beta 1.7.4
	 */
	public <T2, T extends SupplierEventObject<T2>> void dispatchEvent(T event, BiConsumer<T, T2> callback) {
		dispatchEvent((EventObject) event);
		callback.accept(event, event.getResult());
	}

	/**
	 * Creates a new event bus
	 * 
	 * @return New EventBus instance
	 * @since Beta 1.7.4
	 */
	public abstract EventBus createBus();

}
