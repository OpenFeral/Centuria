package org.asf.centuria.minigames.games;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Random;

import org.apache.logging.log4j.MarkerManager;
import org.asf.centuria.Centuria;
import org.asf.centuria.data.XtReader;
import org.asf.centuria.data.XtWriter;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.entities.uservars.UserVarValue;
import org.asf.centuria.interactions.modules.ResourceCollectionModule;
import org.asf.centuria.interactions.modules.resourcecollection.rewards.LootInfo;
import org.asf.centuria.levelevents.LevelEvent;
import org.asf.centuria.levelevents.LevelEventBus;
import org.asf.centuria.minigames.AbstractMinigame;
import org.asf.centuria.minigames.MinigameMessage;
import org.asf.centuria.minigames.games.entities.whatthehex.WTHLevelInfo;
import org.asf.centuria.minigames.games.entities.whatthehex.WTHRewardInfo;
import org.asf.centuria.minigames.games.enums.whatthehex.RewardType;
import org.asf.centuria.packets.xt.gameserver.inventory.InventoryItemDownloadPacket;
import org.asf.centuria.packets.xt.gameserver.minigame.MinigameCurrencyPacket;
import org.asf.centuria.packets.xt.gameserver.minigame.MinigameMessagePacket;
import org.asf.centuria.packets.xt.gameserver.minigame.MinigamePrizePacket;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 
 * What The Hex Server-side Implementation
 * 
 * @author Sky Swimmer
 *
 */
public class GameWhatTheHex extends AbstractMinigame {

	// Its a bit messy...
	// Im not good with math, if this can be improved feel free
	// Most of the mess is the gameover check

	private static ArrayList<WTHLevelInfo> levels = new ArrayList<WTHLevelInfo>();

	static {
		// Load level info
		try {
			// Load the helper
			InputStream strm = InventoryItemDownloadPacket.class.getClassLoader()
					.getResourceAsStream("minigames/whatthehex.json");
			JsonObject helper = JsonParser.parseString(new String(strm.readAllBytes(), "UTF-8")).getAsJsonObject();
			strm.close();

			// Load all levels
			JsonArray levels = helper.get("Levels").getAsJsonArray();
			for (JsonElement ele : levels) {
				JsonObject info = ele.getAsJsonObject();

				WTHLevelInfo level = new WTHLevelInfo();
				level.levelMax = info.get("levelMax").getAsInt();

				JsonObject rewards = info.get("rewards").getAsJsonObject();
				rewards.keySet().forEach(type -> {
					JsonObject rewardInfo = rewards.get(type).getAsJsonObject();
					WTHRewardInfo reward = new WTHRewardInfo();
					if (rewardInfo.has("type"))
						reward.type = (rewardInfo.get("type").getAsString().equals("loot") ? RewardType.LOOT
								: RewardType.ITEM);
					reward.id = rewardInfo.get("id").getAsString();
					if (rewardInfo.has("count"))
						reward.count = rewardInfo.get("count").getAsInt();
					level.rewards.put(type, reward);
				});

				GameWhatTheHex.levels.add(level);
			}
		} catch (IOException e) {
			// This is very bad, should not start allow the server to continue otherwise
			// things will break HARD
			throw new RuntimeException(e);
		}

		// Debug
//		if (Centuria.debugMode) {
//			WTHVis vis = new WTHVis();
//			new Thread(() -> vis.frame.setVisible(true)).start();
//		}
	}

	public class Element {
		public int level;
		public int levelMax;
		public int currentProgress;
		public String currencyRewardType;
		public int currencyRewardAmount;
		public int elementID;
		public int pendingCyclones;
	}

	public class RuneSet {
		public byte rotation;
		public byte amount;
		public byte[] types;
	}

	private static final int[][] boardIndex = new int[][] {
			// 1
			new int[] { -3, 3 },
			// 2
			new int[] { -3, 2 },
			// 3
			new int[] { -3, 1 },
			// 4
			new int[] { -3, 0 },
			// 5
			new int[] { -2, 3 },
			// 6
			new int[] { -2, 2 },
			// 7
			new int[] { -2, 1 },
			// 8
			new int[] { -2, 0 },
			// 9
			new int[] { -2, -1 },
			// 10
			new int[] { -1, 3 },
			// 11
			new int[] { -1, 2 },
			// 12
			new int[] { -1, 1 },
			// 13
			new int[] { -1, 0 },
			// 14
			new int[] { -1, -1 },
			// 15
			new int[] { -1, -2 },
			// 16
			new int[] { 0, 3 },
			// 17
			new int[] { 0, 2 },
			// 18
			new int[] { 0, 1 },
			// 19
			new int[] { 0, 0 },
			// 20
			new int[] { 0, -1 },
			// 21
			new int[] { 0, -2 },
			// 22
			new int[] { 0, -3 },
			// 23
			new int[] { 1, 2 },
			// 24
			new int[] { 1, 1 },
			// 25
			new int[] { 1, 0 },
			// 25
			new int[] { 1, -1 },
			// 26
			new int[] { 1, -2 },
			// 27
			new int[] { 1, -3 },
			// 28
			new int[] { 2, 1 },
			// 29
			new int[] { 2, 0 },
			// 30
			new int[] { 2, -1 },
			// 31
			new int[] { 2, -2 },
			// 32
			new int[] { 2, -3 },
			// 33
			new int[] { 3, 0 },
			// 34
			new int[] { 3, -1 },
			// 35
			new int[] { 3, -2 },
			// 36
			new int[] { 3, -3 } };

