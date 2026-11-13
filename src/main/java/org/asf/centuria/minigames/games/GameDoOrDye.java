package org.asf.centuria.minigames.games;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.asf.centuria.Centuria;
import org.asf.centuria.accounts.CenturiaAccount;
import org.asf.centuria.data.XtReader;
import org.asf.centuria.data.XtWriter;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.entities.uservars.UserVarValue;
import org.asf.centuria.enums.minigames.CodeColor;
import org.asf.centuria.interactions.modules.ResourceCollectionModule;
import org.asf.centuria.interactions.modules.resourcecollection.rewards.LootInfo;
import org.asf.centuria.levelevents.LevelEvent;
import org.asf.centuria.levelevents.LevelEventBus;
import org.asf.centuria.minigames.AbstractMinigame;
import org.asf.centuria.minigames.MinigameMessage;
import org.asf.centuria.packets.xt.gameserver.inventory.InventoryItemDownloadPacket;
import org.asf.centuria.packets.xt.gameserver.minigame.MinigameCurrencyPacket;
import org.asf.centuria.packets.xt.gameserver.minigame.MinigameMessagePacket;
import org.asf.centuria.packets.xt.gameserver.minigame.MinigamePrizePacket;
import org.asf.centuria.util.CombinationSum;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class GameDoOrDye extends AbstractMinigame {

	public int level;
	public ArrayList<CodeColor> solution = new ArrayList<>();
	public ArrayList<CodeColor> dyesOnScreen = new ArrayList<>();
	public ArrayList<CodeColor> availableDyes = new ArrayList<>();
	public int startingIngredientCount;
	public int scorePerIngredient;
	public boolean multipleGuesses;

	public int scoreThreeStars;
	public int scoreTwoStars;
	public int scoreOneStar;

	public class RewardData {
		public String id;
		public int count;
	}

	private ArrayList<RewardData> rewardsOneStar = new ArrayList<RewardData>();
	private ArrayList<RewardData> rewardsTwoStars = new ArrayList<RewardData>();
	private ArrayList<RewardData> rewardsThreeStars = new ArrayList<RewardData>();
	private HashMap<Integer, Integer> timeBonuses = new HashMap<Integer, Integer>();

	private void addLoot(LootInfo reward, ArrayList<RewardData> rewards, CenturiaAccount acc) {
		if (reward.reward.referencedTableId != null) {
			// Find referenced table
			LootInfo ref = ResourceCollectionModule.getLootReward(reward.reward.referencedTableId, acc);
			if (ref != null)
				addLoot(ref, rewards, acc);
		}

		// Add items to memory
		if (reward.reward.itemId != null) {
			RewardData data = new RewardData();
			data.id = reward.reward.itemId;
			data.count = reward.count;
			rewards.add(data);
		}
	}

	@Override
	public boolean canHandle(String levelID) {
		return levelID.equals("5054");
	}

	@Override
	public void onJoin(Player plr) {
		MinigameCurrencyPacket currency = new MinigameCurrencyPacket();
		currency.Currency = "1141";
		plr.client.sendPacket(currency);
	}

	@Override
	public void onExit(Player plr) {
	}

	@MinigameMessage("startLevel")
	public void startLevel(Player plr, XtReader rd) {

		// Deserialize
		level = rd.readInt();

		// reset
		availableDyes.clear();
		solution.clear();
		dyesOnScreen.clear();
		multipleGuesses = false;
		rewardsOneStar.clear();
		rewardsTwoStars.clear();
		rewardsThreeStars.clear();
		timeBonuses.clear();

		// load level def from json
		try {
			InputStream strm = InventoryItemDownloadPacket.class.getClassLoader()
					.getResourceAsStream("minigames/doordye.json");
			JsonObject helper = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8")).getAsJsonObject()
					.get("Levels").getAsJsonArray().get(level).getAsJsonObject();
			strm.close();

			int codeLength = helper.get("length").getAsInt();
			int colors = helper.get("colors").getAsInt();
			boolean allowRepeatColors = helper.get("allowRepeat").getAsBoolean();
			startingIngredientCount = helper.get("ingredientCount").getAsInt();
			scorePerIngredient = helper.get("score").getAsInt();
			scoreOneStar = helper.get("oneStarScore").getAsInt();
			scoreTwoStars = helper.get("twoStarScore").getAsInt();
			scoreThreeStars = helper.get("threeStarScore").getAsInt();

			// Load time bonuses
			JsonObject bonuses = helper.get("timeBonus").getAsJsonObject();
			for (String bonus : bonuses.keySet()) {
				timeBonuses.put(Integer.parseInt(bonus), bonuses.get(bonus).getAsInt());
			}

			// Load rewards
			int rewardIndex = 0;
			for (JsonElement ele : helper.get("reward").getAsJsonArray()) {
				String reward = ele.getAsString();
				ArrayList<RewardData> rewardList = null;
				switch (rewardIndex) {
				case 0:
					rewardList = rewardsOneStar;
					break;
				case 1:
					rewardList = rewardsTwoStars;
					break;
				case 2:
					rewardList = rewardsThreeStars;
					break;
				}
				rewardIndex++;

				// load reward
				LootInfo loot = ResourceCollectionModule.getLootReward(reward, plr.account);
				if (loot != null)
					addLoot(loot, rewardList, plr.account);
			}

			// load available dyes from sum
			int[] arr = { 1, 2, 4, 8, 16, 32, 64, 128, 256 };
			List<List<Integer>> result = new ArrayList<>();
			result = CombinationSum.Sum(arr, colors);
			List<Integer> finalResult = result.get(0);

			for (int i = 0; i < finalResult.size(); i++) {
				CodeColor color = CodeColor.valueOf(finalResult.get(i));
				availableDyes.add(color);
			}

			// generate solution
			if (allowRepeatColors) {
				while (solution.size() < codeLength) {
					int index = (int) (Math.random() * availableDyes.size());
					solution.add(availableDyes.get(index));
				}
			} else {
				ArrayList<CodeColor> used = new ArrayList<CodeColor>();
				while (solution.size() < codeLength) {
					int index = (int) (Math.random() * availableDyes.size());
					if (used.contains(availableDyes.get(index)) == false) {
						solution.add(availableDyes.get(index));
						used.add(availableDyes.get(index));
					}
				}
			}
			Centuria.logger.debug("solution: " + solution);
		} catch (IOException e) {
		}

		// add to screen
		for (int i = 0; i < availableDyes.size(); i++) {
			for (int y = 0; y < startingIngredientCount; y++) {
				dyesOnScreen.add(availableDyes.get(i));
			}
		}

		Centuria.logger.debug("on screen " + dyesOnScreen.toString());
		Centuria.logger.debug("solution " + solution.toString());

		MinigameMessagePacket msg = new MinigameMessagePacket();
		msg.command = "startLevel";
		msg.data = Integer.toString(level);
		plr.client.sendPacket(msg);
	}

	@MinigameMessage("guess")
	public void handleGuess(Player plr, XtReader rd) throws IOException {
		// Deserialize
		int levelTimer = rd.readInt();
		int codeSize = rd.readInt();

		ArrayList<CodeColor> sequence = new ArrayList<>();
		for (int i = 0; i < codeSize; i++) {
			CodeColor color = CodeColor.valueOf(rd.readInt());
			sequence.add(color);
		}
		Centuria.logger.debug("time:" + levelTimer + " code:" + sequence.toString());

		// Handle

		// Remove used dyes
		for (int i = 0; i < sequence.size(); i++) {
			if (dyesOnScreen.contains(sequence.get(i))) {
				dyesOnScreen.remove(sequence.get(i));
			}
		}

		ArrayList<CodeColor> remainingSolution = new ArrayList<CodeColor>(solution);

		// Right ingredient right order
		int correctPositons = 0;
		for (int i = 0; i < sequence.size(); i++) {
			if (sequence.get(i).equals(solution.get(i))) {
				correctPositons++;
				remainingSolution.remove(sequence.get(i));
			}
		}

		// Right ingredient wrong order
		int wrongPositions = 0;
		for (int i = 0; i < sequence.size(); i++) {
			if (!(sequence.get(i).equals(solution.get(i))) && remainingSolution.contains(sequence.get(i))) {
				wrongPositions++;
				remainingSolution.remove(sequence.get(i));
			}
		}

		// Check if win
		boolean win = false;
		if (sequence.equals(solution)) {
			win = true;
		}

		// Check if lose
		boolean lose = false;
		if (!dyesOnScreen.containsAll(solution)) {
			lose = true;
		}

		// Send hint
		XtWriter wr = new XtWriter();
		wr.writeInt(correctPositons); // CorrectPositions
		wr.writeInt(wrongPositions); // WrongPositions
		MinigameMessagePacket pk = new MinigameMessagePacket();
		pk.command = "hint";
		pk.data = wr.encode().substring(4);
		plr.client.sendPacket(pk);

		Centuria.logger.debug("CorrectPositions:" + correctPositons + " WrongPositions:" + wrongPositions);
		Centuria.logger.debug(dyesOnScreen.toString());

		if (lose & !win) {
			XtWriter wr2 = new XtWriter();
			wr2.writeInt(solution.size()); // length
			solution.forEach((n) -> wr2.writeInt(n.getValue())); // code

			MinigameMessagePacket pk2 = new MinigameMessagePacket();
			pk2.command = "endLevelLose";
			pk2.data = wr2.encode().substring(4);
			plr.client.sendPacket(pk2);
		}

		if (win) {
			// Calculate time bonus
			int timeBonusScore = 0;
			int secondsSpent = levelTimer / 1000;

			// Find best bonus
			int lastBonusVal = 0;
			int lastBonusLimit = 0;
			int highestBonusLimit = 0;
			for (int limit : timeBonuses.keySet()) {
				if (limit >= highestBonusLimit)
					highestBonusLimit = limit;
				if (secondsSpent <= limit && (limit < lastBonusLimit || lastBonusLimit == 0)) {
					lastBonusLimit = limit;
					lastBonusVal = timeBonuses.get(limit);
				}
			}

			// Calculate reward
			if (lastBonusVal != 0) {
				int bonusSecs = highestBonusLimit - secondsSpent;
				timeBonusScore = bonusSecs * lastBonusVal;
			}

			// Unlock level
			UserVarValue unlock = plr.account.getSaveSpecificInventory().getUserVarAccesor().getPlayerVarValue("9100",
					0);
			int value = 0;
			if (unlock != null) {
				value = unlock.value;
			}
			if (level > value) {
				plr.account.getSaveSpecificInventory().getUserVarAccesor().setPlayerVarValue("9100", 0, level);
			}

			// Calculate score
			int ingredientScore = dyesOnScreen.size() * scorePerIngredient;
			int firstGuessBonus = multipleGuesses ? 0 : 100;
			int lastIngredientBonus = lose ? 100 : 0;
			int totalScore = ingredientScore + firstGuessBonus + lastIngredientBonus + timeBonusScore;

			// Save score
			UserVarValue var = plr.account.getSaveSpecificInventory().getUserVarAccesor().getPlayerVarValue("9101",
					level);
			int value2 = 0;
			if (var != null)
				value2 = var.value;
			if (totalScore > value2)
				plr.account.getSaveSpecificInventory().getUserVarAccesor().setPlayerVarValue("9101", level, totalScore);

			// Update client
			for (String change : plr.account.getSaveSpecificInventory().getAccessor().getChangedInventories())
				plr.account.getSaveSpecificInventory().getAccessor().transferUpdatedItemsToPlayer(plr, change);

			// Send win
			XtWriter wr1 = new XtWriter();
			wr1.writeInt(ingredientScore); // IngredientScore
			wr1.writeInt(timeBonusScore); // TimeScore
			wr1.writeInt(lastIngredientBonus); // LastIngredientBonus
			wr1.writeInt(firstGuessBonus); // firstGuessBonus
			wr1.writeString("null"); // ?
			MinigameMessagePacket pk1 = new MinigameMessagePacket();
			pk1.command = "endLevelWin";
			pk1.data = wr1.encode().substring(4);
			plr.client.sendPacket(pk1);

			// Get star count
			int stars = 0;
			if (totalScore >= scoreThreeStars)
				stars = 3;
			else if (totalScore >= scoreTwoStars)
				stars = 2;
			else if (totalScore >= scoreOneStar)
				stars = 1;

			// Give reward
			if (stars > 0) {
				// Find rewards
				for (int i = 0; i < stars; i++) {
					ArrayList<RewardData> rewards = null;
					switch (i) {
					case 0:
						rewards = rewardsOneStar;
						break;
					case 1:
						rewards = rewardsTwoStars;
						break;
					case 2:
						rewards = rewardsThreeStars;
						break;
					}

					// Add rewards
					for (RewardData reward : rewards) {
						plr.account.getSaveSpecificInventory().getItemAccessor(plr).add(reward.id, reward.count);

						// Send packet
						MinigamePrizePacket p1 = new MinigamePrizePacket();
						p1.given = true;
						p1.itemDefId = reward.id;
						p1.itemCount = reward.count;
						p1.prizeIndex1 = 0;
						p1.prizeIndex2 = 3;
						plr.client.sendPacket(p1);
					}
				}

				// XP
				LevelEventBus.dispatch(
						new LevelEvent("levelevents.minigames.doordye", new String[] { "level:" + level + 1 }, plr));
			}
			rewardsOneStar.clear();
			rewardsTwoStars.clear();
			rewardsThreeStars.clear();
		}

		if (!lose & !win) {
			multipleGuesses = true;
		}

	}

	@Override
	public AbstractMinigame instantiate() {
		return new GameDoOrDye();
	}

}