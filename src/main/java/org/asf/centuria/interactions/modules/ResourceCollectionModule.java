package org.asf.centuria.interactions.modules;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.apache.logging.log4j.MarkerManager;
import org.asf.centuria.Centuria;
import org.asf.centuria.accounts.CenturiaAccount;
import org.asf.centuria.accounts.highlevel.ItemAccessor;
import org.asf.centuria.data.XtWriter;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.interactions.NetworkedObjects;
import org.asf.centuria.interactions.dataobjects.NetworkedObject;
import org.asf.centuria.interactions.dataobjects.StateInfo;
import org.asf.centuria.interactions.modules.resourcecollection.ResourceDefinition;
import org.asf.centuria.interactions.modules.resourcecollection.ResourceType;
import org.asf.centuria.interactions.modules.resourcecollection.levelhooks.EventInfo;
import org.asf.centuria.interactions.modules.resourcecollection.rewards.HarvestReward;
import org.asf.centuria.interactions.modules.resourcecollection.rewards.LootInfo;
import org.asf.centuria.interactions.modules.resourcecollection.rewards.LootReward;
import org.asf.centuria.interactions.modules.resourcecollection.tables.HarvestTable;
import org.asf.centuria.interactions.modules.resourcecollection.tables.LootTable;
import org.asf.centuria.levelevents.LevelEvent;
import org.asf.centuria.levelevents.LevelEventBus;
import org.asf.centuria.modules.ICenturiaModule;
import org.asf.centuria.modules.ModuleManager;
import org.asf.centuria.networking.smartfox.SmartfoxClient;
import org.asf.centuria.packets.xt.gameserver.inventory.InventoryItemDownloadPacket;
import org.asf.centuria.packets.xt.gameserver.inventory.InventoryItemPacket;
import org.asf.centuria.util.RandomSelectorUtil;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ResourceCollectionModule extends InteractionModule {

	private static Random rnd = new Random();
	private static HashMap<String, ResourceDefinition> resources = new HashMap<String, ResourceDefinition>();
	private static HashMap<String, HarvestTable> harvestTables = new HashMap<String, HarvestTable>();
	private static HashMap<String, LootTable> lootTables = new HashMap<String, LootTable>();
	private static HashMap<String, ArrayList<EventInfo>> levelHooks = new HashMap<String, ArrayList<EventInfo>>();

	// Called to load level hooks into memory
	private static void importLevelHooks(JsonObject data) {
		// Load all tables
		data.keySet().forEach(table -> {
			// Create list
			levelHooks.put(table, new ArrayList<EventInfo>());

			// Add objects
			JsonArray infos = data.get(table).getAsJsonArray();
			for (JsonElement ele : infos) {
				JsonObject eventInfo = ele.getAsJsonObject();
				EventInfo event = new EventInfo();
				event.event = eventInfo.get("event").getAsString();
				eventInfo.get("tags").getAsJsonArray().forEach(t -> event.tags.add(t.getAsString()));
				levelHooks.get(table).add(event);
			}
		});
	}

	// Called to load the tables into memory
	private static void importObjectsIntoMemory(JsonObject data) {
		// Load resources
		if (data.has("Resources")) {
			JsonObject r = data.get("Resources").getAsJsonObject();
			for (String id : r.keySet()) {
				ResourceDefinition def;
				if (resources.containsKey(id)) {
					// Transform
					def = resources.get(id);
				} else {
					// Create
					def = new ResourceDefinition();
					resources.put(id, def);
				}

				// Add items
				JsonObject defObj = r.get(id).getAsJsonObject();
				if (defObj.has("objectName"))
					def.objectName = defObj.get("objectName").getAsString();
				if (defObj.has("lootType"))
					def.lootType = defObj.get("lootType").getAsString().equals("HARVEST") ? ResourceType.HARVEST
							: ResourceType.LOOT;
				if (defObj.has("lootTableId"))
					def.lootTableId = defObj.get("lootTableId").getAsString();
				if (defObj.has("respawnSeconds"))
					def.respawnSeconds = defObj.get("respawnSeconds").getAsDouble();
				if (defObj.has("interactionsBeforeDespawn"))
					def.interactionsBeforeDespawn = defObj.get("interactionsBeforeDespawn").getAsInt();
			}
		}

		// Load harvest tables
		if (data.has("HarvestTables")) {
			JsonObject t = data.get("HarvestTables").getAsJsonObject();
			for (String id : t.keySet()) {
				HarvestTable def;
				if (harvestTables.containsKey(id)) {
					// Transform
					def = harvestTables.get(id);
				} else {
					// Create
					def = new HarvestTable();
					harvestTables.put(id, def);
				}

				// Add items
				JsonObject defObj = t.get(id).getAsJsonObject();
				if (defObj.has("objectName"))
					def.objectName = defObj.get("objectName").getAsString();
				if (defObj.has("rewards")) {
					// Overwrite rewards
					def.rewards.clear();
					JsonArray rewards = defObj.get("rewards").getAsJsonArray();
					for (JsonElement ele : rewards) {
						JsonObject rewardInfo = ele.getAsJsonObject();
						HarvestReward reward = new HarvestReward();
						reward.itemId = rewardInfo.get("itemId").getAsString();
						reward.minCount = rewardInfo.get("minCount").getAsInt();
						reward.maxCount = rewardInfo.get("maxCount").getAsInt();
						reward.weight = rewardInfo.get("weight").getAsInt();
						def.rewards.add(reward);
					}
				}
			}
		}

		// Load loot tables
		if (data.has("LootTables")) {
			JsonObject t = data.get("LootTables").getAsJsonObject();
			for (String id : t.keySet()) {
				LootTable def;
				if (lootTables.containsKey(id)) {
					// Transform
					def = lootTables.get(id);
				} else {
					// Create
					def = new LootTable();
					lootTables.put(id, def);
				}

				// Add items
				JsonObject defObj = t.get(id).getAsJsonObject();
				if (defObj.has("objectName"))
					def.objectName = defObj.get("objectName").getAsString();
				if (defObj.has("rewards")) {
					// Overwrite rewards
					def.rewards.clear();
					JsonArray rewards = defObj.get("rewards").getAsJsonArray();
					for (JsonElement ele : rewards) {
						JsonObject rewardInfo = ele.getAsJsonObject();
						LootReward reward = new LootReward();
						if (rewardInfo.get("hasItem").getAsBoolean())
							reward.itemId = rewardInfo.get("itemId").getAsString();
						if (rewardInfo.get("hasTableReference").getAsBoolean())
							reward.referencedTableId = rewardInfo.get("referencedTableId").getAsString();
						reward.minCount = rewardInfo.get("minCount").getAsInt();
						reward.maxCount = rewardInfo.get("maxCount").getAsInt();
						reward.weight = rewardInfo.get("weight").getAsInt();
						def.rewards.add(reward);
					}
				}
			}
		}
	}

	static {
		initCollection();
	}

	private static void initCollection() {
		try {
			// Load the vanilla resource data
			InputStream strm = InventoryItemDownloadPacket.class.getClassLoader()
					.getResourceAsStream("resourcecollection.json");
			JsonObject helper = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8")).getAsJsonObject();
			importObjectsIntoMemory(helper);
			strm.close();

			// Load transformers
			loadTransformers(ResourceCollectionModule.class);

			// Load module transformers
			for (ICenturiaModule module : ModuleManager.getInstance().getAllModules()) {
				loadTransformers(module.getClass());
			}

			// Load level hooks
			strm = InventoryItemDownloadPacket.class.getClassLoader().getResourceAsStream("leveling/loothooks.json");
			helper = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8")).getAsJsonObject();
			importLevelHooks(helper);
			strm.close();

			// Load module transformers
			for (ICenturiaModule module : ModuleManager.getInstance().getAllModules()) {
				loadHooks(module.getClass());
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static void loadHooks(Class<?> cls) {
		URL source = cls.getProtectionDomain().getCodeSource().getLocation();

		// Generate a base URL
		String baseURL = "";
		String fileName = "";
		try {
			File sourceFile = new File(source.toURI());
			fileName = sourceFile.getName();
			if (sourceFile.isDirectory()) {
				baseURL = source + (source.toString().endsWith("/") ? "" : "/");
			} else {
				baseURL = "jar:" + source + "!/";
			}
		} catch (Exception e) {
			return;
		}

		try {
			// Find the transformer document
			InputStream strm = new URL(baseURL + "leveling/index.json").openStream();
			JsonArray index = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8")).getAsJsonArray();
			strm.close();

			// Load all transformers
			for (JsonElement ele : index) {
				try {
					// Find the transformer document
					strm = new URL(baseURL + "leveling/" + ele.getAsString()).openStream();
					JsonObject transformer = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8"))
							.getAsJsonObject();
					strm.close();

					// Load transformer
					importLevelHooks(transformer);
				} catch (Exception e) {
					Centuria.logger.error("Transformer failed to load: " + ele.getAsString() + " (" + fileName + "): "
							+ e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""));
					e.printStackTrace();
				}
			}
		} catch (Exception e) {
		}
	}

	private static void loadTransformers(Class<?> cls) {
		URL source = cls.getProtectionDomain().getCodeSource().getLocation();

		// Generate a base URL
		String baseURL = "";
		String fileName = "";
		try {
			File sourceFile = new File(source.toURI());
			fileName = sourceFile.getName();
			if (sourceFile.isDirectory()) {
				baseURL = source + (source.toString().endsWith("/") ? "" : "/");
			} else {
				baseURL = "jar:" + source + "!/";
			}
		} catch (Exception e) {
			return;
		}

		try {
			// Find the transformer document
			InputStream strm = new URL(baseURL + "resourcetransformers/index.json").openStream();
			JsonArray index = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8")).getAsJsonArray();
			strm.close();

			// Load all transformers
			for (JsonElement ele : index) {
				try {
					// Find the transformer document
					strm = new URL(baseURL + "resourcetransformers/" + ele.getAsString()).openStream();
					JsonObject transformer = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8"))
							.getAsJsonObject();
					strm.close();

					// Load transformer
					importObjectsIntoMemory(transformer);
				} catch (Exception e) {
					Centuria.logger.error("Transformer failed to load: " + ele.getAsString() + " (" + fileName + "): "
							+ e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""));
					e.printStackTrace();
				}
			}
		} catch (Exception e) {
		}
	}

	@Override
	public void prepareWorld(String levelID, List<String> ids, Player player) {
		// Find resource collection ids
		for (String id : ids) {
			NetworkedObject obj = NetworkedObjects.getObject(id);

			// Check if the object is a resource
			if (obj.primaryObjectInfo != null && resources.containsKey(obj.primaryObjectInfo.defId)) {
				ResourceDefinition def = resources.get(obj.primaryObjectInfo.defId);

				// Add the resource to the player's collection memory data
				if (def.lootType == ResourceType.HARVEST)
					player.account.getSaveSpecificInventory().getInteractionMemory().prepareHarvestItem(levelID, id); // harvest
				else
					player.account.getSaveSpecificInventory().getInteractionMemory().prepareTreasureItem(levelID, id); // treasure
			}
		}
	}

	@Override
	public boolean canHandle(Player player, String id, NetworkedObject obj) {
		// Check if the object is a resource
		if (obj.primaryObjectInfo != null && resources.containsKey(obj.primaryObjectInfo.defId)) {
			ResourceDefinition def = resources.get(obj.primaryObjectInfo.defId);

			// Check harvest validity
			if (def.lootType == ResourceType.HARVEST) {
				// Harvest

				// Retrieve harvest info
				long lastHarvest = player.account.getSaveSpecificInventory().getInteractionMemory()
						.getLastHarvestTime(player.levelID, id);
				int harvested = player.account.getSaveSpecificInventory().getInteractionMemory()
						.getLastHarvestCount(player.levelID, id);

				// Check reset
				if (def.respawnSeconds > -1
						&& lastHarvest + (def.respawnSeconds * 1000l) < System.currentTimeMillis()) {
					player.account.getSaveSpecificInventory().getInteractionMemory().resetHarvestCount(player.levelID,
							id); // reset
					// harvest
					// counter
					return true; // Valid as it had reseted
				}

				// Check harvest count
				if (harvested < def.interactionsBeforeDespawn) {
					return true;
				}
			} else {
				// Treasure

				// Retrieve info
				long lasUnlock = player.account.getSaveSpecificInventory().getInteractionMemory()
						.getLastTreasureUnlockTime(player.levelID, id);

				// Check reset
				if (def.respawnSeconds > -1 && lasUnlock + (def.respawnSeconds * 1000l) < System.currentTimeMillis()) {
					return true; // Valid as it had reseted
				}

				// Check unlock
				return !player.account.getSaveSpecificInventory().getInteractionMemory()
						.hasTreasureBeenUnlocked(player.levelID, id);
			}
		}

		// Check if its treasure
		for (ArrayList<StateInfo> states : obj.stateInfo.values()) {
			for (StateInfo state : states) {
				if (!state.branches.isEmpty()) {
					for (ArrayList<StateInfo> branches : state.branches.values()) {
						for (StateInfo branch : branches)
							if (branch.command.equals("41") && branch.params.length > 0
									&& obj.primaryObjectInfo.type == 13 && branch.params[1].equals("0")) {
								ResourceDefinition def = resources.get(obj.primaryObjectInfo.defId);
								double respawnSeconds = 600; // 10 minutes fallback
								if (def != null) {
									respawnSeconds = def.respawnSeconds;
								} else {
									// Check table presence
									if (!lootTables.containsKey(branch.params[0])) {
										continue;
									}
								}

								// Retrieve info
								long lasUnlock = player.account.getSaveSpecificInventory().getInteractionMemory()
										.getLastTreasureUnlockTime(player.levelID, id);

								// Check reset and last unlock
								if ((respawnSeconds != -1
										|| lasUnlock + (respawnSeconds * 1000l) < System.currentTimeMillis())
										&& !player.account.getSaveSpecificInventory().getInteractionMemory()
												.hasTreasureBeenUnlocked(player.levelID, id)) {
									return true;
								}
							}
					}
				}
			}
		}

		return false;
	}

	@Override
	public boolean shouldDestroyResource(Player player, String id, NetworkedObject obj, int stateN,
			boolean destroyOnCompletion) {
		// Check if the object is a resource
		if (obj.primaryObjectInfo != null && resources.containsKey(obj.primaryObjectInfo.defId)) {
			ResourceDefinition def = resources.get(obj.primaryObjectInfo.defId);

			// Check harvest
			if (def.lootType == ResourceType.HARVEST) {
				// Harvest

				// Retrieve harvest info
				int harvested = player.account.getSaveSpecificInventory().getInteractionMemory()
						.getLastHarvestCount(player.levelID, id);

				// Check harvest count
				if (harvested + 1 < def.interactionsBeforeDespawn) {
					destroyOnCompletion = false; // Can be re-harvested
				}
			}
		}

		return destroyOnCompletion;
	}

	@Override
	public boolean handleInteractionSuccess(Player player, String id, NetworkedObject obj, int state) {
		// Check if the object is a resource
		if (obj.primaryObjectInfo != null && resources.containsKey(obj.primaryObjectInfo.defId)) {
			ResourceDefinition def = resources.get(obj.primaryObjectInfo.defId);

			// Check harvest
			if (def.lootType == ResourceType.HARVEST) {
				// Harvest

				// Retrieve harvest info
				int harvested = player.account.getSaveSpecificInventory().getInteractionMemory()
						.getLastHarvestCount(player.levelID, id);

				// Check harvest count
				if (harvested < def.interactionsBeforeDespawn) {
					HarvestTable table = harvestTables.get(def.lootTableId);
					if (table != null) {
						// Find reward
						HashMap<HarvestReward, Integer> options = new HashMap<HarvestReward, Integer>();
						table.rewards.forEach(t -> {
							options.put(t, t.weight);
						});

						// XP info
						EventInfo ev = new EventInfo();
						ev.event = "levelevents.harvest";

						HarvestReward reward = RandomSelectorUtil.selectWeighted(options);
						if (reward != null) {
							// Give reward
							int count = rnd.nextInt(reward.maxCount + 1);
							while (count < reward.minCount)
								count = rnd.nextInt(reward.maxCount + 1);
							player.account.getSaveSpecificInventory().getItemAccessor(player).add(reward.itemId, count);

							// Add tags
							ev.tags.add("itemdefid:" + reward.itemId);
							ev.tags.add("itemcount:" + count);

							// Add item rarity
							int rarity = ItemAccessor.getItemRarity(reward.itemId);
							ev.tags.add("rarity:" + (rarity == 0 ? "common"
									: (rarity == 1 ? "cool" : (rarity == 2 ? "rare" : "epic"))));

							// Log
							Centuria.logger.info(MarkerManager.getMarker("RESOURCE COLLECTION"),
									"Player " + player.account.getDisplayName() + " harvested " + obj.objectName + " ("
											+ id + ")" + " and received " + count + " of item " + reward.itemId);
						}

						// Set harvest count and timestamp
						player.account.getSaveSpecificInventory().getInteractionMemory().harvested(player.levelID, id);
						player.account.getSaveSpecificInventory().getInteractionMemory().saveTo(player.client);
						player.respawnItems.put(id, (long) (System.currentTimeMillis() + (def.respawnSeconds * 1000l)));
						ev.tags.add("map:" + manName(player));

						// Dispatch XP event
						LevelEventBus.dispatch(new LevelEvent(ev.event, ev.tags.toArray(t -> new String[t]), player));
						return true;
					}
				}
			} else
				return true;
		}
		return false;
	}

	@Override
	public int isDataRequestValid(Player player, String id, NetworkedObject obj, int stateN) {
		// Check if the object is a resource
		if (obj.primaryObjectInfo != null && resources.containsKey(obj.primaryObjectInfo.defId)) {
			ResourceDefinition def = resources.get(obj.primaryObjectInfo.defId);

			// Check harvest
			if (def.lootType == ResourceType.LOOT) {
				// Treasure

				// Check time
				if (player.account.getSaveSpecificInventory().getInteractionMemory()
						.hasTreasureBeenUnlocked(player.levelID, id)
						&& System
								.currentTimeMillis() < (player.account.getSaveSpecificInventory().getInteractionMemory()
										.getLastTreasureUnlockTime(player.levelID, id) + (def.respawnSeconds * 1000l)))
					return 0; // Cannot loot yet

				// Give reward
				giveLootReward(player, def.lootTableId, 2, obj.primaryObjectInfo.defId);

				// Set unlocked and timestamp
				player.account.getSaveSpecificInventory().getInteractionMemory().unlocked(player.levelID, id);
				player.account.getSaveSpecificInventory().getInteractionMemory().saveTo(player.client);
				player.respawnItems.put(id, (long) (System.currentTimeMillis() + (def.respawnSeconds * 1000l)));
				return 1;
			}
		}

		// Check if its treasure
		for (ArrayList<StateInfo> states : obj.stateInfo.values()) {
			for (StateInfo state : states) {
				if (!state.branches.isEmpty()) {
					for (ArrayList<StateInfo> branches : state.branches.values()) {
						for (StateInfo branch : branches) {
							if (branch.command.equals("41") && branch.params.length > 0) {
								// It is, lets find the table

								// Find respawn timestamp
								ResourceDefinition def = resources.get(obj.primaryObjectInfo.defId);
								double respawnSeconds = 600; // 10 minutes fallback // TODO: FIND A BETTER WAY TO DO
																// THIS
								if (def != null) {
									respawnSeconds = def.respawnSeconds;
								} else {
									// Check table presence
									if (!lootTables.containsKey(branch.params[0])) {
										continue;
									}
								}

								// Check time
								if (player.account.getSaveSpecificInventory().getInteractionMemory()
										.hasTreasureBeenUnlocked(player.levelID, id)
										&& System.currentTimeMillis() < (player.account.getSaveSpecificInventory()
												.getInteractionMemory().getLastTreasureUnlockTime(player.levelID, id)
												+ (respawnSeconds * 1000l)))
									return 0; // Cannot loot yet

								// Set unlocked and timestamp
								player.account.getSaveSpecificInventory().getInteractionMemory()
										.unlocked(player.levelID, id);
								player.account.getSaveSpecificInventory().getInteractionMemory().saveTo(player.client);

								// Make sure the resource will be respawned
								player.respawnItems.put(id,
										(long) (System.currentTimeMillis() + (respawnSeconds * 1000l)));
								return 1;
							}
						}
					}
				}
			}
		}

		return -1;
	}

	/**
	 * Selects loot from a loot table
	 * 
	 * @param lootTableId Table to select loot from
	 * @param acc         The account to focus on for item filtering
	 * @return LootInfo object or null
	 */
	public static LootInfo getLootReward(String lootTableId, CenturiaAccount acc) {
		LootTable table = lootTables.get(lootTableId);
		if (table != null) {
			// Find reward
			HashMap<LootReward, Integer> options = new HashMap<LootReward, Integer>();
			table.rewards.forEach(t -> {
				// Check if can be given
				if (ItemAccessor.getInventoryTypeOf(t.itemId) != null && !ItemAccessor.canHaveMultipleOf(t.itemId)
						&& acc.getSaveSpecificInventory().getAccessor()
								.hasInventoryObjectByDefId(ItemAccessor.getInventoryTypeOf(t.itemId), t.itemId))
					return; // Ship

				// Add option
				options.put(t, t.weight);
			});
			LootReward reward = RandomSelectorUtil.selectWeighted(options);
			if (reward != null) {
				// Retrieve reward
				LootInfo info = new LootInfo();
				if (reward.itemId != null) {
					int count = rnd.nextInt(reward.maxCount + 1);
					while (count < reward.minCount)
						count = rnd.nextInt(reward.maxCount + 1);
					info.count = count;
				}
				info.reward = reward;
				return info;
			}
		}
		return null;
	}

	/**
	 * Gives loot to a player
	 * 
	 * @param player      Player to give loot to
	 * @param lootTableId Loot table
	 * @param giftType    Gift type number
	 * @param sourceDefID Source object defID
	 */
	public static void giveLootReward(Player player, String lootTableId, int giftType, String sourceDefID) {
		// Log
		Centuria.logger.info(MarkerManager.getMarker("RESOURCE COLLECTION"),
				"Giving loot to player " + player.account.getDisplayName() + ", giving table " + lootTableId);

		String originalTableId = lootTableId;
		boolean rewarded = false;
		while (true) {
			LootInfo info = getLootReward(lootTableId, player.account);
			if (info != null) {
				LootReward reward = info.reward;
				if (reward.itemId != null) {
					int count = info.count;

					// Select items
					String[] ids = player.account.getSaveSpecificInventory().getItemAccessor(player).add(reward.itemId,
							count);

					// Send gift push popups
					for (String objID : ids) {
						// Send gift object
						JsonObject gift = new JsonObject();
						gift.addProperty("fromType", giftType);
						gift.addProperty("redeemedItemIdsExpectedCount", 0);
						gift.addProperty("giftItemDefId", reward.itemId);
						gift.addProperty("count", count);
						gift.addProperty("giftItemType",
								player.account.getSaveSpecificInventory().getAccessor().getInventoryIDOfItem(objID));
						gift.addProperty("fromId", sourceDefID);
						gift.addProperty("uuid", objID);

						// Send object
						JsonObject components = new JsonObject();
						components.add("Gift", gift);

						// Build object
						String giftID = UUID.randomUUID().toString();
						JsonObject obj = new JsonObject();
						obj.add("components", components);
						obj.addProperty("id", giftID);
						obj.addProperty("type", 302);
						JsonArray update = new JsonArray();
						update.add(obj);
						InventoryItemPacket pkt = new InventoryItemPacket();
						pkt.item = update;
						player.client.sendPacket(pkt);

						// Send gift-push packet
						player.client.sendPacket("%xt%gp%-1%1%" + giftID + "%" + count + "%");
						break;
					}

					// Level hooks
					final String lootTableF = lootTableId;
					if (levelHooks.containsKey(lootTableId)) {
						levelHooks.get(lootTableId).forEach(event -> {
							// Build event object
							EventInfo ev = new EventInfo();
							ev.event = event.event;
							ev.tags.addAll(event.tags);

							// Add tags
							int rarity = ItemAccessor.getItemRarity(reward.itemId);
							ev.tags.add("rarity:" + (rarity == 0 ? "common"
									: (rarity == 1 ? "cool" : (rarity == 2 ? "rare" : "epic"))));
							ev.tags.add("loottable:" + lootTableF);
							ev.tags.add("itemdefid:" + reward.itemId);
							ev.tags.add("itemcount:" + count);
							ev.tags.add("map:" + manName(player));

							// Dispatch event
							LevelEventBus
									.dispatch(new LevelEvent(ev.event, ev.tags.toArray(t -> new String[t]), player));
						});
						if (!rewarded && originalTableId.equals(lootTableF))
							rewarded = true;
					}

					// Hooks for the original table id
					if (levelHooks.containsKey(originalTableId) && !rewarded) {
						rewarded = true;
						levelHooks.get(originalTableId).forEach(event -> {
							// Build event object
							EventInfo ev = new EventInfo();
							ev.event = event.event;
							ev.tags.addAll(event.tags);

							// Add tags
							int rarity = ItemAccessor.getItemRarity(reward.itemId);
							ev.tags.add("rarity:" + (rarity == 0 ? "common"
									: (rarity == 1 ? "cool" : (rarity == 2 ? "rare" : "epic"))));
							ev.tags.add("loottable:" + lootTableF);
							ev.tags.add("itemdefid:" + reward.itemId);
							ev.tags.add("itemcount:" + count);
							ev.tags.add("map:" + manName(player));

							// Dispatch event
							LevelEventBus
									.dispatch(new LevelEvent(ev.event, ev.tags.toArray(t -> new String[t]), player));
						});
					}

					// Chest hook
					LootTable table = lootTables.get(lootTableId);
					if (table != null) {
						// I know, not the best, but idfk how to identify what chest type a chest is
						// except by checking the object name
						if (table.objectName.startsWith("TreasureChest/")) {
							EventInfo ev = new EventInfo();
							ev.event = "levelevents.chests";

							// Add tags
							int rarity = ItemAccessor.getItemRarity(reward.itemId);
							ev.tags.add("rarity:" + (rarity == 0 ? "common"
									: (rarity == 1 ? "cool" : (rarity == 2 ? "rare" : "epic"))));
							ev.tags.add("loottable:" + lootTableId);
							ev.tags.add("itemdefid:" + reward.itemId);
							ev.tags.add("itemcount:" + count);
							ev.tags.add("map:" + manName(player));

							// Its a treasure chest, lets see which type
							if (table.objectName.contains("/Bronze")) {
								// Bronze
								ev.tags.add("type:bronze");

								// Dispatch event
								LevelEventBus.dispatch(
										new LevelEvent(ev.event, ev.tags.toArray(t -> new String[t]), player));
							} else if (table.objectName.contains("/Silver")) {
								// Silver
								ev.tags.add("type:silver");

								// Dispatch event
								LevelEventBus.dispatch(
										new LevelEvent(ev.event, ev.tags.toArray(t -> new String[t]), player));
							} else if (table.objectName.contains("/Gold")) {
								// Gold
								ev.tags.add("type:gold");

								// Dispatch event
								LevelEventBus.dispatch(
										new LevelEvent(ev.event, ev.tags.toArray(t -> new String[t]), player));
							}
						}
					}
				}
				if (reward.referencedTableId != null) {
					// Continue
					lootTableId = reward.referencedTableId;
				} else
					break;
			}
		}
	}

	// Lets not copy/paste like a madman
	private static String manName(Player player) {
		// Find map name
		String map = player.levelID;
		switch (player.levelID) {
		case "820":
			map = "cityfera";
			break;
		case "2364":
			map = "bloodtundra";
			break;
		case "9687":
			map = "lakeroot";
			break;
		case "2147":
			map = "mugmyre";
			break;
		case "1689":
			map = "sanctuary";
			break;
		case "3273":
			map = "sunkenthicket";
			break;
		case "1825":
			map = "shatteredbay";
			break;
		}
		return map;
	}

	@Override
	public boolean initializeWorldObjects(SmartfoxClient client, String id, NetworkedObject obj) {
		Player player = (Player) client.container;

		// Prepare state field
		int pState = -1;

		// Check if the object is a resource
		if (obj.primaryObjectInfo != null && resources.containsKey(obj.primaryObjectInfo.defId)) {
			ResourceDefinition def = resources.get(obj.primaryObjectInfo.defId);

			// Check harvest
			if (def.lootType == ResourceType.HARVEST) {
				// Harvest

				// Retrieve harvest info
				long lastHarvest = player.account.getSaveSpecificInventory().getInteractionMemory()
						.getLastHarvestTime(player.pendingLevelID, id);
				int harvested = player.account.getSaveSpecificInventory().getInteractionMemory()
						.getLastHarvestCount(player.pendingLevelID, id);

				pState = 0;

				// Check reset
				if (def.respawnSeconds > -1
						&& lastHarvest + (def.respawnSeconds * 1000l) < System.currentTimeMillis()) {
					// Reset harvest count
					player.account.getSaveSpecificInventory().getInteractionMemory()
							.resetHarvestCount(player.pendingLevelID, id);
				} else {
					// Check harvest count
					if (harvested >= def.interactionsBeforeDespawn) {
						pState = 2;
						player.respawnItems.put(id, (long) (lastHarvest + (def.respawnSeconds * 1000l)));
					}
				}
			}
		}

		// Check if the object is a resource
		if (obj.primaryObjectInfo != null && resources.containsKey(obj.primaryObjectInfo.defId)) {
			ResourceDefinition def = resources.get(obj.primaryObjectInfo.defId);

			// Check harvest
			if (def.lootType == ResourceType.LOOT) {
				// Treasure

				pState = 0;

				// Retrieve info
				long lasUnlock = player.account.getSaveSpecificInventory().getInteractionMemory()
						.getLastTreasureUnlockTime(player.pendingLevelID, id);

				// Check reset and last unlock
				if (def.respawnSeconds == -1 || lasUnlock + (def.respawnSeconds * 1000l) > System.currentTimeMillis()
						|| player.account.getSaveSpecificInventory().getInteractionMemory()
								.hasTreasureBeenUnlocked(player.pendingLevelID, id)) {
					pState = 2;
					player.respawnItems.put(id, (long) (lasUnlock + (def.respawnSeconds * 1000l)));
				}
			}
		}

		// Check if its treasure
		for (ArrayList<StateInfo> states : obj.stateInfo.values()) {
			for (StateInfo state : states) {
				if (!state.branches.isEmpty()) {
					for (ArrayList<StateInfo> branches : state.branches.values()) {
						for (StateInfo branch : branches) {
							if (branch.command.equals("41") && branch.params.length > 0
									&& obj.primaryObjectInfo.type == 13 && branch.params[1].equals("0")) {
								// Treasure

								pState = 0;
								ResourceDefinition def = resources.get(obj.primaryObjectInfo.defId);
								double respawnSeconds = 600; // 10 minutes fallback
								if (def != null) {
									respawnSeconds = def.respawnSeconds;
								} else {
									// Check table presence
									if (!lootTables.containsKey(branch.params[0])) {
										continue;
									}
								}

								// Retrieve info
								long lasUnlock = player.account.getSaveSpecificInventory().getInteractionMemory()
										.getLastTreasureUnlockTime(player.pendingLevelID, id);

								// Check reset and last unlock
								if ((respawnSeconds == -1
										|| lasUnlock + (respawnSeconds * 1000l) > System.currentTimeMillis())
										&& player.account.getSaveSpecificInventory().getInteractionMemory()
												.hasTreasureBeenUnlocked(player.pendingLevelID, id)) {
									pState = 2;
									player.respawnItems.put(id, (long) (lasUnlock + (respawnSeconds * 1000l)));
								}
							}
						}
					}
				}
			}
		}

		if (pState != -1) {
			// Spawn object
			XtWriter wr = new XtWriter();
			wr.writeString("oi");
			wr.writeInt(-1); // data prefix

			// Object creation parameters
			wr.writeString(id); // World object ID
			wr.writeInt(978);
			wr.writeString(""); // Owner ID

			// Object info
			wr.writeInt(0);
			wr.writeLong(System.currentTimeMillis() / 1000);
			wr.writeDouble(obj.locationInfo.position.x);
			wr.writeDouble(obj.locationInfo.position.y);
			wr.writeDouble(obj.locationInfo.position.z);
			wr.writeDouble(obj.locationInfo.rotation.x);
			wr.writeDouble(obj.locationInfo.rotation.y);
			wr.writeDouble(obj.locationInfo.rotation.z);
			wr.writeDouble(obj.locationInfo.rotation.w);
			wr.add("0%0%0%0.0%0%0%" + pState);
			wr.writeString(""); // data suffix
			client.sendPacket(wr.encode());
		}

		return pState != -1;
	}

	/**
	 * Retrieves an array of loot table ids
	 * 
	 * @return Array of loot table defids
	 */
	public static String[] getLootTableIds() {
		return lootTables.keySet().toArray(t -> new String[t]);
	}

}
