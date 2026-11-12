package org.asf.centuria.modules.eventbus;

/**
 * 
 * Supplier event object - an event object capable of storing result values
 * 
 * @param <T> Event result type
 * 
 * @author Sky Swimmer
 */
public abstract class SupplierEventObject<T> extends EventObject {

	private T value;
	private boolean hasValue;

	/**
	 * Checks if a value is present
	 * 
	 * @return True if present, false otherwise
	 */
	public boolean hasResult() {
		return hasValue;
	}

	/**
	 * Retrieves the result value object
	 * 
	 * @return Result value object
	 */
	public T getResult() {
		return value;
	}

	/**
	 * Assigns the result value
	 * 
	 * @param value Result value to assign
	 */
	public void setResult(T value) {
		this.value = value;
		hasValue = true;
		setHandled();
	}

}
