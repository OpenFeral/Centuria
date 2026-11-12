package org.asf.centuria;

import java.io.File;
import java.io.IOException;

import org.asf.centuria.updater.PolyUpdaterInstaller;

public class CenturiaUpdater {

	public static void main(String[] args) throws IOException {
		// Updater
		if (args.length == 1 && args[0].equals("--update")) {
			System.out.println("Updating Centuria through PolyUpdater...");
			File packageCache = new File("updater/packages");

			// Check validity
			if (!packageCache.exists()) {
				System.err.println("Update folder missing!");
				System.exit(1);
			}

			// Init
			System.out.println("Initializing PolyUpdater...");
			PolyUpdaterInstaller.init(packageCache, new File("."));

			// Install
			System.out.println("Installing updated collections...");
			PolyUpdaterInstaller.installAll();

			System.exit(0);
		}
	}

}
