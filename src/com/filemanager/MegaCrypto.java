package com.filemanager;

import java.io.*;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Crypto helpers for the MEGA protocol (no Android or JSON dependencies). */
class MegaCrypto {
	// ---- MEGA base64: url-safe alphabet, no padding (own codec so it also runs on old Android) ----
	private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

	static byte[] b64d(String s) {
		s = s.replace('+', '-').replace('/', '_').replace(",", "").replace("=", "");
		int n = s.length();
		byte[] out = new byte[n * 6 / 8];
		int acc = 0, bits = 0, o = 0;
		for (int i = 0; i < n; i++) {
			int v = B64.indexOf(s.charAt(i));
			if (v < 0)
				continue;
			acc = (acc << 6) | v;
			bits += 6;
			if (bits >= 8) {
				bits -= 8;
				if (o < out.length)
					out[o++] = (byte) ((acc >> bits) & 0xff);
			}
		}
		if (o == out.length)
			return out;
		byte[] r = new byte[o];
		System.arraycopy(out, 0, r, 0, o);
		return r;
	}

	static String b64e(byte[] b) {
		StringBuilder sb = new StringBuilder();
		int acc = 0, bits = 0;
		for (int i = 0; i < b.length; i++) {
			acc = (acc << 8) | (b[i] & 0xff);
			bits += 8;
			while (bits >= 6) {
				bits -= 6;
				sb.append(B64.charAt((acc >> bits) & 63));
			}
			acc &= (1 << bits) - 1;
		}
		if (bits > 0)
			sb.append(B64.charAt((acc << (6 - bits)) & 63));
		return sb.toString();
	}

	static byte[] aesEcb(byte[] data, byte[] key, boolean encrypt) throws IOException {
		try {
			Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
			c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
			return c.doFinal(data);
		} catch (GeneralSecurityException e) {
			throw new IOException("Crypto error: " + e.getMessage());
		}
	}

	static byte[] aesCbcDecryptZeroIv(byte[] data, byte[] key) throws IOException {
		try {
			Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
			c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
			return c.doFinal(data);
		} catch (GeneralSecurityException e) {
			throw new IOException("Crypto error: " + e.getMessage());
		}
	}

	/** decrypts a key blob (16 or 32 bytes) with an AES-ECB key, block by block */
	static byte[] decryptKey(byte[] enc, byte[] key) throws IOException {
		if (enc.length % 16 != 0)
			throw new IOException("Bad key length");
		return aesEcb(enc, key, false);
	}

	/** file keys are 32 bytes: AES key = first half xor second half; a folder key is 16 bytes as is */
	static byte[] attrKey(byte[] nodeKey) {
		if (nodeKey.length < 32)
			return nodeKey;
		byte[] k = new byte[16];
		for (int i = 0; i < 16; i++)
			k[i] = (byte) (nodeKey[i] ^ nodeKey[i + 16]);
		return k;
	}

	/** "MEGA{...json...}" -> json text, or null when the key was wrong */
	static String decryptAttr(String a, byte[] nodeKey) throws IOException {
		byte[] raw = b64d(a);
		int len = raw.length - raw.length % 16;
		if (len == 0)
			return null;
		byte[] pad = new byte[len];
		System.arraycopy(raw, 0, pad, 0, len);
		byte[] plain = aesCbcDecryptZeroIv(pad, attrKey(nodeKey));
		if (plain.length < 5 || plain[0] != 'M' || plain[1] != 'E' || plain[2] != 'G' || plain[3] != 'A')
			return null;
		int end = plain.length;
		while (end > 4 && plain[end - 1] == 0)
			end--;
		return new String(plain, 4, end - 4, "UTF-8");
	}

	// ---- account version 2: PBKDF2-HMAC-SHA512, 100000 rounds ----
	static byte[] pbkdf2Sha512(byte[] pw, byte[] salt, int iter, int dkLen) throws IOException {
		try {
			Mac mac = Mac.getInstance("HmacSHA512");
			mac.init(new SecretKeySpec(pw, "HmacSHA512"));
			int hl = 64, blocks = (dkLen + hl - 1) / hl;
			byte[] out = new byte[dkLen];
			for (int b = 1; b <= blocks; b++) {
				mac.update(salt);
				mac.update(new byte[] { (byte) (b >>> 24), (byte) (b >>> 16), (byte) (b >>> 8), (byte) b });
				byte[] u = mac.doFinal();
				byte[] t = u.clone();
				for (int i = 1; i < iter; i++) {
					u = mac.doFinal(u);
					for (int j = 0; j < hl; j++)
						t[j] ^= u[j];
				}
				System.arraycopy(t, 0, out, (b - 1) * hl, Math.min(hl, dkLen - (b - 1) * hl));
			}
			return out;
		} catch (GeneralSecurityException e) {
			throw new IOException("Crypto error: " + e.getMessage());
		}
	}

