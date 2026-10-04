package com.filemanager;

import java.io.*;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.zip.*;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Thrown when an encrypted entry needs a password, or the password is wrong. */
class PasswordException extends IOException {
	PasswordException(String m) {
		super(m);
	}
}

/** One entry of a zip central directory (same getters as java.util.zip.ZipEntry). */
class PEntry {
	String name;
	long size, csize, time = -1, localOff, crc;
	int flags, method, dosTime, aesStrength, aesMethod;
	boolean aes;
	ZipEntry ze; // set only when java.util.zip handles the archive (zip64)

	public String getName() {
		return name;
	}

	public long getSize() {
		return size;
	}

	public long getTime() {
		return time;
	}

	public boolean isDirectory() {
		return name.endsWith("/");
	}

	boolean isEncrypted() {
		return (flags & 1) != 0;
	}
}

/**
 * Zip reader that also understands password protected archives:
 * traditional ZipCrypto and WinZip AES (128/192/256). Used instead of java.util.zip.ZipFile,
 * which refuses to open archives that contain encrypted entries.
 * Archives that need zip64 are handed to java.util.zip.ZipFile (no encryption support there).
 */
class PZip implements Closeable {
	private static final HashMap<String, String> passwords = new HashMap<String, String>();

	static synchronized void setPassword(File zip, String pw) {
		if (pw == null)
			passwords.remove(zip.getAbsolutePath());
		else
			passwords.put(zip.getAbsolutePath(), pw);
	}

	static synchronized String getPassword(File zip) {
		return passwords.get(zip.getAbsolutePath());
	}

	private static class Zip64Signal extends IOException {
	}

	final File file;
	private ZipFile del;
	private final ArrayList<PEntry> list = new ArrayList<PEntry>();
	private final HashMap<String, PEntry> map = new HashMap<String, PEntry>();

	PZip(File f) throws IOException {
		file = f;
		try {
			parse();
		} catch (Zip64Signal z) {
			list.clear();
			map.clear();
			loadDelegate();
		}
	}

	private void loadDelegate() throws IOException {
		del = new ZipFile(file);
		Enumeration<? extends ZipEntry> en = del.entries();
		while (en.hasMoreElements()) {
			ZipEntry z = en.nextElement();
			PEntry e = new PEntry();
			e.ze = z;
			e.name = z.getName();
			e.size = z.getSize();
			e.csize = z.getCompressedSize();
			e.time = z.getTime();
			e.method = z.getMethod();
			list.add(e);
			map.put(e.name, e);
		}
	}

	static int u16(byte[] b, int p) {
		return (b[p] & 0xff) | ((b[p + 1] & 0xff) << 8);
	}

	static long u32(byte[] b, int p) {
		return (u16(b, p) & 0xffffL) | ((u16(b, p + 2) & 0xffffL) << 16);
	}