	private ArrayList<Element> elements = new ArrayList<Element>();
	private Random rnd = new Random();
	private int lastRandomInt = 0;

	private RuneSet first;
	private RuneSet second;
	private RuneSet third;

	private byte firstExtraRotation = 0;
	private byte secondExtraRotation = 0;
	private byte thirdExtraRotation = 0;

	private int score = 0;
	private int powerUpProgress = 0;
	private boolean activeBomb = false;

	// Doing it binary to conserve memory usage
	public byte[] board = new byte[37];
	public int[] cyclones = new int[37];

	// Tool to get the board index of a point
	public int getBoardIndex(int x, int y) {
		int p = 0;
		for (int[] coordinates : boardIndex) {
			if (coordinates[0] == x && coordinates[1] == y)
				return p;
			p++;
		}
		return p;
	}

	// Tool to get the coordinates of a board index
	public int[] getCoordinates(int index) {
		int p = 0;
		for (int[] coordinates : boardIndex) {
			if (p == index)
				return coordinates;
			p++;
		}
		return null;
	}

	// Utility to make spawning easier
	private RuneSet spawnTiles(XtWriter target) {
		// Select rotation and count
		int i = rnd.nextInt(0, 14);
		while (i == lastRandomInt)
			i = rnd.nextInt(0, 14);
		lastRandomInt = i;

		// -2 = flora (single)
		// -1 = miasma (single)
		// 0 = flame (single)

		// 1 = flora (top), flame (bottom)
		// 2 = flora (top right), flame (bottom left)
		// 3 = flame (top left), flora (bottom right)

		// 4 = flora (top), flame (middle), miasma (bottom)
		// 5 = flora (top right), flame (middle), miasma (bottom left)
		// 6 = flora (top left), flame (middle), miasma (bottom right)

		// 7 = flora (left), flame (middle top), miasma (right)
		// 8 = flora (left), flame (middle bottom), miasma (right)

		// 9 = flora (right), flame (middle), miasma (bottom)
		// 10 = flora (left), flame (middle), miasma (bottom)

		// 11 = flora (top), flame (middle), miasma (bottom right)
		// 12 = flora (top), flame (middle), miasma (bottom left)
		// 13 = flora (top), miasma (right), flame (bottom)

		return spawnTiles(target, i);
	}

	// Spawn with type
	private RuneSet spawnTiles(XtWriter target, int type) {
		// Select rotation and count
		int count = 0;
		if (type >= 4 && type <= 13)
			count = 3;
		else if (type >= 1)
			count = 2;
		else
			count = 1;
		byte[] types = new byte[count];
		if (count == 3) {
			for (int i = 0; i < count; i++) {
				types[i] = (byte) (i + 2);
			}
		} else if (count == 2) {
			types[0] = (byte) rnd.nextInt(2, 5);
			types[1] = (byte) rnd.nextInt(2, 5);
			while (types[1] == types[0])
				types[1] = (byte) rnd.nextInt(2, 5);
		} else {
			types[0] = (byte) rnd.nextInt(2, 5);
		}

		return spawnTiles(target, type, count, types);
	}

	// Spawn with type
	private RuneSet spawnTiles(XtWriter target, int type, int count, byte[] types) {
		RuneSet set = new RuneSet();

		// Set rotation and count
		set.amount = (byte) count;
		set.rotation = (byte) type;
		set.types = types;

		// Write
		target.writeInt(type);
		target.writeInt(count);
		for (int i = 0; i < count; i++) {
			target.writeInt(set.types[i]);
		}

		return set;
	}

	@Override
	public boolean canHandle(String levelID) {
		return levelID.equals("3272");
	}

	@Override
	public void onJoin(Player player) {
		// Send currency packet
		MinigameCurrencyPacket currency = new MinigameCurrencyPacket();
		currency.Currency = "2709";
		player.client.sendPacket(currency);
	}

	@MinigameMessage("rotateTile")
	public void rotateTile(Player player, XtReader rd) {
		// Rotate tile
		int ind = rd.readInt();
		switch (ind) {
		case 0:
			if (firstExtraRotation + 1 < first.amount)
				firstExtraRotation++;
			else
				firstExtraRotation = 0;
			break;
		case 1:
			if (secondExtraRotation + 1 < second.amount)
				secondExtraRotation++;
			else
				secondExtraRotation = 0;
			break;
		case 2:
			if (thirdExtraRotation + 1 < third.amount)
				thirdExtraRotation++;
			else
				thirdExtraRotation = 0;
			break;
		}
	}

