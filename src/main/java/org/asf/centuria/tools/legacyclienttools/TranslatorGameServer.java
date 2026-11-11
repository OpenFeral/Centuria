package org.asf.centuria.tools.legacyclienttools;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Random;

import org.asf.centuria.networking.smartfox.BaseSmartfoxServer;
import org.asf.centuria.networking.smartfox.SmartfoxClient;
import org.asf.centuria.networking.smartfox.SocketSmartfoxClient;
import org.asf.centuria.packets.smartfox.ISmartfoxPacket;
import org.asf.centuria.packets.xml.handshake.auth.ClientToServerAuthPacket;
import org.asf.centuria.packets.xml.handshake.version.ClientToServerHandshake;
import org.asf.centuria.packets.xml.handshake.version.ServerToClientOK;
import org.asf.centuria.packets.xt.gameserver.PrefixedPacket;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class TranslatorGameServer extends BaseSmartfoxServer {

	private ArrayList<SmartfoxClient> clients = new ArrayList<SmartfoxClient>();

	public TranslatorGameServer(int localPort) throws IOException {
		super(new ServerSocket(localPort, 0, InetAddress.getLoopbackAddress()));
	}

	public boolean isLocalClient(SmartfoxClient client) {
		return clients.contains(client);
	}

	public String apiAddr;
	public String directorAddr;
	public int remotePort;

	private Random rnd = new Random();
	private XmlMapper mapper = new XmlMapper();

	@Override
	protected void registerPackets() {
		mapper = new XmlMapper();

		// Handshake
		registerPacket(new ClientToServerHandshake(mapper));
		registerPacket(new ClientToServerAuthPacket(mapper));

		// Proxy
		registerPacket(new PrefixedPacket());
		registerPacket(new ProxiedRoomJoinPacket());
		registerPacket(new ProxiedObjectUpdatePacket());
		registerPacket(new ProxiedObjectInfoPacket());
		registerPacket(new ProxiedAvatarLookGetPacket());
		registerPacket(new ProxiedInventoryListPacket());
		registerPacket(new XTPacketProxy());
	}

	@Override
	protected void startClient(SmartfoxClient client) throws IOException {
		// Connect to the game server
		SocketSmartfoxClient remoteClient;
		try {
			InputStream strm = new URL(directorAddr + "/v1/bestGameServer").openStream();
			String resp = new String(strm.readAllBytes());
			strm.close();

			JsonObject obj = JsonParser.parseString(resp).getAsJsonObject();
			Socket remoteSock = new Socket(obj.get("smartfoxServer").getAsString(), remotePort);
			remoteClient = new SocketSmartfoxClient(remoteSock, this);
		} catch (Exception e) {
			// Fail
			client.readPacket(ClientToServerHandshake.class);
			client.sendPacket(new ServerToClientOK(mapper));
			client.readPacket(ISmartfoxPacket.class);
			String key = Long.toString(rnd.nextLong(), 16);
			client.sendPacket("<msg t='sys'><body action='rndK' r='-1'><k>" + key + "</k></body></msg>");
			ClientToServerAuthPacket auth = client.readPacket(ClientToServerAuthPacket.class);
			JsonObject response = new JsonObject();
			JsonObject b = new JsonObject();
			b.addProperty("r", auth.rField);
			JsonObject o = new JsonObject();
			o.addProperty("statusId", -1000);
			o.addProperty("_cmd", "login");
			o.addProperty("status", -1000);
			b.add("o", o);
			response.add("b", b);
			response.addProperty("t", "xt");
			client.sendPacket(response.toString());
			client.disconnect();
			return;
		}

		// Handshake
		client.container = remoteClient;
		remoteClient.container = client;
		handshakeClient(client, remoteClient);
		if (client.isConnected()) {
			clients.add(client);

			// Proxy
			Thread proxy = new Thread(() -> {
				try {
					// Client loop
					while (remoteClient.getSocket() != null) {
						String data = readRawPacket(remoteClient);
						System.out.println("Proxy: " + data);
						try {
							handlePacket(data, remoteClient);
						} catch (Exception e) {
							System.err.println("Exception: " + e);
							e.printStackTrace();
							try {
								remoteClient.getSocket().close();
							} catch (Exception e2) {
							}
							if (remoteClient.getSocket() != null) {
								clientDisconnect(remoteClient);
								remoteClient.disconnect();
							}
							return;
						}
					}

					if (remoteClient.getSocket() != null) {
						// Disconnected
						remoteClient.disconnect();
					}
				} catch (Exception e) {
					System.err.println("Exception: " + e);
					e.printStackTrace();
					try {
						remoteClient.getSocket().close();
					} catch (Exception e2) {
					}
					if (remoteClient.getSocket() != null) {
						remoteClient.disconnect();
					}
					return;
				}
			}, "Client proxy");
			proxy.setDaemon(true);
			proxy.start();
		}
	}

	private void handshakeClient(SmartfoxClient localClient, SmartfoxClient remoteClient) {
		// Attempt to handshake
		ClientToServerAuthPacket auth = null;
		try {
			// Read first handshake packet
			remoteClient.sendPacket(localClient.readRawPacket());
			localClient.sendPacket(remoteClient.readRawPacket());

			// Random key
			remoteClient.sendPacket(localClient.readRawPacket());
			localClient.sendPacket(remoteClient.readRawPacket());

			// Authenticate the player
			String authPk = localClient.readRawPacket();
			auth = this.parsePacketPayload(authPk, ClientToServerAuthPacket.class);
			remoteClient.sendPacket(authPk);
			String serverResponse = remoteClient.readRawPacket();

			// Handle authentication
			handleAuthentication(localClient, remoteClient, auth, serverResponse);

			// Login complete packet
			remoteClient.readRawPacket();

			// Request all items from upstream
			remoteClient.sendPacket("%xt%o%ilt%-1%1%");
			remoteClient.sendPacket("%xt%o%ilt%-1%200%");
			remoteClient.sendPacket("%xt%o%ilt%-1%2%");
			remoteClient.sendPacket("%xt%o%ilt%-1%104%");
			remoteClient.sendPacket("%xt%o%ilt%-1%100%");
			remoteClient.sendPacket("%xt%o%ilt%-1%3%");
			remoteClient.sendPacket("%xt%o%ilt%-1%111%");
			remoteClient.sendPacket("%xt%o%ilt%-1%7%");
			remoteClient.sendPacket("%xt%o%ilt%-1%8%");
			remoteClient.sendPacket("%xt%o%ilt%-1%9%");
			remoteClient.sendPacket("%xt%o%ilt%-1%4%");
			remoteClient.sendPacket("%xt%o%ilt%-1%302%");
			remoteClient.sendPacket("%xt%o%ilt%-1%105%");
			remoteClient.sendPacket("%xt%o%ilt%-1%102%");
			remoteClient.sendPacket("%xt%o%ilt%-1%6%");
			remoteClient.sendPacket("%xt%o%ilt%-1%5%");
			remoteClient.sendPacket("%xt%o%ilt%-1%201%");
			remoteClient.sendPacket("%xt%o%ilt%-1%10%");
			remoteClient.sendPacket("%xt%o%ilt%-1%103%");
			remoteClient.sendPacket("%xt%o%ilt%-1%300%");
			remoteClient.sendPacket("%xt%o%ilt%-1%303%");
			remoteClient.sendPacket("%xt%o%ilt%-1%304%");
			remoteClient.sendPacket("%xt%o%ilt%-1%110%");
			remoteClient.sendPacket("%xt%o%ilt%-1%400%");
			remoteClient.sendPacket("%xt%o%ilt%-1%311%");

			ArrayList<String> packets = new ArrayList<String>();

			// Read all packets except IL
			int remainingIL = 25;
			while (remainingIL > 0) {
				String packet = remoteClient.readRawPacket();
				if (!packet.startsWith("%xt%il%"))
					packets.add(packet);
				else {
					remainingIL--;

					// Send
					handlePacket(packet, remoteClient);
				}
			}

			// Complete login
			localClient.sendPacket("%xt%ulc%-1%");

			// Other packets
			for (String packet : packets) {
				handlePacket(packet, remoteClient);
			}
		} catch (Exception e) {
			if (auth != null) {
				JsonObject response = new JsonObject();
				JsonObject b = new JsonObject();
				b.addProperty("r", auth.rField);
				JsonObject o = new JsonObject();
				o.addProperty("statusId", -1000);
				o.addProperty("_cmd", "login");
				o.addProperty("status", -1000);
				b.add("o", o);
				response.add("b", b);
				response.addProperty("t", "xt");
				localClient.sendPacket(response.toString());
			}
			localClient.disconnect();
			remoteClient.disconnect();
		}
	}

	private void handleAuthentication(SmartfoxClient localClient, SmartfoxClient remoteClient,
			ClientToServerAuthPacket auth, String serverAuthResponse) throws IOException {

		// Parse authentication
		JsonObject authData = JsonParser.parseString(serverAuthResponse).getAsJsonObject();
		JsonObject fields = authData.get("b").getAsJsonObject();
		JsonObject object = fields.get("o").getAsJsonObject();
		JsonObject params = object.get("params").getAsJsonObject();

		// Retrieve UUID
		String uuid = auth.nick.split("%")[0];

		// Retrieve display name
		JsonObject displayNameInfo = JsonParser
				.parseString(downloadString(apiAddr + "/i/display_names", "{\"uuids\":[\"" + uuid + "\"]}", auth.pword))
				.getAsJsonObject();

		// Legacy-compatible parameters
		JsonObject newParams = new JsonObject();
		newParams.addProperty("uuid", uuid);
		newParams.addProperty("email", "not_given");
		newParams.addProperty("displayName", displayNameInfo.get("found").getAsJsonArray().get(0).getAsJsonObject()
				.get("display_name").getAsString());
		newParams.addProperty("activeLookId", params.get("activeLookId").getAsString());
		newParams.addProperty("avatarInvId", params.get("avatarInvId").getAsInt());
		newParams.addProperty("sanctuaryLookId", params.get("sanctuaryLookId").getAsString());
		newParams.addProperty("pendingFlags", params.get("pendingFlags").getAsInt());
		newParams.add("inventoryMaxes", new JsonObject());
		newParams.addProperty("userId", 0);
		newParams.addProperty("sessionId", 0);
		newParams.addProperty("jamaaTime", params.get("jamaaTime").getAsLong());
		object.remove("params");
		object.add("params", newParams);

		// Send response
		localClient.sendPacket(authData.toString());
	}

	private String downloadString(String url, String body, String token) throws IOException {
		HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
		conn.addRequestProperty("Authorization", "Bearer " + token);
		conn.setDoOutput(true);
		conn.getOutputStream().write(body.getBytes("UTF-8"));
		InputStream strm = conn.getInputStream();
		String resp = new String(strm.readAllBytes());
		strm.close();
		return resp;
	}

	@Override
	protected void clientDisconnect(SmartfoxClient client) {
		if (client.container != null && client.container instanceof SmartfoxClient) {
			SmartfoxClient oldC = (SmartfoxClient) client.container;
			client.container = null;
			oldC.disconnect();
			clients.remove(client);
		}
	}

	@Override
	protected void onStart() {
		clients.forEach(t -> {
			((SmartfoxClient) t.container).disconnect();
			t.disconnect();
		});
		clients.clear();
	}

	@Override
	protected void onStop() {
	}

	@Override
	protected SmartfoxClient createSocketClient(Socket client) {
		return new SocketSmartfoxClient(client, this);
	}

}
