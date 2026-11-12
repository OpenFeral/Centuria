package org.asf.centuria.tools;

import java.io.File;
import java.io.IOException;
import java.security.Security;

import org.asf.centuria.updater.PolyTools;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

public class PolyPairGenerator {

	public static void main(String[] args) throws IOException {
		if (args.length < 2) {
			System.err.println("Usage: \"<private key>\" \"<public key>\"");
			System.exit(1);
			return;
		}
		String privI = args[0];
		String pubI = args[1];
		Security.addProvider(new BouncyCastleProvider());
		PolyTools.generateKeyPair(new File(pubI), new File(privI));
	}

}