	@MinigameMessage("placeTile")
	public void placeTile(Player player, XtReader rd) {
		// Place tile

		// Read packet
		int ind = rd.readInt();
		int x = rd.readInt();
		int y = rd.readInt();

		// 2-rune tiles:
		// 0: lower = center
		// 1: lower = center
		// 2: upper = center

		// Find tile
		int rot = 0;
		RuneSet tile = null;
		switch (ind) {
		case 0: {
			rot = firstExtraRotation;
			firstExtraRotation = 0;
			tile = first;
			break;
		}
		case 1: {
			rot = secondExtraRotation;
			secondExtraRotation = 0;
			tile = second;
			break;
		}
		case 2: {
			rot = thirdExtraRotation;
			thirdExtraRotation = 0;
			tile = third;
			break;
		}
		case -1: {
			// Bomb
			rot = 0;
			tile = new RuneSet();
			tile.amount = 1;
			tile.rotation = 0;
			tile.types = new byte[] { (byte) 9 };
			break;
		}
		}

		Centuria.logger.debug(MarkerManager.getMarker("WhatTheHex"),
				"Placed: " + x + "/" + y + " (" + tile.rotation + ")");

		// Place tiles
		// It seems with 3-rune tiles, the first and second rune are swapped
		if (tile.types.length == 3) {
			byte t1 = tile.types[1];
			tile.types[1] = tile.types[0];
			tile.types[0] = t1;
		}

		// Handle the manual rotation
		if (tile.types.length > 1 && rot > 0) {
			// Check if it needs to be rotated backwards
			boolean rotateBackwads = false;
			if (tile.rotation == 7 || tile.rotation == 8)
				rotateBackwads = true;

			for (int i = 0; i < rot; i++) {
				byte[] newTypes = new byte[tile.types.length];

				if (tile.types.length == 2) {
					newTypes[1] = tile.types[0];
					newTypes[0] = tile.types[1];
				} else {
					if (!rotateBackwads) {
						newTypes[0] = tile.types[1];
						newTypes[1] = tile.types[2];
						newTypes[2] = tile.types[0];
					} else {
						newTypes[0] = tile.types[2];
						newTypes[1] = tile.types[0];
						newTypes[2] = tile.types[1];
					}
				}
				tile.types = newTypes;
			}
		}

		// List of placed tile coordinates
		ArrayList<Integer> placed = new ArrayList<Integer>();

		// Check if its a cyclone or bomb
		boolean bomb = false;
		boolean cyclone = false;
		if (tile.types.length == 1 && tile.types[0] >= 5 && tile.types[0] <= 7) {
			int elementID = tile.types[0] - 3;
			tile.types[0] = (byte) elementID;
			cyclone = true;
		} else if (tile.types.length == 1 && tile.types[0] == 9) {
			bomb = true;
		}

		if (!bomb) {
			// Place tiles in memory
			switch (tile.rotation) {
			case 0: {
				// Place single tile
				board[this.getBoardIndex(x, y)] = tile.types[0];
				placed.add(getBoardIndex(x, y));
				break;
			}
			case 1: {
				// Place double tile (rotation 1)
				board[this.getBoardIndex(x, y)] = tile.types[0];
				board[this.getBoardIndex(x, y + 1)] = tile.types[1];
				placed.add(getBoardIndex(x, y));
				placed.add(getBoardIndex(x, y + 1));
				break;
			}
			case 2: {
				// Place double tile (rotation 2)
				board[this.getBoardIndex(x + 1, y)] = tile.types[1];
				board[this.getBoardIndex(x, y)] = tile.types[0];
				placed.add(getBoardIndex(x + 1, y));
				placed.add(getBoardIndex(x, y));
				break;
			}
			case 3: {
				// Place double tile (rotation 3)
				board[this.getBoardIndex(x, y)] = tile.types[0];
				board[this.getBoardIndex(x + 1, y - 1)] = tile.types[1];
				placed.add(getBoardIndex(x, y));
				placed.add(getBoardIndex(x + 1, y - 1));
				break;
			}
			case 4: {
				// Place triple tile (rotation 1)
				board[this.getBoardIndex(x, y + 1)] = tile.types[0];
				board[this.getBoardIndex(x, y)] = tile.types[1];
				board[this.getBoardIndex(x, y - 1)] = tile.types[2];
				placed.add(getBoardIndex(x, y + 1));
				placed.add(getBoardIndex(x, y));
				placed.add(getBoardIndex(x, y - 1));
				break;
			}
			case 5: {
				// Place triple tile (rotation 2)
				board[this.getBoardIndex(x + 1, y)] = tile.types[0];
				board[this.getBoardIndex(x, y)] = tile.types[1];
				board[this.getBoardIndex(x - 1, y)] = tile.types[2];
				placed.add(getBoardIndex(x + 1, y));
				placed.add(getBoardIndex(x, y));
				placed.add(getBoardIndex(x - 1, y));
				break;
			}
			case 6: {
				// Place triple tile (rotation 3)
				board[this.getBoardIndex(x - 1, y + 1)] = tile.types[0];
				board[this.getBoardIndex(x, y)] = tile.types[1];
				board[this.getBoardIndex(x + 1, y - 1)] = tile.types[2];
				placed.add(getBoardIndex(x - 1, y + 1));
				placed.add(getBoardIndex(x, y));
				placed.add(getBoardIndex(x + 1, y - 1));
				break;
			}
			case 7: {
				// Place triple tile (rotation 4)
				board[this.getBoardIndex(x - 1, y)] = tile.types[0];
				board[this.getBoardIndex(x, y)] = tile.types[1];
				board[this.getBoardIndex(x + 1, y - 1)] = tile.types[2];
				placed.add(getBoardIndex(x - 1, y));
				placed.add(getBoardIndex(x, y));
				placed.add(getBoardIndex(x + 1, y - 1));
				break;
			}
			case 8: {
				// Place triple tile (rotation 5)
				board[this.getBoardIndex(x - 1, y + 1)] = tile.types[0];
				board[this.getBoardIndex(x, y)] = tile.types[1];
				board[this.getBoardIndex(x + 1, y)] = tile.types[2];
				placed.add(getBoardIndex(x - 1, y + 1));
				placed.add(getBoardIndex(x, y));
				placed.add(getBoardIndex(x + 1, y));
				break;
			}
			case 9: {
				// Place triple tile (rotation 6)
				board[this.getBoardIndex(x + 1, y)] = tile.types[0];
				board[this.getBoardIndex(x, y)] = tile.types[1];
				board[this.getBoardIndex(x, y - 1)] = tile.types[2];
				placed.add(getBoardIndex(x + 1, y));
				placed.add(getBoardIndex(x, y));
				placed.add(getBoardIndex(x, y - 1));
				break;
			}
			case 10: {
				// Place triple tile (rotation 7)
				board[this.getBoardIndex(x - 1, y + 1)] = tile.types[0];
				board[this.getBoardIndex(x, y)] = tile.types[1];
				board[this.getBoardIndex(x, y - 1)] = tile.types[2];
				placed.add(getBoardIndex(x - 1, y + 1));
				placed.add(getBoardIndex(x, y));
				placed.add(getBoardIndex(x, y - 1));
				break;
			}
			case 11: {
				// Place triple tile (rotation 8)
				board[this.getBoardIndex(x, y + 1)] = tile.types[0];
				board[this.getBoardIndex(x, y)] = tile.types[1];
				board[this.getBoardIndex(x + 1, y - 1)] = tile.types[2];
				placed.add(getBoardIndex(x, y + 1));
				placed.add(getBoardIndex(x, y));
				placed.add(getBoardIndex(x + 1, y - 1));
				break;
			}
			case 12: {
				// Place triple tile (rotation 9)
				board[this.getBoardIndex(x, y + 1)] = tile.types[0];
				board[this.getBoardIndex(x, y)] = tile.types[1];
				board[this.getBoardIndex(x - 1, y)] = tile.types[2];
				placed.add(getBoardIndex(x, y + 1));
				placed.add(getBoardIndex(x, y));
				placed.add(getBoardIndex(x - 1, y));
				break;
			}
			case 13: {
				// Place triple tile (rotation 10)
				board[this.getBoardIndex(x, y + 1)] = tile.types[0];
				board[this.getBoardIndex(x, y)] = tile.types[1];
				board[this.getBoardIndex(x + 1, y)] = tile.types[2];
				placed.add(getBoardIndex(x, y + 1));
				placed.add(getBoardIndex(x, y));
				placed.add(getBoardIndex(x + 1, y));
				break;
			}
			}

			if (!cyclone) {
				// Check combos
				int combos = 0;
				boolean flame = false;
				boolean flora = false;
				boolean miasma = false;
				for (int point : placed) {
					int[] coordinates = getCoordinates(point);
					int px = coordinates[0];
					int py = coordinates[1];

					// Check connecting tiles
					int s = tileScore(px, py, new ArrayList<Integer>());

					// Find element type
					if (s > 0) {
						switch (board[point]) {
						case 2: {
							flame = true;
							break;
						}
						case 3: {
							flora = true;
							break;
						}
						case 4: {
							miasma = true;
							break;
						}
						}
					}
				}
				if (flame || flora || miasma) {
					combos = -1;
					if (flame)
						combos++;
					if (flora)
						combos++;
					if (miasma)
						combos++;
				}

				// Power-up meter
				if (combos == 1)
					powerUpProgress += 10;
				else if (combos == 2)
					powerUpProgress += 25;
				Centuria.logger.debug(MarkerManager.getMarker("WhatTheHex"), "Power-up meter: " + powerUpProgress);
				if (powerUpProgress >= 100 && !activeBomb) {
					// Spawn bomb
					XtWriter wr = new XtWriter();
					wr.writeInt(-1);
					spawnTiles(wr, 0, 1, new byte[] { (byte) 9 });
					MinigameMessagePacket pk = new MinigameMessagePacket();
					pk.command = "spawnTile";
					pk.data = wr.encode().substring(4);
					player.client.sendPacket(pk);
					powerUpProgress = 0;
					activeBomb = true;
				}

				// Handle scores
				for (int point : placed) {
					int[] coordinates = getCoordinates(point);
					int px = coordinates[0];
					int py = coordinates[1];

					// Check connecting tiles
					int s = tileScore(px, py, new ArrayList<Integer>());

					// Find element type
					Element ele = elements.stream().filter(t -> t.elementID == board[point]).findFirst().get();

					// Reward
					if (ele != null) {
						score += s * (combos == 0 ? 1 : combos == 1 ? 2 : combos == 2 ? 4 : 1);
						ele.currentProgress += s * (combos == 0 ? 1 : combos == 1 ? 2 : combos == 2 ? 4 : 1);
						Centuria.logger.debug(MarkerManager.getMarker("WhatTheHex"),
								"Element: " + elements.indexOf(ele) + ": " + ele.currentProgress);
						while (ele.currentProgress >= ele.levelMax) {
							int remaining = ele.currentProgress - ele.levelMax;

							// Give reward
							player.account.getSaveSpecificInventory().getItemAccessor(player)
									.add(ele.currencyRewardType, ele.currencyRewardAmount);

							// XP
							LevelEventBus.dispatch(new LevelEvent("levelevents.minigames.whatthehex",
									new String[] { "level:" + ele.level }, player));

							// Send packet
							MinigamePrizePacket p1 = new MinigamePrizePacket();
							p1.given = true;
							p1.itemDefId = ele.currencyRewardType;
							p1.itemCount = ele.currencyRewardAmount;
							p1.prizeIndex1 = elements.indexOf(ele);
							p1.prizeIndex2 = 0;
							player.client.sendPacket(p1);

							// Increase level
							loadLevel(ele, ele.level + 1, player);

							// Add remaining progress
							ele.currentProgress = remaining;

							// Add pending cyclone
							ele.pendingCyclones++;
						}
					}
				}
			} else {
				// Combine attached runes
				cyclones[getBoardIndex(x, y)] = compressRunes(x, y, tile.types[0], new ArrayList<Integer>());
				board[this.getBoardIndex(x, y)] = tile.types[0];
			}
		} else {
			// Remove surrounding non-cyclone tiles
			int p = getBoardIndex(x, y);
			int p1 = getBoardIndex(x - 1, y);
			int p2 = getBoardIndex(x - 1, y + 1);
			int p3 = getBoardIndex(x, y - 1);
			int p4 = getBoardIndex(x + 1, y - 1);
			int p5 = getBoardIndex(x + 1, y);
			int p6 = getBoardIndex(x, y + 1);

			// Check each
			if (p < 37 && cyclones[p] == 0)
				board[p] = 0;
			if (p1 < 37 && cyclones[p1] == 0)
				board[p1] = 0;
			if (p2 < 37 && cyclones[p2] == 0)
				board[p2] = 0;
			if (p3 < 37 && cyclones[p3] == 0)
				board[p3] = 0;
			if (p4 < 37 && cyclones[p4] == 0)
				board[p4] = 0;
			if (p5 < 37 && cyclones[p5] == 0)
				board[p5] = 0;
			if (p6 < 37 && cyclones[p6] == 0)
				board[p6] = 0;

			// Reset
			powerUpProgress = 0;
			activeBomb = false;
		}

		// Send packet
		XtWriter wr = new XtWriter();
		wr.writeInt(ind); // index
		wr.writeInt(x); // x
		wr.writeInt(y); // y
		// Level info
		wr.writeInt(score);
		wr.writeInt(powerUpProgress);
		wr.writeInt(elements.get(0).currentProgress);
		wr.writeInt(elements.get(1).currentProgress);
		wr.writeInt(elements.get(2).currentProgress);
		wr.writeInt(elements.get(0).level);
		wr.writeInt(elements.get(1).level);
		wr.writeInt(elements.get(2).level);
		wr.writeInt(0);
		MinigameMessagePacket pk = new MinigameMessagePacket();
		pk.command = "placeTile";
		pk.data = wr.encode().substring(4);
		player.client.sendPacket(pk);

		// Spawn tile
		wr = new XtWriter();
		wr.writeInt(ind);
		switch (ind) {
		case 0: {
			boolean handled = false;
			for (Element ele : elements) {
				// Check if there is a pending cyclone
				if (ele.pendingCyclones > 0) {
					handled = true;
					ele.pendingCyclones--;
					first = spawnTiles(wr, 0, 1, new byte[] { (byte) (5 + (ele.elementID - 2)) });
					break;
				}
			}
			if (!handled)
				first = spawnTiles(wr);
			break;
		}
		case 1: {
			boolean handled = false;
			for (Element ele : elements) {
				// Check if there is a pending cyclone
				if (ele.pendingCyclones > 0) {
					handled = true;
					ele.pendingCyclones--;
					second = spawnTiles(wr, 0, 1, new byte[] { (byte) (5 + (ele.elementID - 2)) });
					break;
				}
			}
			if (!handled)
				second = spawnTiles(wr);
			break;
		}
		case 2: {
			boolean handled = false;
			for (Element ele : elements) {
				// Check if there is a pending cyclone
				if (ele.pendingCyclones > 0) {
					handled = true;
					ele.pendingCyclones--;
					third = spawnTiles(wr, 0, 1, new byte[] { (byte) (5 + (ele.elementID - 2)) });
					break;
				}
			}
			if (!handled)
				third = spawnTiles(wr);
			break;
		}
		}

		// Game over check
		if (!activeBomb) {
			if (!canPlace(first.rotation) && !canPlace(second.rotation) && !canPlace(third.rotation)) {
				// Game over
				pk = new MinigameMessagePacket();
				pk.command = "gameOver";
				pk.data = "";
				player.client.sendPacket(pk);

				// Save highscore
				UserVarValue var = player.account.getSaveSpecificInventory().getUserVarAccesor()
						.getPlayerVarValue("4932", 0);
				int value = 0;
				if (var != null)
					value = var.value;
				if (score > value) {
					player.account.getSaveSpecificInventory().getUserVarAccesor().setPlayerVarValue("4932", 0, score);

					// Update client
					for (String change : player.account.getSaveSpecificInventory().getAccessor()
							.getChangedInventories())
						player.account.getSaveSpecificInventory().getAccessor().transferUpdatedItemsToPlayer(player,
								change);
				}
			}
		}

		pk = new MinigameMessagePacket();
		pk.command = "spawnTile";
		pk.data = wr.encode().substring(4);
		player.client.sendPacket(pk);
	}

