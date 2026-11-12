package org.asf.centuria.tools;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.security.PrivateKey;
import java.security.Security;

import org.asf.centuria.updater.PolyTools;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

public class PolyJsonSign {

	public static void main(String[] args) throws IOException {
		if (args.length < 2) {
			System.err.println("Usage: \"<keyfile>\" \"<json file>\"");
			System.exit(1);
			return;
		}
		String keyFile = args[0];
		String json = args[1];
		Security.addProvider(new BouncyCastleProvider());
		PrivateKey priv = PolyTools.loadPrivate(new File(keyFile));
		FileReader reader = new FileReader(new File(json));
		JsonElement content = JsonParser.parseReader(reader);
		reader.close();

		// Sign
		JsonElement updated = PolyTools.sign(priv, content);
		System.out.println(updated.toString());
	}

}