	// ---- account version 1: 65536 rounds of AES, then a 16384 round hash of the e-mail ----
	static byte[] strToBytesPadded(byte[] b) {
		int n = (b.length + 3) / 4 * 4;
		byte[] o = new byte[n];
		System.arraycopy(b, 0, o, 0, b.length);
		return o;
	}

	static byte[] prepareKeyV1(String password) throws IOException {
		byte[] arr = strToBytesPadded(password.getBytes("UTF-8")); // big-endian 32-bit words
		byte[] pkey = { (byte) 0x93, (byte) 0xC4, (byte) 0x67, (byte) 0xE3, 0x7D, (byte) 0xB0, (byte) 0xC7, (byte) 0xA4,
				(byte) 0xD1, (byte) 0xBE, 0x3F, (byte) 0x81, 0x01, 0x52, (byte) 0xCB, 0x56 };
		try {
			byte[][] keys = new byte[(arr.length + 15) / 16][16];
			for (int j = 0; j < keys.length; j++)
				System.arraycopy(arr, j * 16, keys[j], 0, Math.min(16, arr.length - j * 16));
			Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
			for (int r = 0; r < 65536; r++)
				for (int j = 0; j < keys.length; j++) {
					c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys[j], "AES"));
					pkey = c.doFinal(pkey);
				}
			return pkey;
		} catch (GeneralSecurityException e) {
			throw new IOException("Crypto error: " + e.getMessage());
		}
	}

	static String stringHashV1(String s, byte[] aesKey) throws IOException {
		byte[] sb = strToBytesPadded(s.getBytes("UTF-8"));
		byte[] h = new byte[16];
		for (int i = 0; i < sb.length / 4; i++)
			for (int k = 0; k < 4; k++)
				h[(i % 4) * 4 + k] ^= sb[i * 4 + k];
		try {
			Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
			c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"));
			for (int r = 0; r < 16384; r++)
				h = c.doFinal(h);
		} catch (GeneralSecurityException e) {
			throw new IOException("Crypto error: " + e.getMessage());
		}
		byte[] out = new byte[8]; // words 0 and 2
		System.arraycopy(h, 0, out, 0, 4);
		System.arraycopy(h, 8, out, 4, 4);
		return b64e(out);
	}

	// ---- session id: RSA-decrypt "csid" with the account's private key ----
	static BigInteger mpi(byte[] b, int off, int[] lenOut) {
		int bits = ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
		int bytes = (bits + 7) / 8;
		lenOut[0] = bytes + 2;
		byte[] m = new byte[bytes];
		System.arraycopy(b, off + 2, m, 0, bytes);
		return new BigInteger(1, m);
	}

	static String sessionIdFromCsid(String csid, String privk, byte[] masterKey) throws IOException {
		byte[] pk = decryptKey(padTo16(b64d(privk)), masterKey);
		int[] l = new int[1];
		int off = 0;
		BigInteger[] parts = new BigInteger[4];
		for (int i = 0; i < 4; i++) {
			if (off + 2 > pk.length)
				throw new IOException("Bad private key");
			parts[i] = mpi(pk, off, l);
			off += l[0];
		}
		BigInteger n = parts[0].multiply(parts[1]);
		byte[] cb = b64d(csid);
		int[] l2 = new int[1];
		BigInteger c = mpi(cb, 0, l2);
		BigInteger m = c.modPow(parts[2], n);
		byte[] mb = m.toByteArray();
		int s = (mb.length > 1 && mb[0] == 0) ? 1 : 0;
		int len = Math.min(43, mb.length - s);
		byte[] sid = new byte[len];
		System.arraycopy(mb, s, sid, 0, len);
		return b64e(sid);
	}

	static byte[] padTo16(byte[] b) {
		int n = (b.length + 15) / 16 * 16;
		if (n == b.length)
			return b;
		byte[] o = new byte[n];
		System.arraycopy(b, 0, o, 0, b.length);
		return o;
	}

	// ---- upload: random keys, attribute encryption, chunk MACs ----
	static byte[] random(int n) {
		byte[] b = new byte[n];
		new java.security.SecureRandom().nextBytes(b);
		return b;
	}

	static byte[] aesCbcEncryptZeroIv(byte[] data, byte[] key) throws IOException {
		try {
			Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
			c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
			return c.doFinal(data);
		} catch (GeneralSecurityException e) {
			throw new IOException("Crypto error: " + e.getMessage());
		}
	}

	/** json text -> base64 of AES-CBC("MEGA" + json, zero padded), the key is the node's attribute key */
	static String encryptAttr(String json, byte[] nodeKey) throws IOException {
		byte[] j = json.getBytes("UTF-8");
		byte[] raw = new byte[4 + j.length];
		raw[0] = 'M';
		raw[1] = 'E';
		raw[2] = 'G';
		raw[3] = 'A';
		System.arraycopy(j, 0, raw, 4, j.length);
		return b64e(aesCbcEncryptZeroIv(padTo16(raw), attrKey(nodeKey)));
	}

	/** AES-CTR encryptor for uploads: iv = 8 byte nonce + 8 zero counter bytes (same layout the download side uses) */
	static Cipher ctrEncryptor(byte[] aesKey, byte[] nonce) throws IOException {
		try {
			byte[] iv = new byte[16];
			System.arraycopy(nonce, 0, iv, 0, 8);
			Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
			c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
			return c;
		} catch (GeneralSecurityException e) {
			throw new IOException("Crypto error: " + e.getMessage());
		}
	}

	/** CBC-MAC of one upload chunk: iv = nonce + nonce, last block zero padded, result = last cipher block */
	static byte[] chunkMac(byte[] data, int len, byte[] aesKey, byte[] nonce) throws IOException {
		try {
			byte[] iv = new byte[16];
			System.arraycopy(nonce, 0, iv, 0, 8);
			System.arraycopy(nonce, 0, iv, 8, 8);
			int n = (len + 15) / 16 * 16;
			byte[] p = new byte[n];
			System.arraycopy(data, 0, p, 0, len);
			Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
			c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
			byte[] out = c.doFinal(p);
			byte[] mac = new byte[16];
			System.arraycopy(out, n - 16, mac, 0, 16);
			return mac;
		} catch (GeneralSecurityException e) {
			throw new IOException("Crypto error: " + e.getMessage());
		}
	}

	/** running file MAC: fileMac = AES(fileMac xor chunkMac) */
	static byte[] foldMac(byte[] fileMac, byte[] chunkMac, byte[] aesKey) throws IOException {
		byte[] x = new byte[16];
		for (int i = 0; i < 16; i++)
			x[i] = (byte) (fileMac[i] ^ chunkMac[i]);
		return aesEcb(x, aesKey, true);
	}

	/** the 32 byte file node key: (aesKey xor (nonce + meta)) + nonce + meta, meta = folded file MAC */
	static byte[] fileNodeKey(byte[] aesKey, byte[] nonce, byte[] fileMac) {
		byte[] meta = new byte[8];
		for (int i = 0; i < 4; i++) {
			meta[i] = (byte) (fileMac[i] ^ fileMac[4 + i]);
			meta[4 + i] = (byte) (fileMac[8 + i] ^ fileMac[12 + i]);
		}
		byte[] nk = new byte[32];
		for (int i = 0; i < 8; i++) {
			nk[i] = (byte) (aesKey[i] ^ nonce[i]);
			nk[8 + i] = (byte) (aesKey[8 + i] ^ meta[i]);
			nk[16 + i] = nonce[i];
			nk[24 + i] = meta[i];
		}
		return nk;
	}

	// ---- file download decryption: AES-CTR, nonce = words 4..5 of the file key ----
	static InputStream ctrStream(final InputStream raw, byte[] fileKey) throws IOException {
		if (fileKey.length < 32)
			throw new IOException("Not a file key");
		byte[] aesKey = attrKey(fileKey);
		byte[] iv = new byte[16];
		System.arraycopy(fileKey, 16, iv, 0, 8);
		final Cipher c;
		try {
			c = Cipher.getInstance("AES/CTR/NoPadding");
			c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
		} catch (GeneralSecurityException e) {
			throw new IOException("Crypto error: " + e.getMessage());
		}
		return new InputStream() {
			public int read() throws IOException {
				byte[] b = new byte[1];
				int n = read(b, 0, 1);
				return n < 0 ? -1 : (b[0] & 0xff);
			}

			public int read(byte[] b, int off, int len) throws IOException {
				int n = raw.read(b, off, len);
				if (n > 0) {
					byte[] p = c.update(b, off, n);
					if (p != null)
						System.arraycopy(p, 0, b, off, p.length);
				}
				return n;
			}

			public void close() throws IOException {
				raw.close();
			}
		};
	}
}
