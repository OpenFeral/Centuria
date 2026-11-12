package org.asf.centuria.networking.persistentservice;

import java.io.IOException;
import java.io.Reader;
import java.net.Socket;
import java.util.ArrayList;
import org.apache.logging.log4j.MarkerManager;
import org.asf.centuria.Centuria;
import org.asf.centuria.networking.persistentservice.networking.AbstractPersistentServicePacket;
import org.asf.connective.tasks.AsyncTaskManager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

public abstract class BasePersistentServiceClient<T extends BasePersistentServiceClient<T, T2>, T2 extends BasePersistentServiceServer<T, T2>> {

	private Socket client;
	private T2 server;
	private JsonReader reader;

	private ArrayList<Object> objects = new ArrayList<Object>();
	private ArrayList<JsonObject> sendQueue = new ArrayList<JsonObject>();

	// Rates
	private int ppsHighest;
	private long ppsHighestLastChange;
	private int ppsCurrent;
	private int ppsLast;

	private boolean ioThreadInited;

	private boolean disconnecting = false;

	/**
	 * Retrieves the amount of packets received in the last second
	 * 
	 * @return Amount of packets received in the last second
	 */
	public int getPacketsPerSecondRate() {
		return ppsLast;
	}

	/**
	 * Retrieves the highest amount of packets that were received in the last PPS
	 * cycle (cycles are around 30 seconds of no increase in highest PPS)
	 * 
	 * @return Amount of packets received in the last cycle
	 */
	public int getHighestPacketsPerSecondRate() {
		return ppsHighest;
	}

	void updatePPS() {
		// Update
		int cPPS = ppsCurrent;
		ppsLast = cPPS;
		ppsCurrent = 0;

		// Check highest count and if its been long enough since the last time the
		// packet rate was high
		if (cPPS > ppsHighest || (System.currentTimeMillis() - ppsHighestLastChange) >= 30000) {
			// Increase highest rate
			ppsHighest = cPPS;
			ppsHighestLastChange = System.currentTimeMillis();
		} else if ((ppsHighest < 50 && ppsHighest >= 30 && cPPS + 10 >= ppsHighest)
				|| (ppsHighest >= 50 && cPPS + (ppsHighest / 10) >= ppsHighest)) {
			// Close to the last number, dont remove the highest count until the amount of
			// packets drops more significantly
			ppsHighestLastChange = System.currentTimeMillis();
		}
	}

	/**
	 * Should be called whenever a packet is read
	 */
	protected void onPacketReceived() {
		// Increase PPS
		ppsCurrent++;
	}

	/**
	 * Retrieves objects from the connection container, used to store information in
	 * clients.
	 * 
	 * @since Beta 1.5.3
	 * @param type Object type
	 * @return Object instance or null
	 */
	@SuppressWarnings("unchecked")
	public <T1> T1 getObject(Class<T1> type) {
		for (Object obj : objects) {
			if (type.isAssignableFrom(obj.getClass()))
				return (T1) obj;
		}
		return null;
	}

	/**
	 * Adds objects to the connection container, used to store information in
	 * clients.
	 * 
	 * @since Beta 1.5.3
	 * @param obj Object to add
	 */
	public void addObject(Object obj) {
		if (getObject(obj.getClass()) == null)
			objects.add(obj);
	}

	// I/O
	private Object sendLock = new Object();

	public BasePersistentServiceClient(Socket client, T2 server) {
		this.client = client;
		this.server = server;

		reader = new JsonReader(new Reader() {

			@Override
			public int read(char[] cbuf, int off, int len) throws IOException {
				byte[] data = new byte[cbuf.length];
				int l = client.getInputStream().read(data);
				for (int i = 0; i < l; i++) {
					cbuf[off + i] = (char) data[i];
				}
				return l;
			}

			@Override
			public void close() throws IOException {
			}

		});
	}

	/**
	 * Stop method called on client cleanup
	 */
	protected void stop() {
	}

	/**
	 * Client intialization code
	 * 
	 * @throws IOException If initializing fails
	 */
	protected abstract void runClient() throws IOException;

	// Packet handling code
	protected void handle(JsonObject packet) {
		if (Centuria.debugMode)
			Centuria.logger.debug(MarkerManager.getMarker(getClass().getSimpleName()), "C->S: " + packet);
		if (!handlePacket(packet)) {
			// Packet not found
			// Allow debug mode to re-register packets
			if (Centuria.debugMode) {
				server.registry.clear();
				server.registerPackets();
			}

			Centuria.logger.error("Unhandled packet: client " + client + " sent: " + packet.toString());
		}
	}