	private void parse() throws IOException {
		RandomAccessFile r = new RandomAccessFile(file, "r");
		try {
			long len = r.length();
			if (len < 22)
				throw new ZipException("Not a zip file");
			int tl = (int) Math.min(len, 65557L);
			byte[] t = new byte[tl];
			r.seek(len - tl);
			r.readFully(t);
			int p = -1;
			for (int i = tl - 22; i >= 0; i--)
				if (t[i] == 0x50 && t[i + 1] == 0x4b && t[i + 2] == 5 && t[i + 3] == 6) {
					p = i;
					break;
				}
			if (p < 0)
				throw new ZipException("End of central directory not found");
			int total = u16(t, p + 10);
			long cdSize = u32(t, p + 12), cdOff = u32(t, p + 16);
			if (total == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL || cdSize > 200L * 1024 * 1024)
				throw new Zip64Signal();
			byte[] cd = new byte[(int) cdSize];
			r.seek(cdOff);
			r.readFully(cd);
			int q = 0;
			for (int k = 0; k < total; k++) {
				if (q + 46 > cd.length || u32(cd, q) != 0x02014b50L)
					throw new ZipException("Bad central directory");
				PEntry e = new PEntry();
				e.flags = u16(cd, q + 8);
				e.method = u16(cd, q + 10);
				e.dosTime = u16(cd, q + 12);
				int dosDate = u16(cd, q + 14);
				e.crc = u32(cd, q + 16);
				e.csize = u32(cd, q + 20);
				e.size = u32(cd, q + 24);
				int nl = u16(cd, q + 28), xl = u16(cd, q + 30), cl = u16(cd, q + 32);
				e.localOff = u32(cd, q + 42);
				if (e.csize == 0xFFFFFFFFL || e.size == 0xFFFFFFFFL || e.localOff == 0xFFFFFFFFL)
					throw new Zip64Signal();
				if (q + 46 + nl + xl + cl > cd.length)
					throw new ZipException("Bad central directory");
				e.name = new String(cd, q + 46, nl, "UTF-8");
				e.time = dosToMillis(dosDate, e.dosTime);
				int xp = q + 46 + nl, xe = xp + xl;
				while (xp + 4 <= xe) {
					int id = u16(cd, xp), sz = u16(cd, xp + 2);
					if (id == 0x9901 && sz >= 7 && xp + 11 <= xe) {
						e.aes = true;
						e.aesStrength = cd[xp + 8] & 0xff;
						e.aesMethod = u16(cd, xp + 9);
					}
					xp += 4 + sz;
				}
				list.add(e);
				map.put(e.name, e);
				q += 46 + nl + xl + cl;
			}
		} finally {
			r.close();
		}
	}

	static long dosToMillis(int date, int time) {
		int day = date & 0x1f;
		int mon = (date >> 5) & 0xf;
		if (day == 0 || mon == 0)
			return -1;
		Calendar c = new GregorianCalendar();
		c.clear();
		c.set(((date >> 9) & 0x7f) + 1980, mon - 1, day, (time >> 11) & 0x1f, (time >> 5) & 0x3f, (time & 0x1f) * 2);
		return c.getTimeInMillis();
	}

	public Enumeration<PEntry> entries() {
		return Collections.enumeration(list);
	}

	public PEntry getEntry(String name) {
		return map.get(name);
	}

	public InputStream getInputStream(PEntry e) throws IOException {
		return open(e, getPassword(file));
	}

	InputStream open(PEntry e, String pw) throws IOException {
		if (del != null)
			return del.getInputStream(e.ze);
		RandomAccessFile r = new RandomAccessFile(file, "r");
		boolean ok = false;
		try {
			byte[] h = new byte[30];
			r.seek(e.localOff);
			r.readFully(h);
			if (u32(h, 0) != 0x04034b50L)
				throw new ZipException("Bad local header");
			long start = e.localOff + 30 + u16(h, 26) + u16(h, 28);
			InputStream in = new RangeStream(r, start, e.csize);
			int method = e.method;
			if (e.isEncrypted()) {
				if (pw == null || pw.length() == 0)
					throw new PasswordException("Password required - expand the zip first and enter its password");
				if (e.aes) {
					in = new AesStream(in, e.aesStrength, pw, e.csize);
					method = e.aesMethod;
				} else
					in = new CryptoStream(in, pw, e);
			}
			if (method == 8)
				in = new InflateStream(in);
			else if (method != 0)
				throw new ZipException("Unsupported compression method " + method);
			ok = true;
			return in;
		} finally {
			if (!ok)
				r.close();
		}
	}

	public void close() throws IOException {
		if (del != null)
			del.close();
	}

	/** true when any entry of this archive is encrypted */
	static boolean needsPassword(File zip) {
		PZip z = null;
		try {
			z = new PZip(zip);
			for (int i = 0; i < z.list.size(); i++)
				if (z.list.get(i).isEncrypted())
					return true;
		} catch (Exception e) {
		} finally {
			if (z != null)
				try {
					z.close();
				} catch (IOException e) {
				}
		}
		return false;
	}

