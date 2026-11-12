package org.asf.centuria.updater.repositories;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.security.PublicKey;

import org.apache.logging.log4j.Logger;
import org.asf.centuria.updater.PolyTools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class PolyRepositoryDef {

	private String id;
	private String url;

	private File keyFileCache;
	private PublicKey key;

	private boolean available = false;

	private boolean inited = false;

	public PolyRepositoryDef(String id, String url, File keyFileCache) {
		if (!url.endsWith("/"))
			url += "/";
		this.id = id;
		this.keyFileCache = keyFileCache;
		this.url = url;
	}

	/**
	 * Initializes (INTERNAL)
	 */
	public void init(Logger logger) throws IOException {
		if (inited)
			throw new IllegalStateException("Already initialized");
		inited = true;

		// Check file
		if (!keyFileCache.exists())
			keyFileCache.mkdirs();

		// Log
		logger.info("Initializing repository " + id + "...");
		logger.debug("Checking for key updates...");
		String current = "";
		File currentKeyJson = new File(keyFileCache, "current.json");
		File currentKeyFile = new File(keyFileCache, "current.pem");
		if (currentKeyJson.exists() && currentKeyFile.exists()) {
			// Load
			logger.debug("Loading existing key for " + id + "...");
			key = PolyTools.loadPublic(currentKeyFile);

			// Load config
			logger.debug("Loading key configuration for " + id + "...");
			current = JsonParser.parseString(Files.readString(currentKeyJson.toPath())).getAsJsonObject().get("id")
					.getAsString();
		}

		// Check for updates
		logger.debug("Contacting server...");
		available = true;
		boolean loggedUpdate = false;
		boolean wasDownloaded = false;
		try {
			String configFileString = "keyconfig-" + current + ".json";
			if (current.isEmpty())
				configFileString = "current.json";
			URL u = new URL(url + "signing/" + configFileString);
			InputStream strm = u.openStream();
			String json = new String(strm.readAllBytes(), "UTF-8");
			strm.close();
			JsonObject upstream = JsonParser.parseString(json).getAsJsonObject();
			while (true) {
				if (key == null) {
					logger.info("Downloading repository key...");
					u = new URL(url + "signing/publickey-"
							+ upstream.get("content").getAsJsonObject().get("id").getAsString() + ".pem");
					strm = u.openStream();
					FileOutputStream fO = new FileOutputStream(currentKeyFile);
					strm.transferTo(fO);
					fO.close();
					strm.close();
					key = PolyTools.loadPublic(currentKeyFile);
					wasDownloaded = true;
				}

				// Verify
				logger.debug("Verifying configuration...");
				JsonElement res = PolyTools.verify(key, upstream);
				if (res == null) {
					if (wasDownloaded) {
						currentKeyFile.delete();
					}
					logger.error("Could not update key for repository " + id
							+ ", the configuration it sent was not signed using the right key!");
					available = false;
					return;
				}
				logger.debug("Reading configuration...");
				upstream = res.getAsJsonObject();
				if (wasDownloaded) {
					// Write
					FileOutputStream fO = new FileOutputStream(currentKeyJson);
					fO.write(upstream.toString().getBytes("UTF-8"));
					fO.close();
				}

				// Check result
				if (!upstream.has("newconfig"))
					break; // Up to date
				try {
					// Update
					if (!loggedUpdate)
						logger.info("Updating keys for " + id + "...");
					else
						logger.debug("Updating keys for " + id + "...");
					loggedUpdate = true;

					// Get pem
					logger.debug("Writing new key...");
					String keyPem = upstream.get("newkey").getAsString();
					FileOutputStream fO = new FileOutputStream(currentKeyFile);
					fO.write(keyPem.getBytes("UTF-8"));
					fO.close();
					key = PolyTools.loadPublic(currentKeyFile);

					// Download new config config
					logger.debug("Downloading new key config...");
					current = upstream.get("newconfig").getAsString();
					u = new URL(url + "signing/keyconfig-" + current + ".json");
					strm = u.openStream();
					json = new String(strm.readAllBytes(), "UTF-8");
					strm.close();
					upstream = JsonParser.parseString(json).getAsJsonObject();
					fO = new FileOutputStream(currentKeyJson);
					fO.write(upstream.toString().getBytes("UTF-8"));
					fO.close();

					logger.debug("Verifying configuration...");
					res = PolyTools.verify(key, upstream);
					if (res == null) {
						logger.error("Could not update key for repository " + id
								+ ", the configuration it sent was not signed using the right key!");
						available = false;
						return;
					}
					logger.debug("Update completed!");
				} catch (Exception e) {
					logger.error("Could not update key from repository " + id, e);
					available = false;
					return;
				}

				// Repeat until done
			}
		} catch (Exception e) {
			logger.error("Could not contact repository " + id + ", it may be offline.");
			available = false;
			return;
		}

		// Done updating
		if (loggedUpdate || wasDownloaded)
			logger.info("Succesfully initialized repository " + id + "!");
		else
			logger.debug("Succesfully initialized repository " + id + "!");
	}

	/**
	 * Verifies signature of elements using the repository keys
	 * 
	 * @param entity Entity to verify
	 * @return Deserialized entity or null if invalid signature
	 */
	public JsonElement verifySignature(JsonObject entity) {
		return PolyTools.verify(key, entity);
	}

	/**
	 * Checks if the repository is available
	 * 
	 * @return True if available, false otherwise
	 */
	public boolean isAvailable() {
		return available;
	}

	/**
	 * Retrieves the repository ID
	 * 
	 * @return Repository ID string
	 */
	public String getId() {
		return id;
	}

	/**
	 * Retrieves the repository URL
	 * 
	 * @return Repository URL string
	 */
	public String getUrl() {
		return url;
	}

}
