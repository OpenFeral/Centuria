package org.asf.centuria.modules.dependencies;

public interface IMavenRepositoryProvider {
	/**
	 * Defines the url of this repository
	 */
	public String serverBaseURL();

	/**
	 * Defines the priority of this repository, the lower the number, the earlier it
	 * is loaded
	 */
	public default int priority() {
		return 5;
	}
}
