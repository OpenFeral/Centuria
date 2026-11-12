package org.asf.centuria.networking.http.api.custom;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import org.asf.centuria.Centuria;
import org.asf.centuria.accounts.AccountManager;
import org.asf.centuria.accounts.CenturiaAccount;
import org.asf.centuria.textfilter.FilterSeverity;
import org.asf.centuria.textfilter.TextFilterService;
import org.asf.centuria.textfilter.result.FilterResult;
import org.asf.connective.RemoteClient;
import org.asf.connective.handlers.HttpPushHandler;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ChangeDisplayNameHandler extends HttpPushHandler {

	@Override
	public void handle(String path, String method, RemoteClient client, String contentType) throws IOException {
		try {
			if (!method.equalsIgnoreCase("post")) {
				this.setResponseStatus(400, "Bad request");
				return;
			}

			// Parse body
			ByteArrayOutputStream strm = new ByteArrayOutputStream();
			getRequest().transferRequestBody(strm);
			byte[] body = strm.toByteArray();
			strm.close();

			// Parse body
			JsonObject request = JsonParser.parseString(new String(body, "UTF-8")).getAsJsonObject();
			if (!request.has("new_display_name")) {
				this.setResponseStatus(400, "Bad request");
				return;
			}

			// Load manager
			AccountManager manager = AccountManager.getInstance();

			// Parse JWT payload
			String token = this.getHeader("Authorization").substring("Bearer ".length());
			if (token.isBlank()) {
				this.setResponseStatus(403, "Access denied");
				return;
			}

			// Parse token
			if (token.isBlank()) {
				this.setResponseStatus(403, "Access denied");
				return;
			}

			// Verify signature
			String verifyD = token.split("\\.")[0] + "." + token.split("\\.")[1];
			String sig = token.split("\\.")[2];
			if (!Centuria.verify(verifyD.getBytes("UTF-8"), Base64.getUrlDecoder().decode(sig))) {
				this.setResponseStatus(403, "Access denied");
				return;
			}

			// Verify expiry
			JsonObject jwtPl = JsonParser
					.parseString(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), "UTF-8"))
					.getAsJsonObject();
			if (!jwtPl.has("exp") || jwtPl.get("exp").getAsLong() < System.currentTimeMillis() / 1000) {
				this.setResponseStatus(403, "Access denied");
				return;
			}

			JsonObject payload = JsonParser
					.parseString(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), "UTF-8"))
					.getAsJsonObject();

			// Find account
			String id = payload.get("uuid").getAsString();

			// Check existence
			if (id == null) {
				// Invalid details
				this.setResponseContent("text/json", "{\"error\":\"invalid_credential\"}");
				this.setResponseStatus(401, "Unauthorized");
				return;
			}

			// Find account
			CenturiaAccount acc = manager.getAccount(id);
			if (acc == null) {
				this.setResponseStatus(401, "Unauthorized");
				return;
			}

			// Prepare response
			JsonObject response = new JsonObject();

			// Check if the name is in use
			String newName = payload.get("new_display_name").getAsString();
			if ((manager.isDisplayNameInUse(newName) && (manager.getUserByDisplayName(newName) == null
					|| !manager.getUserByDisplayName(newName).equals(acc.getAccountID())))
					|| (manager.isDisplayNameInUse(newName) && acc.isRenameRequired())) {
				response.addProperty("status", "failure");
				response.addProperty("error", "display_name_in_use");
				response.addProperty("error_message", "Selected display name is already in use.");
				return; // Name is in use
			}

			// Save new name
			boolean requiredRename = acc.isRenameRequired();
			String oldName = acc.getDisplayName();
			if (acc.updateDisplayName(newName)) {
				if (!requiredRename) {
					// Unlock old name
					manager.releaseDisplayName(oldName);
				} else {
					// Wipe uuid tag of old display name
					manager.releaseDisplayName(oldName);
					manager.lockDisplayName(oldName, "-1");
				}
			} else {
				if (!newName.matches("^[0-9A-Za-z\\-_. ]+") || newName.length() > 16 || newName.length() < 2) {
					response.addProperty("status", "failure");
					response.addProperty("error", "invalid_display_name");
					response.addProperty("error_message", "Invalid display name.");
					setResponseContent("text/json", response.toString());
					return;
				}

				// Prevent banned and filtered words as well as blacklisted names
				// Check display name
				FilterResult res = TextFilterService.getInstance().filter(newName, true, "USERNAMEFILTER");
				if (res.isMatch() && res.getSeverity().ordinal() >= FilterSeverity.USER_STRICT_MODE.ordinal()) {
					response.addProperty("status", "failure");
					response.addProperty("error", "invalid_display_name");
					response.addProperty("error_message",
							"Invalid display name: this name may not be used as it may not be appropriate.");
					setResponseContent("text/json", response.toString());
					return;
				}
			}

			// Send response
			response.addProperty("status", "success");
			response.addProperty("name", acc.getDisplayName());
			response.addProperty("uuid", id);
			response.addProperty("updated", true);
			setResponseContent("text/json", response.toString());
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
	public HttpPushHandler createNewInstance() {
		return new ChangeDisplayNameHandler();
	}

	@Override
	public String path() {
		return "/centuria/updatedisplayname";
	}

}