	private boolean canPlace(int rotation) {
		for (int[] coordinates : boardIndex) {
			if (canPlace(coordinates[0], coordinates[1], rotation))
				return true;
		}
		return false;
	}

	private boolean canPlace(int x, int y, int rotation) {
		switch (rotation) {
		case 0: {
			if (board[this.getBoardIndex(x, y)] != 0)
				return false;
			break;
		}
		case 1: {
			if (this.getBoardIndex(x, y) >= 37 || this.getBoardIndex(x, y + 1) >= 37)
				return false;
			if (board[this.getBoardIndex(x, y)] != 0)
				return false;
			if (board[this.getBoardIndex(x, y + 1)] != 0)
				return false;
			break;
		}
		case 2: {
			if (this.getBoardIndex(x + 1, y) >= 37 || this.getBoardIndex(x, y) >= 37)
				return false;
			if (board[this.getBoardIndex(x + 1, y)] != 0)
				return false;
			if (board[this.getBoardIndex(x, y)] != 0)
				return false;
			break;
		}
		case 3: {
			if (this.getBoardIndex(x + 1, y - 1) >= 37 || this.getBoardIndex(x, y) >= 37)
				return false;
			if (board[this.getBoardIndex(x + 1, y - 1)] != 0)
				return false;
			if (board[this.getBoardIndex(x, y)] != 0)
				return false;
			break;
		}
		case 4: {
			if (this.getBoardIndex(x, y + 1) >= 37 || this.getBoardIndex(x, y) >= 37
					|| this.getBoardIndex(x, y - 1) >= 37)
				return false;
			if (board[this.getBoardIndex(x, y + 1)] != 0)
				return false;
			if (board[this.getBoardIndex(x, y)] != 0)
				return false;
			if (board[this.getBoardIndex(x, y - 1)] != 0)
				return false;
			break;
		}
		case 5: {
			if (this.getBoardIndex(x + 1, y) >= 37 || this.getBoardIndex(x, y) >= 37
					|| this.getBoardIndex(x - 1, y) >= 37)
				return false;
			if (board[this.getBoardIndex(x + 1, y)] != 0)
				return false;
			if (board[this.getBoardIndex(x, y)] != 0)
				return false;
			if (board[this.getBoardIndex(x - 1, y)] != 0)
				return false;
			break;
		}
		case 6: {
			if (this.getBoardIndex(x - 1, y + 1) >= 37 || this.getBoardIndex(x, y) >= 37
					|| this.getBoardIndex(x + 1, y - 1) >= 37)
				return false;
			if (board[this.getBoardIndex(x - 1, y + 1)] != 0)
				return false;
			if (board[this.getBoardIndex(x, y)] != 0)
				return false;
			if (board[this.getBoardIndex(x + 1, y - 1)] != 0)
				return false;
			break;
		}
		case 7: {
			if (this.getBoardIndex(x - 1, y) >= 37 || this.getBoardIndex(x, y) >= 37
					|| this.getBoardIndex(x + 1, y - 1) >= 37)
				return false;
			if (board[this.getBoardIndex(x - 1, y)] != 0)
				return false;
			if (board[this.getBoardIndex(x, y)] != 0)
				return false;
			if (board[this.getBoardIndex(x + 1, y - 1)] != 0)
				return false;
			break;
		}
		case 8: {
			if (this.getBoardIndex(x - 1, y + 1) >= 37 || this.getBoardIndex(x, y) >= 37
					|| this.getBoardIndex(x + 1, y) >= 37)
				return false;
			if (board[this.getBoardIndex(x - 1, y + 1)] != 0)
				return false;
			if (board[this.getBoardIndex(x, y)] != 0)
				return false;
			if (board[this.getBoardIndex(x + 1, y)] != 0)
				return false;
			break;
		}
		case 9: {
			if (this.getBoardIndex(x + 1, y) >= 37 || this.getBoardIndex(x, y) >= 37
					|| this.getBoardIndex(x, y - 1) >= 37)
				return false;
			if (board[this.getBoardIndex(x + 1, y)] != 0)
				return false;
			if (board[this.getBoardIndex(x, y)] != 0)
				return false;
			if (board[this.getBoardIndex(x, y - 1)] != 0)
				return false;
			break;
		}
		case 10: {
			if (this.getBoardIndex(x - 1, y + 1) >= 37 || this.getBoardIndex(x, y) >= 37
					|| this.getBoardIndex(x, y - 1) >= 37)
				return false;
			if (board[this.getBoardIndex(x - 1, y + 1)] != 0)
				return false;
			if (board[this.getBoardIndex(x, y)] != 0)
				return false;
			if (board[this.getBoardIndex(x, y - 1)] != 0)
				return false;
			break;
		}
		case 11: {
			if (this.getBoardIndex(x, y + 1) >= 37 || this.getBoardIndex(x, y) >= 37
					|| this.getBoardIndex(x + 1, y - 1) >= 37)
				return false;
			if (board[this.getBoardIndex(x, y + 1)] != 0)
				return false;
			if (board[this.getBoardIndex(x, y)] != 0)
				return false;
			if (board[this.getBoardIndex(x + 1, y - 1)] != 0)
				return false;
			break;
		}
		case 12: {
			if (this.getBoardIndex(x, y + 1) >= 37 || this.getBoardIndex(x, y) >= 37
					|| this.getBoardIndex(x - 1, y) >= 37)
				return false;
			if (board[this.getBoardIndex(x, y + 1)] != 0)
				return false;
			if (board[this.getBoardIndex(x, y)] != 0)
				return false;
			if (board[this.getBoardIndex(x - 1, y)] != 0)
				return false;
			break;
		}
		case 13: {
			if (this.getBoardIndex(x, y + 1) >= 37 || this.getBoardIndex(x, y) >= 37
					|| this.getBoardIndex(x + 1, y) >= 37)
				return false;
			if (board[this.getBoardIndex(x, y + 1)] != 0)
				return false;
			if (board[this.getBoardIndex(x, y)] != 0)
				return false;
			if (board[this.getBoardIndex(x + 1, y)] != 0)
				return false;
			break;
		}
		}

		return true;
	}

