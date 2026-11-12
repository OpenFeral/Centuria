package org.asf.centuria.modules.events.updates;

import org.asf.centuria.modules.eventbus.EventObject;
import org.asf.centuria.updater.collections.PolyCollection;

/**
 * 
 * Update Event - called when a server update is started (either automated or
 * manually invoked)
 * 
 * @author Sky Swimmer - AerialWorks Software Foundation
 *
 */
public class ServerUpdateEvent extends EventObject {

	private PolyCollection[] updatedCollections;
	private String updateVersion;
	private int minutes;

	public ServerUpdateEvent(String updateVersion, int minutes, PolyCollection[] updatedCollections) {
		this.updateVersion = updateVersion;
		this.minutes = minutes;
		this.updatedCollections = updatedCollections;
	}

	/**
	 * Retrieves the updated server collections
	 * 
	 * @return Array of PolyCollectionDef instances
	 */
	public PolyCollection[] getUpdatedCollections() {
		return updatedCollections.clone();
	}

	/**
	 * Checks if the version property is present in the event object
	 * 
	 * @return True if the property is present, false otherwise
	 */
	public boolean hasVersionInfo() {
		return updateVersion != null;
	}

	/**
	 * Retrieves the version that will be installed
	 * 
	 * @return Update version string or null
	 */
	public String getUpdateVersion() {
		return updateVersion;
	}

	/**
	 * Checks if the update is being rolled out with a timer enabled
	 * 
	 * @return True if the update has a shutdown timer, false if its instant reboot
	 */
	public boolean hasTimer() {
		return minutes != -1;
	}

	/**
	 * Retrieves the amount of minutes before the server reboots to update
	 * 
	 * @return Minutes before update or -1
	 */
	public int getTimeRemaining() {
		return minutes;
	}

}
