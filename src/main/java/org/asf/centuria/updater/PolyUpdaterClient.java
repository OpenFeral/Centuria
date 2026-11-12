package org.asf.centuria.updater;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.asf.centuria.updater.collections.PolyCollection;
import org.asf.centuria.updater.repositories.PolyRepositoryDef;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class PolyUpdaterClient {

	// Updating
	private static boolean cancelUpdate = false;
	private static boolean updating = false;

	private static String nextBaseSoftwareVersion = null;
	private static String nextBaseSoftwareVersionChangelogData = null;

	private static boolean wereUpdatesAvailable = false;

	private static Logger logger;
	private static File target;
	private static File packageCache;

	private static boolean inited = false;

	private static ArrayList<PolyRepositoryDef> repositories = new ArrayList<PolyRepositoryDef>();
	private static LinkedHashMap<String, PolyCollection> collections = new LinkedHashMap<String, PolyCollection>();

	private static class ChannelDownloadSource {
		public String urlBase;
		public String channel;
		public PolyRepositoryDef repository;
		public HashMap<String, String> hashList;
	}

	private static HashMap<PolyCollection, ChannelDownloadSource> channelsToUse = new HashMap<PolyCollection, ChannelDownloadSource>();
	private static HashMap<String, ArrayList<String>> repositoryChannelLists = new HashMap<String, ArrayList<String>>();
	private static HashMap<String, JsonObject> repositoryChannelCache = new HashMap<String, JsonObject>();
	private static HashMap<String, JsonObject> repositoryBuildCache = new HashMap<String, JsonObject>();
	private static HashMap<String, HashMap<String, String>> repositoryBuildListCache = new HashMap<String, HashMap<String, String>>();
	private static HashMap<String, String> resolvedChannelCache = new HashMap<String, String>();

	/**
	 * Deinitializes the updater client
	 */
	public static void deinitialize() {
		inited = false;
		repositories.clear();
		collections.clear();
		resetUpdateStates();
	}

	/**
	 * Initializes the update client
	 * 
	 * @param settings     Update client settings file
	 * @param repoCache    Repository cache folder
	 * @param packageCache Package cache folder
	 * @param target       Target installation folder
	 * @throws IOException
	 */
	public static void init(JsonObject settings, File repoCache, File packageCache, File target) throws IOException {
		if (inited)
			throw new IllegalStateException("Already initialized");
		inited = true;

		// Set fields
		if (logger == null)
			logger = LogManager.getLogger("PolyUpdater");
		PolyUpdaterClient.target = target;
		PolyUpdaterClient.packageCache = packageCache;

		// Load
		logger.info("Initializing update system...");
		if (!settings.has("defaultStrategy"))
			throw new IllegalArgumentException("Missing statement: defaultStrategy");
		String defaultStrategy = settings.get("defaultStrategy").getAsString();
		if (!defaultStrategy.equals("fullupdate") && !defaultStrategy.equals("installonly"))
			throw new IllegalArgumentException(
					"Invalid statement: defaultStrategy: expected installonly or fullupdate, got " + defaultStrategy);
		if (!settings.has("defaultChannel"))
			throw new IllegalArgumentException("Missing statement: defaultStrategy");
		String defaultChannel = settings.get("defaultChannel").getAsString();

		// Read repos
		logger.info("Loading repositories...");
		if (!settings.has("repositories"))
			throw new IllegalArgumentException("Missing statement: repositories");
		else if (!settings.get("repositories").isJsonObject())
			throw new IllegalArgumentException(
					"Invalid statement: repositories: expected object of id keys with url values");
		LinkedHashMap<String, String> repos = new LinkedHashMap<String, String>();
		JsonObject reposJ = settings.get("repositories").getAsJsonObject();
		for (String id : reposJ.keySet()) {
			JsonElement val = reposJ.get(id);
			if (!val.isJsonPrimitive())
				throw new IllegalArgumentException(
						"Invalid statement: repositories: expected object of id keys with url values");
			repos.put(id, val.getAsString());
		}

		// Load repositories
		for (String id : repos.keySet()) {
			String url = repos.get(id);
			File repoCacheDir = new File(repoCache, id);
			PolyRepositoryDef repo = new PolyRepositoryDef(id, url, repoCacheDir);

			// Init
			repo.init(logger);

			// Add
			repositories.add(repo);
		}

		// Read collection defs
		logger.info("Reading collection definitions...");
		if (!settings.has("collections"))
			throw new IllegalArgumentException("Missing statement: collections");
		else if (!settings.get("collections").isJsonObject())
			throw new IllegalArgumentException(
					"Invalid statement: collections: expected object of id keys with collection block values");
		LinkedHashMap<String, JsonObject> cols = new LinkedHashMap<String, JsonObject>();
		JsonObject colsJ = settings.get("collections").getAsJsonObject();
		for (String id : colsJ.keySet()) {
			JsonElement val = colsJ.get(id);
			if (!val.isJsonObject())
				throw new IllegalArgumentException(
						"Invalid statement: collections: expected object of id keys with collection block values");
			cols.put(id, val.getAsJsonObject());
		}

		// Load collections
		for (String id : cols.keySet()) {
			JsonObject col = cols.get(id);
			if (!col.has("enabled") || !col.get("enabled").isJsonPrimitive())
				throw new IllegalArgumentException("Invalid statement: collections: malformed collection: " + id
						+ ": expected object with \"enabled\", \"channel\" and \"strategy\", malformed \"enabled\" block");
			if (!col.has("channel") || !col.get("channel").isJsonPrimitive())
				throw new IllegalArgumentException("Invalid statement: collections: malformed collection: " + id
						+ ": expected object with \"enabled\", \"channel\" and \"strategy\", malformed \"channel\" block");
			if (!col.has("strategy") || !col.get("strategy").isJsonPrimitive())
				throw new IllegalArgumentException("Invalid statement: collections: malformed collection: " + id
						+ ": expected object with \"enabled\", \"channel\" and \"strategy\", malformed \"stategy\" block");
			if (!col.get("enabled").getAsBoolean()) {
				// Skip
				continue;
			}

			// Load collection def
			String channel = col.get("channel").getAsString();
			String strategy = col.get("strategy").getAsString();
			if (channel.equals("inherit"))
				channel = defaultChannel;
			if (strategy.equals("inherit"))
				strategy = defaultStrategy;
			if (!strategy.equals("fullupdate") && !strategy.equals("installonly"))
				throw new IllegalArgumentException(
						"Invalid statement: strategy: expected installonly or fullupdate, got " + defaultStrategy);
			File packageCacheDir = new File(packageCache, id);
			PolyCollection def = new PolyCollection(id, channel, strategy, packageCacheDir);

			// Init
			def.init(logger);

			// Add
			collections.put(def.getId(), def);
		}
	}

	/**
	 * Retrieves the changelog contents of the version of the base software the
	 * client is updating to
	 * 
	 * @return Contents of the base software update changelog, or null if absent
	 */
	public static String getNextBaseSoftwareVersionChangelogData() {
		return nextBaseSoftwareVersionChangelogData;
	}

	/**
	 * Retrieves the version of the base software the client is updating to
	 * 
	 * @return Version that the base software is being updated to or null if absent
	 */
	public static String getBaseSoftwareNextVersion() {
		return nextBaseSoftwareVersion;
	}

	/**
	 * Checks if an update is scheduled
	 * 
	 * @return True if scheduled, false otherwise
	 */
	public static boolean isUpdateScheduled() {
		return updating;
	}

	/**
	 * Checks if the update is cancelled
	 * 
	 * @return True if cancelled, false otherwise
	 */
	public static boolean isUpdateCancelled() {
		return cancelUpdate;
	}

	/**
	 * Cancels the scheduled update
	 * 
	 * @return True if cancelled, false otherwise
	 */
	public static boolean cancelScheduledUpdate() {
		if (updating) {
			cancelUpdate = true;
			nextBaseSoftwareVersion = null;
			nextBaseSoftwareVersionChangelogData = null;
			return true;
		} else
			return false;
	}

	/**
	 * Marks an update as scheduled
	 */
	public static void scheduleUpdate() {
		updating = true;
	}

	/**
	 * Resets the scheduled update
	 */
	public static void resetScheduledUpdate() {
		updating = false;
		cancelUpdate = false;
	}

	/**
	 * Retrieves the registered update repositories
	 * 
	 * @return Array of PolyRepositoryDef instances
	 */
	public static PolyRepositoryDef[] getRepositories() {
		return repositories.toArray(t -> new PolyRepositoryDef[t]);
	}

	/**
	 * Retrieves the registered collections
	 * 
	 * @return Array of PolyCollectionDef instances
	 */
	public static PolyCollection[] getCollections() {
		return collections.values().toArray(t -> new PolyCollection[t]);
	}

	/**
	 * Resets the updater so that
	 */
	public static void resetUpdateStates() {
		// Clear
		channelsToUse.clear();
		repositoryChannelLists.clear();
		repositoryChannelCache.clear();
		repositoryBuildCache.clear();
		repositoryBuildListCache.clear();
		resolvedChannelCache.clear();
		wereUpdatesAvailable = false;

		// Go through collections
		for (PolyCollection col : collections.values()) {
			if (col.hasUpdateAvailable()) {
				try {
					col.updateCollectionAndReset();
				} catch (IOException e) {
				}
			}
		}
	}

	/**
	 * Checks for available updates
	 * 
	 * @return True if updates are available, false otherwise
	 */
	public static boolean checkForUpdates() {
		if (!inited)
			throw new IllegalStateException("Updater not initialized");

		// Log
		logger.info("Checking for updates...");
		channelsToUse.clear();
		repositoryChannelLists.clear();
		repositoryChannelCache.clear();
		repositoryBuildCache.clear();
		repositoryBuildListCache.clear();
		resolvedChannelCache.clear();
		wereUpdatesAvailable = false;

		// Go through collections
		for (PolyCollection col : collections.values()) {
			if (col.hasUpdateAvailable()) {
				try {
					col.updateCollectionAndReset();
				} catch (IOException e) {
				}
			}
		}

		// Prepare
		boolean updateAvailable = false;

		// Go through collections
		for (PolyCollection col : collections.values()) {
			// Check strategy
			if (col.getStrategy().equals("installonly")) {
				// Skip if installed
				if (col.isInstalledLocally())
					continue;
			}

			// Log
			logger.info("Checking for updates for " + col.getId() + "...");

			// Find repository
			boolean foundCollection = false;
			logger.debug("Finding repository for " + col.getId() + " on channel " + col.getChannel() + "...");
			for (PolyRepositoryDef repo : repositories) {
				if (!repo.isAvailable())
					continue;

				// Get channel
				logger.debug("Querying repository " + repo.getId() + " for " + col.getId() + " on channel "
						+ col.getChannel() + "...");
				String buildVersion = null;
				String buildChangelog = null;
				JsonObject buildManifest = null;
				boolean hasUpstreamVersion = false;
				try {
					// Resolve channel
					String channelResolved = resolveChannel(repo, col.getId(), col.getChannel());
					if (channelResolved == null) {
						logger.debug("No matching channel in repository " + repo.getId() + " for " + col.getId()
								+ " on channel " + col.getChannel());
						continue;
					}

					// Resolve default
					String urlBase = repo.getUrl() + "channels/" + col.getId();
					JsonObject resolvedChannelDef = downloadChannel(repo, col.getId(), urlBase, channelResolved);
					while (resolvedChannelDef.get("type").getAsString().equals("default")) {
						channelResolved = resolveChannel(repo, col.getId(),
								resolvedChannelDef.get("target").getAsString());
						if (channelResolved == null) {
							logger.debug("No matching channel in repository " + repo.getId() + " for " + col.getId()
									+ " on channel " + col.getChannel());
							continue;
						}
						resolvedChannelDef = downloadChannel(repo, col.getId(), urlBase, channelResolved);

					}

					// Verify def
					if (!resolvedChannelDef.has("build_id")) {
						logger.error("Malformed build channel: " + col.getChannel() + " on repository " + repo.getUrl()
								+ ": no build_id parameter, this is a repository issue, please report this to the maintainers of the repository");
						throw new IOException();
					}
					if (!resolvedChannelDef.has("build_list_hash")) {
						logger.error("Malformed build channel: " + col.getChannel() + " on repository " + repo.getUrl()
								+ ": no build_list_hash parameter, this is a repository issue, please report this to the maintainers of the repository");
						throw new IOException();
					}

					// Log resolved
					logger.debug("Resolved channel " + col.getChannel() + " from repository " + repo.getId() + " for "
							+ col.getId() + ": " + channelResolved);

					// Repository found
					String buildId = resolvedChannelDef.get("build_id").getAsString();
					String buildListHash = resolvedChannelDef.get("build_list_hash").getAsString();
					logger.debug("Found build: " + buildId + ", verifying build manifest...");

					// Download build manifest
					HashMap<String, String> hashes = downloadBuildList(repo, col.getId(), buildListHash, buildId);
					String manifestHash = hashes.get("build.json");
					JsonObject build = downloadBuildMetadata(repo, col.getId(), manifestHash, buildId);
					if (!build.has("collection_version")) {
						logger.error("Malformed build manifest: " + buildId + " on repository " + repo.getUrl()
								+ ": no collection_version parameter, this is a repository issue, please report this to the maintainers of the repository");
						throw new IOException();
					}

					// Parse
					buildVersion = build.get("collection_version").getAsString();
					if (build.has("collection_build_manifest"))
						buildManifest = build.get("collection_build_manifest").getAsJsonObject();
					if (build.has("collection_changelog"))
						buildChangelog = build.get("collection_changelog").getAsString();
					logger.debug("Remote has version: " + buildVersion + ", build ID " + buildId);
					hasUpstreamVersion = true;
					foundCollection = true;

					// Put channel
					ChannelDownloadSource source = new ChannelDownloadSource();
					source.channel = channelResolved;
					source.repository = repo;
					source.urlBase = repo.getUrl() + "builds/" + buildId + "/";
					source.hashList = hashes;
					channelsToUse.put(col, source);
				} catch (Exception e) {
					logger.debug("Could not query repository " + repo.getId() + " for " + col.getId() + ".");
				}

				// Check
				if (hasUpstreamVersion) {
					// Found upstream

					// Check version
					if (!col.isInstalledLocally() || !col.getCurrentVersion().equals(buildVersion)) {
						// Update available
						logger.info("Found updates for: " + col.getId() + ", new version " + buildVersion);

						// Try downloading changelog
						String changelog = null;
						if (buildChangelog != null) {
							try {
								URL u = new URL(buildChangelog);
								InputStream strm = u.openStream();
								changelog = new String(strm.readAllBytes(), "UTF-8");
								strm.close();
							} catch (Exception e) {
							}
						}

						// Mark
						col.markUpdated(buildVersion, buildManifest, changelog);
						updateAvailable = true;
					} else
						logger.info("No updates available for " + col.getId());
					break;
				}
			}
			if (!foundCollection) {
				logger.error("No sources available for " + col.getId() + "!");
			}
		}

		// Check user content updates
		logger.info("Checking for local update data...");
		File rawUpdates = new File(target, "upgradedata");
		if (rawUpdates.exists() && rawUpdates.isDirectory()) {
			logger.info("Found local update data! Checking mode...");
			File forced = new File(rawUpdates, "forceinstall");
			if (!forced.exists())
				forced = new File(rawUpdates, "forceinstall.txt");
			if (forced.exists()) {
				// Install
				logger.info("Scheduled update for local update data!");
				updateAvailable = true;
			} else {
				if (!updateAvailable) {
					logger.info("");
					logger.info(
							"No update scheduled for local update data and no general updates are available, update will not be installed");
					logger.info(
							"Local update data does not contain a 'forceinstall' file, no update scheduled for local update data");
					logger.info("");
					logger.info(
							"If you wish to install the user content without waiting for a general update, please include a file named 'forceinstall' or 'forceinstall.txt' in the 'upgradedata' folder");
					logger.info("");

				} else {
					logger.info("");
					logger.info("No update scheduled for local update data, however it will still be installed!");
					logger.info("The server will install local update data as general updates are available");
					logger.info("");
					logger.info(
							"However local update data does not contain a 'forceinstall' file, meaning it would otherwise not trigger an install unless the updater is manually triggered");
					logger.info(
							"To change this for a future update, include a file named 'forceinstall' or 'forceinstall.txt' in the 'upgradedata' folder");
					logger.info("");
				}
			}
		} else {
			logger.info(
					"No local update data available, to use this feature, you can create a folder named 'upgradedata' with user content that will be installed during the next update.");
			logger.info(
					"This can be used to install files to resolve update conflicts as well as introducing custom data during an update.");

		}

		// Find base collection
		PolyCollection base = collections.get("base");
		if (base != null && base.hasUpdateAvailable()) {
			nextBaseSoftwareVersion = base.getNewVersion();
			nextBaseSoftwareVersionChangelogData = base.getNewChangelogData();
		}

		// Find collections needing installation but that could not be resolved
		for (PolyCollection col : collections.values()) {
			if (!col.isInstalledLocally() && !col.hasUpdateAvailable()) {
				// Error
				logger.error("Unable to install " + col.getId() + ": no repositories provided usable downloads!");
				updateAvailable = true;
			}
		}
		if (!updateAvailable)
			logger.info("There are currently no updates available.");

		// Return
		wereUpdatesAvailable = updateAvailable;
		return updateAvailable;
	}

	private static String resolveChannel(PolyRepositoryDef repo, String id, String channel) throws IOException {
		String urlBase = repo.getUrl() + "channels/" + id;
		if (!repositoryChannelLists.containsKey(repo.getId() + "-" + id)) {
			// Get channels

			// Log
			logger.debug("Downloading channel list of object " + id + " from repository " + repo.getId() + "...");
			try {
				URL u = new URL(urlBase + "/channels.json");
				InputStream strm = u.openStream();
				String json = new String(strm.readAllBytes(), "UTF-8");
				strm.close();
				JsonObject channelList = JsonParser.parseString(json).getAsJsonObject();

				// Verify signature
				JsonElement res = repo.verifySignature(channelList);
				if (res == null)
					throw new IOException("Signature error");

				// Decode list
				ArrayList<String> channels = new ArrayList<String>();
				channelList = res.getAsJsonObject();
				JsonArray elements = channelList.get("channels").getAsJsonArray();
				for (JsonElement ele : elements) {
					channels.add(ele.getAsString());
				}

				// Save
				repositoryChannelLists.put(repo.getId() + "-" + id, channels);
			} catch (IOException e) {
				// Not present
				repositoryChannelLists.put(repo.getId() + "-" + id, new ArrayList<String>());
				throw e;
			}
		}

		// Check
		if (resolvedChannelCache.containsKey(repo.getId() + "-" + id + "-" + channel))
			return resolvedChannelCache.get(repo.getId() + "-" + id + "-" + channel);

		// Get channel list
		ArrayList<String> channels = repositoryChannelLists.get(repo.getId() + "-" + id);
		if (channels.size() == 0)
			return null;

		// Query start
		logger.debug("Resolving channel " + channel + " from repository " + repo.getId() + " for " + id + "...");

		// Resolver
		while (true) {
			// Try to resolve relative
			String resolveChannel = channel;
			String channelRemainer = "";
			while (!channels.contains(resolveChannel) && resolveChannel.contains("/")) {
				channelRemainer = resolveChannel.substring(resolveChannel.lastIndexOf("/")) + channelRemainer;
				resolveChannel = resolveChannel.substring(0, resolveChannel.lastIndexOf("/"));
			}
			if (!channels.contains(resolveChannel))
				return null;

			// Handle resolving relative
			if (!channel.equals(resolveChannel)) {
				// Resolve parent first
				String targetResolved = resolveChannel(repo, id, resolveChannel);

				// Try resolving child
				String childResolved = resolveChannel(repo, id, targetResolved + channelRemainer);
				if (childResolved == null) {
					// Check if default
					JsonObject resolvedChannelDef = downloadChannel(repo, id, urlBase, targetResolved);
					while (resolvedChannelDef.get("type").getAsString().equals("default")) {
						// Try resolve child

						// Resolve default
						targetResolved = resolveChannel(repo, id, resolvedChannelDef.get("target").getAsString());
						if (targetResolved == null)
							return null; // Invalid
						resolvedChannelDef = downloadChannel(repo, id, urlBase, targetResolved);

						// Resolve child
						childResolved = resolveChannel(repo, id, targetResolved + channelRemainer);
						if (childResolved != null)
							break;
					}

					if (childResolved == null)
						return null; // Invalid
				}

				// Assign
				resolveChannel = childResolved;
			}

			// Resolved directly
			JsonObject channelDef = downloadChannel(repo, id, urlBase, resolveChannel);
			if (!channelDef.has("type")) {
				logger.error("Malformed channel: " + resolveChannel + " on repository " + repo.getUrl()
						+ ": no type parameter, this is a repository issue, please report to the maintainer of the repositorys");
				throw new IOException();
			}
			String type = channelDef.get("type").getAsString();
			if (type.equals("pointer")) {
				// Pointer channel, hard reref
				if (!channelDef.has("target")) {
					logger.error("Malformed pointer channel: " + resolveChannel + " on repository " + repo.getUrl()
							+ ": no target parameter, this is a repository issue, please report to the maintainers of the repository");
					throw new IOException();
				}
				String target = channelDef.get("target").getAsString();

				// Re-resolve
				String targetResolved = resolveChannel(repo, id, target);
				if (targetResolved == null)
					return null; // Invalid

				// Return
				resolvedChannelCache.put(resolveChannel, targetResolved);
				return targetResolved;
			} else if (type.equals("default")) {
				// Default channel definition
				if (!channelDef.has("target")) {
					logger.error("Malformed default channel: " + resolveChannel + " on repository " + repo.getUrl()
							+ ": no target parameter, this is a repository issue, please report this to the maintainers of the repository");
					throw new IOException();
				}

				// Do not resolve default types
				resolvedChannelCache.put(resolveChannel, channel);
				return channel;
			} else if (type.equals("build")) {
				// Return build channel
				resolvedChannelCache.put(resolveChannel, channel);
				return channel;
			} else {
				logger.error("Malformed channel: " + resolveChannel + " on repository " + repo.getUrl()
						+ ": type parameter invalid, this is a repository issue, please report this to the maintainers of the repository");
				throw new IOException();
			}
		}
	}

	private static JsonObject downloadChannel(PolyRepositoryDef repo, String id, String urlBase, String channelId)
			throws IOException {
		// Check cache
		if (repositoryChannelCache.containsKey(repo.getId() + "-" + id + "-" + channelId))
			return repositoryChannelCache.get(repo.getId() + "-" + id + "-" + channelId);

		// Log
		logger.debug("Downloading channel manifest " + channelId + " from repository " + repo.getId() + " for " + id
				+ "...");

		// Download
		String channelUrl = urlBase + "/" + channelId + ".json";
		URL u = new URL(channelUrl);
		InputStream strm = u.openStream();
		String json = new String(strm.readAllBytes(), "UTF-8");
		strm.close();

		// Decode json
		JsonObject channel = JsonParser.parseString(json).getAsJsonObject();

		// Verify signature
		JsonElement res = repo.verifySignature(channel);
		if (res == null)
			throw new IOException("Signature error");

		// Decode
		channel = res.getAsJsonObject();
		repositoryChannelCache.put(repo.getId() + "-" + id + "-" + channelId, channel);
		return channel;
	}

	private static JsonObject downloadBuildMetadata(PolyRepositoryDef repo, String id, String expectedHash,
			String buildId) throws IOException {
		// Check cache
		if (repositoryBuildCache.containsKey(repo.getId() + "-" + buildId))
			return repositoryBuildCache.get(repo.getId() + "-" + buildId);

		// Log
		logger.debug(
				"Downloading build manifest " + buildId + " from repository " + repo.getId() + " for " + id + "...");

		// Download
		URL u = new URL(repo.getUrl() + "builds/" + buildId + "/build.json");
		InputStream strm = u.openStream();
		byte[] data = strm.readAllBytes();
		String json = new String(data, "UTF-8");
		strm.close();

		// Get hash
		int count = 0;
		while (!PolyTools.sha256Hash(data).equals(expectedHash) && count <= 3) {
			u = new URL(repo.getUrl() + "builds/" + buildId + "/build.json");
			strm = u.openStream();
			data = strm.readAllBytes();
			json = new String(data, "UTF-8");
			strm.close();
			count++;
		}
		if (count > 3) {
			logger.error("The build manifest for build id " + buildId + " from repository " + repo.getId() + " for "
					+ id + " could not be verified, hash of the build manifest mistmatched with channel data");
			throw new IOException("Hash mismatch");
		}

		// Decode json
		JsonObject build = JsonParser.parseString(json).getAsJsonObject();
		repositoryChannelCache.put(repo.getId() + "-" + buildId, build);
		return build;
	}

	private static HashMap<String, String> downloadBuildList(PolyRepositoryDef repo, String id, String expectedHash,
			String buildId) throws IOException {
		// Check cache
		if (repositoryBuildListCache.containsKey(repo.getId() + "-" + buildId))
			return repositoryBuildListCache.get(repo.getId() + "-" + buildId);

		// Log
		logger.debug("Downloading build index " + buildId + " from repository " + repo.getId() + " for " + id + "...");

		// Download
		URL u = new URL(repo.getUrl() + "builds/" + buildId + "/hashes.list");
		InputStream strm = u.openStream();
		byte[] data = strm.readAllBytes();
		String hashes = new String(data, "UTF-8");
		strm.close();

		// Get hash
		int count = 0;
		while (!PolyTools.sha256Hash(data).equals(expectedHash) && count <= 3) {
			u = new URL(repo.getUrl() + "builds/" + buildId + "/hashes.list");
			strm = u.openStream();
			data = strm.readAllBytes();
			hashes = new String(data, "UTF-8");
			strm.close();
			count++;
		}
		if (count > 3) {
			logger.error("The build hash list for build id " + buildId + " from repository " + repo.getId() + " for "
					+ id + " could not be verified, hash of the hash list manifest mistmatched with channel data");
			throw new IOException("Hash mismatch");
		}

		// Decode
		HashMap<String, String> build = new LinkedHashMap<String, String>();
		loadHashList(hashes, build);
		for (String name : build.keySet()) {
			name = name.replace("\\", "/");
			if (("/" + name + "/").contains("/../") || ("/" + name + "/").contains("/./")) {
				logger.error("Malformed build list for: " + buildId + " on repository " + repo.getUrl()
						+ ": illegal entry name: " + name);
				throw new IOException();
			}
		}
		repositoryBuildListCache.put(repo.getId() + "-" + buildId, build);
		return build;
	}

	private static class SupportArtifact {
		public PolyCollection[] requirements;
		public String path;

		public boolean verifyRequirement(Map<String, SupportArtifact[]> otherArtifacts) {
			boolean match = true;
			for (PolyCollection requirement : requirements) {
				// Check installed
				if (!requirement.isInstalledLocally() && !requirement.hasUpdateAvailable()) {
					// Not installed, no update available
					match = false;
					break;
				}
			}
			return match;
		}
	}

	/**
	 * Downloads updates if available
	 * 
	 * @param forced             True to forcefully overwrite conflicting files,
	 *                           false to leave conflict detection intact
	 * @param forcedGuideMessage Message to display for how to enable forced install
	 *                           mode
	 * @return True if updates were applied, false if an error occurred during
	 *         pre-updater checks or if no updates were available
	 * @throws IOException If an error occurs while downloading the updater
	 */
	public static boolean downloadUpdates(boolean forced, String forcedGuideMessage) throws IOException {
		if (!inited)
			throw new IllegalStateException("Updater not initialized");

		// Check
		if (!wereUpdatesAvailable && !checkForUpdates())
			return false;
		boolean hasUpdatable = false;
		for (PolyCollection col : collections.values()) {
			if (col.hasUpdateAvailable())
				hasUpdatable = true;
		}
		if (!hasUpdatable) {
			// Check local
			File rawUpdates = new File(target, "upgradedata");
			if (rawUpdates.exists() && rawUpdates.isDirectory()) {
				logger.info("Found local update data! Checking mode...");
				File forceF = new File(rawUpdates, "forceinstall");
				if (!forceF.exists())
					forceF = new File(rawUpdates, "forceinstall.txt");
				if (forceF.exists()) {
					hasUpdatable = true;
				}
			}

			// Check result
			if (!hasUpdatable) {
				// Failed
				return hasUpdatable;
			}
		}

		// Run updater
		logger.info("Preparing download...");

		// Check updated packages, if needed, re-update existing packages providing
		// support for other packages, so that support files are installed
		ArrayList<String> collectionsToReinstall = new ArrayList<String>();
		HashMap<String, SupportArtifact[]> knownArtifacts = new HashMap<String, SupportArtifact[]>();
		logger.info("Checking for new artifacts to install for locally installed packages...");
		for (PolyCollection col : collections.values()) {
			if (col.isInstalledLocally()) {
				// Verify
				logger.info("Finding support artifacts for " + col.getId() + "...");
				if (col.hasUpdateAvailable()) {
					logger.info("Update already queued for " + col.getId() + ", skipped.");
					continue;
				}
				File cacheListMain = new File(col.getCollectionCache(), "installed.list");
				if (cacheListMain.exists()) {
					try {
						// Load cache list
						HashMap<String, String> current = new LinkedHashMap<String, String>();
						String hashes = Files.readString(cacheListMain.toPath());
						loadHashList(hashes, current);

						// Go through artifacts
						ArrayList<SupportArtifact> artifacts = new ArrayList<SupportArtifact>();
						for (String pathName : current.keySet()) {
							String[] targetArtifacts = getSupportArtifactTargets(pathName);
							if (targetArtifacts != null) {
								boolean matchTarget = true;
								ArrayList<PolyCollection> requiredArtifacts = new ArrayList<PolyCollection>();
								for (String target : targetArtifacts) {
									if (collections.containsKey(target)) {
										// Target is recognized
										PolyCollection targetCol = collections.get(target);

										// Add
										requiredArtifacts.add(targetCol);
									} else {
										// Not installed
										matchTarget = false;
										break;
									}
								}
								if (matchTarget && requiredArtifacts.size() != 0) {
									// Recognized
									SupportArtifact arti = new SupportArtifact();
									arti.path = pathName;
									arti.requirements = requiredArtifacts.toArray(t -> new PolyCollection[t]);
									artifacts.add(arti);
								}
							}
						}
						if (artifacts.size() != 0)
							knownArtifacts.put(col.getId(), artifacts.toArray(t -> new SupportArtifact[t]));
					} catch (Exception e) {
						logger.error(
								"Could not process artifacts if " + col.getId() + ", an unexpected error occurred!",
								e);
					}
				}
			}
		}
		for (PolyCollection col : collections.values()) {
			if (col.isInstalledLocally()) {
				// Verify
				if (col.hasUpdateAvailable())
					continue;
				if (!knownArtifacts.containsKey(col.getId()))
					continue;
				SupportArtifact[] artifacts = knownArtifacts.get(col.getId());
				logger.info("Checking for artifact updates for " + col.getId() + "...");
				boolean requireUpdate = false;
				for (SupportArtifact arti : artifacts) {
					if (arti.verifyRequirement(knownArtifacts)) {
						// Artifact can be installed
						requireUpdate = true;
						break;
					}
				}
				if (requireUpdate) {
					logger.info("Update scheduled for " + col.getId() + ": new artifacts available");
					collectionsToReinstall.add(col.getId());
				}
			}
		}

		// Gather colletions
		logger.info("Gathering updated collections...");
		for (PolyCollection col : collections.values()) {
			if (!col.hasUpdateAvailable() && !collectionsToReinstall.contains(col.getId()))
				continue;

			// Log
			if (col.isInstalledLocally()) {
				if (collectionsToReinstall.contains(col.getId()) && !col.hasUpdateAvailable())
					logger.info(
							"Will update " + col.getId() + ": " + col.getCurrentVersion());
				else
					logger.info(
							"Will update " + col.getId() + ": " + col.getCurrentVersion() + " -> "
									+ col.getNewVersion());
			} else
				logger.info("Will install " + col.getId() + ": " + col.getNewVersion());
		}

		// Prepare install
		// Go through collectiions
		int fileTotal = 0;
		int startAt = 0;
		HashMap<String, InstallEntry> installs = new LinkedHashMap<String, InstallEntry>();
		logger.info("Gathering files to install...");
		for (PolyCollection col : collections.values()) {
			if (!col.hasUpdateAvailable() && !collectionsToReinstall.contains(col.getId()))
				continue;
			HashMap<String, String> localHashes = new LinkedHashMap<String, String>();
			HashMap<String, String> installedFileHashes = new LinkedHashMap<String, String>();
			HashMap<String, String> currentlyDownloaded = new LinkedHashMap<String, String>();
			HashMap<String, String> filesToInstall = new LinkedHashMap<String, String>();
			ArrayList<String> previouslyInstalledFiles = new ArrayList<String>();
			InstallEntry e = new InstallEntry();
			e.localHashes = localHashes;
			e.filesToInstall = filesToInstall;
			e.previouslyInstalledFiles = previouslyInstalledFiles;
			installs.put(col.getId(), e);

			// Get source
			ChannelDownloadSource source = channelsToUse.get(col);

			// Log
			logger.debug("Gathering locally-available files for " + col.getId() + "...");

			// Find files
			File cacheListMain = new File(col.getCollectionCache(), "installed.list");
			File cacheListRolling = new File(col.getCollectionCache(), "installing.list");
			File upgradeOutputCache = new File(col.getCollectionCache(), "upgrade-temp");

			// First add files of sources already present locally even if they may not be
			// cached
			for (String name : source.hashList.keySet()) {
				// Get entry
				UpdateEntry entry = parseUpdateEntry(name);
				if (entry == null)
					continue;

				// Check file
				File downloadTarget = new File(target, entry.target);
				if (downloadTarget.exists()) {
					// Get hash
					FileInputStream fIn = new FileInputStream(downloadTarget);
					String hash = PolyTools.sha256Hash(fIn);
					fIn.close();

					// Add
					localHashes.put(name, hash);
					installedFileHashes.put(name, hash);
				}
			}

			// Load hash list from main
			if (cacheListMain.exists()) {
				HashMap<String, String> current = new LinkedHashMap<String, String>();
				String hashes = Files.readString(cacheListMain.toPath());
				loadHashList(hashes, current);
				localHashes.putAll(current);

				// Add previously installed entries
				for (String name : current.keySet()) {
					if (!previouslyInstalledFiles.contains(name))
						previouslyInstalledFiles.add(name);
				}
			}

			// Load hash list from the list of installing hashes
			if (cacheListRolling.exists()) {
				HashMap<String, String> current = new LinkedHashMap<String, String>();
				String hashes = Files.readString(cacheListRolling.toPath());
				loadHashList(hashes, current);

				// Add those that were downloaded successfully
				for (String name : current.keySet()) {
					File tempDownloadFile = new File(upgradeOutputCache, name);
					if (tempDownloadFile.exists() && !currentlyDownloaded.containsKey(name)) {
						if (!previouslyInstalledFiles.contains(name))
							previouslyInstalledFiles.add(name);
						localHashes.put(name, current.get(name));
						currentlyDownloaded.put(name, current.get(name));
						if (!name.endsWith("/.keepempty"))
							startAt++;
					}
				}
			}

			// Gather files to install
			logger.debug("Gathering files to install for " + col.getId() + "...");
			for (String name : source.hashList.keySet()) {
				// Get entry
				UpdateEntry entry = parseUpdateEntry(name);
				if (entry == null)
					continue;

				// Check if introduced
				boolean newlyIntroduced = false;
				if (collectionsToReinstall.contains(col.getId()) && knownArtifacts.containsKey(col.getId())) {
					if (Stream.of(knownArtifacts.get(col.getId())).anyMatch(t -> t.path.equals(name))) {
						newlyIntroduced = true;
						if (previouslyInstalledFiles.contains(name))
							previouslyInstalledFiles.remove(name);
					}
				}

				// Check type
				File downloadTarget = new File(target, entry.target);
				String upstreamHash = source.hashList.get(name);
				switch (entry.type) {

					case PAYLOAD:
					case JSONMERGER: {
						// Check change
						String localHash = localHashes.get(name);
						if (localHash == null || !localHash.equals(upstreamHash) || newlyIntroduced) {
							// Add
							if (!filesToInstall.containsKey(name)) {
								logger.debug("Added: " + name);
								filesToInstall.put(name, upstreamHash);
								if (currentlyDownloaded.containsKey(name))
									currentlyDownloaded.remove(name);
							}
						} else {
							logger.debug("Unchanged: " + name);
						}
						break;
					}

					case SKEL: {
						// Check if the file exists
						String installedLocalHash = installedFileHashes.get(name);
						String expectedLocalHash = localHashes.get(name);
						if (!downloadTarget.exists()
								|| (installedLocalHash != null && installedLocalHash.equals(expectedLocalHash)
										&& previouslyInstalledFiles.contains(name))
								|| newlyIntroduced) {
							// Check change
							if (expectedLocalHash == null || !expectedLocalHash.equals(upstreamHash)) {
								// Add
								if (!filesToInstall.containsKey(name)) {
									logger.debug("Added: " + name);
									filesToInstall.put(name, upstreamHash);
									if (currentlyDownloaded.containsKey(name))
										currentlyDownloaded.remove(name);
								}
							} else {
								logger.debug("Unchanged: " + name);
							}
						}
						break;
					}

				}
			}
		}
		for (String id : installs.keySet()) {
			fileTotal += installs.get(id).filesToInstall.keySet().stream().filter(t -> !t.endsWith("/.keepempty"))
					.count();
		}
		fileTotal += startAt;

		// Check for conflicts
		boolean foundConflicts = false;
		ArrayList<String> conflicts = new ArrayList<String>();
		ArrayList<String> conflictsResolved = new ArrayList<String>();
		logger.info("Checking for file conflicts...");
		for (PolyCollection col : collections.values()) {
			if (!col.hasUpdateAvailable() && !collectionsToReinstall.contains(col.getId()))
				continue;

			// Skip base
			if (col.getId().equals("base") && !col.isInstalledLocally())
				continue;

			// Get details
			InstallEntry installEntry = installs.get(col.getId());
			ChannelDownloadSource source = channelsToUse.get(col);

			// Gather files to install
			for (String name : source.hashList.keySet()) {
				// Get entry
				UpdateEntry entry = parseUpdateEntry(name);
				if (entry == null || name.endsWith("/.keepempty"))
					continue;

				// Check if scheduled
				if (!installEntry.filesToInstall.containsKey(name))
					continue;
				String installHash = installEntry.filesToInstall.get(name);
				String expectedLocalHash = installEntry.localHashes.get(name);
				boolean wasPreviouslyInstalled = installEntry.previouslyInstalledFiles.contains(name);

				// Check file
				File downloadTarget = new File(target, entry.target);
				if (downloadTarget.exists()) {
					// Get hash
					FileInputStream fIn = new FileInputStream(downloadTarget);
					String localHash = PolyTools.sha256Hash(fIn);
					fIn.close();

					// Check type
					if (entry.type != UpdateEntryType.SKEL) {
						// Check change
						if (!wasPreviouslyInstalled || !expectedLocalHash.equals(installHash)) {
							// File changed
							if (!localHash.equals(expectedLocalHash)) {
								File rawUpdates = new File(target, "upgradedata");

								// Check present
								File rawUpdatePatch = new File(rawUpdates, entry.target);
								if (!rawUpdatePatch.exists()) {
									// Changed locally
									conflicts.add(entry.target
											+ ": user changes were made to this file that would otherwise be lost");
									logger.error("Detected file conflict! Collection " + col.getId()
											+ " will update file \"" + entry.target
											+ "\", however the destination file has user changes that would be lost!");
								} else {
									// Changed, but patched
									conflictsResolved.add(entry.target);
									logger.error("Detected file conflict! Collection " + col.getId()
											+ " will update file \"" + entry.target
											+ "\", however the destination file has user changes that would be lost!");
								}
								foundConflicts = true;
							}
						}
					}
				} else {
					// Check if another file with the same name but different casing exists
					File[] conflicting = downloadTarget.getParentFile()
							.listFiles(t -> !t.getName().equals(downloadTarget.getName())
									&& t.getName().equalsIgnoreCase(downloadTarget.getName()));
					if (conflicting.length != 0) {
						// Check if all was scheduled to be deleted
						boolean compatible = true;
						for (File conflict : conflicting) {
							// Check if present in hash list of update and if in the hash list of local
							String targetPath = entry.target.substring(0,
									entry.target.length() - downloadTarget.getName().length()) + conflict.getName();
							String targetName = name.substring(0, name.length() - downloadTarget.getName().length())
									+ conflict.getName();
							boolean deletedRemote = !installEntry.filesToInstall.containsKey(targetName);
							boolean installedLocally = installEntry.previouslyInstalledFiles.contains(targetName);
							if (deletedRemote && installedLocally) {
								// Check hash
								String expectedHash = installEntry.localHashes.get(targetName);
								FileInputStream fIn = new FileInputStream(conflict);
								String localHash = PolyTools.sha256Hash(fIn);
								fIn.close();
								if (!expectedHash.equals(localHash)) {
									// Conflict
									conflicts.add(entry.target + ": file conflicting with local file " + targetPath
											+ ": user changes are present that are conflicting with update data");
									compatible = false;
								}
							} else {
								// Conflicting
								conflicts.add(entry.target + ": file conflicting with local file " + targetPath
										+ ": untracked local file conflicting with update data");
								compatible = false;
							}
						}

						// Conflict
						if (!compatible) {
							logger.error("Detected file conflict! Collection " + col.getId() + " will update file \""
									+ entry.target
									+ "\", however the destination has a file of the same name with different casing!");
							foundConflicts = true;
						}
					}
				}
			}
		}

		// Check result
		if (foundConflicts && !forced) {
			logger.info("");
			logger.error(
					"Unable to perform colletion installation due to the presence of user-made changes that would otherwise be lost! Please reconsile these conflicts before running the updater.");
			logger.error(
					"Please back up the user changes and run the updater in forced install mode once finished to install the update anyways.");
			logger.error(forcedGuideMessage);
			String conflictsString = "";
			for (String file : conflicts) {
				if (!conflictsString.isEmpty())
					conflictsString += "\n";
				conflictsString += " " + file;
			}
			String resolvedConflicts = "";
			for (String file : conflictsResolved) {
				if (!conflictsString.isEmpty())
					resolvedConflicts += "\n";
				resolvedConflicts += " " + file;
			}
			if (!conflictsString.isEmpty()) {
				logger.error("");
				logger.error("Problematic files conflicting with update:");
				for (String line : conflictsString.split("\n"))
					logger.error(line);
			}
			if (!resolvedConflicts.isEmpty()) {
				logger.error("");
				logger.error("Conflicting files scheduled for overwriting with updated user content:");
				for (String line : resolvedConflicts.split("\n"))
					logger.error(line);
				logger.error("");
				logger.error(
						"Note: please verify that the user content files logged here are up to date compared to the upstream versions");
				logger.error("Note: the upgrade will not be aware of the versions provided by user content");
			}
			logger.info("");
			File rawUpdates = new File(target, "upgradedata");
			if (!rawUpdates.exists()) {
				logger.info(
						"No local update data available, to use this feature, you can create a folder named 'upgradedata' with user content that will be installed during the update.");
				logger.info(
						"This can be used to install files to resolve update conflicts as well as introducing custom data during an update.");
			}
			logger.info(
					"To resolve the update conflict safely, add edited updated versions of the conflicting files manually to the 'upgradedata' following the same structure.");
			logger.info("");
			return false;
		} else if (foundConflicts) {
			logger.info("");
			logger.warn("Conflicts were detected during the update!");
			logger.warn("Forced install mode is enabled! Conflicting files WILL be overwritten!");
			String conflictsString = "";
			for (String file : conflicts) {
				if (!conflictsString.isEmpty())
					conflictsString += "\n";
				conflictsString += " " + file;
			}
			String resolvedConflicts = "";
			for (String file : conflictsResolved) {
				if (!conflictsString.isEmpty())
					resolvedConflicts += "\n";
				resolvedConflicts += " " + file;
			}
			if (!conflictsString.isEmpty()) {
				logger.warn("");
				logger.warn("Problematic files conflicting with update:");
				for (String line : conflictsString.split("\n"))
					logger.warn(line);
			}
			if (!resolvedConflicts.isEmpty()) {
				logger.warn("");
				logger.warn("Conflicting files scheduled for overwriting with updated user content:");
				for (String line : resolvedConflicts.split("\n"))
					logger.error(line);
				logger.warn("");
				logger.warn(
						"Note: please verify that the user content files logged here are up to date compared to the upstream versions");
				logger.warn("Note: the upgrade will not be aware of the versions provided by user content");
			}
			logger.info("");
			logger.warn("Warning! Forced install mode is enabled, conflicting files will be overwritten!");
			logger.info("");
		}

		// Write
		logger.info("Writing update metadata...");
		for (PolyCollection col : collections.values()) {
			if (!col.hasUpdateAvailable() && !collectionsToReinstall.contains(col.getId()))
				continue;
			logger.info("Writing update manifest for " + col.getId() + "...");
			File cache = col.getCollectionCache();
			File manifest = new File(cache, "newmanifest.json");
			File changelog = new File(cache, "newchangelog.log");
			if (manifest.exists())
				manifest.delete();
			if (changelog.exists())
				changelog.delete();
			JsonObject newManifest = new JsonObject();
			if (!col.hasUpdateAvailable() && collectionsToReinstall.contains(col.getId())) {
				newManifest.addProperty("collection_id", col.getId());
				newManifest.addProperty("collection_version", col.getCurrentVersion());
				newManifest.add("collection_build_manifest", col.getCurrentBuildManifest());
				Files.writeString(manifest.toPath(), newManifest.toString());
				if (col.getCurrentChangelogData() != null) {
					logger.info("Writing update changelog for " + col.getId() + "...");
					Files.writeString(changelog.toPath(), col.getCurrentChangelogData());
				}
			} else {
				newManifest.addProperty("collection_id", col.getId());
				newManifest.addProperty("collection_version", col.getNewVersion());
				newManifest.add("collection_build_manifest", col.getNewBuildManifest());
				Files.writeString(manifest.toPath(), newManifest.toString());
				if (col.getNewChangelogData() != null) {
					logger.info("Writing update changelog for " + col.getId() + "...");
					Files.writeString(changelog.toPath(), col.getNewChangelogData());
				}
			}
		}

		// Log download start
		logger.info("Starting download...");
		int i = startAt + 1;
		CloseableHttpClient http = HttpClientBuilder.create()
				.setDefaultHeaders(List.of(new BasicHeader("Keep-Alive", "timeout=5"))).build();
		for (PolyCollection col : collections.values()) {
			if (!col.hasUpdateAvailable() && !collectionsToReinstall.contains(col.getId()))
				continue;

			// Get details
			InstallEntry installEntry = installs.get(col.getId());
			ChannelDownloadSource source = channelsToUse.get(col);

			// Find files
			File cacheListRolling = new File(col.getCollectionCache(), "installing.list");

			// Load hash list from the list of installing hashes
			HashMap<String, String> currentInstalled = new LinkedHashMap<String, String>();
			if (cacheListRolling.exists()) {
				String hashes = Files.readString(cacheListRolling.toPath());
				loadHashList(hashes, currentInstalled);
			}

			// Create writer
			FileOutputStream cacheRollingWriter = new FileOutputStream(cacheListRolling);
			try {
				// Write existing
				for (String key : currentInstalled.keySet()) {
					writeHashToList(cacheRollingWriter, key, currentInstalled.get(key));
				}

				// Gather files to install
				for (String name : source.hashList.keySet()) {
					// Get entry
					UpdateEntry entry = parseUpdateEntry(name);
					if (entry == null)
						continue;

					// Check if scheduled
					if (!installEntry.filesToInstall.containsKey(name))
						continue;
					String expectedHash = installEntry.filesToInstall.get(name);

					// Download
					int iState = i;
					if (!name.endsWith("/.keepempty")) {
						logger.info("[" + iState + "/" + fileTotal + "] Downloading " + col.getId() + " "
								+ (col.getNewVersion() == null ? col.getCurrentVersion() : col.getNewVersion()) + ": "
								+ name + "...");
						i++;
					}
					File tempDownloadFile = new File(new File(col.getCollectionCache(), "upgrade-temp"), name);
					tempDownloadFile.getParentFile().mkdirs();
					FileOutputStream fOut = new FileOutputStream(tempDownloadFile);
					String url = source.urlBase
							+ URLEncoder.encode(name, "UTF-8").replace("+", "%20").replace("%2F", "/");

					// Create request
					HttpGet req = new HttpGet(url);
					http.execute(req, t -> {
						InputStream strm = t.getEntity().getContent();
						strm.transferTo(fOut);
						strm.close();
						t.close();
						return null;
					});
					fOut.close();

					// Get hash
					FileInputStream fIn = new FileInputStream(tempDownloadFile);
					String hash = PolyTools.sha256Hash(fIn);
					fIn.close();

					// Verify
					int errorCount = 0;
					if (!hash.equals(expectedHash)) {
						// Error
						while (errorCount <= 3 && !hash.equals(expectedHash)) {
							// Retry
							errorCount++;
							logger.warn("[" + iState + "/" + fileTotal + "] Integrity check error! Retrying download "
									+ col.getId() + " "
									+ (col.getNewVersion() == null ? col.getCurrentVersion() : col.getNewVersion())
									+ ": " + name + "...");
							req = new HttpGet(url);
							FileOutputStream fOut2 = new FileOutputStream(tempDownloadFile);
							http.execute(req, t -> {
								InputStream strm = t.getEntity().getContent();
								strm.transferTo(fOut2);
								strm.close();
								t.close();
								return null;
							});
							fOut2.close();

							// Get hash
							fIn = new FileInputStream(tempDownloadFile);
							hash = PolyTools.sha256Hash(fIn);
							fIn.close();
						}
						if (!hash.equals(expectedHash)) {
							logger.error("An integrity check error occured while downloading " + name + " on channel "
									+ source.channel + " from " + source.repository.getUrl()
									+ ", suspecting something is wrong with the connection or repository, aborting!");
							throw new IOException("Signature mismatch");
						}
					}

					// Success
					// Write hash
					writeHashToList(cacheRollingWriter, name, hash);
				}
			} finally {
				// Close
				cacheRollingWriter.close();
			}
		}

		// Close client
		http.close();
		logger.info("Download finished!");

		// Add remaining
		logger.info("Finalizing hash lists...");
		for (PolyCollection col : collections.values()) {
			if (!col.hasUpdateAvailable() && !collectionsToReinstall.contains(col.getId()))
				continue;

			// Get details
			ChannelDownloadSource source = channelsToUse.get(col);
			new File(col.getCollectionCache(), "upgrade-temp").mkdirs();

			// Find files
			File cacheListRolling = new File(col.getCollectionCache(), "installing.list");
			File cacheListNewInstalled = new File(col.getCollectionCache(), "newinstalled.list");

			// Load hash list from the list of installing hashes
			HashMap<String, String> rolling = new LinkedHashMap<String, String>();
			if (cacheListRolling.exists()) {
				String hashes = Files.readString(cacheListRolling.toPath());
				loadHashList(hashes, rolling);
			}
			for (String id : source.hashList.keySet()) {
				rolling.put(id, source.hashList.get(id));
			}

			// Create writer
			FileOutputStream cacheRollingWriter = new FileOutputStream(cacheListNewInstalled);
			try {
				// Write existing
				for (String key : rolling.keySet()) {
					writeHashToList(cacheRollingWriter, key, rolling.get(key));
				}
			} finally {
				// Close
				cacheRollingWriter.close();
			}
		}

		// Query start
		logger.info("Clearing out caches of unchanged collections...");
		for (File folder : packageCache.listFiles(t -> t.isDirectory())) {
			// Check loaded
			logger.debug("Checking " + folder.getName() + "...");
			PolyCollection col = collections.get(folder.getName());
			if (col == null) {
				// Delete if needed
				logger.debug("Collection " + folder.getName() + " is inactive, checking if files need purging...");
				File dir = new File(folder, "upgrade-temp");
				File tempF = new File(folder, "installing.list");
				File tempFCL = new File(folder, "newchangelog.log");
				File tempFM = new File(folder, "newmanifest.json");
				if (dir.exists() || tempF.exists() || tempFM.exists() || tempFCL.exists()) {
					logger.info("Purging download caches of " + folder.getName() + "...");
					if (dir.exists())
						deleteDir(dir);
					if (tempF.exists()) {
						tempF.delete();
						logger.debug("Deleted: " + tempF.getPath());
					}
					if (tempFCL.exists()) {
						tempFCL.delete();
						logger.debug("Deleted: " + tempFCL.getPath());
					}
					if (tempFM.exists()) {
						tempFM.delete();
						logger.debug("Deleted: " + tempFM.getPath());
					}
				}
			} else {
				// Check updates
				if (!col.hasUpdateAvailable() && !collectionsToReinstall.contains(col.getId())) {
					// Delete
					logger.debug(
							"Collection " + col.getId() + " is not being updated, checking if files need purging...");
					File dir = new File(folder, "upgrade-temp");
					File tempF = new File(folder, "installing.list");
					File tempFCL = new File(folder, "newchangelog.log");
					File tempFM = new File(folder, "newmanifest.json");
					if (dir.exists() || tempF.exists() || tempFM.exists() || tempFCL.exists()) {
						logger.info("Purging download caches of " + folder.getName() + "...");
						if (dir.exists())
							deleteDir(dir);
						if (tempF.exists()) {
							tempF.delete();
							logger.debug("Deleted: " + tempF.getPath());
						}
						if (tempFCL.exists()) {
							tempFCL.delete();
							logger.debug("Deleted: " + tempFCL.getPath());
						}
						if (tempFM.exists()) {
							tempFM.delete();
							logger.debug("Deleted: " + tempFM.getPath());
						}
					}
				}
			}
		}

		// Find collections needing installation but that could not be resolved
		boolean updateError = false;
		for (PolyCollection col : collections.values()) {
			if (!col.isInstalledLocally() && !col.hasUpdateAvailable()
					&& !collectionsToReinstall.contains(col.getId())) {
				// Error
				updateError = true;
				logger.error("Could not install collection " + col.getId()
						+ ", no sources were available for downloading the given collection!");
			}
		}
		if (updateError)
			logger.error("There were packages that could not be installed due to them not having available sources.");

		// Return
		logger.info("Download process finished!");
		return true;
	}

	private static void writeHashToList(FileOutputStream fO, String key, String hash) throws IOException {
		fO.write((key.replace(";", ";sl;").replace(":", ";cl;").replace(" ", ";sp;") + ": " + hash + "\n")
				.getBytes("UTF-8"));
		fO.flush();
	}

	private static void deleteDir(File dir) {
		if (Files.isSymbolicLink(dir.toPath())) {
			// DO NOT RECURSE
			dir.delete();
			logger.debug("Deleted: " + dir.getPath());
			return;
		}
		for (File subDir : dir.listFiles(t -> t.isDirectory())) {
			deleteDir(subDir);
		}
		for (File file : dir.listFiles(t -> !t.isDirectory())) {
			file.delete();
			logger.debug("Deleted: " + file.getPath());
		}
		dir.delete();
		logger.debug("Deleted: " + dir.getPath());
	}

	private static UpdateEntry parseUpdateEntry(String name) {
		// Check
		while (name.startsWith("/"))
			name = name.substring(1);
		while (name.endsWith("/"))
			name = name.substring(0, name.length() - 1);
		if (!name.contains("/"))
			return null;

		// Get element
		String elementType = name.substring(0, name.indexOf("/"));
		String path = name.substring(name.indexOf("/") + 1);
		while (elementType.equals("support")) {
			// Handle support
			String supportPackage = path;
			path = "";
			if (supportPackage.contains("/")) {
				path = supportPackage.substring(supportPackage.indexOf("/") + 1);
				supportPackage = supportPackage.substring(0, supportPackage.indexOf("/"));
			}
			if (supportPackage.isEmpty())
				return null;

			// Get module
			if (collections.containsKey(supportPackage)) {
				// Present
				// Check state
				if (!collections.get(supportPackage).hasUpdateAvailable()
						&& !collections.get(supportPackage).isInstalledLocally()) {
					return null;
				}

				// Handle
				if (!path.contains("/"))
					return null;
				elementType = path.substring(0, path.indexOf("/"));
				path = path.substring(path.indexOf("/") + 1);
			} else
				return null;
		}
		if (elementType.equals("skel")) {
			// Skeleton
			UpdateEntry e = new UpdateEntry();
			e.target = path;
			e.type = UpdateEntryType.SKEL;
			return e;
		}
		if (elementType.equals("payload")) {
			// Payload
			UpdateEntry e = new UpdateEntry();
			e.target = path;
			e.type = UpdateEntryType.PAYLOAD;
			return e;
		}
		if (elementType.equals("jsonmergers")) {
			// JSON merger
			UpdateEntry e = new UpdateEntry();
			e.target = path;
			e.type = UpdateEntryType.JSONMERGER;
			return e;
		}

		// Invalid
		return null;
	}

	private static String[] getSupportArtifactTargets(String name) {
		// Check
		while (name.startsWith("/"))
			name = name.substring(1);
		while (name.endsWith("/"))
			name = name.substring(0, name.length() - 1);
		if (!name.contains("/"))
			return null;

		// Get element
		ArrayList<String> artifacts = new ArrayList<String>();
		String elementType = name.substring(0, name.indexOf("/"));
		String path = name.substring(name.indexOf("/") + 1);
		while (elementType.equals("support")) {
			// Handle support
			String supportPackage = path;
			path = "";
			if (supportPackage.contains("/")) {
				path = supportPackage.substring(supportPackage.indexOf("/") + 1);
				supportPackage = supportPackage.substring(0, supportPackage.indexOf("/"));
			}
			if (supportPackage.isEmpty())
				return null;

			// Handle
			if (!path.contains("/"))
				return null;
			elementType = path.substring(0, path.indexOf("/"));
			path = path.substring(path.indexOf("/") + 1);

			// Add
			if (!artifacts.contains(supportPackage))
				artifacts.add(supportPackage);
		}

		// Return
		return artifacts.toArray(t -> new String[t]);
	}

	private static class InstallEntry {
		public HashMap<String, String> localHashes = new LinkedHashMap<String, String>();
		public HashMap<String, String> filesToInstall = new LinkedHashMap<String, String>();
		public ArrayList<String> previouslyInstalledFiles = new ArrayList<String>();
	}

	private static class UpdateEntry {
		public String target;
		public UpdateEntryType type;
	}

	private static enum UpdateEntryType {
		SKEL,

		PAYLOAD,

		JSONMERGER
	}

	private static void loadHashList(String hashes, HashMap<String, String> build) {
		for (String line : hashes.replace("\r", "").split("\n")) {
			if (line.isEmpty() || !line.contains(": "))
				continue;
			String name = line.substring(0, line.indexOf(": ")).replace(";sp;", " ").replace(";cl;", ":")
					.replace(";sl;", ";");
			String hash = line.substring(line.indexOf(": ") + 2);
			build.put(name, hash);
		}
	}

	/**
	 * Installs JSON upgrade folders
	 * 
	 * @param target Target install path
	 */
	public static void installJsonUpgrades(File target) {
		if (logger == null)
			logger = LogManager.getLogger("PolyUpdater");
		File jsonUpgrade = new File(target, "jsonupgrade");
		if (jsonUpgrade.exists()) {
			// Go through json upgrade
			logger.info("Upgrading json files from upgrade payloads...");
			for (File col : jsonUpgrade.listFiles(t -> t.isDirectory())) {
				installJsonUpgrade(target, col);
				col.delete();
			}
			jsonUpgrade.delete();
		}
	}

	private static void installJsonUpgrade(File target, File source) {
		for (File f : source.listFiles(t -> t.isFile())) {
			try {
				File jsonTarget = new File(target, f.getName());
				FileReader fIn = new FileReader(f);
				JsonElement patch;
				try {
					patch = JsonParser.parseReader(fIn);
				} finally {
					fIn.close();
				}
				if (!jsonTarget.exists()) {
					// Write
					Files.writeString(jsonTarget.toPath(),
							new Gson().newBuilder().setPrettyPrinting().create().toJson(patch));
				}

				// We only support json objects
				if (!patch.isJsonObject())
					throw new IllegalArgumentException();
				JsonObject patchJson = patch.getAsJsonObject();

				FileReader sIn = new FileReader(jsonTarget);
				JsonElement targetFile;
				try {
					targetFile = JsonParser.parseReader(sIn);
				} finally {
					sIn.close();
				}

				// We only support json objects
				if (!targetFile.isJsonObject())
					throw new IllegalArgumentException();

				// Get object
				JsonObject targetObj = targetFile.getAsJsonObject();

				// Merge
				logger.info("Installing patch " + f.getPath() + " into " + jsonTarget.getPath());
				mergeObject(patchJson, targetObj);

				// Write
				FileWriter fO = new FileWriter(jsonTarget);
				new Gson().newBuilder().setPrettyPrinting().create().toJson(targetObj, fO);
				fO.close();
			} catch (Exception e) {
			}
			f.delete();
		}
		for (File d : source.listFiles(t -> t.isDirectory())) {
			installJsonUpgrade(new File(target, d.getName()), d);
			d.delete();
		}
	}

	private static void mergeObject(JsonObject source, JsonObject target) {
		// Merge each entry
		for (String key : source.keySet()) {
			if (source.get(key).isJsonObject()) {
				JsonObject chT = createOrGetJsonObject(target, key);
				mergeObject(source.get(key).getAsJsonObject(), chT);
			} else
				target.add(key, source.get(key));
		}
	}

	private static JsonObject createOrGetJsonObject(JsonObject obj, String name) {
		if (obj.has(name))
			return obj.get(name).getAsJsonObject();
		JsonObject res = new JsonObject();
		obj.add(name, res);
		return res;
	}

}