	// Cyclone code to compress runes
	private int compressRunes(int x, int y, byte type, ArrayList<Integer> checked) {
		int res = 0;

		// Check surrounding tiles
		byte p1 = getOnBoard(x - 1, y);
		byte p2 = getOnBoard(x - 1, y + 1);
		byte p3 = getOnBoard(x, y - 1);
		byte p4 = getOnBoard(x + 1, y - 1);
		byte p5 = getOnBoard(x + 1, y);
		byte p6 = getOnBoard(x, y + 1);

		// Check attaching tiles
		res += compress(p1, type, checked, x - 1, y);
		res += compress(p2, type, checked, x - 1, y + 1);
		res += compress(p3, type, checked, x, y - 1);
		res += compress(p4, type, checked, x + 1, y - 1);
		res += compress(p5, type, checked, x + 1, y);
		res += compress(p6, type, checked, x, y + 1);

		return res;
	}

	// Part of the above method
	private int compress(byte p1, byte type, ArrayList<Integer> checked, int x, int y) {
		int res = 0;
		int p = getBoardIndex(x, y);
		if (p1 == type && !checked.contains(getBoardIndex(x, y))) {
			board[p] = 0;
			res++;
			if (cyclones[p] != 0) {
				res += cyclones[p] - 1;
				cyclones[p] = 0;
			}
			checked.add(p);
			res += compressRunes(x, y, type, checked);
		}
		return res;
	}