	@SuppressWarnings("unchecked")
	protected boolean handlePacket(JsonObject packet) {
		// Find packet in registry
		for (AbstractPersistentServicePacket<T, T2> pkt : server.registry) {
			if (pkt.id().equals(packet.get("cmd").getAsString())) {
				// Found a compatible packet, instantiate it
				AbstractPersistentServicePacket<T, T2> res = pkt.instantiate();

				// Parse packet
				res.parse(packet);

				// Handle packet
				if (res.handle((T) this))
					return true; // Packet was handled, lets end the loop
			}
		}

		// Packet was not handled
		return false;
	}

	/**
	 * Retrieves the client socket
	 * 
	 * @return Socket instance
	 */
	public Socket getSocket() {
		return client;
	}

	/**
	 * Disconnects the client
	 */
	public void disconnect() {
		// Wait for queue to flush
		disconnecting = true;
		int lastSize = 0;
		long lastSent = System.currentTimeMillis();
		boolean hasPackets = true;
		while (hasPackets) {
			int lastPSize = lastSize;
			synchronized (sendQueue) {
				lastSize = sendQueue.size();
				hasPackets = lastSize > 0;
			}

			// Wait
			if (hasPackets) {
				// Reset timer
				if (lastSize != lastPSize)
					lastSent = System.currentTimeMillis();

				// Check time
				if (System.currentTimeMillis() - lastSent > 15000)
					break; // Terminate connection and send logic, taking too long to send
			}
		}

		// Disconnect
		try {
			if (client != null)
				client.close();
		} catch (IOException e) {
		}
		stop();
		client = null;
		sendQueue.clear();
		disconnecting = false;
	}

	/**
	 * Sends a packet to the client
	 * 
	 * @param packet Raw packet to send
	 */
	public void sendPacket(JsonObject packet) {
		if (!ioThreadInited) {
			ioThreadInited = true;

			// Start IO
			AsyncTaskManager.runAsync(() -> {
				// IO Logic
				while (client != null) {
					// Pick up next packet awaiting transmission
					JsonObject pkt = null;
					synchronized (sendLock) {
						// Check
						if (sendQueue.size() != 0) {
							// Get packet
							pkt = sendQueue.get(0);
						}
					}

					// Check result
					if (pkt == null) {
						try {
							Thread.sleep(10);
						} catch (InterruptedException e) {
						}
						continue;
					}

					try {
						// Send packet
						if (getSocket() == null)
							return;
						char[] p = pkt.toString().toCharArray();
						byte[] b = new byte[p.length];
						for (int i = 0; i < p.length; i++)
							b[i] = (byte) p[i];
						client.getOutputStream().write(b);
						client.getOutputStream().write(0);
						client.getOutputStream().flush();
						if (Centuria.debugMode)
							Centuria.logger.debug(MarkerManager.getMarker(getClass().getSimpleName()), "S->C: " + pkt);

						// Remove from queue
						synchronized (sendLock) {
							sendQueue.remove(0);
						}
					} catch (Exception e) {
						// Failed to send
						// Assume disconnect
						sendQueue.clear();
						break;
					}
				}

				// End
				ioThreadInited = false;
			});
		}
		if (!isConnected())
			return;
		synchronized (sendLock) {
			// Add
			if (!isConnected())
				return;
			sendQueue.add(packet);
		}
	}

	/**
	 * Sends a packet to the client
	 * 
	 * @param packet Packet to send
	 */
	public void sendPacket(AbstractPersistentServicePacket<T, T2> packet) {
		JsonObject data = new JsonObject();
		data.addProperty("eventId", packet.id());
		packet.build(data);
		sendPacket(data);
	}

	/**
	 * Reads a single raw packet
	 * 
	 * @return JsonObject instance
	 * @throws IOException If reading fails
	 */
	public JsonObject readRawPacket() throws IOException {
		try {
			JsonElement ele = JsonParser.parseReader(reader);
			if (ele == null || !ele.isJsonObject())
				throw new IOException("Invalid request received, stream likely closed");
			onPacketReceived();
			return ele.getAsJsonObject();
		} catch (IllegalStateException e) {
			throw new IOException("Error reading next packet", e);
		}
	}

	/**
	 * Retrieves the server object
	 * 
	 * @return Server instance
	 */
	public T2 getServer() {
		return server;
	}

	/**
	 * Checks if the client is still connected
	 * 
	 * @return True if connected, false otherwise
	 */
	public boolean isConnected() {
		return client != null && !disconnecting;
	}

}
