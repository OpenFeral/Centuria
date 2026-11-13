package org.asf.centuria.networking.http.api.custom;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;

import org.asf.centuria.Centuria;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.packets.xt.gameserver.inventory.InventoryItemDownloadPacket;
import org.asf.connective.RemoteClient;
import org.asf.connective.processors.HttpPushProcessor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ListPlayersHandler extends HttpPushProcessor {
	@Override
	public void process(String path, String method, RemoteClient client, String contentType) throws IOException {
		try {
			// Load spawn helper
			JsonObject helper = null;
			try {
				// Load helper
				InputStream strm = InventoryItemDownloadPacket.class.getClassLoader()
						.getResourceAsStream("spawns.json");
				helper = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8")).getAsJsonObject().get("Maps")
						.getAsJsonObject();
				strm.close();
			} catch (Exception e) {
			}

			// Send response
			int counter = 0;
			JsonObject response = new JsonObject();
			HashMap<String, Integer> maps = new HashMap<String, Integer>();
			for (Player plr : Centuria.gameServer.getPlayers()) {
				if (!plr.roomReady)
					continue;
				counter++;
				String map = Integer.toString(plr.levelID);
				if (plr.levelID == 25280)
					map = "Tutorial";
				else if (helper.has(Integer.toString(plr.levelID)))
					map = helper.get(Integer.toString(plr.levelID)).getAsString();
				maps.put(map, maps.getOrDefault(map, 0) + 1);
			}
			response.addProperty("active", counter);
			response.addProperty("connected", Centuria.gameServer.getPlayers().length);
			JsonObject mapData = new JsonObject();
			maps.forEach((k, v) -> mapData.addProperty(k, v));
			response.add("maps", mapData);
			setResponseContent("text/json", response.toString());
			getResponse().addHeader("Access-Control-Allow-Origin", "https://emuferal.ddns.net");
		} catch (Exception e) {
			setResponseStatus(500, "Internal Server Error");
			Centuria.logger.error(getRequest().getRequestPath() + " failed: 500: Internal Server Error", e);
		}
	}

	@Override
	public boolean supportsNonPush() {
		return true;
	}

	@Override
	public HttpPushProcessor createNewInstance() {
		return new ListPlayersHandler();
	}

	@Override
	public String path() {
		return "/centuria/listplayers";
	}

}
