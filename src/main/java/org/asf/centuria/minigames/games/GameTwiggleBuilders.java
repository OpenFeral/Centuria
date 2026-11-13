package org.asf.centuria.minigames.games;

import org.asf.centuria.data.XtReader;
import org.asf.centuria.data.XtWriter;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.entities.uservars.UserVarValue;
import org.asf.centuria.minigames.AbstractMinigame;
import org.asf.centuria.minigames.MinigameMessage;
import org.asf.centuria.packets.xt.gameserver.inventory.InventoryItemPacket;
import org.asf.centuria.packets.xt.gameserver.minigame.MinigameCurrencyPacket;
import org.asf.centuria.packets.xt.gameserver.minigame.MinigameMessagePacket;
import org.asf.centuria.packets.xt.gameserver.minigame.MinigamePrizePacket;;

public class GameTwiggleBuilders extends AbstractMinigame {

	public int level;
	public int score;
	public String results;

	@Override
	public boolean canHandle(int levelID) {
		return levelID == 4111;
	}

	@Override
	public void onJoin(Player plr) {
		MinigameCurrencyPacket currency = new MinigameCurrencyPacket();
		currency.Currency = 9514;
		plr.client.sendPacket(currency);
	}

	@MinigameMessage("startLevel")
	public void startLevel(Player plr, XtReader rd) {
		//Deserialize
		level = rd.readInt();

		//Reply
		XtWriter wr1 = new XtWriter();
		wr1.writeInt(level); // IngredientScore
		MinigameMessagePacket pk1 = new MinigameMessagePacket();
		pk1.command = "startLevel";
		pk1.data = wr1.encode().substring(4);
		plr.client.sendPacket(pk1);
	}

	@MinigameMessage("requestResults")
	public void requestResults(Player plr, XtReader rd) {
		results = rd.readRemaining();
	}

	@MinigameMessage("endLevel")
	public void endLevel(Player plr, XtReader rd) {
		//TODO: Use results to calculate score and rewards

		if (results.isEmpty()){
			score = 0;
		}
		else{
			//15 per star * 3
			score = 45;
		}

		// Save score
		UserVarValue var = plr.account.getSaveSpecificInventory().getUserVarAccesor().getPlayerVarValue(9311,
		level);
		int value2 = 0;
		if (var != null)
			value2 = var.value;
		if (score > value2)
			plr.account.getSaveSpecificInventory().getUserVarAccesor().setPlayerVarValue(9311, level, score);

		// Update client
		InventoryItemPacket pkt = new InventoryItemPacket();
		pkt.item = plr.account.getSaveSpecificInventory().getItem("303");
		plr.client.sendPacket(pkt);

		//Prize
		givePrize(plr);
		plr.account.getSaveSpecificInventory().getCurrencyAccessor().addLikes(plr.client,score);


		//End
		XtWriter wr1 = new XtWriter();
		wr1.writeInt(score);
		MinigameMessagePacket pk1 = new MinigameMessagePacket();
		pk1.command = "endLevel";
		pk1.data = wr1.encode().substring(4);
		plr.client.sendPacket(pk1);
	}

	public void givePrize(Player plr) {
		MinigamePrizePacket prize = new MinigamePrizePacket();
		prize.itemDefId = "2327";
		prize.itemCount = 15;
		prize.given = true;
		prize.prizeIndex1 = 6566;
		prize.prizeIndex2 = 0;
		plr.client.sendPacket(prize);

		MinigamePrizePacket prize1 = new MinigamePrizePacket();
		prize1.itemDefId = "2327";
		prize1.itemCount = 15;
		prize1.given = true;
		prize1.prizeIndex1 = 6567;
		prize1.prizeIndex2 = 1;
		plr.client.sendPacket(prize1);

		MinigamePrizePacket prize2 = new MinigamePrizePacket();
		prize2.itemDefId = "2327";
		prize2.itemCount = 15;
		prize2.given = true;
		prize2.prizeIndex1 = 6572;
		prize2.prizeIndex2 = 2;
		plr.client.sendPacket(prize2);
	}

	@Override
	public AbstractMinigame instantiate() {
		return new GameTwiggleBuilders();
	}

	@Override
	public void onExit(Player player) {
	}

}
