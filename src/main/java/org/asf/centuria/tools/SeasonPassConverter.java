package org.asf.centuria.tools;

import java.nio.file.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.TimeZone;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class SeasonPassConverter {

	public static void main(String[] args) throws IOException, ParseException {
		// This tool generates a shops.json file
		//
		// Expected program arguments:
		// <craftableitemchart-csv-file> <localization-csv-file>
		// <calendarchart-csv-file>

		String lastName = "";
		String lastData = null;
		String lastID = null;
		LinkedHashMap<String, String> objectNames = new LinkedHashMap<String, String>();
		LinkedHashMap<String, JsonObject> calendarEntries = new LinkedHashMap<String, JsonObject>();

		File output = new File("seasonpasses");
		output.mkdirs();
		File outputTiers = new File(output, "tiers");
		outputTiers.mkdirs();
		File outputChallenges = new File(output, "challenges");
		outputChallenges.mkdirs();

		JsonObject res = new JsonObject();
		JsonObject localization = new JsonObject();
		System.out.println("Loading localization...");
		for (String line : Files.readAllLines(Path.of(args[1]))) {
			if (line.startsWith("\"") && !line.startsWith("\"\"")) {
				lastID = line.substring(1);
				lastID = lastID.substring(0, lastID.indexOf(","));
				lastData = "";
			} else if (line.startsWith("\"\"")) {
				lastData = "{\n" + lastData;
				lastData = lastData.replace("`", "\"");
				JsonObject obj = JsonParser.parseString(lastData).getAsJsonObject();
				if (obj.get("templateClass").getAsString().equals("LocalizationTemplate")) {
					for (JsonElement ele : obj.get("components").getAsJsonArray()) {
						JsonObject data = ele.getAsJsonObject();
						if (data.get("componentClass").getAsString().equals("LocalizationDefComponent")) {
							data = data.get("componentJSON").getAsJsonObject();
							localization.addProperty(lastID, data.get("en").getAsString());
						}
					}
				}
			} else if (lastData != null) {
				lastData += line + "\n";
			}
		}
		res.add("Localization", localization);

		lastName = "";
		lastData = null;
		lastID = null;
		System.out.println("Loading calendar chart...");
		for (String line : Files.readAllLines(Path.of(args[2]))) {
			if (line.startsWith("\"") && !line.startsWith("\"\"") && !line.startsWith("\"DefID")) {
				lastID = line.substring(1);
				lastName = lastID.substring(lastID.indexOf(",\"\"") + 3);
				lastName = lastName.substring(0, lastName.indexOf("\""));
				lastID = lastID.substring(0, lastID.indexOf(","));
				lastData = "";

				objectNames.put(lastID, lastName);
			} else if (line.startsWith("\"\"")) {
				lastData = "{\n" + lastData;
				lastData = lastData.replace("`", "\"");
				String objName = objectNames.get(lastID);
				JsonObject obj = JsonParser.parseString(lastData).getAsJsonObject();

				for (JsonElement ele : obj.get("components").getAsJsonArray()) {
					JsonObject data = ele.getAsJsonObject();
					if (data.get("componentClass").getAsString().equals("AvailabilityDefComponent")) {
						data = data.get("componentJSON").getAsJsonObject().get("chartDateAvailability")
								.getAsJsonObject();
						calendarEntries.put(objName, data);
						calendarEntries.put(lastID, data);
					}
				}

				lastData = null;
			} else if (lastData != null) {
				lastData += line + "\n";
			}
		}

		lastName = "";
		lastData = null;
		lastID = null;
		System.out.println("Loading item chart...");
		System.out.println("Dumping season passes...");
		ArrayList<String> passes = new ArrayList<String>();
		for (String line : Files.readAllLines(Path.of(args[0]))) {
			if (line.startsWith("\"") && !line.startsWith("\"\"") && !line.startsWith("\"DefID")) {
				lastID = line.substring(1);
				lastName = lastID.substring(lastID.indexOf(",\"\"") + 3);
				lastName = lastName.substring(0, lastName.indexOf("\""));
				lastID = lastID.substring(0, lastID.indexOf(","));
				lastData = "";

				objectNames.put(lastID, lastName);
			} else if (line.startsWith("\"\"")) {
				lastData = "{\n" + lastData;
				lastData = lastData.replace("`", "\"");
				String objName = objectNames.get(lastID);

				// Parse def
				JsonObject pass = null;
				JsonObject availability = null;
				String nameDefId = null;
				JsonObject obj = JsonParser.parseString(lastData).getAsJsonObject();
				for (JsonElement ele : obj.get("components").getAsJsonArray()) {
					JsonObject data = ele.getAsJsonObject();
					if (data.get("componentClass").getAsString().equals("SeasonPassDefComponent")) {
						data = data.get("componentJSON").getAsJsonObject();
						pass = data;
					} else if (data.get("componentClass").getAsString().equals("LocalizedNameDefComponent")) {
						data = data.get("componentJSON").getAsJsonObject();
						nameDefId = data.get("localizedDefID").getAsString();
					}
				}

				// Check result
				if (pass == null)
					continue;

				// Get pass name
				String name = objName;
				if (nameDefId != null) {
					if (localization.has(nameDefId))
						name = localization.get(nameDefId).getAsString();
				}

				// Locate calendar
				String calName = objName;
				if (objName.startsWith("SeasonPass/")) {
					calName = objName.substring("SeasonPass/".length());
				}
				availability = calendarEntries.get(calName);
				if (availability == null) {
					// Try another format // HACKY
					calName = calName.replace("0", "");
					availability = calendarEntries.get(calName);
				}

				// Write if fully valid
				if (pass != null && availability != null) {
					// Parse season pass
					System.out.println("Parsing season pass: " + lastID + " - " + objName + ": " + name);
					String startDate = availability.get("_dateStart").getAsString();
					String endDate = availability.get("_dateEnd").getAsString();
					boolean yearly = availability.get("_ignoreYear").getAsBoolean();

					// Parse pass
					SimpleDateFormat fmt = new SimpleDateFormat("MM'/'dd'/'yyyy");
					fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
					Date dStart = fmt.parse(startDate);
					Date dEnd = fmt.parse(endDate);
					SimpleDateFormat fmtO = new SimpleDateFormat("yyyy'-'MM'-'dd'T'HH':'mm':'ssXXX");
					fmtO.setTimeZone(TimeZone.getTimeZone("UTC"));

					// Build keys
					String passID = lastID;
					String passName = name;
					String passObj = objName;
					String dateStartCenturia = fmtO.format(dStart);
					String dateEndCenturia = fmtO.format(dEnd);
					String premiumPassID = pass.get("_purchaseableDef").getAsString();

					// Parse tiers
					ArrayList<String> tiers = new ArrayList<String>();
					ArrayList<String> challenges = new ArrayList<String>();
					for (JsonElement tier : pass.get("tiers").getAsJsonObject().get("_defIDs").getAsJsonArray()) {
						tiers.add(tier.getAsString());
					}
					for (JsonElement challenge : pass.get("challenges").getAsJsonObject().get("_defIDs")
							.getAsJsonArray()) {
						challenges.add(challenge.getAsString());
					}

					// Dump
					System.out.println();
					System.out.println("ID: " + passID + " - Name: " + passName);
					System.out.println("Object name: " + passObj);
					System.out.println("Start: " + dateStartCenturia);
					System.out.println("End: " + dateEndCenturia);
					System.out.println("Yearly: " + yearly);
					System.out.println("Premium pass ID: " + premiumPassID);
					System.out.println("Tiers: " + tiers.size() + " tiers");
					System.out.println("Challenges: " + challenges.size() + " challenges");
					System.out.println();

					// Create object and write
					JsonObject seasonPassObj = new JsonObject();
					seasonPassObj.addProperty("name", passName);
					seasonPassObj.addProperty("objectName", passObj);
					seasonPassObj.addProperty("passDefID", passID);
					seasonPassObj.addProperty("startDate", dateStartCenturia);
					seasonPassObj.addProperty("endDate", dateEndCenturia);
					seasonPassObj.addProperty("yearly", yearly);
					seasonPassObj.addProperty("premiumPassID", premiumPassID);

					// Add tiers
					JsonArray tiersArr = new JsonArray();
					for (String tier : tiers)
						tiersArr.add(tier);
					seasonPassObj.add("tiers", tiersArr);

					// Add tiers
					JsonArray challengesArr = new JsonArray();
					for (String challenge : challenges)
						challengesArr.add(challenge);
					seasonPassObj.add("challenges", challengesArr);

					// Write to json
					String json = new Gson().newBuilder().setPrettyPrinting().create().toJson(seasonPassObj);
					File fO = new File(output, passID + ".json");
					Files.writeString(fO.toPath(), json);
					passes.add(passID + ".json");
				}

				lastData = null;
			} else if (lastData != null) {
				lastData += line + "\n";
			}
		}

		// Write tiers
		lastName = "";
		lastData = null;
		lastID = null;
		System.out.println("Dumping season pass tiers...");
		ArrayList<String> tierList = new ArrayList<String>();
		for (String line : Files.readAllLines(Path.of(args[0]))) {
			if (line.startsWith("\"") && !line.startsWith("\"\"") && !line.startsWith("\"DefID")) {
				lastID = line.substring(1);
				lastName = lastID.substring(lastID.indexOf(",\"\"") + 3);
				lastName = lastName.substring(0, lastName.indexOf("\""));
				lastID = lastID.substring(0, lastID.indexOf(","));
				lastData = "";

				objectNames.put(lastID, lastName);
			} else if (line.startsWith("\"\"")) {
				lastData = "{\n" + lastData;
				lastData = lastData.replace("`", "\"");
				String objName = objectNames.get(lastID);

				// Parse def
				JsonObject tier = null;
				JsonObject obj = JsonParser.parseString(lastData).getAsJsonObject();
				for (JsonElement ele : obj.get("components").getAsJsonArray()) {
					JsonObject data = ele.getAsJsonObject();
					if (data.get("componentClass").getAsString().equals("SeasonPassTierDefComponent")) {
						data = data.get("componentJSON").getAsJsonObject();
						tier = data;
					}
				}

				// Check result
				if (tier == null)
					continue;

				// Parse tier
				System.out.println("Parsing season pass tier: " + lastID + " - " + objName);
				String tierID = lastID;
				String tierObj = objName;
				boolean isFree = tier.get("isFree").getAsBoolean();
				int requiredAuraPoints = tier.get("requiredAuraPoints").getAsInt();
				String rewardItemID = tier.get("_reward").getAsJsonObject().get("itemDefID").getAsString();
				int rewardCount = tier.get("_reward").getAsJsonObject().get("count").getAsInt();

				// Dump
				System.out.println();
				System.out.println("ID: " + tierID);
				System.out.println("Object name: " + tierObj);
				System.out.println("Required aura points: " + requiredAuraPoints);
				System.out.println("Requirs premium pass: " + !isFree);
				System.out.println("Reward item ID: " + rewardItemID);
				System.out.println("Reward item count: " + rewardCount);
				System.out.println();

				// Create object and write
				JsonObject tierDataObj = new JsonObject();
				tierDataObj.addProperty("tierDefID", tierID);
				tierDataObj.addProperty("objectName", tierObj);
				tierDataObj.addProperty("requiresPremium", !isFree);
				tierDataObj.addProperty("requiredAuraPoints", requiredAuraPoints);
				tierDataObj.addProperty("rewardItemID", rewardItemID);
				tierDataObj.addProperty("rewardCount", rewardCount);

				// Write to json
				String json = new Gson().newBuilder().setPrettyPrinting().create().toJson(tierDataObj);
				File fO = new File(outputTiers, tierID + ".json");
				Files.writeString(fO.toPath(), json);
				tierList.add(tierID + ".json");

				lastData = null;
			} else if (lastData != null) {
				lastData += line + "\n";
			}
		}

		// Dump challenges
		lastName = "";
		lastData = null;
		lastID = null;
		System.out.println("Dumping season pass challenges...");
		ArrayList<String> challengeList = new ArrayList<String>();
		for (String line : Files.readAllLines(Path.of(args[0]))) {
			if (line.startsWith("\"") && !line.startsWith("\"\"") && !line.startsWith("\"DefID")) {
				lastID = line.substring(1);
				lastName = lastID.substring(lastID.indexOf(",\"\"") + 3);
				lastName = lastName.substring(0, lastName.indexOf("\""));
				lastID = lastID.substring(0, lastID.indexOf(","));
				lastData = "";

				objectNames.put(lastID, lastName);
			} else if (line.startsWith("\"\"")) {
				lastData = "{\n" + lastData;
				lastData = lastData.replace("`", "\"");
				String objName = objectNames.get(lastID);

				// Parse def
				JsonObject challenge = null;
				String nameDefId = null;
				JsonObject obj = JsonParser.parseString(lastData).getAsJsonObject();
				for (JsonElement ele : obj.get("components").getAsJsonArray()) {
					JsonObject data = ele.getAsJsonObject();
					if (data.get("componentClass").getAsString().equals("SeasonPassChallengeDefComponent")) {
						data = data.get("componentJSON").getAsJsonObject();
						challenge = data;
					} else if (data.get("componentClass").getAsString().equals("LocalizedNameDefComponent")) {
						data = data.get("componentJSON").getAsJsonObject();
						nameDefId = data.get("localizedDefID").getAsString();
					}
				}

				// Check result
				if (challenge == null)
					continue;

				// Get challenge name
				String name = objName;
				if (nameDefId != null) {
					if (localization.has(nameDefId))
						name = localization.get(nameDefId).getAsString();
				}

				// Parse challenge
				System.out.println("Parsing season pass challenge: " + lastID + " - " + objName + ": " + name);
				String challengeID = lastID;
				String challengeObj = objName;
				String challengeName = name;
				int auraPointsReward = challenge.get("auraPointsReward").getAsInt();
				JsonArray actions = challenge.get("actions").getAsJsonArray();
				String operation = challenge.get("operation").getAsString();
				JsonArray defs = challenge.get("defs").getAsJsonArray();
				JsonArray tags = challenge.get("tags").getAsJsonArray();

				// Dump
				System.out.println();
				System.out.println("ID: " + challengeID + " - Name: " + challengeName);
				System.out.println("Object name: " + challengeObj);
				System.out.println();

				// Create object and write
				JsonObject challengeDataObj = new JsonObject();
				challengeDataObj.addProperty("challengeName", challengeName);
				challengeDataObj.addProperty("objectName", challengeObj);
				challengeDataObj.addProperty("challengeDefID", challengeID);
				challengeDataObj.addProperty("auraPointsReward", auraPointsReward);
				JsonObject script = new JsonObject();
				script.add("actions", actions);
				script.addProperty("operation", operation);
				script.add("defs", defs);
				script.add("tags", tags);
				challengeDataObj.add("script", script);

				// Write to json
				String json = new Gson().newBuilder().setPrettyPrinting().create().toJson(challengeDataObj);
				File fO = new File(outputChallenges, challengeID + ".json");
				Files.writeString(fO.toPath(), json);
				challengeList.add(challengeID + ".json");

				lastData = null;
			} else if (lastData != null) {
				lastData += line + "\n";
			}
		}

		// Write indexes
		System.out.println("Writing indexes...");
		System.out.println("Writing season pass index...");
		JsonArray indexPasses = new JsonArray();
		for (String pass : passes)
			indexPasses.add(pass);
		String json = new Gson().newBuilder().setPrettyPrinting().create().toJson(indexPasses);
		File fO = new File(output, "index.json");
		Files.writeString(fO.toPath(), json);
		System.out.println("Writing tier index...");
		JsonArray indexTiers = new JsonArray();
		for (String tier : tierList)
			indexTiers.add(tier);
		json = new Gson().newBuilder().setPrettyPrinting().create().toJson(indexPasses);
		fO = new File(outputTiers, "index.json");
		Files.writeString(fO.toPath(), json);
		System.out.println("Writing challenge index...");
		JsonArray indexChallenges = new JsonArray();
		for (String challenge : challengeList)
			indexChallenges.add(challenge);
		json = new Gson().newBuilder().setPrettyPrinting().create().toJson(indexChallenges);
		fO = new File(outputChallenges, "index.json");
		Files.writeString(fO.toPath(), json);

		// Log complete
		System.out.println("Completed dump!");
	}
}
