package org.asf.centuria.updater;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;

public class PolyUpdaterInstaller {

	private static File target;
	private static File packageCache;

	private static boolean inited = false;

	/**
	 * Initializes the update installer
	 * 
	 * @throws IOException
	 */
	public static void init(File packageCache, File target) throws IOException {
		if (inited)
			throw new IllegalStateException("Already initialized");
		inited = true;

		// Set fields
		PolyUpdaterInstaller.target = target;
		PolyUpdaterInstaller.packageCache = packageCache;
	}

	private static class CollectionInstallEntry {
		public File cache;
		public File changelogFile;
		public File manifestFile;
		public File changelogFileTarget;
		public File manifestFileTarget;
		public File cacheListRolling;
		public File cacheListInstalled;
		public File cacheListNew;
		public HashMap<String, String> hashList;
	}

	/**
	 * Installs all packages
	 */
	public static void installAll() throws IOException {
		int totalFiles = 0;
		int startAt = 0;
		LinkedHashMap<String, CollectionInstallEntry> entries = new LinkedHashMap<String, CollectionInstallEntry>();
		System.out.println("Finding collections to install...");
		for (File folder : packageCache.listFiles(t -> t.isDirectory())) {
			File cache = new File(folder, "upgrade-temp");
			File changelogFile = new File(folder, "newchangelog.log");
			File manifestFile = new File(folder, "newmanifest.json");
			File changelogFileTarget = new File(folder, "changelog.log");
			File manifestFileTarget = new File(folder, "manifest.json");

			// Find files
			File cacheListRolling = new File(folder, "installing.list");
			File cacheListInstalled = new File(folder, "installed.list");
			File cacheListNew = new File(folder, "newinstalled.list");

			// Prepare install
			if (cache.exists()) {
				// Read entry files
				System.out.println("Preparing to install " + folder.getName() + "...");
				HashMap<String, String> current = new LinkedHashMap<String, String>();
				String hashesInstall = Files.readString(cacheListRolling.toPath());
				loadHashList(hashesInstall, current);

				// Create entry
				CollectionInstallEntry ent = new CollectionInstallEntry();
				ent.cache = cache;
				ent.changelogFile = changelogFile;
				ent.manifestFile = manifestFile;
				ent.changelogFileTarget = changelogFileTarget;
				ent.manifestFileTarget = manifestFileTarget;
				ent.cacheListInstalled = cacheListInstalled;
				ent.cacheListRolling = cacheListRolling;
				ent.cacheListNew = cacheListNew;
				ent.hashList = current;
				entries.put(folder.getName(), ent);
			}
		}
		for (File folder : packageCache.listFiles(t -> t.isDirectory())) {
			File cache = new File(folder, "upgrade-temp");

			// Prepare install
			if (cache.exists()) {
				// Find already-installed packages
				for (String name : entries.get(folder.getName()).hashList.keySet()) {
					UpdateEntry file = parseUpdateEntry(name, entries.keySet());
					if (file == null || name.endsWith("/.keepempty"))
						continue;
					totalFiles++;

					File inputCacheFile = new File(cache, name);
					if (!inputCacheFile.exists()) {
						// Already installed
						startAt++;
					}
				}
			}
		}

		// Install
		int i = startAt + 1;
		System.out.println("Installing all collections...");
		for (String id : entries.keySet()) {
			CollectionInstallEntry entry = entries.get(id);

			// Load hashes
			HashMap<String, String> currentlyInstalled = new LinkedHashMap<String, String>();
			if (entry.cacheListInstalled.exists()) {
				String hashesInstall = Files.readString(entry.cacheListInstalled.toPath());
				loadHashList(hashesInstall, currentlyInstalled);
			}

			// Install files
			for (String name : entry.hashList.keySet()) {
				File inputCacheFile = new File(entry.cache, name);
				if (inputCacheFile.exists()) {
					// Get entry
					UpdateEntry update = parseUpdateEntry(name, entries.keySet());
					if (update == null)
						continue;

					// Install
					if (!name.endsWith("/.keepempty"))
						System.out.println("[" + (i++) + "/" + totalFiles + "] Installing " + id + ": " + name + "...");

					// Get output
					String localInstalledHash = null;
					File targetFile = new File(target, update.target);
					if (targetFile.exists()) {
						// Get local has
						FileInputStream fIn = new FileInputStream(targetFile);
						localInstalledHash = PolyTools.sha256Hash(fIn);
						fIn.close();
					}
					String expectedInstalledHash = null;
					if (currentlyInstalled != null && currentlyInstalled.containsKey(name))
						expectedInstalledHash = currentlyInstalled.get(name);

					// Find conflicting
					if (targetFile.exists()) {
						File[] conflicting = targetFile.getParentFile()
								.listFiles(t -> !t.getName().equals(targetFile.getName())
										&& t.getName().equalsIgnoreCase(targetFile.getName()));
						for (File conflict : conflicting) {
							conflict.delete();
						}
					}

					// Check type
					switch (update.type) {

					case JSONMERGER: {
						// Create folder
						if (!targetFile.getParentFile().exists())
							targetFile.getParentFile().mkdirs();

						// Continue if type is just a folder (.keepempty isnt copied)
						if (name.endsWith("/.keepempty")) {
							inputCacheFile.delete();
							continue;
						}

						// Real json merger
						File jsonMergerOut = new File(new File(new File(target, "jsonupgrade"), id), update.target);
						if (!jsonMergerOut.getParentFile().exists())
							jsonMergerOut.getParentFile().mkdirs();

						// Delete if needed
						if (jsonMergerOut.exists())
							jsonMergerOut.delete();

						// Write
						inputCacheFile.renameTo(jsonMergerOut);

						break;
					}

					case PAYLOAD:
					case SKEL: {
						// Create folder
						if (!targetFile.getParentFile().exists())
							targetFile.getParentFile().mkdirs();

						// Continue if type is just a folder (.keepempty isnt copied)
						if (name.endsWith("/.keepempty")) {
							inputCacheFile.delete();
							continue;
						}

						// Check target file
						// If it exists, check if the local cache matches the installed file
						if (targetFile.exists() && update.type == UpdateEntryType.SKEL
								&& (expectedInstalledHash == null || (expectedInstalledHash != null
										&& !localInstalledHash.equals(expectedInstalledHash)))) {
							// Skip
							inputCacheFile.delete();
							continue;
						}

						// Delete if needed
						if (targetFile.exists())
							targetFile.delete();

						// Write
						inputCacheFile.renameTo(targetFile);
						break;
					}

					}
				}
			}

			// Delete cache contents only
			for (File content : entry.cache.listFiles()) {
				if (content.isDirectory())
					deleteDir(content);
				else
					content.delete();
			}
		}

		// Delete removed files
		System.out.println("Removing deleted files...");
		for (String id : entries.keySet()) {
			CollectionInstallEntry entry = entries.get(id);

			// Load hashes
			if (entry.cacheListNew.exists()) {
				HashMap<String, String> newInstallList = new LinkedHashMap<String, String>();
				String hashesInstallNew = Files.readString(entry.cacheListNew.toPath());
				loadHashList(hashesInstallNew, newInstallList);

				// Go through local files
				if (entry.cacheListInstalled.exists()) {
					// Load hashes
					HashMap<String, String> currentlyInstalled = new LinkedHashMap<String, String>();
					String hashesInstall = Files.readString(entry.cacheListInstalled.toPath());
					loadHashList(hashesInstall, currentlyInstalled);

					// Go through hashes
					for (String file : currentlyInstalled.keySet()) {
						if (!newInstallList.containsKey(file)) {
							// Deleted upstream

							// Get entry
							String expectedHash = currentlyInstalled.get(file);
							UpdateEntry update = parseUpdateEntry(file, null);
							if (update == null)
								continue;

							// Get local file
							File local = new File(target, update.target);
							if (local.exists()) {
								// Get local has
								FileInputStream fIn = new FileInputStream(local);
								String localHash = PolyTools.sha256Hash(fIn);
								fIn.close();

								// Check
								if (localHash.equals(expectedHash)) {
									// Delete
									System.out.println("Deleting: " + file + "...");
									local.delete();
								}
							}
						}
					}
				}
			}
		}

		// Install local data
		System.out.println("Installing local update data...");
		File rawUpdates = new File(target, "upgradedata");
		if (rawUpdates.exists() && rawUpdates.isDirectory()) {
			// Install
			File forced = new File(rawUpdates, "forceinstall");
			if (forced.exists())
				forced.delete();
			forced = new File(rawUpdates, "forceinstall.txt");
			if (forced.exists())
				forced.delete();
			installDir(rawUpdates, target, "");
		}

		// Write manifests
		System.out.println("Writing update information...");
		for (String id : entries.keySet()) {
			CollectionInstallEntry entry = entries.get(id);

			// Migrate hash lists
			System.out.println("Writing update information for " + id + "...");
			HashMap<String, String> current = new LinkedHashMap<String, String>();

			// Load existing
			if (entry.cacheListInstalled.exists()) {
				String hashesInstall = Files.readString(entry.cacheListInstalled.toPath());
				loadHashList(hashesInstall, current);
			}
			if (entry.cacheListNew.exists()) {
				String hashesInstall = Files.readString(entry.cacheListNew.toPath());
				loadHashList(hashesInstall, current);
				entry.cacheListNew.delete();
			}
			entry.cacheListRolling.delete();

			// Add new hashes
			current.putAll(entry.hashList);

			// Write
			FileOutputStream fO = new FileOutputStream(entry.cacheListInstalled);
			for (String name : current.keySet()) {
				String hash = current.get(name);
				writeHashToList(fO, name, hash);
			}

			// Delete old manifest
			if (entry.manifestFileTarget.exists())
				entry.manifestFileTarget.delete();

			// Delete old changelog
			if (entry.changelogFileTarget.exists())
				entry.changelogFileTarget.delete();

			// Install manifest
			entry.manifestFile.renameTo(entry.manifestFileTarget);

			// Install changelog if absent
			if (entry.changelogFile.exists())
				entry.changelogFile.renameTo(entry.changelogFileTarget);

			// Delete cache
			deleteDir(entry.cache);
		}
		System.out.println("Update process completed!");
	}

