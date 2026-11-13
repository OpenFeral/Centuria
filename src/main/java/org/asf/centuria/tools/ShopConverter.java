package org.asf.centuria.tools;

import java.nio.file.*;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ShopConverter {

	public static void main(String[] args) throws IOException {
		// This tool generates a shops.json file
		//
		// Expected program arguments:
		// <shop-csv-file> <shoplist-csv-file> <craftableitemchart-csv-file>
		// <listchart-csv-file> <bundlepack-csv-file>

		String lastName = "";
		String lastData = null;
		String lastID = null;
		LinkedHashMap<String, JsonArray> lists = new LinkedHashMap<String, JsonArray>();
		LinkedHashMap<String, String> objectNames = new LinkedHashMap<String, String>();
		for (String line : Files.readAllLines(Path.of(args[3]))) {
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
				JsonObject obj = JsonParser.parseString(lastData).getAsJsonObject();

				for (JsonElement ele : obj.get("components").getAsJsonArray()) {
					JsonObject data = ele.getAsJsonObject();
					if (data.get("componentClass").getAsString().equals("ListDefComponent")) {
						data = data.get("componentJSON").getAsJsonObject();
						if (data.has("list") && data.get("list").getAsJsonObject().has("_defIDs")) {
							JsonArray items = new JsonArray();
							for (JsonElement ele2 : data.get("list").getAsJsonObject().get("_defIDs").getAsJsonArray())
								items.add(ele2.getAsString());
							lists.put(lastID, items);
						}
					}
				}

				lastData = null;
			} else if (lastData != null) {
				lastData += line + "\n";
			}
		}

		JsonObject uncrafting = new JsonObject();
		LinkedHashMap<String, String> itemTypes = new LinkedHashMap<String, String>();
		LinkedHashMap<String, String> enigmaMap = new LinkedHashMap<String, String>();
		LinkedHashMap<String, LinkedHashMap<String, Integer>> costs = new LinkedHashMap<String, LinkedHashMap<String, Integer>>();
		LinkedHashMap<String, LinkedHashMap<String, Integer>> eurekaItems = new LinkedHashMap<String, LinkedHashMap<String, Integer>>();

		lastName = "";
		lastData = null;
		lastID = null;
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

				boolean uncraftable = false;
				JsonObject obj = JsonParser.parseString(lastData).getAsJsonObject();
				JsonObject purchaseable = null;

				for (JsonElement ele : obj.get("components").getAsJsonArray()) {
					JsonObject data = ele.getAsJsonObject();
					if (data.get("componentClass").getAsString().equals("PurchaseableDefComponent")) {
						data = data.get("componentJSON").getAsJsonObject();
						purchaseable = data;
						LinkedHashMap<String, Integer> cost = new LinkedHashMap<String, Integer>();
						for (JsonElement elem : data.get("_cost").getAsJsonArray()) {
							JsonObject costObj = elem.getAsJsonObject();
							String itm = costObj.get("itemDefID").getAsString();
							int count = costObj.get("count").getAsInt();
							cost.put(itm, count);
						}
						costs.put(lastID, cost);
					} else if (data.get("componentClass").getAsString().equals("PurchaseRareChanceDefComponent")) {
						data = data.get("componentJSON").getAsJsonObject();
						if (!data.get("itemDefID").getAsString().isEmpty()) {
							LinkedHashMap<String, Integer> itemChances;
							if (!eurekaItems.containsKey(lastID)) {
								itemChances = new LinkedHashMap<String, Integer>();
								eurekaItems.put(lastID, itemChances);
							} else
								itemChances = eurekaItems.get(lastID);

							String id = data.get("itemDefID").getAsString();
							int chance = data.get("rareChance").getAsInt();
							itemChances.put(id, chance);
						}
					} else if (data.get("componentClass").getAsString().equals("RecyclableDefComponent")) {
						uncraftable = true;
					} else if (data.get("componentClass").getAsString().equals("ItemDefComponent")) {
						data = data.get("componentJSON").getAsJsonObject();
						itemTypes.put(lastID, data.get("itemType").getAsString());
					} else if (data.get("componentClass").getAsString().equals("EnigmaDefComponent")) {
						data = data.get("componentJSON").getAsJsonObject();
						enigmaMap.put(lastID, data.get("itemDefID").getAsString());
					}
				}

				if (uncraftable && purchaseable != null) {
					JsonObject uncraftInfo = new JsonObject();
					uncraftInfo.addProperty("object", lastName);
					JsonObject results = new JsonObject();
					uncraftInfo.add("result", results);
					for (JsonElement elem : purchaseable.get("_cost").getAsJsonArray()) {
						JsonObject costObj = elem.getAsJsonObject();
						String itm = costObj.get("itemDefID").getAsString();
						int count = costObj.get("count").getAsInt();
						float calc = count * 0.3f;
						int unboundQuant = (calc - (float) ((int) calc) > 0) ? (int) calc + 1 : (int) calc;
						results.addProperty(itm, unboundQuant);
					}
					if (results.size() != 0)
						uncrafting.add(lastID, uncraftInfo);
				}

				lastData = null;
			} else if (lastData != null) {
				lastData += line + "\n";
			}
		}

		lastName = "";
		lastData = null;
		lastID = null;
		LinkedHashMap<String, LinkedHashMap<String, Integer>> bundles = new LinkedHashMap<String, LinkedHashMap<String, Integer>>();
		for (String line : Files.readAllLines(Path.of(args[4]))) {
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
				JsonObject obj = JsonParser.parseString(lastData).getAsJsonObject();

				for (JsonElement ele : obj.get("components").getAsJsonArray()) {
					JsonObject data = ele.getAsJsonObject();
					if (data.get("componentClass").getAsString().equals("PurchaseableDefComponent")) {
						data = data.get("componentJSON").getAsJsonObject();
						LinkedHashMap<String, Integer> cost = new LinkedHashMap<String, Integer>();
						for (JsonElement elem : data.get("_cost").getAsJsonArray()) {
							JsonObject costObj = elem.getAsJsonObject();
							String itm = costObj.get("itemDefID").getAsString();
							int count = costObj.get("count").getAsInt();
							cost.put(itm, count);
						}
						costs.put(lastID, cost);
					} else if (data.get("componentClass").getAsString().equals("BundlePackDefComponent")) {
						data = data.get("componentJSON").getAsJsonObject();
						LinkedHashMap<String, Integer> items = new LinkedHashMap<String, Integer>();
						for (JsonElement elem : data.get("_craftableItems").getAsJsonArray()) {
							JsonObject itmObj = elem.getAsJsonObject();
							String itm = itmObj.get("itemDefID").getAsString();
							int count = itmObj.get("count").getAsInt();
							items.put(itm, count);
						}
						bundles.put(lastID, items);
					}
				}

				lastData = null;
			} else if (lastData != null) {
				lastData += line + "\n";
			}
		}

		lastData = null;
		lastID = null;
		lastName = null;
		boolean skip = false;

		HashMap<String, JsonObject> contents = new HashMap<String, JsonObject>();
		for (String line : Files.readAllLines(Path.of(args[1]))) {
			if (line.startsWith("\"") && !line.startsWith("\"\"") && !line.startsWith("\"DefID")) {
				String id = line.substring(1);
				lastName = id.substring(id.indexOf(",\"\"") + 3);
				lastName = lastName.substring(0, lastName.indexOf("\""));
				id = id.substring(0, id.indexOf(","));
				lastID = id;
				lastData = "";
			} else if (line.startsWith("\"\"")) {
				if (!skip) {
					lastData = "{\n" + lastData;
					lastData = lastData.replace("`", "\"");
					JsonObject obj = JsonParser.parseString(lastData).getAsJsonObject();
					if (obj.get("templateClass").getAsString().equals("ShopContentTemplate")) {
						for (JsonElement ele : obj.get("components").getAsJsonArray()) {
							JsonObject data = ele.getAsJsonObject();
							if (data.get("componentClass").getAsString().equals("ListDefComponent")) {
								data = data.get("componentJSON").getAsJsonObject();

								JsonObject items = new JsonObject();
								for (JsonElement lI : data.get("list").getAsJsonObject().get("_defIDs")
										.getAsJsonArray()) {
									String id = lI.getAsString();
									if (costs.containsKey(id)
											&& !objectNames.get(id).startsWith("AstralShop/ALL/SeasonPass/")) {
										addItem(items, lastID, id, id, costs, eurekaItems, itemTypes, objectNames,
												bundles);
									}
								}

								if (items.size() != 0) {
									contents.put(lastID, items);
								}
							}
						}
					}
				}

				lastData = null;
				lastID = null;
				skip = false;
			} else if (lastData != null) {
				lastData += line + "\n";
			}
		}

		JsonObject res = new JsonObject();
		JsonObject shops = new JsonObject();

		lastData = null;
		lastID = null;
		lastName = null;
		skip = false;
		for (String line : Files.readAllLines(Path.of(args[0]))) {
			if (line.startsWith("\"") && !line.startsWith("\"\"") && !line.startsWith("\"DefID")) {
				String id = line.substring(1);
				lastName = id.substring(id.indexOf(",\"\"") + 3);
				lastName = lastName.substring(0, lastName.indexOf("\""));
				id = id.substring(0, id.indexOf(","));
				lastID = id;
				lastData = "";
			} else if (line.startsWith("\"\"")) {
				if (!skip) {
					lastData = "{\n" + lastData;
					lastData = lastData.replace("`", "\"");
					JsonObject obj = JsonParser.parseString(lastData).getAsJsonObject();
					if (obj.get("templateClass").getAsString().equals("ShopTemplate")) {
						for (JsonElement ele : obj.get("components").getAsJsonArray()) {
							JsonObject data = ele.getAsJsonObject();
							if (data.get("componentClass").getAsString().equals("ShopContentDefComponent")) {
								data = data.get("componentJSON").getAsJsonObject();
								if (!data.has("isDebug") || !data.get("isDebug").getAsBoolean()) {
									if ((data.has("shopContentDefID")
											&& !data.get("shopContentDefID").getAsString().isEmpty()
											&& !data.get("shopContentDefID").getAsString().equals("-1"))
											|| (data.has("enigmaUnlockListDefID")
													&& !data.get("enigmaUnlockListDefID").getAsString().isEmpty()
													&& !data.get("enigmaUnlockListDefID").getAsString().equals("-1"))) {
										JsonObject shopInfo = new JsonObject();
										shopInfo.addProperty("object", lastName);
										shopInfo.addProperty("restockTime", -1);

										if (data.has("shopContentDefID")
												&& !data.get("shopContentDefID").getAsString().isEmpty()
												&& !data.get("shopContentDefID").getAsString().equals("-1")) {
											String id = data.get("shopContentDefID").getAsString();
											if (contents.containsKey(id))
												shopInfo.add("contents", contents.get(id));
											else
												shopInfo.add("contents", new JsonObject());
											if (data.has("enigmaUnlockListDefID")
													&& !data.get("enigmaUnlockListDefID").getAsString().isEmpty()
													&& !data.get("enigmaUnlockListDefID").getAsString().equals("-1")) {
												String id3 = data.get("enigmaUnlockListDefID").getAsString();
												if (lists.containsKey(id3)) {
													JsonArray enigmas = lists.get(id3);
													if (enigmas.size() != 0) {
														for (JsonElement ele2 : enigmas) {
															String id2 = ele2.getAsString();
															addItem(shopInfo.get("contents").getAsJsonObject(), "",
																	enigmaMap.get(id2), id2, costs, eurekaItems,
																	itemTypes, objectNames, bundles);
														}
													}
												}
											}
										} else
											shopInfo.add("contents", new JsonObject());
										if (data.has("enigmaUnlockListDefID")
												&& !data.get("enigmaUnlockListDefID").getAsString().isEmpty()
												&& !data.get("enigmaUnlockListDefID").getAsString().equals("-1")) {
											String id = data.get("enigmaUnlockListDefID").getAsString();
											if (lists.containsKey(id))
												shopInfo.add("enigmas", lists.get(id));
											else
												shopInfo.add("enigmas", new JsonArray());
										} else
											shopInfo.add("enigmas", new JsonArray());
										shops.add(lastID, shopInfo);
									}
								}
							}
						}
					}
				}

				lastData = null;
				lastID = null;
				skip = false;
			} else if (lastData != null) {
				lastData += line + "\n";
			}
		}
		res.add("Shops", shops);
		res.add("Uncrafting", uncrafting);

		System.out.println(new Gson().newBuilder().setPrettyPrinting().create().toJson(res));
	}

	private static void addItem(JsonObject items, String lastID, String id, String oID,
			LinkedHashMap<String, LinkedHashMap<String, Integer>> costs,
			LinkedHashMap<String, LinkedHashMap<String, Integer>> eurekaItems, LinkedHashMap<String, String> itemTypes,
			LinkedHashMap<String, String> objectNames, LinkedHashMap<String, LinkedHashMap<String, Integer>> bundles) {
		JsonObject costObj = new JsonObject();
		costs.get(id).forEach((costId, amount) -> {
			costObj.addProperty(costId, amount);
		});

		JsonObject itms = new JsonObject();
		addItems(itms, id, bundles, 1);

		JsonObject entry = new JsonObject();
		entry.addProperty("object", objectNames.get(id));

		if (lastID.equals("30549")) {
			// Astrale shop
			// Single-time purchases for avatars, body mods, sancs, and enigmas
			if (itms.size() == 1) {
				String type = itemTypes.get(itms.keySet().toArray(t -> new String[t])[0]);
				if (type.equals("1") || type.equals("2") || type.equals("10") || type.equals("7"))
					entry.addProperty("stock", 1);
				else
					entry.addProperty("stock", -1);
			} else
				entry.addProperty("stock", -1);
		} else {
			// Other shop
			// Set to -1 to disable
			entry.addProperty("stock", -1);
		}

		entry.addProperty("requiredLevel", -1);
		entry.add("items", itms);
		entry.add("cost", costObj);

		if (eurekaItems.containsKey(id) && !eurekaItems.get(id).isEmpty()) {
			LinkedHashMap<String, Integer> itemChances = eurekaItems.get(id);
			JsonObject chanceObj = new JsonObject();
			itemChances.forEach((itmId, chance) -> {
				chanceObj.addProperty(itmId, chance);
			});
			entry.add("eurekaItems", chanceObj);
		}

		items.add(oID, entry);
	}

	private static void addItems(JsonObject itms, String id,
			LinkedHashMap<String, LinkedHashMap<String, Integer>> bundles, int count) {
		if (!bundles.containsKey(id))
			itms.addProperty(id, count);
		else
			bundles.get(id).forEach((itm, c) -> addItems(itms, itm, bundles, c));
	}

}