	// Method to check connecting tiles
	public int tileScore(int x, int y, ArrayList<Integer> checked) {
		int res = 0;

		// Check surrounding tiles
		byte type = getOnBoard(x, y);
		byte p1 = getOnBoard(x - 1, y);
		byte p2 = getOnBoard(x - 1, y + 1);
		byte p3 = getOnBoard(x, y - 1);
		byte p4 = getOnBoard(x + 1, y - 1);
		byte p5 = getOnBoard(x + 1, y);
		byte p6 = getOnBoard(x, y + 1);

		// Check attaching tiles
		res += handleSurrounding(p1, type, checked, x - 1, y);
		res += handleSurrounding(p2, type, checked, x - 1, y + 1);
		res += handleSurrounding(p3, type, checked, x, y - 1);
		res += handleSurrounding(p4, type, checked, x + 1, y - 1);
		res += handleSurrounding(p5, type, checked, x + 1, y);
		res += handleSurrounding(p6, type, checked, x, y + 1);

		return res;
	}

	// Part of the above method
	private int handleSurrounding(byte p1, byte type, ArrayList<Integer> checked, int x, int y) {
		int res = 0;
		if (p1 == type && !checked.contains(getBoardIndex(x, y))) {
			int p = getBoardIndex(x, y);
			if (cyclones[p] == 0)
				res++;
			else
				res += cyclones[p];
			checked.add(p);
			res += tileScore(x, y, checked);
		}
		return res;
	}