	private static void writeHashToList(FileOutputStream fO, String key, String hash) throws IOException {
		fO.write((key.replace(";", ";sl;").replace(":", ";cl;").replace(" ", ";sp;") + ": " + hash + "\n")
				.getBytes("UTF-8"));
		fO.flush();
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

	private static void deleteDir(File dir) {
		if (Files.isSymbolicLink(dir.toPath())) {
			// DO NOT RECURSE
			dir.delete();
			return;
		}
		for (File subDir : dir.listFiles(t -> t.isDirectory())) {
			deleteDir(subDir);
		}
		for (File file : dir.listFiles(t -> !t.isDirectory())) {
			file.delete();
		}
		dir.delete();
	}

	private static void installDir(File dir, File target, String prefix) {
		if (Files.isSymbolicLink(dir.toPath())) {
			// DO NOT RECURSE

			// Move
			if (target.exists())
				target.delete();
			dir.renameTo(target);
			return;
		}
		for (File subDir : dir.listFiles(t -> t.isDirectory())) {
			installDir(subDir, new File(dir, subDir.getName()), prefix + subDir.getName() + "/");
		}
		for (File file : dir.listFiles(t -> !t.isDirectory())) {
			// Move
			System.out.println("Installing: " + prefix + file.getName());
			File targetF = new File(target, file.getName());
			if (targetF.exists())
				targetF.delete();
			file.renameTo(targetF);
		}

		// Check empty
		if (dir.listFiles().length == 0)
			dir.delete();
	}

	private static UpdateEntry parseUpdateEntry(String name, Collection<String> activeCollections) {
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
			if (activeCollections == null || activeCollections.contains(supportPackage) || new File(new File(packageCache, supportPackage), "installed.list").exists()) {
				// Present

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

	private static class UpdateEntry {
		public String target;
		public UpdateEntryType type;
	}

	private static enum UpdateEntryType {
		SKEL,

		PAYLOAD,

		JSONMERGER
	}

}
