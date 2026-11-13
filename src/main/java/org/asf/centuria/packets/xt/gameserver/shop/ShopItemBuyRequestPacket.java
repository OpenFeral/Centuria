package org.asf.centuria.packets.xt.gameserver.shop;

import java.io.IOException;
import java.util.HashMap;
import java.util.UUID;

import org.asf.centuria.accounts.highlevel.ItemAccessor;
import org.asf.centuria.data.XtReader;
import org.asf.centuria.data.XtWriter;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.entities.shops.BoughtItemInfo;
import org.asf.centuria.enums.shops.ItemBuyStatus;
import org.asf.centuria.interactions.modules.resourcecollection.levelhooks.EventInfo;
import org.asf.centuria.levelevents.LevelEvent;
import org.asf.centuria.levelevents.LevelEventBus;
import org.asf.centuria.networking.smartfox.SmartfoxClient;
import org.asf.centuria.packets.xt.IXtPacket;
import org.asf.centuria.packets.xt.gameserver.inventory.InventoryItemPacket;
import org.asf.centuria.shops.ShopManager;
import org.asf.centuria.shops.info.ShopItem;
import org.asf.centuria.util.RandomSelectorUtil;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class ShopItemBuyRequestPacket implements IXtPacket<ShopItemBuyRequestPacket> {

	private static final String PACKET_ID = "$b";

	private String item;
	private String shopType;
	private int count;

	@Override
	public ShopItemBuyRequestPacket instantiate() {
		return new ShopItemBuyRequestPacket();
	}

	@Override
	public String id() {
		return PACKET_ID;
	}

	@Override
	public void parse(XtReader reader) throws IOException {
		item = reader.read();
		shopType = reader.read();
		count = reader.readInt();
	}

	@Override
	public void build(XtWriter writer) throws IOException {
	}

	@Override
	public boolean handle(SmartfoxClient client) throws IOException {
		// Buy item
		Player plr = (Player) client.container;
		ItemAccessor acc = plr.account.getSaveSpecificInventory().getItemAccessor(plr);

		// Find item
		ShopItem itm = ShopManager.getShopItemInfo(plr.account, shopType, item, count);
		if (itm == null) {
			// Item unavailable
			ShopItemBuyResponsePacket pkt = new ShopItemBuyResponsePacket();
			pkt.status = ItemBuyStatus.UNAVAILABLE;
			client.sendPacket(pkt);
			return true;
		}

		// Check if the player can afford the item
		for (String itemId : itm.cost.keySet()) {
			int cost = itm.cost.get(itemId) * count;
			if (acc.getCountOfItem(itemId) < cost) {
				// Item unaffordable
				ShopItemBuyResponsePacket pkt = new ShopItemBuyResponsePacket();
				pkt.status = ItemBuyStatus.UNAFFORDABLE;
				client.sendPacket(pkt);
				return true;
			}
		}

		// Check level lock
		if (itm.requiredLevel != -1 && (!plr.account.getLevel().isLevelAvailable()
				|| plr.account.getLevel().getLevel() < itm.requiredLevel)) {
			ShopItemBuyResponsePacket pkt = new ShopItemBuyResponsePacket();
			pkt.status = ItemBuyStatus.LEVEL_LOCKED;
			client.sendPacket(pkt);
			return true;
		}

		// Prepare XP event
		EventInfo ev = new EventInfo();
		ev.event = "levelevents.purchases";

		// Find shop name
		String shop = "unknown";
		switch (shopType) {
		case "11600": {
			shop = "todyefor";
			break;
		}
		case "4688":
		case "11599": {
			shop = "ferafashions";
			break;
		}
		case "30550":
		case "14873": {
			shop = "astrale";
			break;
		}
		case "28741":
		case "28745":
		case "28746": {
			shop = "airmail";
			break;
		}
		case "6707": {
			shop = "bellamysoutpost";
			break;
		}
		case "4957": {
			shop = "reputablegoods";
			break;
		}
		case "11601": {
			shop = "twiglasworkshop";
			break;
		}
		}

		// Add tags
		ev.tags.add("shop:" + shop);
		ev.tags.add("purchase:" + item);

		// Buy item
		ShopItemBuyResponsePacket res = new ShopItemBuyResponsePacket();
		res.status = ItemBuyStatus.SUCCESS;
		for (String itemId : itm.cost.keySet()) {
			int cost = itm.cost.get(itemId) * count;

			// Remove items
			acc.remove(itemId, cost);
		}
		itm.items.forEach((id, amount) -> {
			amount *= count;

			// Check if can be given
			if (ItemAccessor.getInventoryTypeOf(id) != null && !ItemAccessor.canHaveMultipleOf(id)
					&& plr.account.getSaveSpecificInventory().getAccessor()
							.hasInventoryObjectByDefId(ItemAccessor.getInventoryTypeOf(id), id))
				return; // Ship

			// Add item to inventory
			String[] ids;
			if (!id.equals("30197"))
				ids = acc.add(id, amount);
			else {
				// Look slots

				// Add the slot
				plr.account.getSaveSpecificInventory().getAvatarAccessor().addExtraLookSlot();

				// Create fake update
				String slotID = UUID.randomUUID().toString();
				JsonArray arr = new JsonArray();
				JsonObject components = new JsonObject();

				// Add timestamp
				JsonObject ts = new JsonObject();
				ts.addProperty("ts", System.currentTimeMillis());
				components.add("Timestamp", ts);

				// Build object
				JsonObject obj = new JsonObject();
				obj.addProperty("defId", "30197");
				obj.add("components", components);
				obj.addProperty("id", slotID);
				obj.addProperty("type", 315);
				arr.add(obj);

				// Send slot update
				InventoryItemPacket pk = new InventoryItemPacket();
				pk.item = arr;
				client.sendPacket(pk);

				// Send ILs
				for (String change : plr.account.getSaveSpecificInventory().getAccessor().getChangedInventories())
					plr.account.getSaveSpecificInventory().getAccessor().transferUpdatedItemsToPlayer(plr, change);

				// Save ID so the astrale shop wont freeze
				ids = new String[] { slotID };

				// Save items
				for (String itmTS : plr.account.getSaveSpecificInventory().getAccessor().getChangedInventories())
					plr.account.getSaveSpecificInventory().getAccessor().saveUpdatedItems(itmTS, true);
			}
			for (String itemId : ids) {
				BoughtItemInfo i = new BoughtItemInfo();
				i.count = amount;
				i.itemID = itemId;
				res.items.add(i);

				// Find type
				String type = "generic";
				switch (ItemAccessor.getInventoryTypeOf(id)) {
				case "1": {
					type = "avatar";
					break;
				}
				case "2": {
					type = "bodymod";
					break;
				}
				case "7": {
					type = "enigma";
					break;
				}
				case "100": {
					type = "clothing";
					break;
				}
				case "102": {
					type = "furniture";
					break;
				}
				case "103": {
					type = "resource";
					break;
				}
				case "111": {
					type = "dye";
					break;
				}
				case "5":
				case "6":
				case "10": {
					type = "sanctuary";
					break;
				}
				}

				// Add tags
				ev.tags.add("type:" + type);
				ev.tags.add("item:" + id);
				ev.tags.add("itemcount:" + amount);
				int rarity = ItemAccessor.getItemRarity(id);
				ev.tags.add("rarity:"
						+ (rarity == 0 ? "common" : (rarity == 1 ? "cool" : (rarity == 2 ? "rare" : "epic"))));
			}
		});

		// Eureka items
		if (!itm.eurekaItems.isEmpty()) {
			// Build a weight map
			int currentWeight = 0;
			HashMap<String, Integer> weights = new HashMap<String, Integer>();
			for (String item : itm.eurekaItems.keySet()) {
				int chance = itm.eurekaItems.get(item);
				weights.put(item, chance);
				currentWeight += chance;
			}
			if (currentWeight < 100) {
				weights.put(null, 100 - currentWeight);
			}

			// Retrieve entry
			String item = RandomSelectorUtil.selectWeighted(weights);
			if (item != null) {
				// Give eureka item
				BoughtItemInfo e = new BoughtItemInfo();
				e.itemID = acc.add(item);
				e.count = 1;
				res.eurekaItems.add(e);

				// Eureka tag
				ev.tags.add("eureka:true");
			}
		}

		// Send packets
		client.sendPacket(res);

		// Update shop log
		ShopManager.purchaseCompleted(plr.account, shopType, item, count);

		// Shop list
		ShopListPacket resp = new ShopListPacket();
		resp.shopType = shopType;
		resp.items = ShopManager.getShopContents(((Player) client.container).account, shopType);
		client.sendPacket(resp);

		// Dispatch event
		LevelEventBus.dispatch(new LevelEvent(ev.event, ev.tags.toArray(new String[0]), plr));

		return true;
	}

}