	private byte getOnBoard(int x, int y) {
		int p = this.getBoardIndex(x, y);
		if (p < 0 || p >= 37)
			return -1;
		else
			return board[p];
	}

	@MinigameMessage("startGame")
	public void startGame(Player player, XtReader rd) {
		// Save highscore
		UserVarValue var = player.account.getSaveSpecificInventory().getUserVarAccesor().getPlayerVarValue("4932", 0);
		int value = 0;
		if (var != null)
			value = var.value;
		if (score > value) {
			player.account.getSaveSpecificInventory().getUserVarAccesor().setPlayerVarValue("4932", 0, score);

			// Update client
			for (String change : player.account.getSaveSpecificInventory().getAccessor().getChangedInventories())
				player.account.getSaveSpecificInventory().getAccessor().transferUpdatedItemsToPlayer(player, change);
		}

		// Start game
		score = 0;
		powerUpProgress = 0;
		board = new byte[board.length];
		cyclones = new int[cyclones.length];
		firstExtraRotation = 0;
		secondExtraRotation = 0;
		thirdExtraRotation = 0;
		activeBomb = false;

		// Set up first level
		elements.clear();
		Element flame = new Element();
		flame.elementID = 2;
		elements.add(flame);
		loadLevel(flame, 1, player);
		Element flora = new Element();
		flora.elementID = 3;
		elements.add(flora);
		loadLevel(flora, 1, player);
		Element miasma = new Element();
		miasma.elementID = 4;
		elements.add(miasma);
		loadLevel(miasma, 1, player);

		// Start the game
		XtWriter wr = new XtWriter();
		first = spawnTiles(wr);
		second = spawnTiles(wr);
		third = spawnTiles(wr);
		MinigameMessagePacket pk = new MinigameMessagePacket();
		pk.command = "startGame";
		pk.data = wr.encode().substring(4);
		player.client.sendPacket(pk);
	}

