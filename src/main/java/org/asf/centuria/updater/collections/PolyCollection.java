package org.asf.centuria.updater.collections;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class PolyCollection {

	private String id;
	private String channel;
	private String strategy;

	private String currentVersion;
	private String currentChangelogData;
	private JsonObject currentBuildManifest;

	private boolean newBuildInfoAvailable = false;
	private String newVersion;
	private String newChangelogData;
	private JsonObject newBuildManifest;

	private File packageCache;

	private boolean inited = false;
	private boolean installedLocally = false;

	private Logger logger;

	public PolyCollection(String id, String channel, String strategy, File packageCache) {
		this.id = id;
		this.strategy = strategy;
		this.channel = channel;
		this.packageCache = packageCache;
	}

	/**
	 * Initializes (INTERNAL)
	 */
	public void init(Logger logger) throws IOException {
		if (inited)
			throw new IllegalStateException("Already initialized");
		inited = true;

		// Check file
		if (!packageCache.exists())
			packageCache.mkdirs();

		// Log
		logger.info("Registered enabled collection: " + id + "...");
		this.logger = logger;

		// Get current version
		updateCollectionAndReset();
	}

	/**
	 * Updates the collection with local information and resets the update data
	 */
	public void updateCollectionAndReset() throws IOException {
		// Check installed
		logger.debug("Verifying if the collection " + id + " is currently installed...");
		File packageManifest = new File(packageCache, "manifest.json");
		File packageChangelog = new File(packageCache, "changelog.log");
		currentBuildManifest = null;
		currentVersion = null;
		currentChangelogData = null;
		installedLocally = false;
		newBuildManifest = null;
		newVersion = null;
		newChangelogData = null;
		newBuildInfoAvailable = false;
		if (packageManifest.exists()) {
			// Installed
			installedLocally = true;
			JsonObject manifest = JsonParser.parseString(Files.readString(packageManifest.toPath())).getAsJsonObject();
			currentVersion = manifest.get("collection_version").getAsString();
			if (manifest.has("collection_build_manifest") && !manifest.get("collection_build_manifest").isJsonNull())
				currentBuildManifest = manifest.get("collection_build_manifest").getAsJsonObject();
			if (packageChangelog.exists())
				currentChangelogData = Files.readString(packageChangelog.toPath());
			logger.debug("Collection " + id + " is installed locally, current version: " + getCurrentVersion());
		} else
			logger.debug("Collection " + id + " is not installed locally");
	}

	/**
	 * Marks the collection as updated
	 */
	public void markUpdated(String newVersion, JsonObject newBuildManifest, String newChangelogData) {
		this.newBuildManifest = newBuildManifest;
		this.newVersion = newVersion;
		this.newChangelogData = newChangelogData;
		newBuildInfoAvailable = true;
	}

	/**
	 * Checks if the collection has an update scheduled
	 * 
	 * @return True if an update is scheduled, false otherwise
	 */
	public boolean hasUpdateAvailable() {
		return newBuildInfoAvailable;
	}

	/**
	 * Retrieves the new build changelog, returns null if no update is available,
	 * may return null for scheduled updates too if the collection does not provide
	 * one
	 * 
	 * @return Current build changelog data or null if not installed before
	 */
	public String getNewChangelogData() {
		return newChangelogData;
	}

	/**
	 * Retrieves the new build manifest, returns null if no update is available, may
	 * return null for scheduled updates too if the collection does not provide one
	 * 
	 * @return Current build manifest object or null if there is no update
	 */
	public JsonObject getNewBuildManifest() {
		return newBuildManifest;
	}

	/**
	 * Retrieves the current installed version of this collection object, returns
	 * null if no update is available
	 * 
	 * @return Current collection version or null if there is no update
	 */
	public String getNewVersion() {
		return newVersion;
	}

	/**
	 * Retrieves the current build changelog, returns null for newly installing
	 * collections, may return null for installed packages too if the collection
	 * does not provide one
	 * 
	 * @return Current build changelog data or null if not installed before
	 */
	public String getCurrentChangelogData() {
		return currentChangelogData;
	}

	/**
	 * Retrieves the current build manifest, returns null for newly installing
	 * collections, may return null for installed packages too if the collection
	 * does not provide one
	 * 
	 * @return Current build manifest object or null if not installed before
	 */
	public JsonObject getCurrentBuildManifest() {
		return currentBuildManifest;
	}

	/**
	 * Retrieves the current installed version of this collection object, returns
	 * null for newly installing collections
	 * 
	 * @return Current collection version or null if not installed before
	 */
	public String getCurrentVersion() {
		return currentVersion;
	}

	/**
	 * Checks if the collection is installed locally
	 * 
	 * @return True if installed, false otherwise
	 */
	public boolean isInstalledLocally() {
		return installedLocally;
	}

	/**
	 * Retrieves the collection cache folder
	 * 
	 * @return Collection cache file instance
	 */
	public File getCollectionCache() {
		return packageCache;
	}

	/**
	 * Retrieves the collection ID
	 * 
	 * @return Collection ID string
	 */
	public String getId() {
		return id;
	}

	/**
	 * Retrieves the collection channel
	 * 
	 * @return Collection channel string
	 */
	public String getChannel() {
		return channel;
	}

	/**
	 * Retrieves the collection channel
	 * 
	 * @return Collection channel string
	 */
	public String getStrategy() {
		return strategy;
	}

}
