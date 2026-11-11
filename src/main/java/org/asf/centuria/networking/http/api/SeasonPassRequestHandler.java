package org.asf.centuria.networking.http.api;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.TimeZone;

import org.asf.centuria.Centuria;
import org.asf.centuria.accounts.AccountManager;
import org.asf.centuria.accounts.CenturiaAccount;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.seasonpasses.SeasonPassDefinition;
import org.asf.centuria.seasonpasses.SeasonPassManager;
import org.asf.connective.RemoteClient;
import org.asf.connective.handlers.HttpPushHandler;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class SeasonPassRequestHandler extends HttpPushHandler {

	@Override
	public void handle(String pth, String method, RemoteClient client, String contentType) throws IOException {
		try {
			// Parse JWT payload
			String token = this.getHeader("Authorization").substring("Bearer ".length());

			// Verify signature
			String verifyD = token.split("\\.")[0] + "." + token.split("\\.")[1];
			String sig = token.split("\\.")[2];
			if (!Centuria.verify(verifyD.getBytes("UTF-8"), Base64.getUrlDecoder().decode(sig))) {
				this.setResponseStatus(401, "Unauthorized");
				return;
			}

			// Verify expiry
			JsonObject jwtPl = JsonParser
					.parseString(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), "UTF-8"))
					.getAsJsonObject();
			if (!jwtPl.has("exp") || jwtPl.get("exp").getAsLong() < System.currentTimeMillis() / 1000) {
				this.setResponseStatus(401, "Unauthorized");
				return;
			}

			// Find account
			CenturiaAccount acc = AccountManager.getInstance().getAccount(jwtPl.get("uuid").getAsString());
			Player plr = acc.getOnlinePlayerInstance();

			// Get path
			String path = this.getRequestPath().substring(path().length());
			if (path.endsWith("/"))
				path = path.substring(0, path.length() - 2);

			// Time format
			SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
			fmt.setTimeZone(TimeZone.getTimeZone("UTC"));

			// Get season
			SeasonPassDefinition def = SeasonPassManager.getCurrentPass();
			if (plr != null)
				def = plr.getCurrentSeasonPass();
			if (def != null) {
				// Handle path
				if (path.equals("/seasons/player-season")) {
					// Requesting active path

					// Build response
					JsonObject res = new JsonObject();

					// Add user-specifics
					res.addProperty("completed", false); // TODO
					res.addProperty("current_level", 1); // TODO
					res.addProperty("current_points", 0); // TODO

					// Add pass stuff
					res.addProperty("def_id", def.passDefID);
					res.addProperty("end_date", fmt.format(new Date(def.endDateLiveTimestamp)));

					// More user specifics
					res.addProperty("has_pass", false); // TODO

					// Add pass info
					res.addProperty("name", def.objectName);

					// Add tiers
					JsonArray tiers = new JsonArray();
					for (String tier : def.tiers) {
						// TODO: filter for user
						tiers.add(tier);
					}
					res.add("tiers", tiers);

					// Add ID
					res.addProperty("uuid", acc.getAccountID());

					this.setResponseContent("text/json", res.toString());
					return;
				} else if (path.equals("/challenges/" + acc.getAccountID() + "/player-challenges")) {
					// Challenge request

					// Build response
					JsonObject res = new JsonObject();
					JsonArray challenges = new JsonArray();

					// Add challenges
					for (String challenge : def.challenges) {
						// TODO: populate with actual data
						JsonObject ch = new JsonObject();
						ch.addProperty("def_id", challenge);
						ch.addProperty("progress_count", 0);
						challenges.add(ch);
					}

					res.add("challenges", challenges);
					this.setResponseContent("text/json", res.toString());
					return;
				} else if (path.equals("/challenges/" + acc.getAccountID() + "/player-challenges/completed")) {
					// Completed challenge request

					// Build response
					JsonObject res = new JsonObject();
					JsonArray completedChallenges = new JsonArray();

					// TODO: populate

					res.add("challenges", completedChallenges);
					this.setResponseContent("text/json", res.toString());
					return;
				}
			} else {
				// Set failure
				getResponse().setResponseStatus(404, "Not Found");
				JsonObject res = new JsonObject();
				res.addProperty("error", "none_active");
				this.setResponseContent("text/json", res.toString());
				return;
			}

			// Set response
			getResponse().setResponseStatus(400, "Bad Reqeust");
			this.setResponseContent("text/json", "{}");
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
	public boolean supportsChildPaths() {
		return true;
	}

	@Override
	public HttpPushHandler createNewInstance() {
		return new SeasonPassRequestHandler();
	}

	@Override
	public String path() {
		return "/bp";
	}

}
