package org.asf.centuria.shops.info;

import java.util.LinkedHashMap;

public class ShopItem {

	public String objectName;
	public int stock;
	public int requiredLevel;
	public LinkedHashMap<String, Integer> items = new LinkedHashMap<String, Integer>();
	public LinkedHashMap<String, Integer> cost = new LinkedHashMap<String, Integer>();
	public LinkedHashMap<String, Integer> eurekaItems = new LinkedHashMap<String, Integer>();

}
