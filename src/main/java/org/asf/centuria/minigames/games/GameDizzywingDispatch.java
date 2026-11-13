package org.asf.centuria.minigames.games;

import java.util.Queue;
import java.util.Random;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.asf.centuria.Centuria;
import org.asf.centuria.data.XtReader;
import org.asf.centuria.data.XtWriter;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.entities.uservars.UserVarValue;
import org.asf.centuria.minigames.AbstractMinigame;
import org.asf.centuria.minigames.MinigameMessage;
import org.asf.centuria.packets.xt.gameserver.inventory.InventoryItemDownloadPacket;
import org.asf.centuria.packets.xt.gameserver.minigame.MinigameCurrencyPacket;
import org.asf.centuria.packets.xt.gameserver.minigame.MinigameMessagePacket;
import org.asf.centuria.packets.xt.gameserver.minigame.MinigamePrizePacket;
import org.asf.centuria.interactions.modules.ResourceCollectionModule;
import org.asf.centuria.interactions.modules.resourcecollection.rewards.LootInfo;
import org.asf.centuria.levelevents.LevelEvent;
import org.asf.centuria.levelevents.LevelEventBus;
import org.joml.Vector2i;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class GameDizzywingDispatch extends AbstractMinigame {

	private String currentGameUUID;
	public GameState gameState;
	public PuzzleObjectives puzzleObjectives;
	public int level = 0;
	private int score = 0;
	private int moveCount = 0;
	private int dizzyBirdMeter = 0;

	static private JsonArray specialOrders;
	static private JsonArray specialOrderCountRanges;
	static private JsonArray levelRewards;
	static private JsonArray puzzleRewards;
	static private JsonArray achievementToUserVarIndexList;

	static {
		// Load level info
		try {
			// Load all level info into a JsonObject
			InputStream strm = InventoryItemDownloadPacket.class.getClassLoader()
					.getResourceAsStream("minigames/dizzywingdispatch.json");
			JsonObject helper = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8")).getAsJsonObject();
			strm.close();

			// Seperate the level data by Json key
			specialOrders = helper.getAsJsonArray("specialOrders");
			specialOrderCountRanges = helper.getAsJsonArray("specialOrderCountRanges");
			levelRewards = helper.getAsJsonArray("levelRewards");
			puzzleRewards = helper.getAsJsonArray("puzzleRewards");
			achievementToUserVarIndexList = helper.getAsJsonArray("achievementToUserVarIndexList");

		} catch (IOException e) {
			// This is very bad, should not start allow the server to continue otherwise
			// things will break HARD
			throw new RuntimeException(e);
		}

		/*
		 * if (Centuria.debugMode) { DDVis vis = new DDVis(); new Thread(() ->
		 * vis.frame.setVisible(true)).start(); }
		 */
	}

	// Types of puzzles objectives to be kept track by the server
	enum PuzzleObjectiveType {
		HighScore(0), TotalScore(1), MakeFlyers(2), MakeFlyers_SingleGame(3), MakeBombBirds(4),
		MakeBombBirds_SingleGame(5), MakePeacocks(6), MakePeacocks_SingleGame(7), ClearRedBirds(8),
		ClearRedBirds_SingleGame(9), ClearBlueBirds(10), ClearBlueBirds_SingleGame(11), ClearGreenBirds(12),
		ClearGreenBirds_SingleGame(13), ClearYellowBirds(14), ClearYellowBirds_SingleGame(15), ClearPinkBirds(17),
		ClearPinkBirds_SingleGame(18), ClearWhiteBirds(19), ClearWhiteBirds_SingleGame(20), ClearPurpleBirds(21),
		ClearPurpleBirds_SingleGame(22), ClearAquaBirds(23), ClearAquaBirds_SingleGame(24), CompleteOrders(25),
		CompleteOrders_SingleGame(26), CompleteSpecialOrders(27), CompleteSpecialOrders_SingleGame(28),
		CompleteRushHourOrders(29), CompleteRushHourOrders_SingleGame(31), CompleteShortStaffedOrders(32),
		CompleteShortStaffedOrders_SingleGame(33), CompleteComboOrders(34), CompleteComboOrders_SingleGame(35),
		ClearedWithPeacockRed(36), ClearedWithPeacockRed_SingleGame(37), ClearedWithPeacockBlue(38),
		ClearedWithPeacockBlue_SingleGame(39), ClearedWithPeacockGreen(40), ClearedWithPeacockGreen_SingleGame(41),
		ClearedWithPeacockYellow(42), ClearedWithPeacockYellow_SingleGame(43), ClearedWithPeacockPink(44),
		ClearedWithPeacockPink_SingleGame(45), ClearedWithPeacockWhite(46), ClearedWithPeacockWhite_SingleGame(47),
		ClearedWithPeacockPurple(48), ClearedWithPeacockPurple_SingleGame(49), ClearedWithPeacockAqua(50),
		ClearedWithPeacockAqua_SingleGame(51), HatchPowerups(52), HatchPowerups_SingleGame(53), ComboFlyerBomb(54),
		ComboFlyerBomb_SingleGame(55), ComboFlyerPeacock(56), ComboFlyerPeacock_SingleGame(57), ComboBombPeacock(58),
		ComboBombPeacock_SingleGame(59), ComboFlyerFlyer(60), ComboFlyerFlyer_SingleGame(61), ComboBombBomb(62),
		ComboBombBomb_SingleGame(63), ComboPeacockPeacock(64), ComboPeacockPeacock_SingleGame(65);

		private final int value;

		private PuzzleObjectiveType(int value) {
			this.value = value;
		}

		public int getVal() {
			return value;
		}

	}

	// Short hand for constants used in saving to player inventory
	enum UserVarIDs {
		persistentAchievementDataUserVarDefId("13392"), puzzleRedemptionStatusUserVarDefId("13404"),
		puzzlePieceRedemptionStatusUserVarDefId("13405"), savedGameUserVarDefId("30613"), tutorial("13624"),
		userVarInventory("303");

		private final String value;

		private UserVarIDs(String value) {
			this.value = value;
		}

		public String getVal() {
			return value;
		}

	}

	// all game logic is stored in this class
	public class GameState {

		// helper classes

		// represents a tile in the game board
		public class GridCell {
			private int tileHealth;
			private TileType TileType;
			private BoosterType Booster;

			public GridCell(int health, TileType tileType, BoosterType booster) {
				tileHealth = health;
				TileType = tileType;
				Booster = booster;
			}

			public void setTileType(TileType tileType) {
				TileType = tileType;
			}

			public TileType getTileType() {
				return TileType;
			}

			public void setBooster(BoosterType booster) {
				Booster = booster;
			}

			public BoosterType getBooster() {
				return Booster;
			}

			public void setHealth(int health) {
				tileHealth = health;
			}

			public int getHealth() {
				return tileHealth;
			}

			public boolean isBoosted() {
				return Booster != BoosterType.None;
			}

		}

		// keep tracks of the types of orders that need to be cleared before a level may
		// end
		public class LevelObjectives {
			public int objectivesTracker[][] = { { -1, -1, 0 }, { -1, -1, 1 }, { -1, -1, 2 }, { -1, -1, 3 } };

			JsonObject specialOrderData;
			JsonObject specialOrderCountRangeData;

			int currScore = 0;

			public LevelObjectives() {
				newLevelNewObjectives();
			}

			public void newLevelNewObjectives() {

				currScore = 0;
				if (level != 0) {

					// get level data for the level range the current level falls into

					for (JsonElement ele : specialOrders) {
						JsonObject data = ele.getAsJsonObject();
						if ((level >= data.get("_fromLevelNumber").getAsInt()
								&& level <= data.get("_toLevelNumber").getAsInt())
								|| data.get("_isToLevelInfinite").getAsBoolean()) {

							specialOrderData = data;
							break;
						}
					}

					for (JsonElement ele : specialOrderCountRanges) {
						JsonObject data = ele.getAsJsonObject();
						if ((level >= data.get("_fromLevelNumber").getAsInt()
								&& level <= data.get("_toLevelNumber").getAsInt())
								|| data.get("_isToLevelInfinite").getAsBoolean()) {

							specialOrderCountRangeData = data;
							break;
						}
					}
				} else {
					specialOrderData = specialOrders.get(0).getAsJsonObject();
					specialOrderCountRangeData = specialOrderCountRanges.get(0).getAsJsonObject();
				}

				// clear the score objective
				initObjective(LevelObjectiveType.ScoreRequirement, scoreRequirement());

				// randomly pick objectives to be given to the player
				if (!diceRoll("_regularLevelAppearancePercent")) {

					int clothing = 0;
					int eggs = 0;

					if (diceRoll("_accessoriesAppearancePercent")) {
						clothing = initObjective(LevelObjectiveType.ClothingLeft, "_minimumAccessoryCount",
								"_maximumAccessoryCount");
					} else {
						clearObjective(LevelObjectiveType.ClothingLeft);
					}

					if (diceRoll("_eggsAppearancePercent")) {
						eggs = initObjective(LevelObjectiveType.EggsLeft, "_minimumEggRowCount", "_maximumEggRowCount");
					} else {
						clearObjective(LevelObjectiveType.EggsLeft);
					}

					if (diceRoll("_limitedMovesAppearancePercent")) {
						initObjective(LevelObjectiveType.MovesLeft, "_minimumLimitedMovesOnlyCount",
								"_maximumLimitedMovesOnlyCount");
					} else {
						clearObjective(LevelObjectiveType.MovesLeft);
					}

					// Add clothing and eggs to the board if the respective objectives are given
					for (int y = gridSize.y - 1; y >= 0; y--) {
						for (int x = gridSize.x - 1; x >= 0; x--) {
							Vector2i curr = new Vector2i(x, y);
							GridCell currCell = getCell(curr);

							if (!currCell.isBoosted()) {
								if (currCell.getTileType() != TileType.HatOrPurse && !currCell.isBoosted()
										&& clothing > 0) {

									currCell.setTileType(TileType.HatOrPurse);
									setCell(curr, currCell);
									clothing--;
								} else if (currCell.getHealth() == 0 && !currCell.isBoosted() && eggs > 0) {
									currCell.setHealth(1);
									setCell(curr, currCell);
									eggs--;
								}
							}
						}
					}

				}

			}

			public boolean isNextLevel() { // returns true if level has increased
				trackEggsAndClothing();

				boolean goToNextLevel = true;
				goToNextLevel &= isAchieved(LevelObjectiveType.ClothingLeft);
				goToNextLevel &= isAchieved(LevelObjectiveType.EggsLeft);
				goToNextLevel &= isAchieved(LevelObjectiveType.ScoreRequirement);
				goToNextLevel &= !hasRunOutOfMoves();
				goToNextLevel &= isObjective(LevelObjectiveType.ScoreRequirement);

				if (goToNextLevel) {
					level++;

					// update puzzle objectives

					int numberOfOrders = 0;

					if (isAchieved(LevelObjectiveType.ClothingLeft) && isObjective(LevelObjectiveType.ClothingLeft)) {
						puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.CompleteSpecialOrders);
						puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.CompleteSpecialOrders_SingleGame);
						numberOfOrders++;
					}
					if (isAchieved(LevelObjectiveType.MovesLeft) && isObjective(LevelObjectiveType.MovesLeft)) {
						puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.CompleteRushHourOrders);
						puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.CompleteRushHourOrders_SingleGame);
						numberOfOrders++;
					}
					if (isAchieved(LevelObjectiveType.EggsLeft) && isObjective(LevelObjectiveType.EggsLeft)) {
						puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.CompleteShortStaffedOrders);
						puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.CompleteShortStaffedOrders_SingleGame);
						numberOfOrders++;
					}
					if (numberOfOrders > 1) {
						puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.CompleteComboOrders);
						puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.CompleteComboOrders_SingleGame);
					}
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.CompleteOrders);
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.CompleteOrders_SingleGame);

					// add new bird types to be placed on to the board

					if (level > 25 && !spawnTiles.contains(TileType.AquaBird)) {
						spawnTiles.add(TileType.AquaBird);
					} else if (level > 75 && !spawnTiles.contains(TileType.BlueBird)) {
						spawnTiles.add(TileType.BlueBird);
					} else if (level > 100 && !spawnTiles.contains(TileType.PinkBird)) {
						spawnTiles.add(TileType.PinkBird);
					}

					// perform a loot table lookup to give a reward to the player

					for (JsonElement ele : levelRewards) {
						JsonObject levelData = (JsonObject) ele;
						JsonArray rewardData = levelData.getAsJsonArray("lootData");

						int randNo = randomizer.nextInt(100);
						int sumWeight = 0;

						if ((level >= levelData.get("levelIndex").getAsInt()
								&& level <= levelData.get("endLevelIndex").getAsInt())
								|| levelData.get("isEndLevelInfinite").getAsBoolean()) {

							for (JsonElement eleReward : rewardData) {
								JsonObject reward = (JsonObject) eleReward;
								if (sumWeight < randNo && randNo <= sumWeight + reward.get("weight").getAsInt()) {
									String lootTableDefID = reward.get("lootTableDefID").getAsString();
									Centuria.logger.debug(lootTableDefID, "lootTableDefId");
									LootInfo chosenReward = ResourceCollectionModule.getLootReward(lootTableDefID, player.account);

									// Give reward
									String itemId = chosenReward.reward.itemId;
									while (itemId == null) {
										chosenReward = ResourceCollectionModule
												.getLootReward(chosenReward.reward.referencedTableId, player.account);
										itemId = chosenReward.reward.itemId;
									}
									Centuria.logger.debug(itemId, "itemID");

									int count = chosenReward.count;

									player.account.getSaveSpecificInventory().getItemAccessor(player).add(itemId,
											count);

									// XP
									LevelEventBus.dispatch(new LevelEvent("levelevents.minigames.dizzywingdispatch",
											new String[] { "level:" + level }, player));

									if (lootTableDefID != "13629") {
										// Send packet
										MinigamePrizePacket p1 = new MinigamePrizePacket();
										p1.given = true;
										p1.itemDefId = itemId;
										p1.itemCount = count;
										p1.given = true;
										p1.prizeIndex1 = 0;
										p1.prizeIndex2 = 0;
										player.client.sendPacket(p1);
									} else {
										MinigameCurrencyPacket currency = new MinigameCurrencyPacket();
										currency.Currency = itemId;
										player.client.sendPacket(currency);
									}

									break;

								} else {
									sumWeight += reward.get("weight").getAsInt();
								}

							}
							break;

						}
					}

					// get the objectives for the new level
					newLevelNewObjectives();
				}

				return goToNextLevel;
			}

			public void addScore(int increase) { // called externally to keep track of score
				currScore += increase;
				updateObjective(LevelObjectiveType.ScoreRequirement, currScore);
			}

			public void trackMoves() { // called externally to keep track of moves remaining
				if (isObjective(LevelObjectiveType.MovesLeft)) {
					updateObjectiveByChange(LevelObjectiveType.MovesLeft, 1);
				}
			}

			public boolean hasRunOutOfMoves() {
				if (isObjective(LevelObjectiveType.MovesLeft)) {
					return objectivesTracker[LevelObjectiveType.MovesLeft.ordinal()][1] < 0;
				} else {
					return false;
				}
			}

			private void trackEggsAndClothing() { // called to keep track of remaining eggs and clothing
				if (isObjective(LevelObjectiveType.EggsLeft)) {
					int numberOfEggs = 0;
					for (int x = 0; x < gridSize.x; x++) {
						for (int y = 0; y < gridSize.y; y++) {
							Vector2i curr = new Vector2i(x, y);

							if (getCell(curr).getHealth() > 0) {
								numberOfEggs++;
							}
						}
					}
					updateObjectiveRemaining(LevelObjectiveType.EggsLeft, numberOfEggs);
				}

				if (isObjective(LevelObjectiveType.ClothingLeft)) {
					int numberOfClothing = 0;
					for (int x = 0; x < gridSize.x; x++) {
						for (int y = 0; y < gridSize.y; y++) {
							Vector2i curr = new Vector2i(x, y);

							if (getCell(curr).getTileType() == TileType.HatOrPurse) {
								numberOfClothing++;
							}
						}
					}
					updateObjectiveRemaining(LevelObjectiveType.ClothingLeft, numberOfClothing);
				}
			}

			// helper functions

			private boolean diceRoll(String attribute) { // probability of objectives is expressed as percentages
				return randomizer.nextInt(100) < specialOrderData.get(attribute).getAsInt();
			}

			private boolean isObjective(LevelObjectiveType objectiveType) { // check if objective has been assigned to
																			// the player
				return objectivesTracker[objectiveType.ordinal()][0] != -1;
			}

			private boolean isAchieved(LevelObjectiveType objectiveType) { // returns true if objective is fulfilled or
																			// not assigned
				if (objectivesTracker[objectiveType.ordinal()][0] == -1) {
					return true;
				} else if (objectivesTracker[objectiveType.ordinal()][0] <= objectivesTracker[objectiveType
						.ordinal()][1]) {
					return true;
				} else {
					return false;
				}
			}

			private int initObjective(LevelObjectiveType objectiveType, String minimum, String maximum) { // generates
																											// objective
																											// given
																											// names of
																											// Json
																											// elements
																											// that
																											// contain
																											// level
																											// data

				int goal = randomizer.nextInt(specialOrderCountRangeData.get(minimum).getAsInt(),
						specialOrderCountRangeData.get(maximum).getAsInt() + 1);
				if (goal > 0) {
					objectivesTracker[objectiveType.ordinal()][0] = goal;
					objectivesTracker[objectiveType.ordinal()][1] = 0;
				} else {
					objectivesTracker[objectiveType.ordinal()][0] = -1;
					objectivesTracker[objectiveType.ordinal()][1] = -1;
				}

				return objectivesTracker[objectiveType.ordinal()][0];
			}

			private int scoreRequirement() { // formula for score required to pass a level

				int r = 50; // round to the nearest r
				int s = 500; // starting level score
				Float g = 2.5f; // growth rate
				int o = 125; // offset

				return (int) (r * Math.round((s * Math.log1p(g * (level + o) - g + 1f) + s) / r));
			}

			// These are internal functions for changing objectives in a level or update
			// their progression

			private void initObjective(LevelObjectiveType objectiveType, int requirement) {
				objectivesTracker[objectiveType.ordinal()][0] = requirement;
				objectivesTracker[objectiveType.ordinal()][1] = 0;
			}

			private void clearObjective(LevelObjectiveType objectiveType) {
				objectivesTracker[objectiveType.ordinal()][0] = -1;
				objectivesTracker[objectiveType.ordinal()][1] = -1;
			}

			private void updateObjectiveByChange(LevelObjectiveType objectiveType, int value) {
				objectivesTracker[objectiveType.ordinal()][1] += value;
			}

			private void updateObjectiveRemaining(LevelObjectiveType objectiveType, int value) {
				objectivesTracker[objectiveType.ordinal()][1] = objectivesTracker[objectiveType.ordinal()][0] - value;
			}

			private void updateObjective(LevelObjectiveType objectiveType, int value) {
				objectivesTracker[objectiveType.ordinal()][1] = value;
			}

		}

		// the "power ups" in the game
		enum BoosterType {
			None, BuzzyBirdHorizontal, BuzzyBirdVertical, BoomBird, PrismPeacock
		}

		// the tile "colors" in the game
		enum TileType {
			AquaBird, BlueBird, GreenBird, PinkBird, PurpleBird, RedBird, SnowyBird, YellowBird, HatOrPurse, None
		}

		// the types of orders in a level, including the score equirement
		enum LevelObjectiveType {
			ScoreRequirement, MovesLeft, EggsLeft, ClothingLeft
		}

		// class fields

		// game board data
		private GridCell[][] grid;
		private Vector2i gridSize;

		// used by the flood fill algorithm
		private boolean toVisit[][];
		private boolean visited[][];

		// used to generate new tiles
		private List<TileType> spawnTiles;

		// source of all randomness in the game
		private Random randomizer;

		// used to calculate current score as multiple matches in a row give more points
		private int matchComboScore;

		// used to keep track of level objectives
		public LevelObjectives levelObjectives;

		// to send level rewards to the player
		private Player player;

		public GameState(Player Player) {
			gridSize = new Vector2i(9, 9);
			spawnTiles = new ArrayList<TileType>(Arrays.asList(TileType.GreenBird, TileType.PurpleBird,
					TileType.RedBird, TileType.SnowyBird, TileType.YellowBird));
			randomizer = new Random(currentGameUUID.hashCode());
			initializeGameBoard();
			floodFillClearVisited();
			levelObjectives = new LevelObjectives();
			player = Player;
		}

		// functions related to accessing or modifying game tiles

		public GridCell getCell(Vector2i pos) {
			if (pos.x >= 0 && pos.x < gridSize.x && pos.y >= 0 && pos.y < gridSize.y) {
				return grid[pos.x][pos.y];
			}
			return null;
		}

		public void setCell(Vector2i pos, GridCell cell) {
			if (pos.x >= 0 && pos.x < gridSize.x && pos.y >= 0 && pos.y < gridSize.y && cell != null) {
				grid[pos.x][pos.y] = cell;
			}
		}

		// template function with one less argument to avoid errors
		private void clearCell(Vector2i pos, boolean isScore, boolean forceClear) {
			clearCell(pos, isScore, forceClear, false);
		}

		private void clearCell(Vector2i pos, boolean isScore, boolean forceClear, boolean isClearedByPeacock) {

			if (getCell(pos) == null) {
				return;
			}

			switch (getCell(pos).getTileType()) {
			case RedBird:
				puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearRedBirds);
				puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearRedBirds_SingleGame);
				break;
			case BlueBird:
				puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearBlueBirds);
				puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearBlueBirds_SingleGame);
				break;
			case GreenBird:
				puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearGreenBirds);
				puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearGreenBirds_SingleGame);
				break;
			case YellowBird:
				puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearYellowBirds);
				puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearYellowBirds_SingleGame);
				break;
			case PinkBird:
				puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearPinkBirds);
				puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearPinkBirds_SingleGame);
				break;
			case SnowyBird:
				puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearWhiteBirds);
				puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearWhiteBirds_SingleGame);
				break;
			case PurpleBird:
				puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearPurpleBirds);
				puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearPurpleBirds_SingleGame);
				break;
			case AquaBird:
				puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearAquaBirds);
				puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearAquaBirds_SingleGame);
				break;
			case None:
				break;
			default:
				break;
			}
			if (isClearedByPeacock) {
				switch (getCell(pos).getTileType()) {
				case RedBird:
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearedWithPeacockRed);
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearedWithPeacockRed_SingleGame);
					break;
				case BlueBird:
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearedWithPeacockBlue);
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearedWithPeacockBlue_SingleGame);
					break;
				case GreenBird:
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearedWithPeacockGreen);
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearedWithPeacockGreen_SingleGame);
					break;
				case YellowBird:
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearedWithPeacockYellow);
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearedWithPeacockYellow_SingleGame);
					break;
				case PinkBird:
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearedWithPeacockPink);
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearedWithPeacockPink_SingleGame);
					break;
				case SnowyBird:
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearedWithPeacockWhite);
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearedWithPeacockWhite_SingleGame);
					break;
				case PurpleBird:
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearedWithPeacockPurple);
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearedWithPeacockPurple_SingleGame);
					break;
				case AquaBird:
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearedWithPeacockAqua);
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ClearedWithPeacockAqua_SingleGame);
					break;
				case None:
					break;
				default:
					break;
				}
			}

			if (forceClear) {
				setCell(pos, new GridCell(0, TileType.None, BoosterType.None));
				return;
			} else if (getCell(pos).getHealth() > 0) {
				breakEggTile(pos);
			} else if (getCell(pos).getTileType() != TileType.HatOrPurse
					&& getCell(pos).getBooster() != BoosterType.PrismPeacock) {
				setCell(pos, new GridCell(0, TileType.None, BoosterType.None));
			}

			if (isScore) {
				score += 30;
				dizzyBirdMeter += 30;
			}

			switch (getCell(pos).getBooster()) {
			case BuzzyBirdHorizontal:
				buzzyBirdHorizontalBehaviour(pos, 1);
				break;
			case BuzzyBirdVertical:
				buzzyBirdVerticalBehaviour(pos, 1);
				break;
			case BoomBird:
				boomBirdBehaviour(pos, 3);
				break;
			default:

				break;
			}
		}

		private void breakEggTile(Vector2i pos) {
			GridCell eggTile = getCell(pos);
			if (eggTile != null) {
				eggTile.setHealth(0);
				setCell(pos, eggTile);
				levelObjectives.trackEggsAndClothing();
			}
		}

		private void clearGrid() {
			grid = new GridCell[gridSize.x][gridSize.y];
		}

		// functions that generate some type of string representation of the game board

		public int calculateBoardChecksum() // level of emulation accuracy uncertain
		{
			byte[] inArray = new byte[gridSize.x * gridSize.y];

			for (int x = 0; x < gridSize.x; x++) {
				for (int y = 0; y < gridSize.y; y++) {
					inArray[x + gridSize.x * y] = gridCellByteValueForChecksum(new Vector2i(x, y));
				}
			}

			// Board checksum algorithm!
			String string1 = Base64.getEncoder().encodeToString(inArray);
			String string2 = currentGameUUID.toString();
			String string3 = ((Integer) (moveCount)).toString();
			return (string1 + string2 + string3).hashCode();
		}

		private byte gridCellByteValueForChecksum(Vector2i pos) // level of emulation accuracy uncertain
		{
			int byteValue = 0;
			GridCell cell = getCell(pos);

			int tileTypeValue = cell.getTileType().ordinal();

			if (cell.isBoosted()) {
				byteValue += (cell.isBoosted() ? 1 : 0) * 20 + tileTypeValue;
			} else {
				byteValue += tileTypeValue * 2 + cell.tileHealth;
			}

			return (byte) byteValue;
		}

		public String toBase64String() {
			byte[] inArray = new byte[gridSize.x * gridSize.y];

			for (int x = 0; x < gridSize.x; x++) {
				for (int y = 0; y < gridSize.y; y++) {
					inArray[x + gridSize.x * y] = gridCellByteValueForBase64String(new Vector2i(x, y));
				}
			}

			return Base64.getEncoder().encodeToString(inArray);
		}

		public byte gridCellByteValueForBase64String(Vector2i pos) {
			int byteValue = 0;
			GridCell cell = getCell(pos);

			if (cell.TileType == TileType.HatOrPurse) {
				byteValue = 18;
			} else if (cell.Booster == BoosterType.PrismPeacock) {
				byteValue = 88;
			} else {
				byteValue += ((cell.Booster == BoosterType.None ? 2 : 1) * cell.TileType.ordinal()
						+ (cell.tileHealth == 0 ? 0 : 1));
				byteValue += cell.Booster.ordinal() * 20;
			}

			return (byte) byteValue;
		}

		// functions that generate new game boards

		private void initializeGameBoard() // level of emulation accuracy uncertain
		{

			clearGrid();
			List<TileType> shuffledSpawnTiles = new ArrayList<TileType>(spawnTiles);

			for (int y = 0; y < gridSize.y; y++) {
				for (int x = 0; x < gridSize.x; x++) {
					Collections.shuffle(shuffledSpawnTiles, randomizer);

					for (TileType spawnTile : shuffledSpawnTiles) {
						// checks so that matches don't naturally form
						if ((x < 2 || !(getCell(new Vector2i(x - 1, y)).TileType == spawnTile)
								|| !(getCell(new Vector2i(x - 2, y)).TileType == spawnTile))
								&& (y < 2 || !(getCell(new Vector2i(x, y - 1)).TileType == spawnTile)
										|| !(getCell(new Vector2i(x, y - 2)).TileType == spawnTile))) {
							setCell(new Vector2i(x, y), new GridCell(0, spawnTile, BoosterType.None));
							break;
						}
					}
				}
			}
		}

		// used by the scramble tiles command
		public void scrambleTiles() {
			List<GridCell> scrambledTiles = new ArrayList<>();

			for (int x = 0; x < gridSize.x; x++) {
				for (int y = 0; y < gridSize.y; y++) {
					Vector2i curr = new Vector2i(x, y);
					scrambledTiles.add(getCell(curr));
				}
			}

			Collections.shuffle(scrambledTiles, randomizer);

			int tileNumber = 0;
			for (GridCell tile : scrambledTiles) {
				setCell(new Vector2i(tileNumber % gridSize.y, tileNumber / gridSize.y), tile);
				tileNumber++;
			}

			while (true) { // copied over from the calculateMoves function

				boolean isMatches = findAndClearMatches(new Vector2i(-1, -1), new Vector2i(-1, -1)); // blank input
				fillGaps();
				clearHats();
				fillGaps(); // replace tiles marked as having being cleared

				if (!isMatches) {
					break;
				}

			}
		}

		private void clearHats() { // level of emulation accuracy uncertain
			for (int x = 0; x < gridSize.x; x++) {
				for (int y = 0; y < gridSize.y; y++) { // for each column
					Vector2i curr = new Vector2i(x, y);
					if (getCell(curr).getTileType() == TileType.HatOrPurse) {
						clearCell(curr, false, true);
					} else {
						break;
					}
				}
			}

			fillGaps();
			levelObjectives.trackEggsAndClothing();
		}

		public void fillGaps() { // called whenever tiles have been removed, it shifts down tiles suspended
									// midair and generates new ones
			for (int x = 0; x < gridSize.x; x++) {
				int noGapsInColumn = 0;
				for (int y = 0; y < gridSize.y; y++) { // for each column
					GridCell currCell = getCell(new Vector2i(x, y));
					if (currCell.TileType == TileType.None && currCell.Booster == BoosterType.None) {
						noGapsInColumn++;
					} else {
						setCell(new Vector2i(x, y - noGapsInColumn), currCell); // move all the tiles down to eliminate
																				// gaps
					}
				}
				for (int y = gridSize.y; y >= gridSize.y - noGapsInColumn; y--) {
					setCell(new Vector2i(x, y),
							new GridCell(0, spawnTiles.get(randomizer.nextInt(spawnTiles.size())), BoosterType.None)); // now
																														// fill
																														// the
																														// gaps
				}
			}
		}

		// functions related to responding to a move from the player

		// first function called by the move command handling function
		public void calculateMove(Vector2i pos1, Vector2i pos2) {

			moveCount++;
			levelObjectives.trackMoves();

			matchComboScore = 0;

			if (pos2.y != -1) { // if given two tiles as input

				GridCell cell1 = getCell(pos1); // swap the tiles
				setCell(pos1, getCell(pos2));
				setCell(pos2, cell1);

				/*
				 * while(findAndClearMatches(pos1, pos2)){ // for as long as there are still
				 * matches on the game board
				 * 
				 * fillGaps(); clearHats();
				 * 
				 * }
				 */
				floodFillClearVisited(); // reset the visited array

				// Guarantee pos1 to have a more powerful or as powerful booster as pos2
				if (getCell(pos2).getBooster().ordinal() > getCell(pos1).getBooster().ordinal()) {
					Vector2i temp = pos1;
					pos1 = pos2;
					pos2 = temp;
				}

				if (getCell(pos1).isBoosted() && getCell(pos2).isBoosted()) { // booster combo behaviours

					if ((getCell(pos2).getBooster() == BoosterType.BuzzyBirdHorizontal
							|| getCell(pos2).getBooster() == BoosterType.BuzzyBirdVertical)
							&& getCell(pos1).getBooster() == BoosterType.BoomBird) { // Buzzy bird and boom bird

						ComboBuzzyBoomBirdBehaviour(pos1, pos2);

					} else if (getCell(pos1).getBooster() == BoosterType.BoomBird
							&& getCell(pos2).getBooster() == BoosterType.BoomBird) { // Boom bird and boom bird

						ComboBoomBoomBirdBehaviour(pos1, pos2);

					} else if ((getCell(pos1).getBooster() == BoosterType.BuzzyBirdHorizontal
							|| getCell(pos1).getBooster() == BoosterType.BuzzyBirdVertical)
							&& (getCell(pos2).getBooster() == BoosterType.BuzzyBirdHorizontal
									|| getCell(pos2).getBooster() == BoosterType.BuzzyBirdVertical)) { // Buzzy bird and
																										// buzzy bird

						ComboBuzzyBuzzyBirdBehaviour(pos1, pos2);

					} else if (getCell(pos1).getBooster() == BoosterType.PrismPeacock
							&& (getCell(pos2).getBooster() == BoosterType.BuzzyBirdHorizontal
									|| getCell(pos2).getBooster() == BoosterType.BuzzyBirdVertical)) { // Prism peacock
																										// and buzzy
																										// bird

						ComboPrismBuzzyBirdBehaviour(pos1, pos2);

					} else if (getCell(pos1).getBooster() == BoosterType.PrismPeacock
							&& (getCell(pos2).getBooster() == BoosterType.BoomBird)) { // Prism peacock and boom bird

						ComboPrismBoomBirdBehaviour(pos1, pos2);

					} else if (getCell(pos1).getBooster() == BoosterType.PrismPeacock
							&& getCell(pos1).getBooster() == BoosterType.PrismPeacock) { // Prism peacock and prism
																							// peacock

						ComboPrismPrismPeacockBehaviour(pos1, pos2);
					}

				} else if (getCell(pos1).isBoosted() && !getCell(pos2).isBoosted()) { // single booster behaviours

					switch (getCell(pos1).getBooster()) {
					case BuzzyBirdHorizontal:
						buzzyBirdHorizontalBehaviour(pos1, 1);
						break;
					case BuzzyBirdVertical:
						buzzyBirdVerticalBehaviour(pos1, 1);
						break;
					case BoomBird:
						boomBirdBehaviour(pos1, 3);
						break;
					case PrismPeacock:
						prismPeacockBehaviour(pos1, getCell(pos2).getTileType());
						break;
					default:
						break;
					}
				}

			} else { // only one tile as input

				switch (getCell(pos1).getBooster()) {
				case BuzzyBirdHorizontal:
					buzzyBirdHorizontalBehaviour(pos1, 1);
					break;
				case BuzzyBirdVertical:
					buzzyBirdVerticalBehaviour(pos1, 1);
					break;
				case BoomBird:
					boomBirdBehaviour(pos1, 3);
					break;
				default:
					break;
				}

			}

			while (findAndClearMatches(pos1, pos2)) { // for as long as there are still matches on the game board

				fillGaps();
				clearHats();

			}

		}

		// identifies continuous lines of tiles with the same color, main function
		// called by calculateMove
		public boolean findAndClearMatches(Vector2i pos1, Vector2i pos2) { // Returns true if a match is found.

			floodFillClearVisited();

			boolean isMatch = false;

			Map<Vector2i, Integer> matchType = new HashMap<>();

			// find all vertical matches
			for (int x = 0; x < gridSize.x; x++) {
				int sameTiles = 1;
				for (int y = 0; y < gridSize.y; y++) { // for each column
					if (floodFillIsNeighbourCell(new Vector2i(x, y), new Vector2i(x, y + 1), false)) { // keep track of
																										// number of
																										// tiles with
																										// the same tile
																										// type next to
																										// each other
						sameTiles++;
					} else if (sameTiles > 2) {
						isMatch = true;

						for (int backtrack = y - sameTiles + 1; backtrack <= y; backtrack++) {
							Vector2i back = new Vector2i(x, backtrack);

							floodFillSetToVisit(back);
							matchType.put(back, sameTiles);
						}
						sameTiles = 1;
					} else {
						sameTiles = 1;
					}
				}
			}

			// find all horizontal matches
			for (int y = 0; y < gridSize.y; y++) { // for each row
				int sameTiles = 1;
				for (int x = 0; x < gridSize.x; x++) {
					if (floodFillIsNeighbourCell(new Vector2i(x + 1, y), new Vector2i(x, y), false)) { // keep track of
																										// number of
																										// tiles with
																										// the same tile
																										// type next to
																										// each other
						sameTiles++;
					} else if (sameTiles > 2) {
						isMatch = true;

						for (int backtrack = x - sameTiles + 1; backtrack <= x; backtrack++) {
							Vector2i back = new Vector2i(backtrack, y);

							floodFillSetToVisit(back);
							matchType.put(back, sameTiles);
						}
						sameTiles = 1;
					} else {
						sameTiles = 1;
					}
				}
			}

			// clear all matches
			for (int x = 0; x < gridSize.x; x++) {
				for (int y = 0; y < gridSize.y; y++) {
					Vector2i curr = new Vector2i(x, y);

					if (!floodFillGetVisited(curr)) {
						floodFill(curr, matchType.get(curr), pos1, pos2);
					}
				}
			}

			return isMatch;
		}

		// a breadth-first search algorithm designed to clear matches and place a
		// booster tile (doesn't always place in correct position)
		public void floodFill(Vector2i pos, int matchType, Vector2i swap1, Vector2i swap2) {

			TileType refType = getCell(pos).getTileType();
			BoosterType refBooster = BoosterType.None;

			List<Vector2i> connectedNodes = new LinkedList<>();
			Queue<Vector2i> floodFillQueue = new LinkedList<>();
			floodFillQueue.add(pos);

			int matchTileSize = matchType;

			while (!floodFillQueue.isEmpty()) {
				Vector2i curr = floodFillQueue.poll();

				if (getCell(curr) == null)
					continue;
				if (!floodFillGetVisited(curr))
					continue;
				if (getCell(pos).getTileType() != getCell(curr).getTileType())
					continue;
				// if(getCell(pos).getBooster() != getCell(curr).getBooster()) continue;
				// if(getCell(pos).getHealth() > 0 && getCell(curr).getHealth() > 0) continue;

				floodFillSetVisited(curr);
				connectedNodes.add(curr);

				floodFillQueue.add(new Vector2i(curr.x - 1, curr.y));
				floodFillQueue.add(new Vector2i(curr.x + 1, curr.y));
				floodFillQueue.add(new Vector2i(curr.x, curr.y - 1));
				floodFillQueue.add(new Vector2i(curr.x, curr.y + 1));

				// detect tiles connected to other tiles in an L or T shape

				int horizontalConnections = 0;
				int verticalConnections = 0;

				if (floodFillIsNeighbourCell(curr, new Vector2i(curr.x - 1, curr.y), true))
					horizontalConnections++;
				if (floodFillIsNeighbourCell(curr, new Vector2i(curr.x + 1, curr.y), true))
					horizontalConnections++;
				if (floodFillIsNeighbourCell(curr, new Vector2i(curr.x, curr.y - 1), true))
					verticalConnections++;
				if (floodFillIsNeighbourCell(curr, new Vector2i(curr.x, curr.y + 1), true))
					verticalConnections++;

				if (((horizontalConnections == 1 && verticalConnections == 1)
						|| (horizontalConnections == 2 && verticalConnections == 1)
						|| (horizontalConnections == 1 && verticalConnections == 2)) && matchTileSize != 5) { // do not
																												// change
																												// to
																												// boom
																												// bird
																												// if a
																												// prism
																												// peacock
																												// would
																												// form
					matchTileSize = 6;
				}
			}

			// keep track of puzzle objectives
			if (getCell(pos).getTileType() != TileType.None) {
				switch (matchTileSize) {
				case 3:
					scoreCombo(30);
					break;
				case 4:
					refBooster = randomizer.nextInt(2) == 0 ? BoosterType.BuzzyBirdHorizontal
							: BoosterType.BuzzyBirdVertical;
					scoreCombo(150);
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.MakeFlyers);
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.MakeFlyers_SingleGame);
					break;
				case 5:
					refBooster = BoosterType.PrismPeacock;
					scoreCombo(150);
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.MakePeacocks);
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.MakePeacocks_SingleGame);
					break;
				case 6:
					refBooster = BoosterType.BoomBird;
					scoreCombo(150);
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.MakeBombBirds);
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.MakeBombBirds_SingleGame);
					break;
				default:
					break;
				}

				if (matchTileSize > 3) { // I am not sure what type of goal "Hatch Powerups" is
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.HatchPowerups);
					puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.HatchPowerups_SingleGame);
				}
			}

			for (Vector2i node : connectedNodes) {
				clearCell(node, false, false);

				breakEggTile(new Vector2i(node.x - 1, node.y)); // break the eggs surrounding a match
				breakEggTile(new Vector2i(node.x + 1, node.y));
				breakEggTile(new Vector2i(node.x, node.y - 1));
				breakEggTile(new Vector2i(node.x, node.y + 1));
			}

			if (refBooster != BoosterType.None) {

				if (refBooster == BoosterType.PrismPeacock) {
					refType = TileType.None;
				}

				if (connectedNodes.contains(swap1)) {
					setCell(swap1, new GridCell(0, refType, refBooster));
				} else if (connectedNodes.contains(swap2)) {
					setCell(swap2, new GridCell(0, refType, refBooster));
				} else {
					setCell(pos, new GridCell(0, refType, refBooster));
				}
			}

		}

		// flood fill helper functions
		public boolean floodFillIsNeighbourCell(Vector2i pos1, Vector2i pos2, boolean checkMatch) {
			GridCell cell1 = getCell(pos1);
			GridCell cell2 = getCell(pos2);

			if (cell1 == null || cell2 == null || cell1.getTileType() == TileType.HatOrPurse
					|| cell2.getTileType() == TileType.HatOrPurse) {
				return false;
			} else if (cell1.getTileType() == cell2.getTileType()) {
				// !cell1.isBoosted() && !cell2.isBoosted() &&
				// cell1.getHealth() == 0 && cell1.getHealth() == 0

				return checkMatch ? floodFillGetToVisit(pos2) && floodFillGetToVisit(pos2) : true;
			}

			return false;
		}

		// false = don't visit this cell/cell has been visited
		// true = visit this cell/cell has not been visited
		public void floodFillSetToVisit(Vector2i pos) {
			toVisit[pos.x][pos.y] = true;
			visited[pos.x][pos.y] = true;
		}

		public boolean floodFillGetToVisit(Vector2i pos) { // also returns true if uninitialized
			if (pos.x >= 0 && pos.x < gridSize.x && pos.y >= 0 && pos.y < gridSize.y) {
				return toVisit[pos.x][pos.y];
			}
			return false;
		}

		public void floodFillSetVisited(Vector2i pos) {
			visited[pos.x][pos.y] = false;
		}

		public boolean floodFillGetVisited(Vector2i pos) {
			if (pos.x >= 0 && pos.x < gridSize.x && pos.y >= 0 && pos.y < gridSize.y) {
				return visited[pos.x][pos.y];
			}
			return false;
		}

		public void floodFillClearVisited() {
			toVisit = new boolean[gridSize.x][gridSize.y];
			visited = new boolean[gridSize.x][gridSize.y];
		}

		// functions that define booster tile behaviours, as in how they clear
		// surrounding tiles

		// template function to avoid errors
		private void buzzyBirdHorizontalBehaviour(Vector2i pos, int size) {
			buzzyBirdHorizontalBehaviour(pos, size, false);
		}

		private void buzzyBirdHorizontalBehaviour(Vector2i pos, int size, boolean isClearedByPeacock) {

			clearCell(pos, false, true);

			int d = Math.floorDiv(size, 2);

			for (int y = pos.y - d; y <= pos.y + d; y++) {
				for (int x = 0; x < gridSize.x; x++) {
					Vector2i curr = new Vector2i(x, y);
					if (getCell(curr) != null) {
						if (getCell(curr).getBooster() != BoosterType.BuzzyBirdHorizontal) { // two buzzy birds in the
																								// same direction
																								// results in an X
																								// instead
							clearCell(curr, true, false, isClearedByPeacock);
						} else {
							clearCell(curr, true, true, isClearedByPeacock);
							buzzyBirdVerticalBehaviour(curr, 1);
						}
					}
				}
			}
			fillGaps();
		}

		private void buzzyBirdVerticalBehaviour(Vector2i pos, int size) {
			buzzyBirdVerticalBehaviour(pos, size, false);
		}

		private void buzzyBirdVerticalBehaviour(Vector2i pos, int size, boolean isClearedByPeacock) {

			clearCell(pos, false, true);

			int d = Math.floorDiv(size, 2);

			for (int x = pos.x - d; x <= pos.x + d; x++) {
				for (int y = 0; y < gridSize.y; y++) {
					Vector2i curr = new Vector2i(x, y);
					if (getCell(curr) != null) {
						if (getCell(curr).getBooster() != BoosterType.BuzzyBirdVertical) {
							clearCell(curr, true, false, isClearedByPeacock);
						} else {
							clearCell(curr, true, true, isClearedByPeacock);
							buzzyBirdHorizontalBehaviour(curr, 1);
						}
					}
				}
			}
			fillGaps();
		}

		private void boomBirdBehaviour(Vector2i pos, int size) {

			clearCell(pos, false, true); // sometimes the original booster needs to be removed to prevent an infinte
											// loop

			int d = Math.floorDiv(size, 2);

			for (int x = pos.x - d; x <= pos.x + d; x++) {
				for (int y = pos.y - d; y <= pos.y + d; y++) {

					Vector2i curr = new Vector2i(x, y);

					if (x != pos.x && y != pos.y) {
						clearCell(curr, true, false);
					}
				}
			}
			fillGaps();
			for (int x = pos.x - d; x <= pos.x + d; x++) {
				for (int y = pos.y - d - 1; y <= pos.y + d - 1; y++) {
					Vector2i curr = new Vector2i(x, y);
					clearCell(curr, true, false);
				}
			}
			fillGaps();
		}

		private void prismPeacockBehaviour(Vector2i pos, TileType refType) {

			clearCell(pos, false, true);

			for (int y = 0; y < gridSize.y; y++) {
				for (int x = 0; x < gridSize.x; x++) {
					Vector2i curr = new Vector2i(x, y);
					GridCell currCell = getCell(curr);

					if (currCell.TileType == refType && !currCell.isBoosted()) {
						clearCell(curr, true, false, true);
					}
				}
			}
			fillGaps();
		}

		private void ComboPrismPrismPeacockBehaviour(Vector2i pos1, Vector2i pos2) {
			clearCell(pos1, false, true);
			clearCell(pos2, false, true);

			puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ComboPeacockPeacock);
			puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ComboPeacockPeacock_SingleGame);

			for (int x = 0; x < gridSize.x; x++) {
				for (int y = 0; y < gridSize.y; y++) {
					Vector2i curr = new Vector2i(x, y);
					clearCell(curr, true, false, true);
				}
			}

			fillGaps();
		}

		private void ComboPrismBoomBirdBehaviour(Vector2i pos1, Vector2i pos2) {
			TileType refType = getCell(pos2).TileType;

			clearCell(pos1, false, true);
			clearCell(pos2, true, true);

			puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ComboBombPeacock);
			puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ComboBombPeacock_SingleGame);

			List<Vector2i> newBoomBirds = new ArrayList<>();

			for (int x = 0; x < gridSize.x; x++) {
				for (int y = 0; y < gridSize.y; y++) {
					Vector2i curr = new Vector2i(x, y);
					GridCell currCell = getCell(curr);

					if (currCell.getTileType() == refType && !currCell.isBoosted()) {
						currCell.setBooster(BoosterType.BoomBird); // presumably no need to check for tile health
						setCell(curr, currCell);
						newBoomBirds.add(curr);
					}
				}
			}

			int d = Math.floorDiv(3, 2);

			for (Vector2i pos : newBoomBirds) { // first explosions

				for (int x = pos.x - d; x <= pos.x + d; x++) {
					for (int y = pos.y - d; y <= pos.y + d; y++) {

						Vector2i curr = new Vector2i(x, y);

						if (getCell(curr) != null && !newBoomBirds.contains(curr)
								&& getCell(curr).getTileType() != TileType.None) {
							clearCell(curr, true, false, true);
						}
					}
				}
			}
			fillGaps();

			newBoomBirds.clear();

			for (int x = 0; x < gridSize.x; x++) {
				for (int y = 0; y < gridSize.y; y++) {
					Vector2i curr = new Vector2i(x, y);
					GridCell currCell = getCell(curr);

					if (currCell.getTileType() == refType && currCell.getBooster() == BoosterType.BoomBird) {
						newBoomBirds.add(curr);
					}
				}
			}

			for (Vector2i pos : newBoomBirds) { // second explosions

				for (int x = pos.x - d; x <= pos.x + d; x++) {
					for (int y = pos.y - d; y <= pos.y + d; y++) {

						Vector2i curr = new Vector2i(x, y);
						clearCell(curr, true, true, true);
					}
				}
			}

			fillGaps();
		}

		private void ComboPrismBuzzyBirdBehaviour(Vector2i pos1, Vector2i pos2) {
			TileType refType = getCell(pos2).TileType;

			clearCell(pos1, false, true);
			clearCell(pos2, true, true);

			puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ComboFlyerPeacock);
			puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ComboFlyerPeacock_SingleGame);

			List<Vector2i> newBuzzyBirds = new ArrayList<>();

			for (int x = 0; x < gridSize.x; x++) {
				for (int y = 0; y < gridSize.y; y++) {
					Vector2i curr = new Vector2i(x, y);

					if (getCell(curr).getTileType() == refType && !getCell(curr).isBoosted()) {
						newBuzzyBirds.add(curr);
					}
				}
			}

			for (Vector2i newBuzzyBird : newBuzzyBirds) {
				boolean coinflip = randomizer.nextInt(2) == 1;

				if (coinflip) {
					buzzyBirdHorizontalBehaviour(newBuzzyBird, 1, true);
				} else {
					buzzyBirdVerticalBehaviour(newBuzzyBird, 1, true);
				}
			}
		}

		private void ComboBuzzyBuzzyBirdBehaviour(Vector2i pos1, Vector2i pos2) {
			clearCell(pos1, false, true);
			clearCell(pos2, true, true);

			buzzyBirdHorizontalBehaviour(pos1, 1);
			buzzyBirdVerticalBehaviour(pos1, 1);

			puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ComboFlyerFlyer);
			puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ComboFlyerFlyer_SingleGame);
		}

		private void ComboBoomBoomBirdBehaviour(Vector2i pos1, Vector2i pos2) {
			clearCell(pos1, false, true);
			clearCell(pos2, true, true);

			boomBirdBehaviour(pos1, 5);

			puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ComboBombBomb);
			puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ComboBombBomb_SingleGame);
		}

		private void ComboBuzzyBoomBirdBehaviour(Vector2i pos1, Vector2i pos2) {
			clearCell(pos1, false, true);
			clearCell(pos2, true, true);

			buzzyBirdHorizontalBehaviour(pos1, 3);
			buzzyBirdVerticalBehaviour(pos1, 3);

			puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ComboFlyerBomb);
			puzzleObjectives.incrementPuzzleTemp(PuzzleObjectiveType.ComboFlyerBomb_SingleGame);
		}

		// functions related to level objectives, or call functions in the level
		// objectives class

		private void scoreCombo(int scoreIncrease) {
			matchComboScore += scoreIncrease;

			dizzyBirdMeter += scoreIncrease / 30;
			score += matchComboScore;
			levelObjectives.addScore(matchComboScore);
			puzzleObjectives.addScore(matchComboScore);
		}

		public boolean isNextLevel() {
			return levelObjectives.isNextLevel();
		}

		public List<int[]> getObjectiveProgress() { // called externally when building a syncClient packet
			levelObjectives.trackEggsAndClothing();
			List<int[]> progress = new ArrayList<>();
			for (LevelObjectiveType objectiveType : LevelObjectiveType.values()) {
				int[] objectiveData = levelObjectives.objectivesTracker[objectiveType.ordinal()];
				if (objectiveData[0] != -1) {
					progress.add(objectiveData);
				}
			}
			return progress;
		}

		public boolean hasRunOutOfMoves() {
			return levelObjectives.hasRunOutOfMoves();
		}

		// functions related to updating the board when a syncClient message is sent

	}

	public class PuzzleObjectives {

		private int puzzleCurrentProgress[];
		private int addToTotalScore;

		public PuzzleObjectives() {
			puzzleCurrentProgress = new int[100];
			addToTotalScore = 0;
		}

		// functions keeping track of changes to be made to the player inventory in
		// relation to puzzle objectives

		public void incrementPuzzleTemp(PuzzleObjectiveType puzzle) {
			puzzleCurrentProgress[puzzle.getVal()]++;
		}

		public void setPuzzleTemp(PuzzleObjectiveType puzzle, int value) {
			puzzleCurrentProgress[puzzle.getVal()] = value;
		}

		public void clearPuzzleTemp(PuzzleObjectiveType puzzle) {
			puzzleCurrentProgress[puzzle.getVal()] = 0;
		}

		public int getPuzzleTemp(PuzzleObjectiveType puzzle) {
			return puzzleCurrentProgress[puzzle.getVal()];
		}

		// functions related to saving puzzle objectives data to the player inventory.

		private int puzzleTypeToUserVarIndex(PuzzleObjectiveType puzzle) {
			for (JsonElement ele : achievementToUserVarIndexList) {
				JsonObject dataPair = (JsonObject) ele;
				if (dataPair.get("achievementType").getAsInt() == puzzle.getVal()) {
					return dataPair.get("userVarIndex").getAsInt();
				}
			}
			return -1;
		}

		private void setPuzzleObjectiveUserVar(Player player, PuzzleObjectiveType puzzle, int value) {
			player.account.getSaveSpecificInventory().getUserVarAccesor().setPlayerVarValue(
					UserVarIDs.persistentAchievementDataUserVarDefId.getVal(), puzzleTypeToUserVarIndex(puzzle), value);
		}

		private int getPuzzleObjectiveUserVar(Player player, PuzzleObjectiveType puzzle) {

			UserVarValue value = player.account.getSaveSpecificInventory().getUserVarAccesor()
					.getPlayerVarValue(UserVarIDs.persistentAchievementDataUserVarDefId.getVal(), puzzle.getVal());
			if (value == null) {
				setPuzzleObjectiveUserVar(player, puzzle, 0);
				return 0;
			} else {
				return player.account.getSaveSpecificInventory().getUserVarAccesor().getPlayerVarValue(
						UserVarIDs.persistentAchievementDataUserVarDefId.getVal(), puzzle.getVal()).value;
			}
		}

		// these are called when the game starts and ends.

		private void saveSavedGameUserVar(Player player) {
			setPuzzleTemp(PuzzleObjectiveType.HighScore, score);
			setPuzzleTemp(PuzzleObjectiveType.TotalScore, score);

			// write into the player inventory the number of moves made in the level, send
			// inventory item packet
			player.account.getSaveSpecificInventory().getUserVarAccesor()
					.setPlayerVarValue(UserVarIDs.savedGameUserVarDefId.getVal(), level, moveCount);

			// in case no puzzle objective has ever been written to the player inventory
			if (player.account.getSaveSpecificInventory().getUserVarAccesor()
					.getPlayerVarValue(UserVarIDs.persistentAchievementDataUserVarDefId.getVal()) == null) {

				for (int i = 0; i < PuzzleObjectiveType.values().length; i++) {
					player.account.getSaveSpecificInventory().getUserVarAccesor()
							.setPlayerVarValue(UserVarIDs.persistentAchievementDataUserVarDefId.getVal(), i, 0);
				}
			}

			// write into the player inventory the puzzle objectives
			for (PuzzleObjectiveType puzzleType : PuzzleObjectiveType.values()) {
				if (getPuzzleTemp(puzzleType) > 0) {
					Centuria.logger.debug(puzzleType.toString(), Integer.toString(getPuzzleTemp(puzzleType)));
				}
			}
			for (PuzzleObjectiveType puzzleType : PuzzleObjectiveType.values()) {
				if (puzzleType.toString().contains("SingleGame") || puzzleType == PuzzleObjectiveType.HighScore) { // highest
																													// in
																													// a
																													// game

					setPuzzleObjectiveUserVar(player, puzzleType,
							Math.max(getPuzzleTemp(puzzleType), getPuzzleObjectiveUserVar(player, puzzleType)));
				} else if (puzzleType != PuzzleObjectiveType.TotalScore) { // cumulative, total score handled in
																			// resetSavedGameUserVar
					setPuzzleObjectiveUserVar(player, puzzleType,
							getPuzzleTemp(puzzleType) + getPuzzleObjectiveUserVar(player, puzzleType));
					clearPuzzleTemp(puzzleType);
				}
			}
			setPuzzleObjectiveUserVar(player, PuzzleObjectiveType.TotalScore,
					getPuzzleObjectiveUserVar(player, PuzzleObjectiveType.TotalScore) + addToTotalScore);
			addToTotalScore = 0;
		}

		public void addScore(int value) {
			addToTotalScore += value;
		}

		private void resetSavedGameUserVar(Player player) {

			// reset these values as a new game has been started
			level = 0;
			score = 0;
			moveCount = 0;
			dizzyBirdMeter = 0;

			// initialise user vars
			player.account.getSaveSpecificInventory().getUserVarAccesor()
					.setPlayerVarValue(UserVarIDs.savedGameUserVarDefId.getVal(), 0, 1);
			player.account.getSaveSpecificInventory().getUserVarAccesor()
					.setPlayerVarValue(UserVarIDs.tutorial.getVal(), 0, 1);
		}

		private void loadSavedGameUserVar(Player player) { // the game only keeps track of the highest score ever and
															// I'm not sure what the continue game button should do

			// retrieve the data from the player inventory
			UserVarValue[] savedGame = player.account.getSaveSpecificInventory().getUserVarAccesor()
					.getPlayerVarValue(UserVarIDs.savedGameUserVarDefId.getVal());
			int prevScore = getPuzzleObjectiveUserVar(player, PuzzleObjectiveType.HighScore);

			// load in the values associated with a previous game
			if (savedGame != null) {
				level = Math.max(1, savedGame.length - 1);
				score = prevScore;
				moveCount = savedGame[savedGame.length - 1].value;
				dizzyBirdMeter = 0;
			}
		}

	}

	// functions that implement the game's protocols.

	@Override
	public AbstractMinigame instantiate() {
		return new GameDizzywingDispatch();
	}

	@Override
	public boolean canHandle(String levelID) {
		return levelID.equals("8192");
	}

	@Override
	public void onJoin(Player plr) {
	}

	@Override
	public void onExit(Player plr) {
	}

	@MinigameMessage("startGame")
	public void startGame(Player player, XtReader rd) {

		// save score and number of moves made on this level
		saveGame(player, rd);

		puzzleObjectives = new PuzzleObjectives();
		puzzleObjectives.resetSavedGameUserVar(player);

		// The GUID is used as the seed for the random number generator.
		currentGameUUID = UUID.randomUUID().toString();
		gameState = new GameState(player);

		// the format of the minigame message response packet
		XtWriter mmData = new XtWriter();
		mmData.writeString(currentGameUUID);
		mmData.writeInt(gameState.calculateBoardChecksum());
		mmData.writeInt(level);
		mmData.writeInt(0); // not sure
		mmData.writeInt(score);
		mmData.writeInt(0); // not sure

		// send response packet
		MinigameMessagePacket mm = new MinigameMessagePacket();
		mm.command = "startGame";
		mm.data = mmData.encode().substring(4);
		player.client.sendPacket(mm);

		// load in server-generated game board
		syncClient(player, rd);

	}

	@MinigameMessage("move")
	public void move(Player player, XtReader rd) {

		int clientChecksum = rd.readInt();

		// process move sent by client
		gameState.calculateMove(new Vector2i(rd.readInt(), rd.readInt()), new Vector2i(rd.readInt(), rd.readInt()));
		// sync client if sever checksum differs from client checksum (always occurs at
		// current)
		if (clientChecksum != gameState.calculateBoardChecksum()) {
			syncClient(player, rd);
		}

		saveGame(player, rd);

		// check if level should increase
		if (gameState.isNextLevel()) {
			moveCount = 0;
			goToLevel(player);
		}

	}

	private void goToLevel(Player player) {

		// packet data
		XtWriter mmData = new XtWriter();
		mmData.writeInt(level);
		mmData.writeInt(0); // not sure
		mmData.writeInt(score);
		mmData.writeInt(0); // not sure

		// send packet
		MinigameMessagePacket mm = new MinigameMessagePacket();
		mm.command = "goToLevel";
		mm.data = mmData.encode().substring(4);
		player.client.sendPacket(mm);

	}

	@MinigameMessage("dizzyBird")
	public void dizzyBird(Player player, XtReader rd) {
		dizzyBirdMeter = 0; // reset the meter at the right-hand side of the screen
		gameState.scrambleTiles();
		syncClient(player, rd);
	}

	@MinigameMessage("continueGame")
	public void continueGame(Player player, XtReader rd) {

		// send start game client with previous values
		puzzleObjectives = new PuzzleObjectives();
		puzzleObjectives.loadSavedGameUserVar(player);

		// The GUID is used as the seed for the random number generator.
		currentGameUUID = UUID.randomUUID().toString();
		gameState = new GameState(player);

		// the format of the minigame message response packet
		XtWriter mmData = new XtWriter();
		mmData.writeString(currentGameUUID);
		mmData.writeInt(gameState.calculateBoardChecksum());
		// it seems the game cannot start unless these specific values are used
		mmData.writeInt(1);
		mmData.writeInt(0);
		mmData.writeInt(500);
		mmData.writeInt(0);

		// send response packet
		MinigameMessagePacket mm = new MinigameMessagePacket();
		mm.command = "startGame";
		mm.data = mmData.encode().substring(4);
		player.client.sendPacket(mm);

		// load in server-generated game board
		syncClient(player, rd);
	}

	@MinigameMessage("saveGame")
	public void saveGame(Player player, XtReader rd) {

		if (gameState != null) {
			puzzleObjectives.saveSavedGameUserVar(player);

			if (player.client != null && player.client.isConnected()) {
				// Send to client
				for (String change : player.account.getSaveSpecificInventory().getAccessor().getChangedInventories())
					player.account.getSaveSpecificInventory().getAccessor().transferUpdatedItemsToPlayer(player,
							change);
			}
		}
	}

	@MinigameMessage("redeemPiece")
	public void redeemPiece(Player player, XtReader rd) { // need to keep track of what has already been redeemed
		int packetPaintingIndex = rd.readInt(); // 0 - 6
		int packetPieceIndex = rd.readInt(); // 0 - 16
		int packetOverallIndex = packetPaintingIndex * 16 + packetPieceIndex;

		Centuria.logger.debug("Painting " + Integer.toString(packetPaintingIndex) + " Piece "
				+ Integer.toString(packetPieceIndex) + " Index " + Integer.toString(packetOverallIndex));

		// if no puzzle piece data has ever been written into the player inventory
		if (player.account.getSaveSpecificInventory().getUserVarAccesor()
				.getPlayerVarValue(UserVarIDs.puzzlePieceRedemptionStatusUserVarDefId.getVal()) == null) {

			for (int i = 0; i < 6 * 16; i++) {
				player.account.getSaveSpecificInventory().getUserVarAccesor()
						.setPlayerVarValue(UserVarIDs.puzzlePieceRedemptionStatusUserVarDefId.getVal(), i, 0);
			}
		}
		if (player.account.getSaveSpecificInventory().getUserVarAccesor()
				.getPlayerVarValue(UserVarIDs.puzzleRedemptionStatusUserVarDefId.getVal()) == null) {

			for (int i = 0; i < 6; i++) {
				player.account.getSaveSpecificInventory().getUserVarAccesor()
						.setPlayerVarValue(UserVarIDs.puzzleRedemptionStatusUserVarDefId.getVal(), i, 0);
			}
		}

		// write the piece data sent by the client packet into the player inventory
		player.account.getSaveSpecificInventory().getUserVarAccesor()
				.setPlayerVarValue(UserVarIDs.puzzlePieceRedemptionStatusUserVarDefId.getVal(), packetOverallIndex, 1);

		// loop through all pieces to see if a painting should be given to the player
		for (int currPaintingIndex = 0; currPaintingIndex < 6; currPaintingIndex++) {

			Boolean fullPainting = true;
			for (int currPieceIndex = 0; currPieceIndex < 16; currPieceIndex++) {

				UserVarValue puzzleRedemptionStatus = player.account.getSaveSpecificInventory().getUserVarAccesor()
						.getPlayerVarValue(UserVarIDs.puzzlePieceRedemptionStatusUserVarDefId.getVal(),
								currPaintingIndex * 16 + currPieceIndex);
				fullPainting &= (puzzleRedemptionStatus != null && puzzleRedemptionStatus.value == 1);
			}

			UserVarValue paintingRedemptionStatus = player.account.getSaveSpecificInventory().getUserVarAccesor()
					.getPlayerVarValue(UserVarIDs.puzzleRedemptionStatusUserVarDefId.getVal(), currPaintingIndex);
			if (fullPainting && paintingRedemptionStatus.value == 0) {

				// give the painting
				String rewardID = puzzleRewards.get(currPaintingIndex).getAsString();

				// give player the item
				player.account.getSaveSpecificInventory().getItemAccessor(player).add(rewardID, 1);

				// send player a notification
				MinigamePrizePacket p1 = new MinigamePrizePacket();
				p1.given = true;
				p1.itemDefId = rewardID;
				p1.itemCount = 1;
				p1.prizeIndex1 = 1;
				p1.prizeIndex2 = 0;
				player.client.sendPacket(p1);

				player.account.getSaveSpecificInventory().getUserVarAccesor().setPlayerVarValue(
						UserVarIDs.puzzleRedemptionStatusUserVarDefId.getVal(), currPaintingIndex, 1);
			}
		}
		if (player.client != null && player.client.isConnected()) {
			// Send to client
			for (String change : player.account.getSaveSpecificInventory().getAccessor().getChangedInventories())
				player.account.getSaveSpecificInventory().getAccessor().transferUpdatedItemsToPlayer(player, change);
		}
	}

	@MinigameMessage("syncClient")
	public void syncClient(Player player, XtReader rd) {
		XtWriter mmData = new XtWriter();
		mmData.writeInt(moveCount);
		mmData.writeInt(level);
		mmData.writeInt(score);
		mmData.writeInt(dizzyBirdMeter);
		mmData.writeString(gameState.toBase64String());

		List<int[]> objectiveProgress = gameState.getObjectiveProgress();

		if (!gameState.hasRunOutOfMoves()) {
			mmData.writeInt(objectiveProgress.size());
			for (int[] objectiveData : objectiveProgress) {
				mmData.writeInt(objectiveData[2]); // objective type
				mmData.writeInt(objectiveData[0]); // objective requirement
				mmData.writeInt(objectiveData[1]); // objective current progress
			}
		} else {
			mmData.writeInt(2);

			mmData.writeInt(objectiveProgress.get(0)[2]);
			mmData.writeInt(objectiveProgress.get(0)[0]);
			mmData.writeInt(objectiveProgress.get(0)[1]);

			mmData.writeInt(1);
			mmData.writeInt(-1);
			mmData.writeInt(-1);
		}

		MinigameMessagePacket mm = new MinigameMessagePacket();
		mm.command = "syncClient";
		mm.data = mmData.encode().substring(4);
		player.client.sendPacket(mm);
	}

}
