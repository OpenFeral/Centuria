package org.asf.centuria.accounts.impl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;

import org.apache.logging.log4j.MarkerManager;
import org.asf.centuria.Centuria;
import org.asf.centuria.accounts.CenturiaAccount;
import org.asf.centuria.accounts.LevelInfo;
import org.asf.centuria.accounts.impl.leveltypes.LevelReward;
import org.asf.centuria.accounts.impl.leveltypes.LevelRewards;
import org.asf.centuria.accounts.impl.leveltypes.RewardDefinition;
import org.asf.centuria.accounts.impl.leveltypes.TriggerInfo;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.levelevents.LevelEventBus;
import org.asf.centuria.packets.xt.gameserver.inventory.InventoryItemDownloadPacket;
import org.asf.centuria.packets.xt.gameserver.inventory.InventoryItemPacket;
import org.asf.centuria.packets.xt.gameserver.levels.XpUpdatePacket;
import org.asf.centuria.packets.xt.gameserver.levels.XpUpdatePacket.CompletedLevel;
import org.asf.centuria.util.RandomSelectorUtil;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;

public class LevelManager extends LevelInfo {

	private CenturiaAccount account;
	private static Random rnd = new Random();

	// Options
	private static int maxLevel;
	private static String levelCurveEval;
	private static LevelRewards rewards;

