package org.asf.centuria.seasonpasses;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;

/**
 * 
 * Season Pass Definition
 * 
 * @author Sky Swimmer
 *
 */
public class SeasonPassDefinition {

	public String name;
	public String objectName;
	public String passDefID;

	public Date startDate;
	public Date endDate;

	public long startDateLiveTimestamp;
	public long endDateLiveTimestamp;

	public boolean yearly;

	public String premiumPassID;

	public ArrayList<String> tiers = new ArrayList<String>();
	public ArrayList<String> challenges = new ArrayList<String>();

	/**
	 * Updates the timestamps on which the season pass is active
	 */
	public void updateTimestamps() {
		// Check if currently active
		long start = startDate.getTime();
		long end = endDate.getTime();

		// Check if active
		if (!(System.currentTimeMillis() > start && System.currentTimeMillis() < end) && end > start) {
			// Not active, try changing to local year
			Calendar calCurrent = Calendar.getInstance();
			calCurrent.setTime(new Date(System.currentTimeMillis()));
			Calendar calStart = Calendar.getInstance();
			calStart.setTime(startDate);
			Calendar calEnd = Calendar.getInstance();
			calEnd.setTime(endDate);

			// Get deviation
			int yDeviation = calEnd.get(Calendar.YEAR) - calStart.get(Calendar.YEAR);

			// Update
			calStart.set(Calendar.YEAR, calCurrent.get(Calendar.YEAR));
			calEnd.set(Calendar.YEAR, calCurrent.get(Calendar.YEAR) + yDeviation);

			// Set result
			start = calStart.getTimeInMillis();
			end = calEnd.getTimeInMillis();
		}

		// Apply
		startDateLiveTimestamp = start;
		endDateLiveTimestamp = end;
	}

}