	/** tries the password on the smallest encrypted file of the archive */
	static boolean verifyPassword(File zip, String pw) {
		if (pw == null || pw.length() == 0)
			return false;
		PZip z = null;
		InputStream in = null;
		try {
			z = new PZip(zip);
			PEntry best = null;
			for (int i = 0; i < z.list.size(); i++) {
				PEntry e = z.list.get(i);
				if (e.isEncrypted() && !e.isDirectory() && (best == null || e.csize < best.csize))
					best = e;
			}
			if (best == null)
				return true;
			in = z.open(best, pw);
			if (best.size > 4L * 1024 * 1024)
				return true; // header check already passed
			CRC32 crc = new CRC32();
			byte[] x = new byte[65536];
			int n;
			while ((n = in.read(x)) > 0)
				crc.update(x, 0, n);
			return best.aes || best.crc == 0 || crc.getValue() == best.crc;
		} catch (IOException e) {
			return false; // wrong password (or garbage after decrypting)
		} finally {
			if (in != null)
				try {
					in.close();
				} catch (IOException e) {
				}
			if (z != null)
				try {
					z.close();
				} catch (IOException e) {
				}
		}
	}

	// ---------------- streams ----------------

	static void readFully(InputStream in, byte[] b) throws IOException {
		int o = 0;
		while (o < b.length) {
			int n = in.read(b, o, b.length - o);
			if (n < 0)
				throw new EOFException("Unexpected end of zip data");
			o += n;
		}
	}

	private static class RangeStream extends InputStream {
		final RandomAccessFile r;
		long pos;
		final long end;

		RangeStream(RandomAccessFile r, long start, long len) {
			this.r = r;
			pos = start;
			end = start + len;
		}

		public int read() throws IOException {
			byte[] b = new byte[1];
			int n = read(b, 0, 1);
			return n < 0 ? -1 : (b[0] & 0xff);
		}

		public int read(byte[] b, int off, int len) throws IOException {
			if (pos >= end)
				return -1;
			if (len > end - pos)
				len = (int) (end - pos);
			r.seek(pos);
			int n = r.read(b, off, len);
			if (n > 0)
				pos += n;
			return n;
		}

		public void close() throws IOException {
			r.close();
		}
	}

	private static class InflateStream extends InflaterInputStream {
		InflateStream(InputStream in) {
			super(in, new Inflater(true), 65536);
		}

		public void close() throws IOException {
			inf.end();
			super.close();
		}
	}

	private static final int[] CRC_TABLE = new int[256];
	static {
		for (int n = 0; n < 256; n++) {
			int c = n;
			for (int k = 0; k < 8; k++)
				c = (c & 1) != 0 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
			CRC_TABLE[n] = c;
		}
	}

	static int crcByte(int crc, int b) {
		return (crc >>> 8) ^ CRC_TABLE[(crc ^ b) & 0xff];
	}

	/** traditional PKWARE encryption ("ZipCrypto") */
	static class ZipCryptoKeys {
		int k0 = 0x12345678, k1 = 0x23456789, k2 = 0x34567890;

		ZipCryptoKeys(String pw) throws IOException {
			byte[] pb = pw.getBytes("UTF-8");
			for (int i = 0; i < pb.length; i++)
				update(pb[i] & 0xff);
		}

		void update(int c) {
			k0 = crcByte(k0, c);
			k1 = (k1 + (k0 & 0xff)) * 134775813 + 1;
			k2 = crcByte(k2, k1 >>> 24);
		}

		int keyByte() {
			int t = (k2 & 0xffff) | 2;
			return ((t * (t ^ 1)) >>> 8) & 0xff;
		}

		int decrypt(int c) {
			int p = (c ^ keyByte()) & 0xff;
			update(p);
			return p;
		}

		int encrypt(int p) {
			int c = (p ^ keyByte()) & 0xff;
			update(p);
			return c;
		}
	}

	private static class CryptoStream extends InputStream {
		final InputStream in;
		final ZipCryptoKeys keys;

