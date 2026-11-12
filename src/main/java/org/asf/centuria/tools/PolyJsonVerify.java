package org.asf.centuria.tools;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.security.PublicKey;
import java.security.Security;

import org.asf.centuria.updater.PolyTools;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class PolyJsonVerify {

	public static void main(String[] args) throws IOException {
		if (args.length < 2) {
			System.err.println("Usage: \"<keyfile>\" \"<json file>\"");
			System.exit(1);
			return;
		}
		String keyFile = args[0];
		String json = args[1];
		Security.addProvider(new BouncyCastleProvider());
		PublicKey pub = PolyTools.loadPublic(new File(keyFile));
		FileReader reader = new FileReader(new File(json));
		JsonObject content = JsonParser.parseReader(reader).getAsJsonObject();
		reader.close();

		// Sign
		JsonElement updated = PolyTools.verify(pub, content);
		if (updated == null) {
			System.err.println("Signature validation error");
			System.exit(1);
			return;
		}
		System.out.println(updated.toString());
	}

}
