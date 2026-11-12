package org.asf.centuria.networking.http.api;

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

public class UpdateDisplayNameHandler extends HttpPushHandler {

	@Override
	public void handle(String path, String method, RemoteClient client, String contentType) throws IOException {
		try {
			// Parse body
			ByteArrayOutputStream strm = new ByteArrayOutputStream();
			getRequest().transferRequestBody(strm);
			byte[] body = strm.toByteArray();
			strm.close();

			// Load manager
			AccountManager manager = AccountManager.getInstance();

			// Parse body
			JsonObject change = JsonParser.parseString(new String(body, "UTF-8")).getAsJsonObject();
			String newName = change.get("new_display_name").getAsString().replace("+", " ");

			// Parse JWT payload
			String token = this.getHeader("Authorization").substring("Bearer ".length());

			// Verify signature
			String verifyD = token.split("\\.")[0] + "." + token.split("\\.")[1];
			String sig = token.split("\\.")[2];
			if (!Centuria.verify(verifyD.getBytes("UTF-8"), Base64.getUrlDecoder().decode(sig))) {
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

			JsonObject jP = JsonParser
					.parseString(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), "UTF-8"))
					.getAsJsonObject();

			// Find account
			CenturiaAccount acc = manager.getAccount(jP.get("uuid").getAsString());
			if (acc == null) {
				this.setResponseStatus(401, "Unauthorized");
				return;
			}

			// Check if the name is in use
			if ((manager.isDisplayNameInUse(newName) && (manager.getUserByDisplayName(newName) == null
					|| !manager.getUserByDisplayName(newName).equals(acc.getAccountID())))
					|| (manager.isDisplayNameInUse(newName) && acc.isRenameRequired())) {
				setResponseContent("text/json", "{\"error\":\"display_name_already_taken\"}");
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

				// Tell authorization to save password
				manager.makePasswordUpdateRequested(acc.getAccountID());
			} else {
				JsonObject response = new JsonObject();
				if (!newName.matches("^[0-9A-Za-z\\-_. ]+") || newName.length() > 16 || newName.length() < 2) {
					response.addProperty("error", "display_name_invalid_format");
					setResponseContent("text/json", response.toString());
					return;
				}

				// Prevent banned and filtered words as well as blacklisted names
				// Check display name
				FilterResult res = TextFilterService.getInstance().filter(newName, true, "USERNAMEFILTER");
				if (res.isMatch() && res.getSeverity().ordinal() >= FilterSeverity.USER_STRICT_MODE.ordinal()) {
					response.addProperty("error", "display_name_sift_rejected");
					setResponseContent("text/json", response.toString());
					return;
				}

			}
		} catch (Exception e) {
			setResponseStatus(500, "Internal Server Error");
			Centuria.logger.error(getRequest().getRequestPath() + " failed: 500: Internal Server Error", e);
		}
	}

	@Override
	public HttpPushHandler createNewInstance() {
		return new UpdateDisplayNameHandler();
	}

	@Override
	public String path() {
		return "/dn/update_display_name";
	}

}