		CryptoStream(InputStream in, String pw, PEntry e) throws IOException {
			this.in = in;
			keys = new ZipCryptoKeys(pw);
			byte[] h = new byte[12];
			readFully(in, h);
			for (int i = 0; i < 12; i++)
				h[i] = (byte) keys.decrypt(h[i] & 0xff);
			int chk = h[11] & 0xff;
			if (chk != (int) ((e.crc >>> 24) & 0xff) && chk != ((e.dosTime >> 8) & 0xff))
				throw new PasswordException("Wrong password");
		}

		public int read() throws IOException {
			int c = in.read();
			return c < 0 ? -1 : keys.decrypt(c);
		}

		public int read(byte[] b, int off, int len) throws IOException {
			int n = in.read(b, off, len);
			for (int i = 0; i < n; i++)
				b[off + i] = (byte) keys.decrypt(b[off + i] & 0xff);
			return n;
		}

		public void close() throws IOException {
			in.close();
		}
	}

	static byte[] pbkdf2(byte[] pw, byte[] salt, int iter, int dkLen) throws IOException {
		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(pw, "HmacSHA1"));
			int hl = 20, blocks = (dkLen + hl - 1) / hl;
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

	/** WinZip AES: AES-CTR with a little-endian counter, key from PBKDF2-HMAC-SHA1 (1000 rounds) */
	static class AesCtr {
		final Cipher ecb;
		final byte[] ctr = new byte[16], ks = new byte[16];
		int ksPos = 16;

		AesCtr(byte[] key) throws IOException {
			try {
				ecb = Cipher.getInstance("AES/ECB/NoPadding");
				ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
			} catch (GeneralSecurityException e) {
				throw new IOException("Crypto error: " + e.getMessage());
			}
			ctr[0] = 1;
		}

		void xor(byte[] b, int off, int len) throws IOException {
			try {
				for (int i = 0; i < len; i++) {
					if (ksPos == 16) {
						byte[] k = ecb.doFinal(ctr);
						System.arraycopy(k, 0, ks, 0, 16);
						ksPos = 0;
						for (int c = 0; c < 16; c++)
							if (++ctr[c] != 0)
								break;
					}
					b[off + i] ^= ks[ksPos++];
				}
			} catch (GeneralSecurityException e) {
				throw new IOException("Crypto error: " + e.getMessage());
			}
		}
	}

	static int aesKeyLen(int strength) throws IOException {
		if (strength == 1)
			return 16;
		if (strength == 2)
			return 24;
		if (strength == 3)
			return 32;
		throw new ZipException("Unsupported AES strength " + strength);
	}

	private static class AesStream extends InputStream {
		final InputStream in;
		final AesCtr ctr;
		long remaining;

		AesStream(InputStream in, int strength, String pw, long csize) throws IOException {
			this.in = in;
			int keyLen = aesKeyLen(strength), saltLen = keyLen / 2;
			byte[] salt = new byte[saltLen], pv = new byte[2];
			readFully(in, salt);
			readFully(in, pv);
			byte[] dk = pbkdf2(pw.getBytes("UTF-8"), salt, 1000, keyLen * 2 + 2);
			if (dk[2 * keyLen] != pv[0] || dk[2 * keyLen + 1] != pv[1])
				throw new PasswordException("Wrong password");
			byte[] key = new byte[keyLen];
			System.arraycopy(dk, 0, key, 0, keyLen);
			ctr = new AesCtr(key);
			remaining = Math.max(0, csize - saltLen - 2 - 10); // trailing 10 bytes = authentication code
		}

		public int read() throws IOException {
			byte[] b = new byte[1];
			int n = read(b, 0, 1);
			return n < 0 ? -1 : (b[0] & 0xff);
		}

		public int read(byte[] b, int off, int len) throws IOException {
			if (remaining <= 0)
				return -1;
			int n = in.read(b, off, (int) Math.min(len, remaining));
			if (n <= 0)
				return n;
			remaining -= n;
			ctr.xor(b, off, n);
			return n;
		}

		public void close() throws IOException {
			in.close();
		}
	}
}
