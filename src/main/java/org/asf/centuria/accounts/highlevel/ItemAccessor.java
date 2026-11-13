package org.asf.centuria.accounts.highlevel;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.asf.centuria.accounts.PlayerInventory;
import org.asf.centuria.accounts.highlevel.itemdata.inventory.InventoryDefinitionContainer;
import org.asf.centuria.accounts.highlevel.itemdata.inventory.impl.AvatarSpeciesHelper;
import org.asf.centuria.accounts.highlevel.itemdata.inventory.impl.BodyModHelper;
import org.asf.centuria.accounts.highlevel.itemdata.inventory.impl.ClothingHelper;
import org.asf.centuria.accounts.highlevel.itemdata.inventory.impl.CurrencyHelper;
import org.asf.centuria.accounts.highlevel.itemdata.inventory.impl.FurnitureHelper;
import org.asf.centuria.accounts.highlevel.itemdata.inventory.impl.GenericHelper;
import org.asf.centuria.accounts.highlevel.itemdata.inventory.impl.GenericHelperWithQuantity;
import org.asf.centuria.accounts.highlevel.itemdata.inventory.impl.SanctuaryClassHelper;
import org.asf.centuria.accounts.highlevel.itemdata.inventory.impl.SanctuaryHouseHelper;
import org.asf.centuria.accounts.highlevel.itemdata.inventory.impl.SanctuaryIslandHelper;
import org.asf.centuria.accounts.highlevel.itemdata.item.ItemBundleEntry;
import org.asf.centuria.accounts.highlevel.itemdata.item.ItemComponent;
import org.asf.centuria.accounts.highlevel.itemdata.item.ItemInfo;
import org.asf.centuria.entities.components.generic.TradeableComponent;
import org.asf.centuria.entities.inventoryitems.InventoryItem;
import org.asf.centuria.entities.inventoryitems.InventoryItemManager;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.enums.inventory.InventoryStorageType;
import org.asf.centuria.enums.inventory.InventoryType;
import org.asf.centuria.packets.xt.gameserver.inventory.InventoryItemDownloadPacket;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ItemAccessor {

	private Player player;
	private PlayerInventory inventory;
	private static LinkedHashMap<String, ItemInfo> definitions = new LinkedHashMap<String, ItemInfo>();
	private static LinkedHashMap<String, ItemBundleEntry> bundles = new LinkedHashMap<String, ItemBundleEntry>();

	private static ItemComponent tradelisComponent() {
		JsonObject tr = new JsonObject();
		tr.addProperty("isInTradeList", false);

		return new ItemComponent("Tradable", tr);
	}

	private static ItemComponent enigmaComonent() {
		JsonObject e = new JsonObject();
		e.addProperty("activated", false);

		return new ItemComponent("Enigma", e);
	}

	private static final Map<String, InventoryDefinitionContainer> inventoryTypeMap = new HashMap<String, InventoryDefinitionContainer>() {
		{
			// Avatar species
			put("1", new InventoryDefinitionContainer(InventoryStorageType.SINGLE_ITEM, new AvatarSpeciesHelper()));

			// Body mods
			put("2", new InventoryDefinitionContainer(InventoryStorageType.SINGLE_ITEM, new BodyModHelper()));

			// Clothing
			put("100", new InventoryDefinitionContainer(InventoryStorageType.OBJECT_BASED, new ClothingHelper()));

			// Sanctuary classes
			put("10", new InventoryDefinitionContainer(InventoryStorageType.SINGLE_ITEM, new SanctuaryClassHelper()));

			// Sanctuary houses
			put("5", new InventoryDefinitionContainer(InventoryStorageType.SINGLE_ITEM, new SanctuaryHouseHelper()));

			// Sanctuary islands
			put("6", new InventoryDefinitionContainer(InventoryStorageType.SINGLE_ITEM, new SanctuaryIslandHelper()));

			// Furniture
			put("102", new InventoryDefinitionContainer(InventoryStorageType.OBJECT_BASED, new FurnitureHelper()));

			// Currency
			put("104", new InventoryDefinitionContainer(InventoryStorageType.QUANTITY_BASED, new CurrencyHelper()));

			// Resources
			put("103", new InventoryDefinitionContainer(InventoryStorageType.QUANTITY_BASED,
					new GenericHelperWithQuantity("103", tradelisComponent())));

			// Dyes
			put("111", new InventoryDefinitionContainer(InventoryStorageType.QUANTITY_BASED,
					new GenericHelperWithQuantity("111", tradelisComponent())));

			// Enigmas (ty to Mewt/Uzukara for pointing this out)
			put("7", new InventoryDefinitionContainer(InventoryStorageType.SINGLE_ITEM,
					new GenericHelper("7", enigmaComonent())));

			// Inspirations
			put("8", new InventoryDefinitionContainer(InventoryStorageType.SINGLE_ITEM,
					new GenericHelper("8", new ItemComponent("Inspiration", new JsonObject()))));

			// Season passes
			put("12", new InventoryDefinitionContainer(InventoryStorageType.SINGLE_ITEM, new GenericHelper("12")));
		}
	};

	private static final String[] tradeableInventories = new String[] { "100", "102", "103", "111" };

	static {
		try {
			// Load the helper
			InputStream strm = InventoryItemDownloadPacket.class.getClassLoader()
					.getResourceAsStream("itemdefinitions.json");
			JsonObject helper = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8")).getAsJsonObject()
					.get("Definitions").getAsJsonObject();
			strm.close();

			// Load all items
			final JsonObject helperFinal = helper;
			helper.keySet().forEach(itemID -> {
				JsonObject itmI = helperFinal.get(itemID).getAsJsonObject();
				ItemInfo info = new ItemInfo();
				info.inventory = itmI.get("inventory").getAsString();
				info.objectName = itmI.get("objectName").getAsString();
				info.rarity = itmI.get("rarity").getAsInt();
				if (!info.inventory.equals("0"))
					definitions.put(itemID, info);
			});

			// Load bundles
			strm = InventoryItemDownloadPacket.class.getClassLoader().getResourceAsStream("bundles.json");
			helper = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8")).getAsJsonObject().get("bundles")
					.getAsJsonObject();
			strm.close();

			// Load all bundles
			final JsonObject helperFinal2 = helper;
			helper.keySet().forEach(itemID -> {
				JsonObject itmI = helperFinal2.get(itemID).getAsJsonObject();
				ItemBundleEntry info = new ItemBundleEntry();
				info.objectName = itmI.get("objectName").getAsString();
				JsonObject itms = itmI.get("items").getAsJsonObject();
				itms.keySet().forEach(id -> info.items.put(id, itms.get(id).getAsInt()));
				bundles.put(itemID, info);
			});
		} catch (IOException e) {
		}
	}

	public ItemAccessor(PlayerInventory inventory, Player player) {
		this.inventory = inventory;
		this.player = player;
	}

	/**
	 * Checks if a user can have multiple of a specific item or if its a give-once
	 * item
	 * 
	 * @param defID Item def ID to check
	 * @return True if this item allows multiple of the same item to be present,
	 *         false otherwise or if not known
	 */
	public static boolean canHaveMultipleOf(String defID) {
		// Find definition
		ItemInfo info = definitions.get(defID);
		if (info == null) {
			// Find bundle
			if (bundles.containsKey(defID)) {
				// Its a bundle
				return true;
			}

			// We dont know!
			return false;
		}

		// Find inventory
		if (!inventoryTypeMap.containsKey(info.inventory))
			return false;
		InventoryDefinitionContainer container = inventoryTypeMap.get(info.inventory);
		if (container.inventoryType == InventoryStorageType.SINGLE_ITEM)
			return false;
		return true;
	}

	/**
	 * Retrieves the rarity of a item
	 * 
	 * @param itemDefId Item defID
	 * @return Rarity number, 0 = common, 1 = cool, 2 = rare, 3 = epic
	 */
	public static int getItemRarity(String itemDefId) {
		if (definitions.containsKey(itemDefId))
			return definitions.get(itemDefId).rarity;
		return 0;
	}

	/**
	 * Retrieves an array of defIDs for a specific inventory type
	 * 
	 * @param inventory Inventory type string
	 * @return Array of defIDs
	 */
	public static String[] getItemDefinitionsIn(String inventory) {
		return definitions.keySet().stream().filter(t -> definitions.get(t).inventory.equals(inventory))
				.toArray(t -> new String[t]);
	}

	/**
	 * Retrieves the inventory type of a item by defID
	 * 
	 * @param defID Item defID
	 * @return Inventory type string or null
	 */
	public static String getInventoryTypeOf(String defID) {
		// Find definition
		ItemInfo info = definitions.get(defID);
		if (info == null)
			return null;
		return info.inventory;
	}

	/**
	 * Adds items by object
	 * 
	 * @param object Inventory item object
	 * @return New item ID string or null if invalid
	 */
	public String add(JsonObject object) {
		JsonObject obj = null;

		// Find definition
		ItemInfo info = definitions.get(object.get("defId").getAsString());
		if (info == null)
			return null;

		// Find inventory
		if (!inventoryTypeMap.containsKey(info.inventory))
			return null;
		InventoryDefinitionContainer container = inventoryTypeMap.get(info.inventory);

		// Find type handler
		switch (container.inventoryType) {

		// Single-item
		case SINGLE_ITEM: {
			// Check if the item is present
			if (inventory.getAccessor().hasInventoryObjectByDefId(info.inventory, object.get("defId").getAsString()))
				return null; // invalid

			// Add item directly
			obj = container.inventoryInteraction.addOne(inventory, object);
			break;
		}

		// Object-based item
		case OBJECT_BASED: {
			// Add item directly
			obj = container.inventoryInteraction.addOne(inventory, object);
			break;
		}

		// Quantity-based item
		case QUANTITY_BASED: {
			// Check quantity field
			if (object.has("components") && object.get("components").getAsJsonObject().has("Quantity")) {
				int q = object.get("components").getAsJsonObject().get("Quantity").getAsJsonObject().get("quantity")
						.getAsInt();

				// Add items
				if (q >= 0) {
					if (!inventory.getAccessor().hasInventoryObjectByDefId(info.inventory,
							object.get("defId").getAsString())) {
						// Add item directly
						obj = container.inventoryInteraction.addOne(inventory, object);
					} else {
						// Add to existing
						container.inventoryInteraction.addMultiple(inventory, object.get("defId").getAsString(), q);
						obj = inventory.getAccessor().findInventoryObjectByDefId(info.inventory,
								object.get("defId").getAsString());
					}
				}
			} else {
				// Add item directly
				obj = container.inventoryInteraction.addOne(inventory, object);
			}
			break;
		}

		}

		if (obj != null) {
			// Add item to save
			inventory.getAccessor().markChanged(info.inventory, obj.get("id").getAsString());
			inventory.getAccessor().transferUpdatedItemsToPlayer(player, info.inventory);

			// Save other items
			for (String itm : inventory.getAccessor().getChangedInventories()) {
				inventory.setItem(itm, inventory.getItem(itm));

				if (!info.inventory.equals(itm)) {
					// Transfer to player
					inventory.getAccessor().transferUpdatedItemsToPlayer(player, itm);
				}
			}
		}

		// Return id
		return (obj != null ? obj.get("id").getAsString() : null);
	}

	/**
	 * Adds items by DefID
	 * 
	 * @param defID Item defID
	 * @return Item ID string or null if invalid
	 */
	public String add(String defID) {
		JsonObject obj = null;

		// Find definition
		ItemInfo info = definitions.get(defID);
		if (info == null) {
			// Find bundle
			if (bundles.containsKey(defID)) {
				String res = null;

				// Add items
				ItemBundleEntry entry = bundles.get(defID);
				for (String id : entry.items.keySet()) {
					int count = entry.items.get(id);

					// Check if can be given
					if (getInventoryTypeOf(id) != null && !canHaveMultipleOf(id) && inventory.getAccessor()
							.hasInventoryObjectByDefId(ItemAccessor.getInventoryTypeOf(id), id))
						continue; // Ship

					// Add
					String[] ids = add(id, count);
					if (res == null && ids.length > 0)
						res = ids[0];
				}

				return res;
			}

			return null;
		}

		// Find inventory
		if (!inventoryTypeMap.containsKey(info.inventory))
			return null;
		InventoryDefinitionContainer container = inventoryTypeMap.get(info.inventory);

		// Find type handler
		switch (container.inventoryType) {

		// Single-item
		case SINGLE_ITEM: {
			// Check if the item is present
			if (inventory.getAccessor().hasInventoryObjectByDefId(info.inventory, defID))
				return null; // invalid

			// Fall through to the quantity-based handler as the rest is the same
		}

		// Object-based and quantity-based items
		case OBJECT_BASED:
		case QUANTITY_BASED: {
			// Add item
			obj = container.inventoryInteraction.addOne(inventory, defID);
			break;
		}

		}

		if (obj != null) {
			// Add item to save
			inventory.getAccessor().markChanged(info.inventory, obj.get("id").getAsString());
			inventory.getAccessor().transferUpdatedItemsToPlayer(player, info.inventory);

			// Save other items
			for (String itm : inventory.getAccessor().getChangedInventories()) {
				inventory.setItem(itm, inventory.getItem(itm));

				if (!info.inventory.equals(itm)) {
					// Transfer to player
					inventory.getAccessor().transferUpdatedItemsToPlayer(player, itm);
				}
			}
		}

		// Return id
		return (obj != null ? obj.get("id").getAsString() : null);
	}

	/**
	 * Adds items by DefID
	 * 
	 * @param defID Item defID
	 * @param count Amount of the item to add
	 * @return Array of item IDs that were added
	 */
	public String[] add(String defID, int count) {
		// Check validity
		if (count <= 0)
			return new String[0]; // Nonsense count so lets return an empty array

		JsonObject[] objs = null;

		// Find definition
		ItemInfo info = definitions.get(defID);
		if (info == null) {
			// Find bundle
			if (bundles.containsKey(defID)) {
				ArrayList<String> res = new ArrayList<String>();

				// Add items
				ItemBundleEntry entry = bundles.get(defID);
				for (String id : entry.items.keySet()) {
					int itC = entry.items.get(id);

					// Check if can be given
					if (getInventoryTypeOf(id) != null && !canHaveMultipleOf(id) && inventory.getAccessor()
							.hasInventoryObjectByDefId(ItemAccessor.getInventoryTypeOf(id), id))
						continue; // Ship

					// Add
					String[] ids = add(id, count * itC);
					for (String itmI : ids)
						res.add(itmI);
				}

				return res.toArray(t -> new String[t]);
			}

			return new String[0];
		}

		// Find inventory
		if (!inventoryTypeMap.containsKey(info.inventory))
			return new String[0];
		InventoryDefinitionContainer container = inventoryTypeMap.get(info.inventory);

		// Find type handler
		switch (container.inventoryType) {

		// Single-item
		case SINGLE_ITEM: {
			// Check if the item is present
			if (count > 1 || inventory.getAccessor().hasInventoryObjectByDefId(info.inventory, defID))
				return new String[0]; // invalid

			// Fall through to the quantity-based handler as the rest is the same
		}

		// Object-based and quantity-based items
		case OBJECT_BASED:
		case QUANTITY_BASED: {
			// Add item
			objs = container.inventoryInteraction.addMultiple(inventory, defID, count);
			break;
		}

		}

		if (objs != null) {
			// Mark items
			ArrayList<String> syncedInventories = new ArrayList<String>();
			for (JsonObject obj : objs) {
				// Mark
				inventory.getAccessor().markChanged(obj.get("type").getAsString(), obj.get("id").getAsString());
				if (!syncedInventories.contains(obj.get("type").getAsString()))
					syncedInventories.add(obj.get("type").getAsString());
			}

			// Transfer
			for (String i : syncedInventories)
				inventory.getAccessor().transferUpdatedItemsToPlayer(player, i);

			// Save items
			for (String itm : inventory.getAccessor().getChangedInventories()) {
				inventory.setItem(itm, inventory.getItem(itm));

				if (!syncedInventories.contains(itm)) {
					// Transfer to player
					inventory.getAccessor().transferUpdatedItemsToPlayer(player, itm);
				}
			}
		}

		// Return ids
		return (objs != null ? Stream.of(objs).map(t -> t.get("id").getAsString()).toArray(t -> new String[t])
				: new String[0]);
	}

	/**
	 * Removes items by DefID
	 * 
	 * @param defID Item defID
	 * @return True if successful, false otherwise
	 */
	public boolean remove(String defID) {
		// Find definition
		ItemInfo info = definitions.get(defID);
		if (info == null)
			return false;

		// Find inventory
		if (!inventoryTypeMap.containsKey(info.inventory))
			return false;
		InventoryDefinitionContainer container = inventoryTypeMap.get(info.inventory);

		String removedItemUUID = null;

		// Find type handler
		switch (container.inventoryType) {

		// Single-item
		case SINGLE_ITEM: {
			// Check if the item is present
			if (!inventory.getAccessor().hasInventoryObjectByDefId(info.inventory, defID))
				return false; // invalid

			// Fall through to the quantity-based handler as the rest is the same
		}

		// Object-based and quantity-based items
		case OBJECT_BASED:
		case QUANTITY_BASED: {
			// Remove item
			removedItemUUID = container.inventoryInteraction.removeOne(inventory, defID);
			if (removedItemUUID == null)
				return false;
			break;
		}

		}

		// Mark items
		if (inventory.getAccessor().hasInventoryObjectByItemId(info.inventory, removedItemUUID)) {
			// Mark changed
			inventory.getAccessor().markChanged(info.inventory, removedItemUUID);
		} else {
			// Mark deleted
			inventory.getAccessor().markDeleted(info.inventory, removedItemUUID);
		}

		// Transfer
		inventory.getAccessor().transferUpdatedItemsToPlayer(player, info.inventory);

		// Save other items
		for (String itm : inventory.getAccessor().getChangedInventories()) {
			inventory.setItem(itm, inventory.getItem(itm));

			if (!itm.equals(info.inventory)) {
				// Transfer to player
				inventory.getAccessor().transferUpdatedItemsToPlayer(player, itm);
			}
		}

		// Return success
		return true;
	}

	/**
	 * Remove items by DefID
	 * 
	 * @param defID Item defID
	 * @param count Amount of the item to remove
	 * @return True if successful, false otherwise
	 */
	public boolean remove(String defID, int count) {
		// Check validity
		if (count <= 0)
			return false;

		// Find definition
		ItemInfo info = definitions.get(defID);
		if (info == null)
			return false;

		// Find inventory
		if (!inventoryTypeMap.containsKey(info.inventory))
			return false;
		InventoryDefinitionContainer container = inventoryTypeMap.get(info.inventory);

		String[] removedItemUUIDs = null;

		// Find type handler
		switch (container.inventoryType) {

		// Single-item
		case SINGLE_ITEM: {
			// Check if the item is present
			if (count > 1 || !inventory.getAccessor().hasInventoryObjectByDefId(info.inventory, defID))
				return false; // invalid

			// Fall through to the quantity-based handler as the rest is the same
		}

		// Object-based and quantity-based items
		case OBJECT_BASED:
		case QUANTITY_BASED: {
			// Remove item
			removedItemUUIDs = container.inventoryInteraction.removeMultiple(inventory, defID, count);
			if (removedItemUUIDs == null)
				return false;
			break;
		}

		}

		// Mark items
		for (String id : removedItemUUIDs) {
			if (inventory.getAccessor().hasInventoryObjectByItemId(info.inventory, id)) {
				// Mark changed
				inventory.getAccessor().markChanged(info.inventory, id);
			} else {
				// Mark deleted
				inventory.getAccessor().markDeleted(info.inventory, id);
			}
		}

		// Transfer
		inventory.getAccessor().transferUpdatedItemsToPlayer(player, info.inventory);

		// Save other items
		for (String itm : inventory.getAccessor().getChangedInventories()) {
			inventory.setItem(itm, inventory.getItem(itm));

			if (!itm.equals(info.inventory)) {
				// Transfer to player
				inventory.getAccessor().transferUpdatedItemsToPlayer(player, itm);
			}
		}

		// Return success
		return true;
	}

	/**
	 * Removes items by object
	 * 
	 * @param object Inventory item object
	 * @return True if successful, false otherwise
	 */
	public boolean remove(JsonObject object) {
		// Find definition
		ItemInfo info = definitions.get(object.get("defId").getAsString());
		if (info == null)
			return false;

		// Find inventory
		if (!inventoryTypeMap.containsKey(info.inventory))
			return false;
		InventoryDefinitionContainer container = inventoryTypeMap.get(info.inventory);
		String[] removedItemUUIDs = null;

		// Find type handler
		switch (container.inventoryType) {

		// Single-item
		case SINGLE_ITEM: {
			// Check if the item is present
			if (!inventory.getAccessor().hasInventoryObjectByDefId(info.inventory, object.get("defId").getAsString()))
				return false; // invalid

			String removedItemUUID = container.inventoryInteraction.removeOne(inventory, object);

			// Remove item directly
			if (removedItemUUID == null)
				return false;

			removedItemUUIDs = new String[] { removedItemUUID };
			break;
		}

		// Object-based item
		case OBJECT_BASED: {
			// Remove item directly
			String removedItemUUID = container.inventoryInteraction.removeOne(inventory, object);

			// Remove item directly
			if (removedItemUUID == null)
				return false;

			removedItemUUIDs = new String[] { removedItemUUID };

			break;
		}

		// Quantity-based item
		case QUANTITY_BASED: {
			// Check quantity field
			if (object.has("components") && object.get("components").getAsJsonObject().has("Quantity")) {
				int q = object.get("components").getAsJsonObject().get("Quantity").getAsJsonObject().get("quantity")
						.getAsInt();

				// Remove items
				if (q >= 0) {
					if (!inventory.getAccessor().hasInventoryObjectByDefId(info.inventory,
							object.get("defId").getAsString())) {
						// Invalid
						return false;
					} else {
						// Remove existing
						removedItemUUIDs = container.inventoryInteraction.removeMultiple(inventory,
								object.get("defId").getAsString(), 1);
						if (removedItemUUIDs == null) {
							return false;
						}
					}
				}
			} else {
				// Remove item directly
				String removedItemUUID = container.inventoryInteraction.removeOne(inventory, object);

				// Remove item directly
				if (removedItemUUID == null)
					return false;

				removedItemUUIDs = new String[] { removedItemUUID };
				break;
			}
			break;
		}

		}

		// Mark items
		for (String id : removedItemUUIDs) {
			if (inventory.getAccessor().hasInventoryObjectByItemId(info.inventory, id)) {
				// Mark changed
				inventory.getAccessor().markChanged(info.inventory, id);
			} else {
				// Mark deleted
				inventory.getAccessor().markDeleted(info.inventory, id);
			}
		}

		// Transfer
		inventory.getAccessor().transferUpdatedItemsToPlayer(player, info.inventory);

		// Save other items
		for (String itm : inventory.getAccessor().getChangedInventories()) {
			inventory.setItem(itm, inventory.getItem(itm));

			if (!itm.equals(info.inventory)) {
				// Transfer to player
				inventory.getAccessor().transferUpdatedItemsToPlayer(player, itm);
			}
		}

		// Return success
		return true;
	}

	/**
	 * Checks if a item is quantity-based or not
	 * 
	 * @since Beta 1.5.3
	 * @param defID Item DefID
	 * @return True if quantity-based, false otherwise
	 */
	public boolean isQuantityBased(String defID) {
		// Find definition
		ItemInfo info = definitions.get(defID);
		if (info == null)
			return false;

		// Check if the inventory is present
		if (!inventory.containsItem(info.inventory))
			return false;

		// Find inventory
		if (!inventoryTypeMap.containsKey(info.inventory))
			return false;
		InventoryDefinitionContainer container = inventoryTypeMap.get(info.inventory);

		return container.inventoryType == InventoryStorageType.QUANTITY_BASED;
	}

	/**
	 * Retrieves the quantity of the given item currently in the player's inventory
	 * 
	 * @param defID Item defID to retrieve the count of
	 * @return Item count
	 */
	public int getCountOfItem(String defID) {
		// Find definition
		ItemInfo info = definitions.get(defID);
		if (info == null)
			return 0;

		// Check if the inventory is present
		if (!inventory.containsItem(info.inventory))
			return 0;

		// Find inventory
		if (!inventoryTypeMap.containsKey(info.inventory))
			return 0;
		InventoryDefinitionContainer container = inventoryTypeMap.get(info.inventory);

		// Find type handler
		switch (container.inventoryType) {

		// Single-item
		case SINGLE_ITEM: {
			// Check if the item is present
			if (inventory.getAccessor().hasInventoryObjectByDefId(info.inventory, defID))
				return 1;
			else
				return 0;
		}

		// Object-based
		case OBJECT_BASED: {
			int count = 0;

			// Find all items of this type
			JsonArray items = inventory.getItem(info.inventory).getAsJsonArray();
			for (JsonElement ele : items) {
				JsonObject itm = ele.getAsJsonObject();
				if (itm.get("defId").getAsString().equals(defID)) {
					count++;
				}
			}

			return count;
		}

		// Quantity-based
		case QUANTITY_BASED: {
			// Find item
			if (!inventory.getAccessor().hasInventoryObjectByDefId(info.inventory, defID))
				return 0; // Not present
			JsonObject obj = inventory.getAccessor().findInventoryObjectByDefId(info.inventory, defID);
			if (!obj.has("components") || !obj.get("components").getAsJsonObject().has("Quantity"))
				return 0; // Invalid

			// Return the quanity directly
			return obj.get("components").getAsJsonObject().get("Quantity").getAsJsonObject().get("quantity").getAsInt();
		}

		}

		// Fallback
		return 0;

	}

	public List<InventoryItem> loadTradeList() throws InstantiationException, IllegalAccessException,
			IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException {
		List<InventoryItem> tradeList = new ArrayList<InventoryItem>();

		for (String inventoryNumber : tradeableInventories) {
			// Find inventory
			if (!inventoryTypeMap.containsKey(inventoryNumber))
				return null;

			if (!inventory.containsItem(inventoryNumber))
				inventory.setItem(inventoryNumber, new JsonArray());

			var invObject = inventory.getItem(inventoryNumber).getAsJsonArray();

			var itemClass = InventoryItemManager
					.getItemTypeFromInvType(InventoryType.get(Integer.parseInt(inventoryNumber)));

			for (var itemObject : invObject) {
				var tradeableComponent = itemObject.getAsJsonObject().get(InventoryItem.COMPONENTS_PROPERTY_NAME)
						.getAsJsonObject().get(TradeableComponent.COMPONENT_NAME);

				if (tradeableComponent != null) {
					if (tradeableComponent.getAsJsonObject().get("isInTradeList").getAsBoolean()) {
						var invItem = itemClass.getDeclaredConstructor().newInstance();
						invItem.fromJsonObject(itemObject.getAsJsonObject());

						tradeList.add(invItem);
					}
				}
			}
		}

		return tradeList;
	}
}