	// Simple shortcut to loading levels
	private void loadLevel(Element element, int level, Player player) {
		// Find level
		WTHLevelInfo lv = null;
		if (level - 1 >= levels.size()) {
			// Fallback
			lv = levels.get(levels.size() - 1);
		} else {
			// Select level
			lv = levels.get(level - 1);
		}

		// Level info
		element.levelMax = lv.levelMax;
		element.currentProgress = 0;
		element.level = level;

		// Find element type
		String elementType = "unknown";
		switch (elements.indexOf(element)) {
		case 0: {
			elementType = "flame";
			break;
		}
		case 1: {
			elementType = "flora";
			break;
		}
		case 2: {
			elementType = "miasma";
			break;
		}
		}

		// Find reward
		if (lv.rewards.containsKey(elementType)) {
			WTHRewardInfo reward = lv.rewards.get(elementType);

			// Find item
			if (reward.type == RewardType.ITEM) {
				// Item
				element.currencyRewardType = reward.id;
				element.currencyRewardAmount = reward.count;
			} else {
				// Loot
				LootInfo loot = ResourceCollectionModule.getLootReward(reward.id, player.account);
				while (true) {
					if (loot.reward == null)
						break;

					if (loot.reward.referencedTableId != null)
						loot = ResourceCollectionModule.getLootReward(loot.reward.referencedTableId, player.account);
					else {
						element.currencyRewardType = loot.reward.itemId;
						element.currencyRewardAmount = reward.count * loot.count;
						break;
					}
				}
			}
		}

		// Set up rewards
		MinigamePrizePacket p1 = new MinigamePrizePacket();
		p1.given = false;
		p1.itemDefId = element.currencyRewardType;
		p1.itemCount = element.currencyRewardAmount;
		p1.prizeIndex1 = elements.indexOf(element);
		p1.prizeIndex2 = 0;
		player.client.sendPacket(p1);
	}

	@Override
	public AbstractMinigame instantiate() {
		return new GameWhatTheHex();
	}

	@Override
	public void onExit(Player player) {
		// Save highscore
		UserVarValue var = player.account.getSaveSpecificInventory().getUserVarAccesor().getPlayerVarValue("4932", 0);
		int value = 0;
		if (var != null)
			value = var.value;
		if (score > value) {
			player.account.getSaveSpecificInventory().getUserVarAccesor().setPlayerVarValue("4932", 0, score);
			for (String change : player.account.getSaveSpecificInventory().getAccessor().getChangedInventories())
				player.account.getSaveSpecificInventory().getAccessor().transferUpdatedItemsToPlayer(player, change);
		}
	}

}
