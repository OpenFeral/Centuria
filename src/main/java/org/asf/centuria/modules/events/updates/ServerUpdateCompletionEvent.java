package org.asf.centuria.modules.events.updates;

import org.asf.centuria.modules.eventbus.EventObject;
import org.asf.centuria.updater.collections.PolyCollection;

/**
 * 
 * Update Completion Event - called when a server update finishes and when the
 * server is about to restart.
 * 
 * @author Sky Swimmer - AerialWorks Software Foundation
 *
 */
public class ServerUpdateCompletionEvent extends EventObject {

	private String updateVersion;
	private PolyCollection[] updatedCollections;

	public ServerUpdateCompletionEvent(String updateVersion, PolyCollection[] updatedCollections) {
		this.updateVersion = updateVersion;
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

}
