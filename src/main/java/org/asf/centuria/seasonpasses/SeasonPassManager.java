package org.asf.centuria.seasonpasses;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.TimeZone;

import org.apache.logging.log4j.MarkerManager;

import org.asf.centuria.Centuria;
import org.asf.centuria.modules.ICenturiaModule;
import org.asf.centuria.modules.ModuleManager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 
 * Season Pass Manager
 * 
 * @author Sky Swimmer
 *
 */
public class SeasonPassManager {

	private static HashMap<String, SeasonPassDefinition> passes = new HashMap<String, SeasonPassDefinition>();
	private static SeasonPassDefinition currentPass;

	static {
		// Load the helper
		Centuria.logger.info(MarkerManager.getMarker("SeasonManager"), "Loading season passes...");
		try {
			// Find the transformer document
			InputStream strm = SeasonPassManager.class.getClassLoader().getResourceAsStream("seasonpasses/index.json");
			JsonArray index = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8")).getAsJsonArray();
			strm.close();

			// Load all transformers
			for (JsonElement ele : index) {
				try {
					// Find the transformer document
					strm = SeasonPassManager.class.getClassLoader()
							.getResourceAsStream("seasonpasses/" + ele.getAsString());
					JsonObject pass = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8"))
							.getAsJsonObject();
					loadSeasonPass(pass, ele.getAsString());
					strm.close();
				} catch (Exception e) {
					Centuria.logger.error(MarkerManager.getMarker("SeasonManager"),
							"Season pass failed to load: " + ele.getAsString() + " (centuria)", e);
				}
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		// Load transformers
		Centuria.logger.info(MarkerManager.getMarker("SeasonManager"), "Loading season pass transformers...");
		loadSeasonPassTransformers(SeasonPassManager.class);

		// Load module transformers
		for (ICenturiaModule module : ModuleManager.getInstance().getAllModules()) {
			loadSeasonPassTransformers(module.getClass());
		}

		// Load challenges
		// FIXME

		// Load tiers
		// FIXME

		// Find current active pass
		SeasonPassDefinition current = getCurrentPass();
		Centuria.logger.info("Loading active pass...");
		if (current == null)
			Centuria.logger.info(MarkerManager.getMarker("SeasonManager"), "Current season pass: none");
		else
			Centuria.logger.info(MarkerManager.getMarker("SeasonManager"),
					"Current season pass: " + current.passDefID + ", name: " + current.name + ", tiers: "
							+ current.tiers.size() + ", challenges: " + current.challenges.size());
	}

	private static void loadSeasonPass(JsonObject obj, String fileName) throws ParseException {
		// Time format
		SimpleDateFormat fmt = new SimpleDateFormat("yyyy'-'MM'-'dd'T'HH':'mm':'ssXXX");
		fmt.setTimeZone(TimeZone.getTimeZone("UTC"));

		// Read pass
		Centuria.logger.debug(MarkerManager.getMarker("SeasonManager"), "Loading pass file: " + fileName);

		// Load data
		SeasonPassDefinition def;
		String defID = obj.get("passDefID").getAsString();
		if (passes.containsKey(defID)) {
			// Load existing pass
			def = passes.get(defID);
		} else {
			// Create new definition
			def = new SeasonPassDefinition();
		}
		def.passDefID = defID;
		if (obj.has("name"))
			def.name = obj.get("name").getAsString();
		if (obj.has("objectName"))
			def.objectName = obj.get("objectName").getAsString();
		if (obj.has("startDate"))
			def.startDate = fmt.parse(obj.get("startDate").getAsString());
		if (obj.has("endDate"))
			def.endDate = fmt.parse(obj.get("endDate").getAsString());
		if (obj.has("yearly"))
			def.yearly = obj.get("yearly").getAsBoolean();
		if (obj.has("premiumPassID"))
			def.premiumPassID = obj.get("premiumPassID").getAsString();
		if (obj.has("tiers"))
			obj.get("tiers").getAsJsonArray().forEach(t -> def.tiers.add(t.getAsString()));
		if (obj.has("challenges"))
			obj.get("challenges").getAsJsonArray().forEach(t -> def.challenges.add(t.getAsString()));

		// Update
		def.updateTimestamps();

		// Add
		passes.put(def.passDefID, def);
		Centuria.logger.info(MarkerManager.getMarker("SeasonManager"),
				"Loaded season pass " + def.passDefID + ": " + def.name);
		Centuria.logger.debug(MarkerManager.getMarker("SeasonManager"),
				def.challenges.size() + " challenges loaded, " + def.tiers.size() + " tiers loaded.");
	}

	private static void loadSeasonPassTransformers(Class<?> cls) {
		URL source = cls.getProtectionDomain().getCodeSource().getLocation();

		// Generate a base URL
		String baseURL = "";
		String fileName = "";
		try {
			File sourceFile = new File(source.toURI());
			fileName = sourceFile.getName();
			if (sourceFile.isDirectory()) {
				baseURL = source + (source.toString().endsWith("/") ? "" : "/");
			} else {
				baseURL = "jar:" + source + "!/";
			}
		} catch (Exception e) {
			return;
		}

		try {
			// Find the transformer document
			InputStream strm = new URL(baseURL + "seasonpasstransformers/index.json").openStream();
			JsonArray index = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8")).getAsJsonArray();
			strm.close();

			// Log
			Centuria.logger.info(MarkerManager.getMarker("SeasonManager"),
					"Loading season pass transformers for " + cls.getTypeName() + "...");

			// Load all transformers
			for (JsonElement ele : index) {
				try {
					// Find the transformer document
					strm = new URL(baseURL + "seasonpasstransformers/" + ele.getAsString()).openStream();
					JsonObject transformer = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8"))
							.getAsJsonObject();
					strm.close();

					// Load transformer
					loadSeasonPass(transformer, ele.getAsString());
				} catch (Exception e) {
					Centuria.logger.error(MarkerManager.getMarker("SeasonManager"),
							"Transformer failed to load: " + ele.getAsString() + " (" + fileName + ")", e);
				}
			}
		} catch (Exception e) {
			if (e instanceof FileNotFoundException)
				return;
			throw new RuntimeException(e);
		}
	}

	/**
	 * Retrieves season passes by ID
	 * 
	 * @param defID Pass definition ID
	 * @return Season pass definition or null
	 */
	public static SeasonPassDefinition getPass(int defID) {
		return getPass(Integer.toString(defID));
	}

	/**
	 * Retrieves season passes by ID
	 * 
	 * @param defID Pass definition ID
	 * @return Season pass definition or null
	 */
	public static SeasonPassDefinition getPass(String defID) {
		return passes.get(defID);
	}

	/**
	 * Retrieves the active season pass
	 * 
	 * @return Season pass definition or null
	 */
	public static SeasonPassDefinition getCurrentPass() {
		if (currentPass != null && System.currentTimeMillis() < currentPass.endDateLiveTimestamp)
			return currentPass;

		// Find pass
		for (SeasonPassDefinition pass : passes.values()) {
			// Check yearly
			// First pass over passes skips these
			if (pass.yearly)
				continue;

			// Check start and end
			if (System.currentTimeMillis() >= pass.startDateLiveTimestamp
					&& System.currentTimeMillis() < pass.endDateLiveTimestamp) {
				currentPass = pass;
				return pass; // Found a match
			}
		}

		// Check if any yearly pass exists
		for (SeasonPassDefinition pass : passes.values()) {
			// Only do yearly passes
			if (!pass.yearly)
				continue;

			// Update
			pass.updateTimestamps();

			// Check start and end
			if (System.currentTimeMillis() >= pass.startDateLiveTimestamp
					&& System.currentTimeMillis() < pass.endDateLiveTimestamp) {
				currentPass = pass;
				return pass; // Found a match
			}
		}

		// Not found
		return null;
	}

}
