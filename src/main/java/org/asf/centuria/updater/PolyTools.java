package org.asf.centuria.updater;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.bouncycastle.pqc.jcajce.spec.DilithiumParameterSpec;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class PolyTools {

	private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

	// PEM parser
	private static byte[] pemDecode(String pem) {
		String base64 = pem.replace("\r", "");

		// Strip header
		while (base64.startsWith("-"))
			base64 = base64.substring(1);
		while (!base64.startsWith("-"))
			base64 = base64.substring(1);
		while (base64.startsWith("-"))
			base64 = base64.substring(1);

		// Clean data
		base64 = base64.replace("\n", "");

		// Strip footer
		while (base64.endsWith("-"))
			base64 = base64.substring(0, base64.length() - 1);
		while (!base64.endsWith("-"))
			base64 = base64.substring(0, base64.length() - 1);
		while (base64.endsWith("-"))
			base64 = base64.substring(0, base64.length() - 1);

		// Decode and return
		return Base64.getDecoder().decode(base64);
	}

	// PEM emitter
	private static String pemEncode(byte[] key, String type) {
		// Generate header
		String PEM = "-----BEGIN " + type + " KEY-----";

		// Generate payload
		String base64 = new String(Base64.getEncoder().encode(key));

		// Generate PEM
		while (true) {
			PEM += "\n";
			boolean done = false;
			for (int i = 0; i < 64; i++) {
				if (base64.isEmpty()) {
					done = true;
					break;
				}
				PEM += base64.substring(0, 1);
				base64 = base64.substring(1);
			}
			if (base64.isEmpty())
				break;
			if (done)
				break;
		}

		// Append footer
		PEM += "\n";
		PEM += "-----END " + type + " KEY-----";

		// Return PEM data
		return PEM;
	}

	public static PrivateKey loadPrivate(File privateKey) throws IOException {
		// Load keys
		try {
			KeyFactory fac = KeyFactory.getInstance("Dilithium");
			return fac.generatePrivate(new PKCS8EncodedKeySpec(pemDecode(Files.readString(privateKey.toPath()))));
		} catch (InvalidKeySpecException | NoSuchAlgorithmException e1) {
			throw new RuntimeException(e1);
		}
	}

	public static PublicKey loadPublic(File publicKey) throws IOException {
		// Load keys
		try {
			KeyFactory fac = KeyFactory.getInstance("Dilithium");
			return fac.generatePublic(new X509EncodedKeySpec(pemDecode(Files.readString(publicKey.toPath()))));
		} catch (InvalidKeySpecException | NoSuchAlgorithmException e1) {
			throw new RuntimeException(e1);
		}
	}
	
	public static String sha256Hash(InputStream stream) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			while (true) {
				byte[] data = new byte[20480];
				int i = stream.read(data);
				if (i <= 0)
					break;
				digest.update(data, 0, i);
			}
			return bytesToHex(digest.digest()).toLowerCase();
		} catch (NoSuchAlgorithmException | IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static String sha256Hash(byte[] data) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(data);
			return bytesToHex(hash).toLowerCase();
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	public static String bytesToHex(byte[] bytes) {
		char[] hexChars = new char[bytes.length * 2];
		for (int j = 0; j < bytes.length; j++) {
			int v = bytes[j] & 0xFF;
			hexChars[j * 2] = HEX_ARRAY[v >>> 4];
			hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
		}
		return new String(hexChars);
	}

	public static KeyPair generateKeyPair(File publicKey, File privateKey) throws IOException {
		// Generate new keys
		KeyPairGenerator gen;
		try {
			gen = KeyPairGenerator.getInstance("Dilithium");
			gen.initialize(DilithiumParameterSpec.dilithium5);
		} catch (InvalidAlgorithmParameterException | NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
		KeyPair pair = gen.generateKeyPair();

		// Save keys
		Files.writeString(publicKey.toPath(), pemEncode(pair.getPublic().getEncoded(), "PUBLIC"));
		Files.writeString(privateKey.toPath(), pemEncode(pair.getPrivate().getEncoded(), "PRIVATE"));
		return pair;
	}

	// Signature generator
	public static byte[] sign(PrivateKey privateKey, byte[] data) {
		try {
			Signature sig = Signature.getInstance("Dilithium");
			sig.initSign(privateKey);
			sig.update(data);
			return sig.sign();
		} catch (SignatureException | NoSuchAlgorithmException | InvalidKeyException e) {
			throw new RuntimeException(e);
		}
	}

	// Signature verification
	public static boolean verify(PublicKey publicKey, byte[] data, byte[] signature) {
		try {
			Signature sig = Signature.getInstance("Dilithium");
			sig.initVerify(publicKey);
			sig.update(data);
			return sig.verify(signature);
		} catch (SignatureException | NoSuchAlgorithmException | InvalidKeyException e) {
			return false;
		}
	}

	public static JsonObject sign(PrivateKey priv, JsonElement content) {
		try {
			// Sign
			JsonElement newContent = content.deepCopy();
			JsonObject dataJson = new JsonObject();
			dataJson.add("content", newContent);
			byte[] data = newContent.toString().getBytes("UTF-8");
			byte[] sig = PolyTools.sign(priv, data);
			dataJson.addProperty("signature", Base64.getEncoder().encodeToString(sig));
			return dataJson;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static JsonElement verify(PublicKey publicKey, JsonObject content) {
		try {
			// Sign
			JsonObject newContent = content.deepCopy();
			if (!newContent.has("signature"))
				return null;
			JsonElement ele = newContent.get("content");
			byte[] data = ele.toString().getBytes("UTF-8");
			byte[] sig = Base64.getDecoder().decode(newContent.get("signature").getAsString());
			if (!verify(publicKey, data, sig))
				return null;
			return ele;
		} catch (Exception e) {
			return null;
		}
	}

}
