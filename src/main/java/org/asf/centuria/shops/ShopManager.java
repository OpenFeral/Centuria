package org.asf.centuria.shops;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;

import org.apache.logging.log4j.MarkerManager;
import org.asf.centuria.Centuria;
import org.asf.centuria.accounts.CenturiaAccount;
import org.asf.centuria.accounts.highlevel.ItemAccessor;
import org.asf.centuria.modules.ICenturiaModule;
import org.asf.centuria.modules.ModuleManager;
import org.asf.centuria.seasonpasses.SeasonPassManager;
import org.asf.centuria.shops.info.ShopInfo;
import org.asf.centuria.shops.info.ShopItem;
import org.asf.centuria.shops.info.UncraftingInfo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ShopManager {

	private static LinkedHashMap<String, ShopInfo> shops = new LinkedHashMap<String, ShopInfo>();
	private static LinkedHashMap<String, ArrayList<String>> itemShops = new LinkedHashMap<String, ArrayList<String>>();
	private static LinkedHashMap<String, UncraftingInfo> uncrafting = new LinkedHashMap<String, UncraftingInfo>();

	private static void loadShopData(JsonObject helper) {
		JsonObject shopsJson = helper.get("Shops").getAsJsonObject();
		shopsJson.keySet().forEach((shopId) -> {
			JsonObject shopObject = shopsJson.get(shopId).getAsJsonObject();

			// Load into object
			ShopInfo shop;
			if (!shops.containsKey(shopId)) {
				// Create
				shop = new ShopInfo();

				// Save in memory
				shops.put(shopId, shop);
			} else {
				// Transform
				shop = shops.get(shopId);

				// Clear old content list if needed
				if (shopObject.has("contents"))
					shop.contents.clear();
			}

			// Load enigmas
			if (shopObject.has("enigmas")) {
				JsonArray enigmas = shopObject.get("enigmas").getAsJsonArray();
				enigmas.forEach((id) -> {
					if (!shop.enigmas.contains(id.getAsString()))
						shop.enigmas.add(id.getAsString());
				});
			}

			// Load object name if present
			if (shopObject.has("object"))
				shop.object = shopObject.get("object").getAsString();

			// Load stock info if present
			if (shopObject.has("restockTime"))
				shop.restockTime = shopObject.get("restockTime").getAsInt();

			// Load content if present
			if (shopObject.has("contents")) {
				JsonObject contents = shopObject.get("contents").getAsJsonObject();
				contents.keySet().forEach((sId) -> {
					JsonObject item = contents.get(sId).getAsJsonObject();

					// Build object
					ShopItem itm = new ShopItem();
					itm.objectName = item.get("object").getAsString();

					// Load level lock info (if present)
					itm.requiredLevel = item.get("requiredLevel").getAsInt();

					// Load stock info
					itm.stock = item.get("stock").getAsInt();

					// Load costs
					JsonObject costs = item.get("cost").getAsJsonObject();
					costs.keySet().forEach((id) -> itm.cost.put(id, costs.get(id).getAsInt()));

					// Load eureka items (if present)
					if (item.has("eurekaItems")) {
						JsonObject items = item.get("eurekaItems").getAsJsonObject();
						items.keySet().forEach((id) -> itm.eurekaItems.put(id, items.get(id).getAsInt()));
					}

					// Load items
					JsonObject items = item.get("items").getAsJsonObject();
					items.keySet().forEach((id) -> itm.items.put(id, items.get(id).getAsInt()));

					// Add object
					shop.contents.put(sId, itm);
				});
			}
		});

		// Uncrafting
		JsonObject uncraftingJson = helper.get("Uncrafting").getAsJsonObject();
		uncraftingJson.keySet().forEach((item) -> {
			JsonObject info = uncraftingJson.get(item).getAsJsonObject();

			// Load into object
			UncraftingInfo ucInfo;
			if (!uncrafting.containsKey(item)) {
				// Create
				ucInfo = new UncraftingInfo();

				// Save in memory
				uncrafting.put(item, ucInfo);
			} else {
				// Transform
				ucInfo = uncrafting.get(item);

				// Clear old content list if needed
				if (info.has("result"))
					ucInfo.result.clear();
			}

			// Load object name if present
			if (info.has("object"))
				ucInfo.object = info.get("object").getAsString();

			// Load results
			if (info.has("result")) {
				JsonObject result = info.get("result").getAsJsonObject();
				result.keySet().forEach((id) -> ucInfo.result.put(id, result.get(id).getAsInt()));
			}
		});
	}

	static {
		try {
			// Load the helper
			InputStream strm = ShopManager.class.getClassLoader().getResourceAsStream("shops.json");
			JsonObject helper = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8")).getAsJsonObject();
			strm.close();

			// Load the shops into memory
			loadShopData(helper);

			// Load transformers
			loadTransformers(ShopManager.class);

			// Load module transformers
			for (ICenturiaModule module : ModuleManager.getInstance().getAllModules()) {
				loadTransformers(module.getClass());
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
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
			InputStream strm = new URL(baseURL + "shoptransformers/index.json").openStream();
			JsonArray index = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8")).getAsJsonArray();
			strm.close();

			// Load all transformers
			for (JsonElement ele : index) {
				try {
					// Find the transformer document
					strm = new URL(baseURL + "shoptransformers/" + ele.getAsString()).openStream();
					JsonObject transformer = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8"))
							.getAsJsonObject();
					strm.close();

					// Load transformer
					loadShopData(transformer);
				} catch (Exception e) {
					Centuria.logger.error(MarkerManager.getMarker("SHOPS"),
							"Transformer failed to load: " + ele.getAsString() + " (" + fileName + ")", e);
				}
			}

			// Scan into memory
			shops.forEach((id, shop) -> {
				// Scan contents
				shop.contents.forEach((itmId, itm) -> {
					if (!itemShops.containsKey(itmId))
						itemShops.put(itmId, new ArrayList<String>());
					if (!itemShops.get(itmId).contains(id))
						itemShops.get(itmId).add(id);
				});
			});
		} catch (Exception e) {
			if (e instanceof FileNotFoundException)
				return;
			throw new RuntimeException(e);
		}
	}

	/**
	 * Retrieves the amount of times a player purchased a specific item
	 * 
	 * @param player Player to check the purchase count of
	 * @param shopId Shop ID
	 * @param itemId Item defID
	 * @return Amount of purchases a player made of a specific item, this resets if
	 *         the shop in question restocks
	 */
	public static int getPurchaseCount(CenturiaAccount player, String shopId, String itemId) {
		// Check inventory object
		if (!player.getSaveSpecificInventory().containsItem("purchaselog") || !shops.containsKey(shopId)) {
			// Add item so it caches and stops freezing up
			player.getSaveSpecificInventory().setItem("purchaselog", new JsonObject());
			return 0;
		}

		// Find item
		JsonObject log = player.getSaveSpecificInventory().getItem("purchaselog").getAsJsonObject();
		if (log.has(shopId)) {
			JsonObject shopLog = log.get(shopId).getAsJsonObject();

			// Check restock
			int restock = shops.get(shopId).restockTime;
			if (restock != -1) {
				// Check restock timestamp
				long activationTime = shopLog.get("activationTime").getAsLong();
				if ((activationTime + (long) (restock * 1000)) < System.currentTimeMillis()) {
					// Restock

					// Delete the purchases from memory
					log.remove(shopId);

					// Save data
					player.getSaveSpecificInventory().setItem("purchaselog", log);

					// Return 0 as the shop has been restocked
					return 0;
				}
			}

			// Find item
			shopLog = shopLog.get("items").getAsJsonObject();
			if (shopLog.has(itemId)) {
				return shopLog.get(itemId).getAsInt();
			}
		}

		// Not found
		return 0;
	}

	/**
	 * Retrieves the contents of a specific shop
	 * 
	 * @param player Player to create the list for
	 * @param shopId Shop content defID
	 * @return Array of items in the shop
	 */
	public static String[] getShopContents(CenturiaAccount player, String shopId) {
		if (!shops.containsKey(shopId))
			return new String[0];

		// Store in memory for easy access
		ShopInfo shop = shops.get(shopId);

		// Create list of items
		ArrayList<String> items = new ArrayList<String>();

		// Add season pass
		if (SeasonPassManager.getCurrentPass() != null)
			items.add(SeasonPassManager.getCurrentPass().premiumPassID); // FIXME: cost

		// Add module stock
		// FIXME

		// Add general contents
		shop.contents.forEach((id, item) -> {
			// Skip enigma IDs
			if (shop.enigmas.contains(id))
				return;

			if (item.stock != -1) {
				// Check mode
				if (item.stock == 1 && shop.restockTime == -1) {
					// Only add if not in the player inventory

					boolean hasItems = true;
					for (String itm : item.items.keySet()) {
						String inv = ItemAccessor.getInventoryTypeOf(itm);
						if (!player.getSaveSpecificInventory().getAccessor().hasInventoryObjectByDefId(inv, itm)) {
							hasItems = false;
						}
					}

					if (hasItems) {
						// The items are already in the player inventory, so no need to add it to the
						// shop
						return;
					}
				}

				// Check level lock
				if (item.requiredLevel != -1 && (!player.getLevel().isLevelAvailable()
						|| player.getLevel().getLevel() < item.requiredLevel)) {
					return;
				}

				// Check stock
				int purchaseCount = getPurchaseCount(player, shopId, id);
				if (purchaseCount > item.stock) {
					// Out of stock
					return;
				}
			}

			// Add item
			if (!items.contains(id))
				items.add(id);
		});

		// Enigma items
		for (String enigma : shop.enigmas) {
			// Add enigma if present in the inventory
			if (player.getSaveSpecificInventory().getAccessor().hasInventoryObjectByDefId("7", enigma)) {
				JsonObject obj = player.getSaveSpecificInventory().getAccessor().findInventoryObjectByDefId("7",
						enigma);

				// Check if its been unraveled
				JsonObject data = obj.get("components").getAsJsonObject().get("Enigma").getAsJsonObject();
				if (data.get("activated").getAsBoolean())
					// Add it
					items.add(player.getSaveSpecificInventory().getInspirationAccessor().getEnigmaResult(enigma));
			}
		}

		// Filter items
		for (String id : items.toArray(t -> new String[t])) {
			// Check if valid
			if (ItemAccessor.getInventoryTypeOf(id) != null && !ItemAccessor.canHaveMultipleOf(id)
					&& player.getSaveSpecificInventory().getAccessor()
							.hasInventoryObjectByDefId(ItemAccessor.getInventoryTypeOf(id), id)) {
				items.remove(id);
			}
		}

		// Return
		return items.toArray(t -> new String[t]);
	}

	/**
	 * Retrieves the inactivate enigma items a player has
	 * 
	 * @param player Player to create the list for
	 * @param shopId Shop content defID
	 * @return Array of enigmas to unravel
	 */
	public static String[] getEnigmaItems(CenturiaAccount player, String shopId) {
		if (!shops.containsKey(shopId))
			return new String[0];

		// Store in memory for easy access
		ShopInfo shop = shops.get(shopId);

		// Create list of items
		ArrayList<String> items = new ArrayList<String>();
		shop.enigmas.forEach(enigma -> {
			// Add enigma if present in the inventory
			if (player.getSaveSpecificInventory().getAccessor().hasInventoryObjectByDefId("7", enigma)) {
				JsonObject obj = player.getSaveSpecificInventory().getAccessor().findInventoryObjectByDefId("7",
						enigma);

				// Check if its been unraveled
				JsonObject data = obj.get("components").getAsJsonObject().get("Enigma").getAsJsonObject();
				if (!data.get("activated").getAsBoolean())
					items.add(obj.get("id").getAsString());
			}
		});

		return items.toArray(t -> new String[t]);
	}

	/**
	 * Retrieves information about a given shop item ID
	 * 
	 * @param player Player accessing the shop
	 * @param shopId Shop ID
	 * @param itemId Shop item ID
	 * @return ShopItem or null if unavailable
	 */
	public static ShopItem getShopItemInfo(CenturiaAccount player, String shopId, String itemId) {
		return getShopItemInfo(player, shopId, itemId, -1);
	}

	/**
	 * Retrieves information about a given shop item ID
	 * 
	 * @param player Player accessing the shop
	 * @param shopId Shop ID
	 * @param itemId Shop item ID
	 * @param count  How much of this item the player intends to buy
	 * @return ShopItem or null if unavailable
	 */
	public static ShopItem getShopItemInfo(CenturiaAccount player, String shopId, String itemId, int count) {
		if (!shops.containsKey(shopId))
			return null;

		// Store in memory for easy access
		ShopInfo shop = shops.get(shopId);

		// Check if the item is present
		if (!shop.contents.containsKey(itemId)) {
			// Check enigma item
			for (String enigma : shop.enigmas) {
				if (player.getSaveSpecificInventory().getInspirationAccessor().getEnigmaResult(enigma).equals(itemId)) {
					// Check if enigma is present in the inventory
					if (player.getSaveSpecificInventory().getAccessor().hasInventoryObjectByDefId("7", enigma)) {
						JsonObject obj = player.getSaveSpecificInventory().getAccessor().findInventoryObjectByDefId("7",
								enigma);

						// Check if its been unraveled
						JsonObject data = obj.get("components").getAsJsonObject().get("Enigma").getAsJsonObject();
						if (data.get("activated").getAsBoolean()) {
							// Found it, return enigma
							return shop.contents.get(enigma);
						}
					}
					break;
				}
			}

			return null; // Item not recognized
		}
		ShopItem item = shop.contents.get(itemId);

		// Check stock
		if (item.stock != -1) {
			// Check mode
			if (item.stock == 1 && shop.restockTime == -1) {
				// Only add if not in the player inventory

				boolean hasItems = true;
				for (String itm : item.items.keySet()) {
					String inv = ItemAccessor.getInventoryTypeOf(itm);
					if (!player.getSaveSpecificInventory().getAccessor().hasInventoryObjectByDefId(inv, itm)) {
						hasItems = false;
					}
				}

				if (hasItems) {
					// Out of stock
					return null;
				}
			}

			// Check stock
			int purchaseCount = getPurchaseCount(player, shopId, itemId) + (count < 0 ? 0 : count - 1);
			if (purchaseCount > item.stock) {
				// Out of stock
				return null;
			}
		}

		// In stock, return info
		return item;
	}

	/**
	 * Adds a item to the purchase log
	 * 
	 * @param player Player accessing the shop
	 * @param shopId Shop ID
	 * @param itemId Shop item ID
	 * @param count  How much of this item the player bought
	 */
	public static void purchaseCompleted(CenturiaAccount player, String shopId, String itemId, int count) {
		if (!shops.containsKey(shopId))
			return;

		// Store in memory for easy access
		ShopInfo shop = shops.get(shopId);

		// Check if the item is present
		if (!shop.contents.containsKey(itemId))
			return; // Item not recognized

		// Find log item
		JsonObject log;
		if (!player.getSaveSpecificInventory().containsItem("purchaselog"))
			log = new JsonObject();
		else
			log = player.getSaveSpecificInventory().getItem("purchaselog").getAsJsonObject();

		JsonObject shopLog;
		if (!log.has(shopId)) {
			// Add shop
			shopLog = new JsonObject();
			shopLog.addProperty("activationTime", System.currentTimeMillis());
			shopLog.add("items", new JsonObject());
		} else {
			shopLog = log.get(shopId).getAsJsonObject();
		}

		// Find purchased item in log or create it
		if (!shopLog.has(itemId))
			shopLog.addProperty(itemId, count);
		else {
			// Append
			int currentCount = shopLog.get(itemId).getAsInt();
			shopLog.remove(itemId);
			shopLog.addProperty(itemId, currentCount + count);
		}

		// Save log
		player.getSaveSpecificInventory().setItem("purchaselog", log);
	}

	/**
	 * Checks if the given ID is a valid shop ID
	 * 
	 * @param id ID to check
	 * @return True if the ID is a valid shop ID, false otherwise
	 */
	public static boolean isShop(String id) {
		return shops.containsKey(id);
	}

	/**
	 * Retrieves the IDs of the shops this item can be bought from
	 * 
	 * @param id Item defID
	 * @return Array of shop defIDs
	 */
	public static String[] getShopsForItem(String id) {
		// Check existence
		if (!itemShops.containsKey(id))
			return new String[0];
		return itemShops.get(id).toArray(t -> new String[t]);
	}

	/**
	 * Retrieves uncrafting information
	 * 
	 * @param itemDefId Item defID
	 * @return UncraftingInfo object instance or null
	 */
	public static UncraftingInfo getUncraft(String itemDefId) {
		return uncrafting.get(itemDefId);
	}

}