	static {
		try {
			File levelConf = new File("leveling.conf");
			if (!levelConf.exists()) {
				// Create config
				Files.writeString(levelConf.toPath(), "max-level=101\n"
						+ "rewards=resource://leveling/levelrewards.json\n"
						+ "triggers=resource://leveling/leveltriggers.json\n"
						+ "level-curve-eval=(450 + ((level - 1) * 107) + ((totalxp / 100) - ((level - 1) * 32.83662)))\n");
			}

			// Load config
			HashMap<String, String> config = new HashMap<String, String>();
			for (String line : Files.readAllLines(levelConf.toPath())) {
				if (line.isEmpty() || line.startsWith("#"))
					continue;
				String key = line;
				String value = "";
				if (key.contains("=")) {
					value = key.substring(key.indexOf("=") + 1);
					key = key.substring(0, key.indexOf("="));
				}
				config.put(key, value);
			}

			// Parse
			maxLevel = Integer.valueOf(config.getOrDefault("max-level", "101"));
			levelCurveEval = config.getOrDefault("level-curve-eval",
					"(450 + ((level - 1) * 107) + ((totalxp / 100) - ((level - 1) * 32.83662)))");

			// Load rewards
			JsonObject rewardsConf;
			String rewardsFile = config.getOrDefault("rewards", "resource://leveling/levelrewards.json");
			if (rewardsFile.startsWith("resource://")) {
				String res = rewardsFile.substring("resource://".length());

				// Load the resource
				InputStream strm = InventoryItemDownloadPacket.class.getClassLoader().getResourceAsStream(res);
				rewardsConf = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8")).getAsJsonObject();
				strm.close();
			} else {
				rewardsConf = JsonParser.parseString(Files.readString(Path.of(rewardsFile))).getAsJsonObject();
			}

			// Parse
			rewards = new LevelRewards();
			if (rewardsConf.has("rewardsForLevels")) {
				// Load level rewards
				JsonArray lvlRewards = rewardsConf.get("rewardsForLevels").getAsJsonArray();
				for (JsonElement ele : lvlRewards) {
					LevelReward reward = new LevelReward();

					// Parse reward
					JsonObject rewardInfo = ele.getAsJsonObject();
					for (JsonElement e2 : rewardInfo.get("levels").getAsJsonArray()) {
						reward.levels.add(e2.getAsString());
					}
					for (JsonElement e2 : rewardInfo.get("rewards").getAsJsonArray()) {
						reward.rewards.add(new RewardDefinition().parse(e2.getAsJsonObject()));
					}

					rewards.rewardsForLevels.add(reward);
				}
			}
			if (rewardsConf.has("fallbackRewards")) {
				// Load fallback rewards
				JsonArray fallbackRewards = rewardsConf.get("fallbackRewards").getAsJsonArray();
				for (JsonElement ele : fallbackRewards) {
					rewards.fallbackRewards.add(new RewardDefinition().parse(ele.getAsJsonObject()));
				}
			}

			// Load triggers
			JsonObject triggers;
			String triggerFile = config.getOrDefault("triggers", "resource://leveling/leveltriggers.json");
			if (triggerFile.startsWith("resource://")) {
				String res = triggerFile.substring("resource://".length());

				// Load the resource
				InputStream strm = InventoryItemDownloadPacket.class.getClassLoader().getResourceAsStream(res);
				triggers = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8")).getAsJsonObject();
				strm.close();
			} else {
				triggers = JsonParser.parseString(Files.readString(Path.of(triggerFile))).getAsJsonObject();
			}

			// Register all triggers
			triggers.keySet().forEach(eventType -> {
				ArrayList<TriggerInfo> infos = new ArrayList<TriggerInfo>();
				JsonArray triggerData = triggers.get(eventType).getAsJsonArray();
				for (JsonElement ele : triggerData) {
					JsonObject obj = ele.getAsJsonObject();
					TriggerInfo trigger = new TriggerInfo();
					trigger.xp = obj.get("xp").getAsInt();
					if (obj.has("endHere"))
						trigger.endHere = obj.get("endHere").getAsBoolean();
					if (obj.has("conditions")) {
						JsonArray conditions = obj.get("conditions").getAsJsonArray();
						for (JsonElement cond : conditions)
							trigger.conditions.add(cond.getAsString());
					}
					infos.add(trigger);
				}
				LevelEventBus.registerHandler(eventType, event -> {
					int addedXp = 0;

					// Find handler
					for (TriggerInfo trigger : infos) {
						// Check conditions
						boolean match = true;
						for (String condition : trigger.conditions) {
							if (!Stream.of(event.getTags()).anyMatch(t -> t.equals(condition))) {
								match = false;
								break;
							}
						}
						if (match) {
							// Handle
							addedXp += trigger.xp;
							if (trigger.endHere)
								break;
						}
					}

					// Add xp
					event.getPlayer().account.getLevel().addXP(addedXp);
				});
			});
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public LevelManager(CenturiaAccount account) {
		this.account = account;
	}

	@Override
	public boolean isLevelAvailable() {
		return true;
	}

	// Method to create a level object
	private void createLevelObject() {
		// Create level object
		JsonObject levelInfo = new JsonObject();

		// Basics
		levelInfo.addProperty("totalXp", 0);
		levelInfo.addProperty("currentLevel", 1);
		levelInfo.addProperty("currentLevelXp", 0);

		// Evaluate the curve
		ExpressionBuilder builder = new ExpressionBuilder(levelCurveEval);
		builder.variables("level", "lastlevel", "totalxp");
		Expression exp = builder.build();
		exp.setVariable("level", 1);
		exp.setVariable("lastlevel", 0);
		exp.setVariable("totalxp", 0);
		int levelUpCount = (int) exp.evaluate();
		if (levelUpCount > 15000)
			levelUpCount = 15000;
		levelInfo.addProperty("currentLevelMaxXp", levelUpCount);

		// Save
		account.getSaveSpecificInventory().setItem("level", levelInfo);
	}

	@Override
	public int getLevel() {
		// Retrieve level object
		if (!account.getSaveSpecificInventory().containsItem("level")) {
			// Create object
			createLevelObject();
		}

		// Retrieve level
		JsonObject level = account.getSaveSpecificInventory().getItem("level").getAsJsonObject();
		return level.get("currentLevel").getAsInt();
	}

	@Override
	public int getTotalXP() {
		// Retrieve level object
		if (!account.getSaveSpecificInventory().containsItem("level")) {
			// Create object
			createLevelObject();
		}

		// Retrieve total xp
		JsonObject level = account.getSaveSpecificInventory().getItem("level").getAsJsonObject();
		return level.get("totalXp").getAsInt();
	}

	@Override
	public int getCurrentXP() {
		// Retrieve level object
		if (!account.getSaveSpecificInventory().containsItem("level")) {
			// Create object
			createLevelObject();
		}

		// Retrieve current xp
		JsonObject level = account.getSaveSpecificInventory().getItem("level").getAsJsonObject();
		return level.get("currentLevelXp").getAsInt();
	}

	@Override
	public int getLevelupXPCount() {
		// Retrieve level object
		if (!account.getSaveSpecificInventory().containsItem("level")) {
			// Create object
			createLevelObject();
		}

		// Retrieve current max xp
		JsonObject level = account.getSaveSpecificInventory().getItem("level").getAsJsonObject();
		return level.get("currentLevelMaxXp").getAsInt();
	}

	@Override
	public void addXP(int xp) {
		Centuria.logger.debug(MarkerManager.getMarker("LEVELING"), "Add xp: " + xp);

		// Retrieve level object
		if (!account.getSaveSpecificInventory().containsItem("level")) {
			// Create object
			createLevelObject();
		}
		JsonObject level = account.getSaveSpecificInventory().getItem("level").getAsJsonObject();
		int tXP = getTotalXP();
		int cL = getLevel();
		int cLUP = getLevelupXPCount();
		int cXP = getCurrentXP();

		// Level cap
		if (getLevel() >= maxLevel && getCurrentXP() >= getLevelupXPCount())
			return;

		// Add xp
		if (cXP + xp < cLUP) {
			// Regular xp update

			// Add fields
			XpUpdatePacket packet = new XpUpdatePacket();
			packet.userId = account.getAccountID();
			packet.addedXp = xp;
			packet.previous = new XpUpdatePacket.Level();
			packet.previous.level = getLevel();
			packet.previous.levelUpXp = getLevelupXPCount();
			packet.previous.xp = getCurrentXP();

			// Add xp
			level.remove("totalXp");
			level.remove("currentLevelXp");
			level.addProperty("totalXp", tXP + xp);
			level.addProperty("currentLevelXp", cXP + xp);
			account.getSaveSpecificInventory().setItem("level", level);

			// Add new level
			packet.current = new XpUpdatePacket.Level();
			packet.current.level = getLevel();
			packet.current.levelUpXp = getLevelupXPCount();
			packet.current.xp = getCurrentXP();
			packet.totalXp = getTotalXP();

			// Send packet
			String room = null;
			if (account.getOnlinePlayerInstance() != null) {
				room = account.getOnlinePlayerInstance().room;
			}
			for (Player plr : Centuria.gameServer.getPlayers()) {
				if (plr.roomReady && plr.room != null && (room == null || plr.room.equals(room))) {
					plr.client.sendPacket(packet);
				}
			}
		} else {
			// Level up
			int newXp = cXP + xp;

			// Add fields
			XpUpdatePacket packet = new XpUpdatePacket();
			packet.userId = account.getAccountID();
			packet.addedXp = xp;
			packet.previous = new XpUpdatePacket.Level();
			packet.previous.level = getLevel();
			packet.previous.levelUpXp = getLevelupXPCount();
			packet.previous.xp = getCurrentXP();
			packet.current = new XpUpdatePacket.Level();

			// Add levels
			int remaining = addLevels(newXp, packet.completedLevels, cL);

			// Add xp
			int currentLevel = getLevel();
			level.remove("totalXp");
			level.remove("currentLevel");
			level.remove("currentLevelXp");
			level.remove("currentLevelMaxXp");
			level.addProperty("totalXp", tXP + xp);
			int nextLevel = currentLevel + packet.completedLevels.size();
			if (nextLevel > maxLevel)
				nextLevel = maxLevel;
			level.addProperty("currentLevel", nextLevel);
			level.addProperty("currentLevelXp", remaining);
			// Evaluate the curve
			ExpressionBuilder builder = new ExpressionBuilder(levelCurveEval);
			builder.variables("level", "lastlevel", "totalxp");
			Expression exp = builder.build();
			exp.setVariable("level", getLevel());
			exp.setVariable("lastlevel", currentLevel);
			exp.setVariable("totalxp", getTotalXP());
			int levelUpCount = (int) exp.evaluate();
			if (levelUpCount > 15000)
				levelUpCount = 15000;
			level.addProperty("currentLevelMaxXp", levelUpCount);
			account.getSaveSpecificInventory().setItem("level", level);

			// Add new level
			packet.current = new XpUpdatePacket.Level();
			packet.current.level = getLevel();
			packet.current.levelUpXp = getLevelupXPCount();
			packet.current.xp = getCurrentXP();
			packet.totalXp = getTotalXP();

			// Send packet
			String room = null;
			if (account.getOnlinePlayerInstance() != null) {
				room = account.getOnlinePlayerInstance().room;
			}
			for (Player plr : Centuria.gameServer.getPlayers()) {
				if (plr.roomReady && plr.room != null && (room == null || plr.room.equals(room))) {
					plr.client.sendPacket(packet);
				}
			}
		}
	}

	private int addLevels(int xp, ArrayList<CompletedLevel> output, int currentLevel) {
		int levelUpCount = getLevelupXPCount();
		int totXp = getTotalXP();
		int remaining = 0;
		while (true) {
			remaining = xp - levelUpCount;
			xp -= levelUpCount;

			// Add completed level
			CompletedLevel lvl = new CompletedLevel();
			lvl.level = currentLevel;
			lvl.levelUpXp = levelUpCount;
			output.add(lvl);

			// Final fallback reward (25 likes in case all fail)
			lvl.levelUpRewardDefId = "2327";
			lvl.levelUpRewardQuantity = 25;

			// Create a list of potential rewards
			LinkedHashMap<RewardDefinition, Integer> levelRewards = new LinkedHashMap<RewardDefinition, Integer>();
			for (LevelReward potentialReward : rewards.rewardsForLevels) {
				// Check if it matches
				boolean match = false;
				for (String level : potentialReward.levels) {
					if (level.equals(Integer.toString(currentLevel))) {
						match = true;
						break;
					} else if (level.contains("-")) {
						int min = Integer.parseInt(level.substring(0, level.indexOf("-")));
						int max = Integer.parseInt(level.substring(level.indexOf("-") + 1));
						if (currentLevel > min && currentLevel < max) {
							match = true;
							break;
						}
					}
				}

				// Add if possible
				if (match) {
					RewardDefinition reward = getReward(potentialReward.rewards, currentLevel, totXp);
					if (reward != null)
						levelRewards.put(reward, reward.weight);
				}
			}

			// Fallback if needed
			if (levelRewards.size() == 0) {
				RewardDefinition reward = getReward(rewards.fallbackRewards, currentLevel, totXp);
				if (reward != null)
					levelRewards.put(reward, reward.weight);
			}

			// Find result
			RewardDefinition res = RandomSelectorUtil.selectWeighted(levelRewards);
			if (res != null) {
				lvl.levelUpRewardDefId = res.itemDefId;
				lvl.levelUpRewardQuantity = res.itemQuantity;
			}

			// Add item
			String[] objects = account.getSaveSpecificInventory().getItemAccessor(account.getOnlinePlayerInstance())
					.add(lvl.levelUpRewardDefId, lvl.levelUpRewardQuantity);
			if (account.getOnlinePlayerInstance() != null) {
				if (objects.length == 0) {
					Centuria.logger.error(MarkerManager.getMarker("LEVELING"), "Invalid level reward: "
							+ lvl.levelUpRewardDefId + " for level " + lvl.level + ", defaulting to likes.");

					// Default to likes
					lvl.levelUpRewardDefId = "2327";
					lvl.levelUpRewardQuantity = 100;
					objects = account.getSaveSpecificInventory().getItemAccessor(account.getOnlinePlayerInstance())
							.add(lvl.levelUpRewardDefId, lvl.levelUpRewardQuantity);
				}
				String objID = objects[0];

				// Create giftpush
				JsonObject gift = new JsonObject();
				gift.addProperty("fromType", 7);
				gift.addProperty("redeemedItemIdsExpectedCount", 0);
				gift.addProperty("giftItemDefId", lvl.levelUpRewardDefId);
				gift.addProperty("count", lvl.levelUpRewardQuantity);
				gift.addProperty("giftItemType",
						account.getSaveSpecificInventory().getAccessor().getInventoryIDOfItem(objID));
				gift.addProperty("fromId", -1);
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
				lvl.levelUpRewardGiftId = giftID;
				JsonArray update = new JsonArray();
				update.add(obj);
				InventoryItemPacket pkt = new InventoryItemPacket();
				pkt.item = update;
				account.getOnlinePlayerInstance().client.sendPacket(pkt);

				// Send gift-push packet
				account.getOnlinePlayerInstance().client
						.sendPacket("%xt%gp%-1%1%" + giftID + "%" + lvl.levelUpRewardQuantity + "%");
			}

			// Increase level
			currentLevel++;

			// Evaluate the curve
			ExpressionBuilder builder = new ExpressionBuilder(levelCurveEval);
			builder.variables("level", "lastlevel", "totalxp");
			Expression exp = builder.build();
			exp.setVariable("level", currentLevel);
			exp.setVariable("lastlevel", currentLevel - 1);
			exp.setVariable("totalxp", totXp);
			levelUpCount = (int) exp.evaluate();
			if (levelUpCount > 15000)
				levelUpCount = 15000;

			// Increase
			totXp += levelUpCount;

			// Log
			Centuria.logger.debug(MarkerManager.getMarker("LEVELING"), "Level completed: " + (currentLevel - 1));

			if (remaining < levelUpCount || currentLevel > maxLevel)
				// Alright we can break, no more levels to add
				break;
		}
		if (currentLevel > maxLevel)
			return levelUpCount;
		return remaining;
	}

	private RewardDefinition getReward(ArrayList<RewardDefinition> rewards, int currentLevel, int totXp) {
		// Find rewards that are possible with the current level
		for (RewardDefinition reward : rewards) {
			int weight = reward.weight;
			if (reward.weightEval != null) {
				// Evaluate weight
				ExpressionBuilder builder = new ExpressionBuilder(reward.weightEval);
				builder.variables("level", "lastlevel", "totalxp");
				Expression exp = builder.build();
				exp.setVariable("level", currentLevel);
				exp.setVariable("lastlevel", currentLevel - 1);
				exp.setVariable("totalxp", totXp);
				weight = (int) exp.evaluate();
			}

			// Check quantity
			if (weight > 0) {
				int quantity = reward.itemQuantity;
				if (reward.quantityEval != null) {
					// Evaluate quantity
					ExpressionBuilder builder = new ExpressionBuilder(reward.quantityEval);
					builder.variables("level", "lastlevel", "totalxp");
					Expression exp = builder.build();
					exp.setVariable("level", currentLevel);
					exp.setVariable("lastlevel", currentLevel - 1);
					exp.setVariable("totalxp", totXp);
					quantity = (int) exp.evaluate();
				} else if (reward.quantityMin != -1 && reward.quantityMax != -1) {
					// Random quantity
					quantity = rnd.nextInt(reward.quantityMax + 1);
					while (quantity < reward.quantityMin)
						quantity = rnd.nextInt(reward.quantityMax + 1);
				}

				// Check result
				RewardDefinition result = new RewardDefinition();
				result.itemDefId = reward.itemDefId;
				result.itemQuantity = quantity;
				result.weight = weight;
				return result;
			}
		}
		return null;
	}

	@Override
	public void onWorldJoin(Player player) {
	}

	@Override
	public void removeXP(int xp) {
		// Add fields
		XpUpdatePacket packet = new XpUpdatePacket();
		packet.userId = account.getAccountID();
		packet.previous = new XpUpdatePacket.Level();
		packet.previous.level = getLevel();
		packet.previous.levelUpXp = getLevelupXPCount();
		packet.previous.xp = getCurrentXP();
		packet.current = new XpUpdatePacket.Level();

		// Get new xp count
		if (getCurrentXP() - xp < 0)
			xp = getCurrentXP();
		packet.addedXp = -xp;
		xp = getCurrentXP() - xp;

		// Create level object
		JsonObject levelInfo = new JsonObject();

		// Basics
		levelInfo.addProperty("totalXp", getTotalXP());
		levelInfo.addProperty("currentLevel", getLevel());
		levelInfo.addProperty("currentLevelXp", xp);
		levelInfo.addProperty("currentLevelMaxXp", getLevelupXPCount());

		// Save
		account.getSaveSpecificInventory().setItem("level", levelInfo);

		// Add new level
		packet.current = new XpUpdatePacket.Level();
		packet.current.level = getLevel();
		packet.current.levelUpXp = getLevelupXPCount();
		packet.current.xp = getCurrentXP();
		packet.totalXp = getTotalXP();

		// Send packet
		String room = null;
		if (account.getOnlinePlayerInstance() != null) {
			room = account.getOnlinePlayerInstance().room;
		}
		for (Player plr : Centuria.gameServer.getPlayers()) {
			if (plr.roomReady && plr.room != null && (room == null || plr.room.equals(room))) {
				plr.client.sendPacket(packet);
			}
		}
	}

	@Override
	public void setLevel(int level) {
		// Add fields
		XpUpdatePacket packet = new XpUpdatePacket();
		packet.userId = account.getAccountID();
		packet.addedXp = 0;
		packet.previous = new XpUpdatePacket.Level();
		packet.previous.level = getLevel();
		packet.previous.levelUpXp = getLevelupXPCount();
		packet.previous.xp = getCurrentXP();
		packet.current = new XpUpdatePacket.Level();

		// Create level object
		JsonObject levelInfo = new JsonObject();

		// Evaluate the curve
		int tXP = 0;
		int lUpXp = 0;
		for (int i = 0; i < level; i++) {
			ExpressionBuilder builder = new ExpressionBuilder(levelCurveEval);
			builder.variables("level", "lastlevel", "totalxp");
			Expression exp = builder.build();
			exp.setVariable("level", i + 1);
			exp.setVariable("lastlevel", i);
			exp.setVariable("totalxp", tXP);
			int levelUpCount = (int) exp.evaluate();
			if (levelUpCount > 15000)
				levelUpCount = 15000;
			tXP += levelUpCount;
			lUpXp = levelUpCount;
		}

		// Basics
		levelInfo.addProperty("totalXp", tXP);
		levelInfo.addProperty("currentLevel", level);
		levelInfo.addProperty("currentLevelXp", 0);
		levelInfo.addProperty("currentLevelMaxXp", lUpXp);

		// Save
		account.getSaveSpecificInventory().setItem("level", levelInfo);

		// Add new level
		packet.current = new XpUpdatePacket.Level();
		packet.current.level = getLevel();
		packet.current.levelUpXp = getLevelupXPCount();
		packet.current.xp = getCurrentXP();
		packet.totalXp = getTotalXP();

		// Send packet
		String room = null;
		if (account.getOnlinePlayerInstance() != null) {
			room = account.getOnlinePlayerInstance().room;
		}
		for (Player plr : Centuria.gameServer.getPlayers()) {
			if (plr.roomReady && plr.room != null && (room == null || plr.room.equals(room))) {
				plr.client.sendPacket(packet);
			}
		}
	}

	@Override
	public void addLevel(int levels) {
		int xpToAdd = 0;
		int level = getLevel();
		int tXP = getTotalXP();
		for (int i = 0; i < levels; i++) {
			ExpressionBuilder builder = new ExpressionBuilder(levelCurveEval);
			builder.variables("level", "lastlevel", "totalxp");
			Expression exp = builder.build();
			exp.setVariable("level", level);
			exp.setVariable("lastlevel", level - 1);
			exp.setVariable("totalxp", tXP);
			int levelUpCount = (int) exp.evaluate();
			if (levelUpCount > 15000)
				levelUpCount = 15000;

			tXP += levelUpCount;
			xpToAdd += levelUpCount;
			level++;
		}

		addXP(xpToAdd);
	}

	@Override
	public void resetLevelXP() {
		// Add fields
		XpUpdatePacket packet = new XpUpdatePacket();
		packet.userId = account.getAccountID();
		packet.addedXp = 0;
		packet.previous = new XpUpdatePacket.Level();
		packet.previous.level = getLevel();
		packet.previous.levelUpXp = getLevelupXPCount();
		packet.previous.xp = getCurrentXP();
		packet.current = new XpUpdatePacket.Level();

		// Create level object
		JsonObject levelInfo = new JsonObject();

		// Evaluate the curve
		int tXP = 0;
		int lUpXp = 0;
		int level = getLevel();
		for (int i = 0; i < level; i++) {
			ExpressionBuilder builder = new ExpressionBuilder(levelCurveEval);
			builder.variables("level", "lastlevel", "totalxp");
			Expression exp = builder.build();
			exp.setVariable("level", i + 1);
			exp.setVariable("lastlevel", i);
			exp.setVariable("totalxp", tXP);
			int levelUpCount = (int) exp.evaluate();
			if (levelUpCount > 15000)
				levelUpCount = 15000;
			tXP += levelUpCount;
			lUpXp = levelUpCount;
		}

		// Basics
		levelInfo.addProperty("totalXp", tXP);
		levelInfo.addProperty("currentLevel", level);
		levelInfo.addProperty("currentLevelXp", 0);
		levelInfo.addProperty("currentLevelMaxXp", lUpXp);

		// Save
		account.getSaveSpecificInventory().setItem("level", levelInfo);

		// Add new level
		packet.current = new XpUpdatePacket.Level();
		packet.current.level = getLevel();
		packet.current.levelUpXp = getLevelupXPCount();
		packet.current.xp = getCurrentXP();
		packet.totalXp = getTotalXP();

		// Send packet
		String room = null;
		if (account.getOnlinePlayerInstance() != null) {
			room = account.getOnlinePlayerInstance().room;
		}
		for (Player plr : Centuria.gameServer.getPlayers()) {
			if (plr.roomReady && plr.room != null && (room == null || plr.room.equals(room))) {
				plr.client.sendPacket(packet);
			}
		}
	}

}
