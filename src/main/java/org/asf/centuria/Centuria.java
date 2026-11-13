package org.asf.centuria;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyManagementException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.SignatureException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.UUID;
import java.util.stream.Stream;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.asf.connective.ConnectiveHttpServer;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.pqc.jcajce.spec.DilithiumParameterSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.asf.centuria.connective.logger.Log4jManagerImpl;
import org.asf.centuria.dms.DMManager;
import org.asf.centuria.entities.components.ComponentManager;
import org.asf.centuria.entities.inventoryitems.InventoryItemManager;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.modules.ICenturiaModule;
import org.asf.centuria.modules.ModuleManager;
import org.asf.centuria.modules.eventbus.EventBus;
import org.asf.centuria.modules.events.accounts.AccountDisconnectEvent;
import org.asf.centuria.modules.events.accounts.AccountDisconnectEvent.DisconnectType;
import org.asf.centuria.modules.events.servers.APIServerStartupEvent;
import org.asf.centuria.modules.events.servers.DirectorServerStartupEvent;
import org.asf.centuria.modules.events.updates.AutomaticUpdateFailedEvent;
import org.asf.centuria.modules.events.updates.ServerUpdateCompletionEvent;
import org.asf.centuria.modules.events.updates.ServerUpdateEvent;
import org.asf.centuria.modules.events.updates.UpdateCancelEvent;
import org.asf.centuria.networking.chatserver.ChatClient;
import org.asf.centuria.networking.chatserver.ChatServer;
import org.asf.centuria.networking.chatserver.rooms.ChatRoomTypes;
import org.asf.centuria.networking.gameserver.GameServer;
import org.asf.centuria.networking.http.api.FallbackAPIProcessor;
import org.asf.centuria.networking.http.api.GameRegistrationHandler;
import org.asf.centuria.networking.http.api.AuthenticateHandler;
import org.asf.centuria.networking.http.api.DisplayNameValidationHandler;
import org.asf.centuria.networking.http.api.DisplayNamesRequestHandler;
import org.asf.centuria.networking.http.api.RequestTokenHandler;
import org.asf.centuria.networking.http.api.SeasonPassRequestHandler;
import org.asf.centuria.networking.http.api.UpdateDisplayNameHandler;
import org.asf.centuria.networking.http.api.UserHandler;
import org.asf.centuria.networking.http.api.XPDetailsHandler;
import org.asf.centuria.networking.http.api.custom.ChangeDisplayNameHandler;
import org.asf.centuria.networking.http.api.custom.ChangeLoginNameHandler;
import org.asf.centuria.networking.http.api.custom.ChangePasswordHandler;
import org.asf.centuria.networking.http.api.custom.DeleteAccountHandler;
import org.asf.centuria.networking.http.api.custom.ListPlayersHandler;
import org.asf.centuria.networking.http.api.custom.LoginRefreshHandler;
import org.asf.centuria.networking.http.api.custom.PlayerDataDownloadHandler;
import org.asf.centuria.networking.http.api.custom.RegistrationHandler;
import org.asf.centuria.networking.http.api.custom.SaveManagerHandler;
import org.asf.centuria.networking.http.api.custom.UserDetailsHandler;
import org.asf.centuria.networking.http.director.GameServerRequestHandler;
import org.asf.centuria.networking.voicechatserver.VoiceChatServer;
import org.asf.centuria.seasonpasses.SeasonPassManager;
import org.asf.centuria.textfilter.TextFilterService;
import org.asf.centuria.updater.PolyUpdaterClient;
import org.asf.centuria.updater.collections.PolyCollection;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class Centuria {
	// Configuration
	public static Logger logger;
	public static HashMap<String, String> serverProperties;
	public static HashMap<String, String> textFilterProperties;
	public static boolean defaultUseManagedSaves = false;
	public static boolean allowRegistration = true;
	public static boolean defaultGiveAllAvatars = true;
	public static boolean defaultGiveAllMods = true;
	public static boolean defaultGiveAllClothes = true;
	public static boolean defaultGiveAllWings = true;
	public static boolean defaultGiveAllFurnitureItems = true;
	public static boolean defaultGiveAllSanctuaryTypes = true;
	public static boolean defaultGiveAllCurrency = true;
	public static boolean defaultGiveAllResources = true;
	public static boolean defaultAllowGiveItemSanctuaryTypes = true;
	public static boolean defaultAllowGiveItemAvatars = true;
	public static boolean defaultAllowGiveItemMods = true;
	public static boolean defaultAllowGiveItemClothes = true;
	public static boolean defaultAllowGiveItemFurnitureItems = true;
	public static boolean defaultAllowGiveItemResources = true;
	public static boolean defaultAllowGiveItemEnigmas = true;
	public static boolean defaultAllowGiveItemCurrency = true;
	public static boolean defaultAllowSkipTwiggleWork = true;
	public static boolean defaultEnableCreativeRestock = false;
	public static boolean staffFixedUpdateError = false;
	public static boolean forceInstallUpdate = false;
	private static boolean lockUpdaterUntilFixed = false;
	public static boolean encryptChat = false;
	public static boolean encryptGame = false;
	public static boolean encryptVoiceChat = false;
	public static boolean debugMode = false;
	public static String discoveryAddress = "localhost";
	public static String spawnBehaviour;

	public static long keepAliveWarningInterval = 0;
	public static long keepAliveKickInterval = 0;

	public static boolean hasUpdaterFailed() {
		return lockUpdaterUntilFixed;
	}

	// Current version
	// FIXME: replace with better solution
	public static String SERVER_UPDATE_VERSION = "b1.7.4";

	// Servers
	private static ConnectiveHttpServer apiServer;
	public static ConnectiveHttpServer directorServer;
	public static GameServer gameServer;
	public static ChatServer chatServer;
	public static VoiceChatServer voiceChatServer;

	// Keys
	private static PrivateKey privateKey;
	private static PublicKey publicKey;

	// Messages
	private static String NIL_UUID = new UUID(0, 0).toString();

	/**
	 * Main method used to start the servers
	 * 
	 * @throws SecurityException
	 * @throws NoSuchMethodException
	 * @throws IllegalArgumentException
	 * @throws IllegalAccessException
	 * @throws InstantiationException
	 */
	public static void main(String[] args)
			throws InvocationTargetException, IOException, NoSuchAlgorithmException, InstantiationException,
			IllegalAccessException, IllegalArgumentException, NoSuchMethodException, SecurityException {
		// Splash message
		System.out.println("--------------------------------------------------------------------");
		System.out.println("                                                                    ");
		System.out.println("                              Centuria                              ");
		System.out.println("                       Fer.al Server Emulator                       ");
		System.out.println("                                                                    ");
		System.out.println("                           Version b1.7.4                           ");
		System.out.println("                                                                    ");
		System.out.println("--------------------------------------------------------------------");
		System.out.println("");

		// Setup logging
		if (System.getProperty("debugMode") != null) {
			System.setProperty("log4j2.configurationFile", Centuria.class.getResource("/log4j2-ide.xml").toString());
			debugMode = true;
		} else {
			System.setProperty("log4j2.configurationFile", Centuria.class.getResource("/log4j2.xml").toString());
		}
		logger = LogManager.getLogger("CENTURIA");
		Security.addProvider(new BouncyCastleProvider());

		// Check arguments
		if (Stream.of(args).anyMatch(t -> t.equals("--force-update"))) {
			forceInstallUpdate = true;
		}

		// Check if coming from a legacy update
		if (new File("installedfromlegacyupdatemarker").exists()) {
			// Enable force install mode so we can overwrite the old legacy payloads
			// properly
			forceInstallUpdate = true;
		}

		// Load http logger
		new Log4jManagerImpl().assignAsMain();

		// Load modules
		ModuleManager.getInstance().init();

		// Create updater files if needed
		File repoCache = new File("updater/repositories");
		File packageCache = new File("updater/packages");
		repoCache.mkdirs();
		packageCache.mkdirs();
		if (!new File("updater.json").exists()) {
			boolean oldDisableUpdater = true;
			boolean oldEnableRuntime = true;
			if (new File("updater.conf").exists()) {
				// Parse properties
				HashMap<String, String> properties = new HashMap<String, String>();
				for (String line : Files.readAllLines(Path.of("updater.conf"))) {
					String key = line;
					String value = "";
					if (key.contains("=")) {
						value = key.substring(key.indexOf("=") + 1);
						key = key.substring(0, key.indexOf("="));
					}
					properties.put(key, value);
				}

				// Check if disabled
				oldDisableUpdater = Boolean.parseBoolean(properties.getOrDefault("disable", "true"));
				oldEnableRuntime = Boolean.parseBoolean(properties.getOrDefault("runtime-auto-update", "true"));
			}

			// Create config
			InputStream strm = Centuria.class.getClassLoader().getResourceAsStream("skel/updater.json");
			String configData = new String(strm.readAllBytes(), "UTF-8");
			strm.close();
			Files.writeString(Path.of("updater.json"),
					configData.replace("%oldEnableRuntime%", oldEnableRuntime ? "true" : "false")
							.replace("%oldEnableUpdater%", !oldDisableUpdater ? "true" : "false"));
		}

		// Parse properties
		JsonObject updateSettings = JsonParser.parseString(Files.readString(Path.of("updater.json"))).getAsJsonObject();

		// Check if disabled
		boolean disableUpdater = updateSettings.has("enabled") && !updateSettings.get("enabled").getAsBoolean();

		// Check json upgrade
		PolyUpdaterClient.installJsonUpgrades(new File("."));

		// Updater
		if (!disableUpdater) {
			// Initialize
			PolyUpdaterClient.init(updateSettings, repoCache, packageCache, new File("."));

			// Check for updates
			if (shouldUpdate()) {
				// Download
				if (new File("installedfromlegacyupdatemarker").exists()) {
					// Enable force install mode so we can overwrite the old legacy payloads
					// properly
					forceInstallUpdate = true;
				}
				if (!downloadUpdate(
						"Add the argument \"--force-update\" to the server command to forcefully install the update bypassing conflict detection mechanism.")) {
					// Failed
					logger.fatal("The download process could not be completed, please check the log for errors.");
					EventBus.getInstance().dispatchEvent(new AutomaticUpdateFailedEvent());
					System.exit(1);
					return;
				}

				// Check for errors
				boolean hasInstallables = false;
				boolean shouldWarn = false;
				for (PolyCollection col : PolyUpdaterClient.getCollections()) {
					if (!col.isInstalledLocally() && !col.hasUpdateAvailable()) {
						shouldWarn = true;
					} else if (col.hasUpdateAvailable()) {
						hasInstallables = true;
					}
				}
				if (shouldWarn && !hasInstallables) {
					// Error
					logger.fatal(
							"Automatic installation of packages failed! The server was unable to download requested collections.");
					System.exit(1);
					return;
				} else if (shouldWarn) {
					// Error
					logger.warn(
							"There are collections that are pending installation however not all could be provided a source for downloading, please review the logs!");
					logger.warn(
							"The server will proceed with downloading the packages that are available, however this is likely to crash the server on next startup!");
				}

				// Dispatch event
				EventBus.getInstance()
						.dispatchEvent(new ServerUpdateEvent(PolyUpdaterClient.getBaseSoftwareNextVersion(), -1,
								Stream.of(PolyUpdaterClient.getCollections()).filter(t -> t.hasUpdateAvailable())
										.toArray(t -> new PolyCollection[t])));

				// Dispatch completion event
				EventBus.getInstance()
						.dispatchEvent(new ServerUpdateCompletionEvent(PolyUpdaterClient.getBaseSoftwareNextVersion(),
								Stream.of(PolyUpdaterClient.getCollections()).filter(t -> t.hasUpdateAvailable())
										.toArray(t -> new PolyCollection[t])));

				// Copy jar
				if (!new File("updater.jar").exists() && new File("centuria.jar").exists())
					Files.copy(Path.of("centuria.jar"), Path.of("updater.jar"));
				else if (!new File("updater.jar").exists() && new File("Centuria.jar").exists())
					Files.copy(Path.of("Centuria.jar"), Path.of("updater.jar"));
				if (new File("installedfromlegacyupdatemarker").exists())
					new File("installedfromlegacyupdatemarker").delete();

				// Exit server
				logger.info("Restarting server!");
				System.exit(0);
			}

			// Check if automatic updating is enabled
			if (updateSettings.has("enableRuntimeUpdater")
					&& updateSettings.get("enableRuntimeUpdater").getAsBoolean()) {
				int mins = updateSettings.has("runtimeUpdaterTimer")
						? updateSettings.get("runtimeUpdaterTimer").getAsInt()
						: 10;

				// Start the automatic update thread
				Thread updater = new Thread(() -> {
					boolean wasLocked = false;
					JsonObject updateSettingsLocal = updateSettings;
					while (true) {
						// Run every 2 minutes
						try {
							Thread.sleep(120000);
						} catch (InterruptedException e) {
							break;
						}

						// Check scheduled
						if (PolyUpdaterClient.isUpdateScheduled()) {
							// Update was scheduled manually
							// Wait until its no longer scheduled
							continue;
						}

						// Check
						if (lockUpdaterUntilFixed && !staffFixedUpdateError)
							continue;
						if (wasLocked && staffFixedUpdateError) {
							// Reload config
							logger.info("Loading update configuration...");
							try {
								updateSettingsLocal = JsonParser.parseString(Files.readString(Path.of("updater.json")))
										.getAsJsonObject();
							} catch (Exception e) {
								// Failed Failed
								staffFixedUpdateError = false;
								logger.fatal("Could not reinitialize automatic updater!", e);
								EventBus.getInstance().dispatchEvent(new AutomaticUpdateFailedEvent());
								PolyUpdaterClient.resetUpdateStates();
								lockUpdaterUntilFixed = true;
								staffFixedUpdateError = false;
								wasLocked = true;
								continue;
							}
						}
						staffFixedUpdateError = false;

						// De-initialize the updater
						logger.info("Beginning automatic update checker...");
						PolyUpdaterClient.deinitialize();

						// Reinit
						try {
							// Reinit
							logger.info("Reinitializing update system...");
							PolyUpdaterClient.init(updateSettingsLocal, repoCache, packageCache, new File("."));
						} catch (Exception e) {
							// Failed Failed
							logger.fatal("Could not reinitialize automatic updater!", e);
							EventBus.getInstance().dispatchEvent(new AutomaticUpdateFailedEvent());
							PolyUpdaterClient.resetUpdateStates();
							lockUpdaterUntilFixed = true;
							staffFixedUpdateError = false;
							wasLocked = true;
							continue;
						}

						// Check for updates
						if (shouldUpdate()) {
							try {
								// Download
								if (new File("installedfromlegacyupdatemarker").exists()) {
									// Enable force install mode so we can overwrite the old legacy payloads
									// properly
									forceInstallUpdate = true;
								}
								if (!downloadUpdate(
										"Use the admin command \"forceinstallupdate\" ingame to forcefully install updates bypassing the conflict detection mechanism.")) {
									// Failed
									logger.fatal(
											"The download process could not be completed, please check the log for errors.");
									EventBus.getInstance().dispatchEvent(new AutomaticUpdateFailedEvent());
									PolyUpdaterClient.resetUpdateStates();
									lockUpdaterUntilFixed = true;
									wasLocked = true;

									// Cancel if needed
									if (PolyUpdaterClient.isUpdateScheduled()) {
										boolean res = PolyUpdaterClient.cancelScheduledUpdate();
										if (res)
											EventBus.getInstance().dispatchEvent(new UpdateCancelEvent());
										forceInstallUpdate = false;
									}

									// Error
									throw new IOException();
								}

								// Check for errors
								boolean hasInstallables = false;
								boolean shouldWarn = false;
								for (PolyCollection col : PolyUpdaterClient.getCollections()) {
									if (!col.isInstalledLocally() && !col.hasUpdateAvailable()) {
										shouldWarn = true;
									} else if (col.hasUpdateAvailable()) {
										hasInstallables = true;
									}
								}
								if (shouldWarn && !hasInstallables) {
									// Error
									logger.fatal(
											"Automatic installation of packages failed! The server was unable to download requested collections.");
									EventBus.getInstance().dispatchEvent(new AutomaticUpdateFailedEvent());
									PolyUpdaterClient.resetUpdateStates();
									lockUpdaterUntilFixed = true;
									wasLocked = true;
									throw new IOException();
								} else if (shouldWarn) {
									// Error
									logger.fatal(
											"There are collections that are pending installation however not all could be provided a source for downloading, please review the logs!");
									EventBus.getInstance().dispatchEvent(new AutomaticUpdateFailedEvent());
									PolyUpdaterClient.resetUpdateStates();
									lockUpdaterUntilFixed = true;
									wasLocked = true;
									throw new IOException();
								}
							} catch (IOException e) {
								if (lockUpdaterUntilFixed)
									continue;

								// Rerun after an hour
								Centuria.logger.error("An error occurred while downloading available updates!", e);
								try {
									Thread.sleep(60 * 1000);
								} catch (InterruptedException e1) {
									break;
								}
								continue;
							}

							// Schedule updater
							if (!PolyUpdaterClient.isUpdateScheduled()) {
								logger.info("Scheduling update for automatic restart!");
								runUpdater(mins);
							}
							return;
						}
					}
				}, "Automatic update thread");
				updater.setDaemon(true);
				updater.start();
			}
		}

		// Managers
		logger.info("Initializing services...");
		logger.info("Initializing DM manager...");
		DMManager.getInstance();

		logger.info("Initializing textfilter service...");
		TextFilterService.getInstance().initService();

		logger.info("Initializing item component manager...");
		ComponentManager.registerAllComponents();

		logger.info("Initializing item types...");
		InventoryItemManager.registerAllItems();

		// Start the servers
		startServer();

		// Wait for exit
		directorServer.waitForExit();
		apiServer.waitForExit();
		chatServer.stop();
		voiceChatServer.stop();
		gameServer.stop();
	}

	/**
	 * Cancels the update
	 * 
	 * @return True if successful, false otherwise
	 */
	public static boolean cancelUpdate() {
		boolean res = PolyUpdaterClient.cancelScheduledUpdate();
		if (res)
			EventBus.getInstance().dispatchEvent(new UpdateCancelEvent());
		forceInstallUpdate = false;
		return res;
	}

	/**
	 * Runs the update timer (kicks players after a specified time for server
	 * reboot)
	 * 
	 * @param mins Time given before restart in minutes
	 * @return True if successful, false otherwise
	 */
	public static boolean runUpdater(int mins) {
		// Run timer
		if (PolyUpdaterClient.isUpdateScheduled())
			return false; // Already scheduled

		// Check state
		if (!PolyUpdaterClient.isUpdateCancelled()) {
			// Mark scheduled
			PolyUpdaterClient.scheduleUpdate();

			// Dispatch
			EventBus.getInstance()
					.dispatchEvent(new ServerUpdateEvent(PolyUpdaterClient.getBaseSoftwareNextVersion(), mins,
							Stream.of(PolyUpdaterClient.getCollections()).filter(t -> t.hasUpdateAvailable())
									.toArray(t -> new PolyCollection[t])));

			// Check if server is empty
			if (Centuria.gameServer.getPlayers().length == 0) {
				// Restart now
				updateShutdown(null);
				return true;
			}

			// Start timer
			final int minutes = mins;
			Thread th = new Thread(() -> {
				int remaining = minutes;
				while (!PolyUpdaterClient.isUpdateCancelled()) {
					// Check
					if (Centuria.gameServer.getPlayers().length == 0) {
						// Restart now
						updateShutdown(null);
						break;
					}

					// Send message
					String message = null;
					switch (remaining) {
					case 120:
					case 60:
					case 30:
					case 15:
					case 10:
					case 5:
					case 3:
						message = "%xt%ua%-1%7391|" + remaining + "%";
						break;
					case 1:
						message = "%xt%ua%-1%7390|1%";
						break;
					case 0:
						// Shut down
						updateShutdown(null);
						return;
					}

					if (message != null) {
						// Warn everyone
						for (Player plr : Centuria.gameServer.getPlayers()) {
							plr.client.sendPacket(message);
						}
					}

					for (int i = 0; i < 60; i++) {
						if (PolyUpdaterClient.isUpdateCancelled())
							break;
						try {
							Thread.sleep(1000);
						} catch (InterruptedException e) {
						}
					}

					remaining--;
				}
				PolyUpdaterClient.resetScheduledUpdate();
			});
			th.setName("Update Scheduler Thread");
			th.start();

			// Return
			return true;
		}

		return false;
	}

	/**
	 * Shuts down the server with a update message
	 */
	public static void updateShutdown(String reason) {
		// Dispatch event if the update was instant
		logger.info("Shutting down server for update...");
		if (!PolyUpdaterClient.isUpdateScheduled()) {
			EventBus.getInstance()
					.dispatchEvent(new ServerUpdateEvent(PolyUpdaterClient.getBaseSoftwareNextVersion(), -1,
							Stream.of(PolyUpdaterClient.getCollections()).filter(t -> t.hasUpdateAvailable())
									.toArray(t -> new PolyCollection[t])));
		}

		// Shut down the server
		disconnectPlayersForShutdown(reason);

		// Dispatch completion event
		EventBus.getInstance()
				.dispatchEvent(new ServerUpdateCompletionEvent(PolyUpdaterClient.getBaseSoftwareNextVersion(),
						Stream.of(PolyUpdaterClient.getCollections()).filter(t -> t.hasUpdateAvailable())
								.toArray(t -> new PolyCollection[t])));

		// Copy jar
		try {
			if (!new File("updater.jar").exists() && new File("centuria.jar").exists())
				Files.copy(Path.of("centuria.jar"), Path.of("updater.jar"));
			else if (!new File("updater.jar").exists() && new File("Centuria.jar").exists())
				Files.copy(Path.of("Centuria.jar"), Path.of("updater.jar"));
			if (new File("installedfromlegacyupdatemarker").exists())
				new File("installedfromlegacyupdatemarker").delete();
		} catch (IOException e) {
		}

		// Exit
		System.exit(0);
	}

	/**
	 * Disconnects all players with a update message
	 */
	public static void disconnectPlayersForShutdown(String reason) {
		// Disconnect everyone
		for (Player plr : Centuria.gameServer.getPlayers()) {
			plr.client.sendPacket("%xt%ua%-1%__FORCE_RELOGIN__%");
		}

		// Inform the game server to disconnect with maintenance
		Centuria.gameServer.shutdown = true;
		Centuria.gameServer.maintenance = true;

		// Wait a bit
		int i = 0;
		while (Centuria.gameServer.getPlayers().length != 0) {
			i++;
			if (i == 30)
				break;
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
			}
		}
		for (Player plr : Centuria.gameServer.getPlayers()) {
			// Dispatch event
			EventBus.getInstance()
					.dispatchEvent(new AccountDisconnectEvent(plr.account, reason, DisconnectType.SERVER_SHUTDOWN));

			plr.client.disconnect();
		}

		// Wait for log off
		int l = 0;
		while (Centuria.gameServer.getPlayers().length != 0) {
			l++;
			if (l == 60) {
				break;
			}
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
			}
		}
	}

	public static void startServer()
			throws InvocationTargetException, UnknownHostException, IOException, NoSuchAlgorithmException {
		// Server configuration
		File serverConf = new File("server.conf");
		if (!serverConf.exists()) {
			Files.writeString(serverConf.toPath(), "" //
					+ "api-port=37501\n" //
					+ "director-port=37502\n" //
					+ "game-port=37503\n" //
					+ "chat-port=37504\n"//
					+ "voice-chat-port=37505\n" //
					+ "\n" //
					+ "allow-registration=true\n" //
					+ "\n" //
					+ "give-all-avatars=false\n" //
					+ "give-all-mods=false\n" //
					+ "give-all-clothes=false\n" //
					+ "give-all-wings=false\n" //
					+ "give-all-sanctuary-types=false\n" //
					+ "give-all-furniture=false\n" //
					+ "give-all-currency=false\n" //
					+ "give-all-resources=false\n" //
					+ "allow-giveitem-resources=false\n" //
					+ "allow-giveitem-enigmas=false\n" //
					+ "allow-giveitem-currency=false\n" //
					+ "allow-giveitem-furniture=false\n" //
					+ "allow-giveitem-sanctuary-types=false\n" //
					+ "allow-giveitem-clothes=false\n" //
					+ "allow-giveitem-avatars=false\n" //
					+ "allow-giveitem-mods=false\n" //
					+ "allow-skip-twiggle-work=false\n" //
					+ "enable-creative-restock=false\n" //
					+ "\n" //
					+ "server-spawn-behaviour=random\n" //
					+ "default-save-behaviour=single\n" //
					+ "\n" //
					+ "discovery-server-address=localhost\n" //
					+ "\n" //
					+ "encrypt-api=false\n" //
					+ "encrypt-chat=true\n" //
					+ "encrypt-voice-chat=true\n" //
					+ "encrypt-game=false\n" //
					+ "encrypt-director=false\n" //
					+ "debug-mode=false\n" //
					+ "\n" //
					+ "vpn-user-whitelist=vpn-whitelist\n" //
					+ "vpn-ipv4-banlist=\n" //
					+ "vpn-ipv6-banlist=\n" //
					+ "\n" //
					+ "allowed-proxies=");
		}

		// Parse properties
		serverProperties = new HashMap<String, String>();
		for (String line : Files.readAllLines(serverConf.toPath())) {
			if (line.isEmpty() || line.startsWith("#"))
				continue;
			String key = line;
			String value = "";
			if (key.contains("=")) {
				value = key.substring(key.indexOf("=") + 1);
				key = key.substring(0, key.indexOf("="));
			}
			serverProperties.put(key, value);
		}

		// Parse keepalive
		File keepAliveConf = new File("keepalive.conf");
		if (!keepAliveConf.exists()) {
			Files.writeString(keepAliveConf.toPath(), "" //
					+ "keep-alive-warning-interval-secs=3600\n" //
					+ "keep-alive-kick-interval-secs=4500\n" //
					+ "");
		}
		HashMap<String, String> keepAliveProperties = new HashMap<String, String>();
		for (String line : Files.readAllLines(keepAliveConf.toPath())) {
			if (line.isEmpty() || line.startsWith("#"))
				continue;
			String key = line;
			String value = "";
			if (key.contains("=")) {
				value = key.substring(key.indexOf("=") + 1);
				key = key.substring(0, key.indexOf("="));
			}
			keepAliveProperties.put(key, value);
		}
		keepAliveWarningInterval = Long
				.parseLong(keepAliveProperties.getOrDefault("keep-alive-warning-interval-secs", "3600"));
		keepAliveKickInterval = Long
				.parseLong(keepAliveProperties.getOrDefault("keep-alive-kick-interval-secs", "4500"));

		File textFilterConf = new File("textfilter.conf");
		if (!textFilterConf.exists()) {
			Files.writeString(textFilterConf.toPath(), "" //
					+ "# Public chat notice displayed when the chat loads\n" //
					+ "public-chat-notice=Welcome to the public chat! Please keep in mind that these chats are monitored by the server moderators. As Fer.al is host to a playerbase of players as young as 13, there are chat filters present on the server, however we try to keep them relatively relaxed. Check the server rules for more information, and Welcome to Fer.al!\n" //
					+ "private-chat-notice=Welcome to direct messages! Keep in mind that while staff doesn't actively monitor these, considering our 13+ playerbase, chats are not encrypted and chat filters are still active in private chats, and filter triggers do still alert the moderators.\\n\\nThis is out of user safety concerns only, we do not monitor these chats actively, and they are in normal circumstances inaccesssible. In cases of severe reports, staff can request the server chats from the operators. For more information refer to the server rules.\n" //
					+ "\n" //
					+ "\n" //
					+ "# The amount of messages to retain in memory during context matching\n" //
					+ "chat-message-memory-max-length=10\n" //
					+ "\n" //
					+ "# The amount of messages surrounding filter triggers to retain\n" //
					+ "chat-message-trigger-retain-surrounding=10\n" //
					+ "\n" //
					+ "# The amount of triggers to retain in memory during context matching\n" //
					+ "chat-message-memory-max-triggers=5\n" //
					+ "\n" //
					+ "# The maximum position age of past filter triggers, eg. setting this to 15 will keep triggers that are up to 15 messages old, any more messages, and the trigger is dropped\n" //
					+ "chat-message-trigger-age-position-limit=15\n" //
					+ "\n" //
					+ "# The maximum age of past filter triggers time-wise in milliseconds, by default set to 20 minutes\n" //
					+ "chat-message-trigger-age-timems-limit=1200000\n" //
					+ "\n" //
					+ "\n" //
					+ "# The amount of time before heightened sensitivity disables in milliseconds, set to -1 to disable\n" //
					+ "heightened-sensitivity-deactivate-timer=900000\n" //
					+ "\n" //
					+ "# The amount of time before chat reenables after being disabled automatically in milliseconds, set to -1 to disable\n" //
					+ "heightened-sensitivity-chat-reactivate-timer=900000\n" //
					+ "\n" //
					+ "# The amount of filter triggers needed for heightened sensitivity mode to engage, set to -1 to disable\n" //
					+ "heightened-sensitivity-trigger-threshold=15\n" //
					+ "\n" //
					+ "# The maximum age of filter triggers in milliseconds for heightened sensitivity mode, set to -1 to disable\n" //
					+ "heightened-sensitivity-trigger-max-age=900000\n" //
					+ "\n" //
					+ "# The maximum amount of filter triggers during heightened sensitivity mode before chat is disabled, set to -1 to disable\n" //
					+ "heightened-sensitivity-chatdisable-threshold-primary=5\n" //
					+ "\n" //
					+ "# The maximum age of filter triggers in milliseconds for heightened sensitivity mode, set to -1 to disable\n" //
					+ "heightened-sensitivity-chatdisable-max-age-primary=300000\n" //
					+ "\n" //
					+ "# The maximum amount of filter triggers during heightened sensitivity mode before chat is disabled (secondary threshold), set to -1 to disable\n" //
					+ "heightened-sensitivity-chatdisable-threshold-secondary=30\n" //
					+ "\n" //
					+ "# The maximum age of filter triggers in milliseconds for heightened sensitivity mode (secondary threshold), set to -1 to disable\n" //
					+ "heightened-sensitivity-chatdisable-max-age-secondary=1800000\n" //
			);
		}
		textFilterProperties = new HashMap<String, String>();
		for (String line : Files.readAllLines(textFilterConf.toPath())) {
			if (line.isEmpty() || line.startsWith("#"))
				continue;
			String key = line;
			String value = "";
			if (key.contains("=")) {
				value = key.substring(key.indexOf("=") + 1);
				key = key.substring(0, key.indexOf("="));
			}
			textFilterProperties.put(key, value);
		}

		// Load or generate keys for JWT signatures
		File publicKey = new File("publickey-di.pem");
		File privateKey = new File("privatekey-di.pem");
		if (!publicKey.exists() || !privateKey.exists()) {
			// Generate new keys
			KeyPairGenerator gen = KeyPairGenerator.getInstance("Dilithium");
			try {
				gen.initialize(DilithiumParameterSpec.dilithium5);
			} catch (InvalidAlgorithmParameterException e) {
				throw new RuntimeException(e);
			}
			KeyPair pair = gen.generateKeyPair();

			// Save keys
			Files.writeString(publicKey.toPath(), pemEncode(pair.getPublic().getEncoded(), "PUBLIC"));
			Files.writeString(privateKey.toPath(), pemEncode(pair.getPrivate().getEncoded(), "PRIVATE"));
		}
		// Load keys
		KeyFactory fac = KeyFactory.getInstance("Dilithium");
		try {
			Centuria.privateKey = fac
					.generatePrivate(new PKCS8EncodedKeySpec(pemDecode(Files.readString(privateKey.toPath()))));
			Centuria.publicKey = fac
					.generatePublic(new X509EncodedKeySpec(pemDecode(Files.readString(publicKey.toPath()))));
		} catch (InvalidKeySpecException | IOException e1) {
			throw new RuntimeException(e1);
		}

		// Load properties into memory
		allowRegistration = serverProperties.getOrDefault("allow-registration", "true").equals("true");
		defaultGiveAllAvatars = serverProperties.getOrDefault("give-all-avatars", "true").equals("true");
		defaultGiveAllMods = serverProperties.getOrDefault("give-all-mods", "true").equals("true");
		defaultGiveAllClothes = serverProperties.getOrDefault("give-all-clothes", "true").equals("true");
		defaultGiveAllWings = serverProperties.getOrDefault("give-all-wings", "true").equals("true");
		defaultGiveAllSanctuaryTypes = serverProperties.getOrDefault("give-all-sanctuary-types", "true").equals("true");
		defaultGiveAllFurnitureItems = serverProperties.getOrDefault("give-all-furniture", "true").equals("true");
		defaultGiveAllResources = serverProperties.getOrDefault("give-all-resources", "true").equals("true");
		defaultGiveAllCurrency = serverProperties.getOrDefault("give-all-currency", "true").equals("true");
		defaultAllowGiveItemAvatars = serverProperties.getOrDefault("allow-giveitem-avatars", "true").equals("true");
		defaultAllowGiveItemMods = serverProperties.getOrDefault("allow-giveitem-mods", "true").equals("true");
		defaultAllowGiveItemClothes = serverProperties.getOrDefault("allow-giveitem-clothes", "true").equals("true");
		defaultAllowGiveItemSanctuaryTypes = serverProperties.getOrDefault("allow-giveitem-sanctuary-types", "true")
				.equals("true");
		defaultAllowGiveItemFurnitureItems = serverProperties.getOrDefault("allow-giveitem-furniture", "true")
				.equals("true");
		defaultAllowGiveItemResources = serverProperties.getOrDefault("allow-giveitem-resources", "true")
				.equals("true");
		defaultAllowSkipTwiggleWork = serverProperties.getOrDefault("allow-skip-twiggle-work", "true").equals("true");
		defaultEnableCreativeRestock = serverProperties.getOrDefault("enable-creative-restock", "true").equals("true");
		defaultAllowGiveItemEnigmas = serverProperties.getOrDefault("allow-giveitem-enigmas", "true").equals("true");
		defaultAllowGiveItemCurrency = serverProperties.getOrDefault("allow-giveitem-currency", "true").equals("true");
		encryptChat = serverProperties.getOrDefault("encrypt-chat", "false").equals("true")
				&& new File("keystore.jks").exists() && new File("keystore.jks.password").exists();
		encryptGame = serverProperties.getOrDefault("encrypt-game", "false").equals("true")
				&& new File("keystore.jks").exists() && new File("keystore.jks.password").exists();
		encryptVoiceChat = serverProperties.getOrDefault("encrypt-voice-chat", "true").equals("true")
				&& new File("keystore.jks").exists() && new File("keystore.jks.password").exists();
		discoveryAddress = serverProperties.getOrDefault("discovery-server-address", discoveryAddress);
		debugMode = serverProperties.getOrDefault("debug-mode", "false").equals("true");
		spawnBehaviour = serverProperties.getOrDefault("server-spawn-behaviour", "random");
		defaultUseManagedSaves = serverProperties.getOrDefault("default-save-behaviour", "single").equals("managed");
		if (spawnBehaviour == null)
			spawnBehaviour = "random";
		if (System.getProperty("debugMode", "false").equals("true"))
			debugMode = true;

		// Create default save settings if needed
		File defaultSaveSettingsFile = new File("savemanager.json");
		if (!defaultSaveSettingsFile.exists()) {
			JsonObject defaultSaveSettings = new JsonObject();

			// Create settings and deduct from server config
			String defaultSaveName = "default";
			if ((defaultGiveAllAvatars && defaultGiveAllMods && defaultGiveAllWings && defaultGiveAllSanctuaryTypes)
					|| (defaultAllowGiveItemAvatars && defaultAllowGiveItemMods
							&& defaultAllowGiveItemSanctuaryTypes)) {
				defaultSaveName = "creative";
			} else if (!defaultGiveAllAvatars && !defaultGiveAllMods && !defaultGiveAllClothes && !defaultGiveAllWings
					&& !defaultGiveAllSanctuaryTypes && !defaultGiveAllFurnitureItems && !defaultGiveAllResources
					&& !defaultGiveAllCurrency) {
				defaultSaveName = "experience";
			}

			// Create save array
			JsonObject saves = new JsonObject();
			JsonObject colors = new JsonObject();
			colors.addProperty("developer", "#ff00e6");
			colors.addProperty("admin", "red");
			colors.addProperty("moderator", "orange");
			colors.addProperty("player", "default");
			JsonObject prefixes = new JsonObject();
			prefixes.addProperty("developer", "[dev]");
			prefixes.addProperty("admin", "[admin]");
			prefixes.addProperty("moderator", "[mod]");
			prefixes.addProperty("player", "");
			JsonObject save = new JsonObject();
			save.addProperty("sanctuaryLimitOverride", -1);
			save.addProperty("giveAllAvatars", defaultGiveAllAvatars);
			save.addProperty("giveAllClothes", defaultGiveAllClothes);
			save.addProperty("giveAllMods", defaultGiveAllMods);
			save.addProperty("giveAllWings", defaultGiveAllWings);
			save.addProperty("giveAllFurnitureItems", defaultGiveAllFurnitureItems);
			save.addProperty("giveAllSanctuaryTypes", defaultGiveAllSanctuaryTypes);
			save.addProperty("giveAllCurrency", defaultGiveAllCurrency);
			save.addProperty("giveAllResources", defaultGiveAllResources);
			save.addProperty("allowGiveItemAvatars", defaultAllowGiveItemAvatars);
			save.addProperty("allowGiveItemClothes", defaultAllowGiveItemClothes);
			save.addProperty("allowGiveItemMods", defaultAllowGiveItemMods);
			save.addProperty("allowGiveItemFurnitureItems", defaultAllowGiveItemFurnitureItems);
			save.addProperty("allowGiveItemSanctuaryTypes", defaultAllowGiveItemSanctuaryTypes);
			save.addProperty("allowGiveItemCurrency", defaultAllowGiveItemCurrency);
			save.addProperty("allowGiveItemResources", defaultAllowGiveItemResources);
			save.addProperty("allowGiveItemEnigmas", defaultAllowGiveItemEnigmas);
			save.add("saveColors", colors);
			save.add("saveNamePrefixes", prefixes);
			saves.add(defaultSaveName, save);

			// Set basics
			defaultSaveSettings.addProperty("defaultSaveName", defaultSaveName);
			defaultSaveSettings.addProperty("migrationSaveName", defaultSaveName);
			defaultSaveSettings.add("saves", saves);

			try {
				Files.writeString(defaultSaveSettingsFile.toPath(),
						new Gson().newBuilder().setPrettyPrinting().create().toJson(defaultSaveSettings));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		// Load season passes
		SeasonPassManager.getCurrentPass();

		// Start the servers
		Centuria.logger
				.info("Starting API server on port " + Integer.parseInt(serverProperties.get("api-port")) + "...");

		//
		// Start API server
		try {
			// Create properties
			HashMap<String, String> props = new HashMap<String, String>();
			props.put("address", "0.0.0.0");
			props.put("port", serverProperties.get("api-port"));

			// Check HTTPS
			if (serverProperties.getOrDefault("encrypt-api", "false").equals("true")
					&& new File("keystore.jks").exists() && new File("keystore.jks.password").exists()) {
				// Start HTTPS
				props.put("keystore", "keystore.jks");
				props.put("keystore-password", Files.readString(Path.of("keystore.jks.password")));
				apiServer = ConnectiveHttpServer.createNetworked("HTTPS/1.1", props);
				setupAPI(apiServer);
				apiServer.start();
			} else {
				// Start HTTP
				apiServer = ConnectiveHttpServer.createNetworked("HTTP/1.1", props);
				setupAPI(apiServer);
				apiServer.start();
			}
		} catch (Exception e) {
			Centuria.logger.fatal("Unable to start on port " + Integer.parseInt(serverProperties.get("api-port"))
					+ "! Switching to debug mode!");
			Centuria.logger.fatal("If you are not attempting to debug the server, please run as root.");

			HashMap<String, String> props = new HashMap<String, String>();
			props.put("address", "0.0.0.0");
			props.put("port", "37501");
			if (serverProperties.getOrDefault("encrypt-api", "false").equals("true")
					&& new File("keystore.jks").exists() && new File("keystore.jks.password").exists()) {
				props.put("keystore", "keystore.jks");
				props.put("keystore-password", Files.readString(Path.of("keystore.jks.password")));
				apiServer = ConnectiveHttpServer.createNetworked("HTTPS/1.1", props);
				setupAPI(apiServer);
				apiServer.start();
			} else {
				apiServer = ConnectiveHttpServer.createNetworked("HTTP/1.1", props);
				setupAPI(apiServer);
				apiServer.start();
			}
		}

		//
		// Debug API
		if (System.getProperty("debugAPI") != null) {
			Centuria.logger.info("Starting debug api...");
			HashMap<String, String> props = new HashMap<String, String>();
			props.put("address", "0.0.0.0");
			props.put("port", System.getProperty("debugAPI"));
			ConnectiveHttpServer apiServer = ConnectiveHttpServer.createNetworked("HTTP/1.1", props);
			setupAPI(apiServer);
			apiServer.start();
		}

		//
		// Start director server
		Centuria.logger.info(
				"Starting Director server on port " + Integer.parseInt(serverProperties.get("director-port")) + "...");

		// Create properties
		HashMap<String, String> props = new HashMap<String, String>();
		props.put("address", "0.0.0.0");
		props.put("port", serverProperties.get("director-port"));

		// Check HTTPS
		if (serverProperties.getOrDefault("encrypt-director", "false").equals("true")
				&& new File("keystore.jks").exists() && new File("keystore.jks.password").exists()) {
			// Start HTTPS
			props.put("keystore", "keystore.jks");
			props.put("keystore-password", Files.readString(Path.of("keystore.jks.password")));
			directorServer = ConnectiveHttpServer.createNetworked("HTTPS/1.1", props);
			if (serverProperties.containsKey("allowed-proxies")) {
				for (String addr : serverProperties.get("allowed-proxies").replace(" ", "").split(","))
					directorServer.addAllowedProxySources(addr);
			}

			// Register handlers
			directorServer.registerHandler(new GameServerRequestHandler());

			// Dispatch event
			EventBus.getInstance().dispatchEvent(new DirectorServerStartupEvent(directorServer));

			// Start
			directorServer.start();
		} else {
			// Start HTTP
			directorServer = ConnectiveHttpServer.createNetworked("HTTP/1.1", props);
			if (serverProperties.containsKey("allowed-proxies")) {
				for (String addr : serverProperties.get("allowed-proxies").replace(" ", "").split(","))
					directorServer.addAllowedProxySources(addr);
			}

			// Register handlers
			directorServer.registerHandler(new GameServerRequestHandler());

			// Dispatch event
			EventBus.getInstance().dispatchEvent(new DirectorServerStartupEvent(directorServer));

			// Start
			directorServer.start();
		}
		//
		// Load game server
		ServerSocket sock;
		Centuria.logger
				.info("Starting Game server on port " + Integer.parseInt(serverProperties.get("game-port")) + "...");
		if (encryptGame)
			try {
				sock = getContext(new File("keystore.jks"),
						Files.readString(Path.of("keystore.jks.password")).toCharArray()).getServerSocketFactory()
						.createServerSocket(Integer.parseInt(serverProperties.get("game-port")), 0,
								InetAddress.getByName("0.0.0.0"));
			} catch (UnrecoverableKeyException | KeyManagementException | NumberFormatException | KeyStoreException
					| NoSuchAlgorithmException | CertificateException | IOException e) {
				sock = new ServerSocket(Integer.parseInt(serverProperties.get("game-port")), 0,
						InetAddress.getByName("0.0.0.0"));
			}
		else
			sock = new ServerSocket(Integer.parseInt(serverProperties.get("game-port")), 0,
					InetAddress.getByName("0.0.0.0"));
		for (ICenturiaModule module : ModuleManager.getInstance().getAllModules()) {
			gameServer = module.replaceGameServer(sock);
			if (gameServer != null)
				break;
		}
		if (gameServer == null)
			gameServer = new GameServer(sock);

		// Server settings
		gameServer.whitelistFile = serverProperties.get("vpn-user-whitelist");

		// Download VPN ips
		try {
			String vpnIpv4 = serverProperties.getOrDefault("vpn-ipv4-banlist", "");
			if (!vpnIpv4.isEmpty()) {
				InputStream strm = new URL(vpnIpv4).openStream();
				String data = new String(strm.readAllBytes(), "UTF-8").replace("\r", "");
				for (String line : data.split("\n")) {
					if (line.isBlank() || line.startsWith("#"))
						continue;
					gameServer.vpnIpsV4.add(line);
				}
				strm.close();
			}
			String vpnIpv6 = serverProperties.getOrDefault("vpn-ipv6-banlist", "");
			if (!vpnIpv6.isEmpty()) {
				InputStream strm = new URL(vpnIpv6).openStream();
				String data = new String(strm.readAllBytes(), "UTF-8").replace("\r", "");
				for (String line : data.split("\n")) {
					if (line.isBlank() || line.startsWith("#"))
						continue;
					gameServer.vpnIpsV6.add(line);
				}
				strm.close();
			}
		} catch (IOException e) {
		}

		// Start game server
		gameServer.start();

		//
		// Start chat server
		Centuria.logger
				.info("Starting Chat server on port " + Integer.parseInt(serverProperties.get("chat-port")) + "...");
		if (encryptChat)
			try {
				sock = getContext(new File("keystore.jks"),
						Files.readString(Path.of("keystore.jks.password")).toCharArray()).getServerSocketFactory()
						.createServerSocket(Integer.parseInt(serverProperties.getOrDefault("chat-port", "37504")), 0,
								InetAddress.getByName("0.0.0.0"));
			} catch (UnrecoverableKeyException | KeyManagementException | NumberFormatException | KeyStoreException
					| NoSuchAlgorithmException | CertificateException | IOException e) {
				sock = new ServerSocket(Integer.parseInt(serverProperties.getOrDefault("chat-port", "37504")), 0,
						InetAddress.getByName("0.0.0.0"));
			}
		else
			sock = new ServerSocket(Integer.parseInt(serverProperties.getOrDefault("chat-port", "37504")), 0,
					InetAddress.getByName("0.0.0.0"));
		for (ICenturiaModule module : ModuleManager.getInstance().getAllModules()) {
			chatServer = module.replaceChatServer(sock);
			if (chatServer != null)
				break;
		}
		if (chatServer == null)
			chatServer = new ChatServer(sock);
		chatServer.start();

		//
		// Start voice chat server
		Centuria.logger.info("Starting Voice Chat server on port "
				+ Integer.parseInt(serverProperties.getOrDefault("voice-chat-port", "37505")) + "...");
		if (encryptVoiceChat)
			try {
				sock = getContext(new File("keystore.jks"),
						Files.readString(Path.of("keystore.jks.password")).toCharArray()).getServerSocketFactory()
						.createServerSocket(Integer.parseInt(serverProperties.getOrDefault("voice-chat-port", "37505")),
								0, InetAddress.getByName("0.0.0.0"));
			} catch (UnrecoverableKeyException | KeyManagementException | NumberFormatException | KeyStoreException
					| NoSuchAlgorithmException | CertificateException | IOException e) {
				sock = new ServerSocket(Integer.parseInt(serverProperties.getOrDefault("voice-chat-port", "37505")), 0,
						InetAddress.getByName("0.0.0.0"));
				encryptVoiceChat = false;
			}
		else
			sock = new ServerSocket(Integer.parseInt(serverProperties.getOrDefault("voice-chat-port", "37505")), 0,
					InetAddress.getByName("0.0.0.0"));
		voiceChatServer = new VoiceChatServer(sock);
		voiceChatServer.start();

		// Post-initialize modules
		Centuria.logger.info("Post-initializing Centuria modules...");
		for (ICenturiaModule module : ModuleManager.getInstance().getAllModules()) {
			Centuria.logger.info("Post-initializing module: " + module.id());
			module.postInit();
		}

		// Log completion
		Centuria.logger.info("Successfully started emulated servers.");
	}

	private static void setupAPI(ConnectiveHttpServer apiServer) {
		// Proxies
		if (serverProperties.containsKey("allowed-proxies")) {
			for (String addr : serverProperties.get("allowed-proxies").replace(" ", "").split(","))
				apiServer.addAllowedProxySources(addr);
		}

		// Allow modules to register handlers
		EventBus.getInstance().dispatchEvent(new APIServerStartupEvent(apiServer));

		// API processors
		apiServer.registerHandler(new UserHandler());
		apiServer.registerHandler(new XPDetailsHandler());
		apiServer.registerHandler(new AuthenticateHandler());
		apiServer.registerHandler(new UpdateDisplayNameHandler());
		apiServer.registerHandler(new DisplayNamesRequestHandler());
		apiServer.registerHandler(new DisplayNameValidationHandler());
		apiServer.registerHandler(new RequestTokenHandler());
		apiServer.registerHandler(new GameRegistrationHandler());
		apiServer.registerHandler(new SeasonPassRequestHandler());

		// Custom API
		apiServer.registerHandler(new LoginRefreshHandler());
		apiServer.registerHandler(new ChangePasswordHandler());
		apiServer.registerHandler(new ChangeDisplayNameHandler());
		apiServer.registerHandler(new ChangeLoginNameHandler());
		apiServer.registerHandler(new DeleteAccountHandler());
		apiServer.registerHandler(new UserDetailsHandler());
		apiServer.registerHandler(new ListPlayersHandler());
		apiServer.registerHandler(new RegistrationHandler());
		apiServer.registerHandler(new PlayerDataDownloadHandler());
		apiServer.registerHandler(new SaveManagerHandler());

		// Fallback
		apiServer.registerHandler(new FallbackAPIProcessor());
	}

	private static SSLContext getContext(File keystore, char[] password)
			throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException, KeyManagementException,
			CertificateException, FileNotFoundException, IOException {
		KeyStore mainStore = KeyStore.getInstance("JKS");
		mainStore.load(new FileInputStream(keystore), password);

		KeyManagerFactory managerFactory = KeyManagerFactory.getInstance("SunX509");
		managerFactory.init(mainStore, password);

		SSLContext cont = SSLContext.getInstance("TLS");
		cont.init(managerFactory.getKeyManagers(), null, null);

		return cont;
	}

	// PEM parser
	private static byte[] pemDecode(String pem) {
		String base64 = pem.replace("\r", "");

		// Strip header
		while (base64.startsWith("-"))
			base64 = base64.substring(1);
		while (!base64.startsWith("-"))
			base64 = base64.substring(1);
		while (base64.startsWith("-"))
			base64 = base64.substring(1);

		// Clean data
		base64 = base64.replace("\n", "");

		// Strip footer
		while (base64.endsWith("-"))
			base64 = base64.substring(0, base64.length() - 1);
		while (!base64.endsWith("-"))
			base64 = base64.substring(0, base64.length() - 1);
		while (base64.endsWith("-"))
			base64 = base64.substring(0, base64.length() - 1);

		// Decode and return
		return Base64.getDecoder().decode(base64);
	}

	// PEM emitter
	private static String pemEncode(byte[] key, String type) {
		// Generate header
		String PEM = "-----BEGIN " + type + " KEY-----";

		// Generate payload
		String base64 = new String(Base64.getEncoder().encode(key));

		// Generate PEM
		while (true) {
			PEM += "\n";
			boolean done = false;
			for (int i = 0; i < 64; i++) {
				if (base64.isEmpty()) {
					done = true;
					break;
				}
				PEM += base64.substring(0, 1);
				base64 = base64.substring(1);
			}
			if (base64.isEmpty())
				break;
			if (done)
				break;
		}

		// Append footer
		PEM += "\n";
		PEM += "-----END " + type + " KEY-----";

		// Return PEM data
		return PEM;
	}

	// Signature generator
	public static byte[] sign(byte[] data) {
		try {
			Signature sig = Signature.getInstance("Dilithium");
			sig.initSign(privateKey);
			sig.update(data);
			return sig.sign();
		} catch (SignatureException | NoSuchAlgorithmException | InvalidKeyException e) {
			throw new RuntimeException(e);
		}
	}

	// Signature verification
	public static boolean verify(byte[] data, byte[] signature) {
		try {
			Signature sig = Signature.getInstance("Dilithium");
			sig.initVerify(publicKey);
			sig.update(data);
			return sig.verify(signature);
		} catch (SignatureException | NoSuchAlgorithmException | InvalidKeyException e) {
			return false;
		}
	}

	/**
	 * Sends a system message to a player
	 * 
	 * @param player  Player to send the message to
	 * @param message Message to send
	 */
	public static void systemMessage(Player player, String message) {
		systemMessage(player, message, false);
	}

	/**
	 * Sends a system message to a player
	 * 
	 * @param player  Player to send the message to
	 * @param message Message to send
	 * @param inDm    True for DM message, false otherwise
	 */
	public static void systemMessage(Player player, String message, boolean inDm) {
		if (player == null)
			return;
		ChatClient client = chatServer.getClient(player.account.getAccountID());
		if (client != null) {
			if (inDm && !client.isInRoom("SYSTEM")) {
				// Build response
				JsonObject res = new JsonObject();
				res.addProperty("eventId", "conversations.openPrivate");
				res.addProperty("conversationId", NIL_UUID);
				res.addProperty("success", true);
				client.sendPacket(res);
				res = new JsonObject();
				res.addProperty("conversationId", "SYSTEM");
				res.addProperty("eventId", "conversations.create");
				res.addProperty("success", true);
				client.sendPacket(res);
				client.joinRoom(NIL_UUID, ChatRoomTypes.PRIVATE_CHAT);
			}

			// Send response
			JsonObject res = new JsonObject();
			res.addProperty("conversationType", inDm ? "private" : "room");
			res.addProperty("conversationId", inDm ? "SYSTEM" : "room_" + player.levelID);
			res.addProperty("message", message);
			res.addProperty("source", NIL_UUID);
			res.addProperty("sentAt", LocalDateTime.now().toString());
			res.addProperty("eventId", "chat.postMessage");
			res.addProperty("success", true);
			client.sendPacket(res);
		}
	}

	// Updater
	private static boolean shouldUpdate() {
		return PolyUpdaterClient.checkForUpdates();
	}

	private static boolean downloadUpdate(String forcedMessage) throws IOException {
		try {
			return PolyUpdaterClient.downloadUpdates(forceInstallUpdate, forcedMessage);
		} finally {
			forceInstallUpdate = false;
		}
	}
}
