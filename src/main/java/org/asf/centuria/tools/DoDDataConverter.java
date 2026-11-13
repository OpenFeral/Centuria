package org.asf.centuria.tools;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class DoDDataConverter {

	public static void main(String[] args) throws IOException {
		// This tool generates a minigames/doordye.json file
		// Expected program arguments: <csv-file>

		JsonObject res = new JsonObject();
		JsonArray levels = new JsonArray();
		String lastData = null;
		String lastID = null;
		String lastName = "";
		ArrayList<Integer> lootIds = new ArrayList<Integer>();
		for (String line : Files.readAllLines(Path.of(args[0]))) {
			if (line.startsWith("\"") && !line.startsWith("\"\"")) {
				lastID = line.substring(1);
				if (lastID.isEmpty())
					break;
				lastName = lastID.substring(lastID.indexOf(",\"\"") + 3);
				lastName = lastName.substring(0, lastName.indexOf("\""));
				lastID = lastID.substring(0, lastID.indexOf(","));
				lastData = "";
			} else if (line.startsWith("\"\"")) {
				lastData = "{\n" + lastData;
				lastData = lastData.replace("`", "\"");
				JsonObject obj = JsonParser.parseString(lastData).getAsJsonObject();
				if (obj.get("templateClass").getAsString().equals("CasualGameTemplate")) {
					boolean isDod = false;
					JsonObject dodData = null;

					// Find DoD info
					for (JsonElement ele : obj.get("components").getAsJsonArray()) {
						JsonObject data = ele.getAsJsonObject();
						if (data.get("componentClass").getAsString().equals("CodeBreakerDefComponent")) {
							data = data.get("componentJSON").getAsJsonObject();
							dodData = data;
							isDod = true;
						}
					}
					if (isDod) {
						// Load reward ids
						for (JsonElement ele : obj.get("components").getAsJsonArray()) {
							JsonObject data = ele.getAsJsonObject();
							if (data.get("componentClass").getAsString().equals("MinigameRewardDefComponent")) {
								data = data.get("componentJSON").getAsJsonObject();
								for (JsonElement ele2 : data.get("lootLists").getAsJsonArray().get(0).getAsJsonObject()
										.get("list").getAsJsonObject().get("_defIDs").getAsJsonArray()) {
									lootIds.add(ele2.getAsInt());
								}
							}
						}

						// Load reward data
						LinkedHashMap<Integer, Integer[]> rewardIndexes = new LinkedHashMap<Integer, Integer[]>();

						// Load most specific first
						for (JsonElement ele : dodData.get("levelRewards").getAsJsonArray()) {
							JsonObject reward = ele.getAsJsonObject();
							if (reward.get("isRange").getAsBoolean()) {
								// Load it
								int start = reward.get("levelIndex").getAsInt();
								int end = reward.get("endLevelIndex").getAsInt();
								for (int i = start; i <= end; i++) {
									rewardIndexes.put(i,
											new Integer[] { reward.get("oneStarRewardIndex").getAsInt(),
													reward.get("twoStarRewardIndex").getAsInt(),
													reward.get("threeStarRewardIndex").getAsInt() });
								}
							}
						}

						// Load least specific after that
						for (JsonElement ele : dodData.get("levelRewards").getAsJsonArray()) {
							JsonObject reward = ele.getAsJsonObject();
							if (!reward.get("isRange").getAsBoolean()) {
								// Set it
								rewardIndexes.put(reward.get("levelIndex").getAsInt(),
										new Integer[] { reward.get("oneStarRewardIndex").getAsInt(),
												reward.get("twoStarRewardIndex").getAsInt(),
												reward.get("threeStarRewardIndex").getAsInt() });
							}
						}

						// Create level array
						int index = 0;
						for (JsonElement ele : dodData.get("levels").getAsJsonArray()) {
							JsonArray reward = new JsonArray();
							Integer[] lvlRewards = rewardIndexes.get(index + 1);
							for (int ind : lvlRewards) {
								reward.add(lootIds.get(ind));
							}
							JsonObject lvl = ele.getAsJsonObject();
							JsonObject timeBonus = new JsonObject();
							for (JsonElement ele2 : lvl.get("timerScoreThresholds").getAsJsonArray()) {
								JsonObject bonus = ele2.getAsJsonObject();
								timeBonus.addProperty(bonus.get("seconds").getAsString(),
										bonus.get("pointsPerSecond").getAsInt());
							}
							JsonObject level = new JsonObject();
							level.addProperty("name", lvl.get("name").getAsString());
							level.addProperty("length", lvl.get("codeLength").getAsInt());
							level.addProperty("colors", lvl.get("colors").getAsInt());
							level.addProperty("allowRepeat", lvl.get("allowRepeatColors").getAsBoolean());
							level.addProperty("ingredientCount", lvl.get("startingIngredientCount").getAsInt());
							level.addProperty("score", lvl.get("scorePerIngredient").getAsInt());
							level.addProperty("oneStarScore", lvl.get("oneStarScore").getAsInt());
							level.addProperty("twoStarScore", lvl.get("twoStarScore").getAsInt());
							level.addProperty("threeStarScore", lvl.get("threeStarScore").getAsInt());
							level.add("timeBonus", timeBonus);
							level.add("reward", reward);
							levels.add(level);
							index++;
						}

						break;
					}
				}
				lastData = null;
			} else if (lastData != null) {
				lastData += line + "\n";
			}
		}
		res.add("Levels", levels);

		System.out.println(new Gson().newBuilder().setPrettyPrinting().create().toJson(res));
	}

}
