package org.asf.centuria.tools;

import java.io.IOException;
import java.nio.file.*;
import java.util.LinkedHashMap;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class BundleConverter {

	public static void main(String[] args) throws IOException {
		// This tool generates a bundleinfo.json file
		//
		// Expected program arguments:
		// <bundlepackchart-csv-file>

		String lastName = "";
		String lastData = null;
		String lastID = null;

		JsonObject res = new JsonObject();
		JsonObject definitions = new JsonObject();
		for (String line : Files.readAllLines(Path.of(args[0]))) {
			if (line.startsWith("\"") && !line.startsWith("\"\"") && !line.startsWith("\"DefID")) {
				lastID = line.substring(1);
				lastName = lastID.substring(lastID.indexOf(",\"\"") + 3);
				lastName = lastName.substring(0, lastName.indexOf("\""));
				lastID = lastID.substring(0, lastID.indexOf(","));
				lastData = "";
			} else if (line.startsWith("\"\"")) {
				lastData = "{\n" + lastData;
				lastData = lastData.replace("`", "\"");
				JsonObject obj = JsonParser.parseString(lastData).getAsJsonObject();

				LinkedHashMap<String, Integer> items = new LinkedHashMap<String, Integer>();
				for (JsonElement ele : obj.get("components").getAsJsonArray()) {
					JsonObject data = ele.getAsJsonObject();
					if (data.get("componentClass").getAsString().equals("BundlePackDefComponent")) {
						data = data.get("componentJSON").getAsJsonObject();
						JsonArray itms = data.get("_craftableItems").getAsJsonArray();
						for (JsonElement ele2 : itms) {
							JsonObject itm = ele2.getAsJsonObject();
							items.put(itm.get("itemDefID").getAsString(), itm.get("count").getAsInt());
						}
					}
				}

				if (items.size() > 0) {
					JsonObject itm = new JsonObject();
					JsonObject itemData = new JsonObject();
					items.forEach((k, v) -> itemData.addProperty(k, v));
					itm.addProperty("objectName", lastName);
					itm.add("items", itemData);
					definitions.add(lastID, itm);
				}

				lastData = null;
			} else if (lastData != null) {
				lastData += line + "\n";
			}
		}
		res.add("bundles", definitions);

		System.out.println(new Gson().newBuilder().setPrettyPrinting().create().toJson(res));
	}

}
