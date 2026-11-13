package org.asf.centuria.shops.info;

import java.util.ArrayList;
import java.util.LinkedHashMap;

public class ShopInfo {

	public String object;
	public int restockTime;
	public LinkedHashMap<String, ShopItem> contents = new LinkedHashMap<String, ShopItem>();
	public ArrayList<String> enigmas = new ArrayList<String>();

}
