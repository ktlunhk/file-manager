package com.filemanager;

import java.io.*;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;
import java.util.zip.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Writes zip archives, optionally password protected (AES-256 "WinZip AE-2", or classic ZipCrypto for
 * programs such as Windows Explorer). Streams everything, no temp files. Files are limited to 4 GB.
 */
class PZipWriter implements Closeable {
	private final OutputStream out;
	private long pos = 0;
	private final String pw;
	private final boolean aes;
	private final ArrayList<byte[]> cds = new ArrayList<byte[]>();
	private final SecureRandom rnd = new SecureRandom();

	PZipWriter(OutputStream out, String password, boolean aes) {
		this.out = new BufferedOutputStream(out, 65536);
		this.pw = (password != null && password.length() > 0) ? password : null;
		this.aes = aes;
	}

	private static void p16(ByteArrayOutputStream o, int v) {
		o.write(v & 0xff);
		o.write((v >>> 8) & 0xff);
	}

	private static void p32(ByteArrayOutputStream o, long v) {
		p16(o, (int) (v & 0xffff));
		p16(o, (int) ((v >>> 16) & 0xffff));
	}

	private void raw(byte[] b, int off, int len) throws IOException {
		out.write(b, off, len);
		pos += len;
	}

	private void raw(ByteArrayOutputStream o) throws IOException {
		byte[] b = o.toByteArray();
		raw(b, 0, b.length);
	}

	private static int[] dos(long millis) {
		Calendar c = new GregorianCalendar();
		c.setTimeInMillis(millis > 0 ? millis : System.currentTimeMillis());
		int y = c.get(Calendar.YEAR);
		if (y < 1980)
			return new int[] { (1 << 5) | 1, 0 };
		int date = ((y - 1980) << 9) | ((c.get(Calendar.MONTH) + 1) << 5) | c.get(Calendar.DAY_OF_MONTH);
		int time = (c.get(Calendar.HOUR_OF_DAY) << 11) | (c.get(Calendar.MINUTE) << 5) | (c.get(Calendar.SECOND) / 2);
		return new int[] { date, time };
	}

	private static byte[] aesExtra() {
		ByteArrayOutputStream e = new ByteArrayOutputStream();
		p16(e, 0x9901);
		p16(e, 7);
		p16(e, 2); // AE-2
		e.write('A');
		e.write('E');
		e.write(3); // AES-256
		p16(e, 8); // real method: deflate
		return e.toByteArray();
	}

	private void check32(long v) throws IOException {
		if (v > 0xFFFFFFFFL)
			throw new IOException("File too large for a zip (over 4 GB)");
	}

	public void addDir(String name, long time) throws IOException {
		if (!name.endsWith("/"))
			name += "/";
		byte[] nb = name.getBytes("UTF-8");
		int[] d = dos(time);
		long off = pos;
		check32(off);
		ByteArrayOutputStream h = new ByteArrayOutputStream();
		p32(h, 0x04034b50L);
		p16(h, 20);
		p16(h, 0x0800);
		p16(h, 0);
		p16(h, d[1]);
		p16(h, d[0]);
		p32(h, 0);
		p32(h, 0);
		p32(h, 0);
		p16(h, nb.length);
		p16(h, 0);
		h.write(nb, 0, nb.length);
		raw(h);
		cds.add(central(nb, 20, 0x0800, 0, d, 0, 0, 0, off, new byte[0], 0x10));
	}

	private byte[] central(byte[] nb, int ver, int flags, int method, int[] d, long crc, long csize, long usize,
			long off, byte[] extra, int extAttr) {
		ByteArrayOutputStream c = new ByteArrayOutputStream();
		p32(c, 0x02014b50L);
		p16(c, 20);
		p16(c, ver);
		p16(c, flags);
		p16(c, method);
		p16(c, d[1]);
		p16(c, d[0]);
		p32(c, crc);
		p32(c, csize);
		p32(c, usize);
		p16(c, nb.length);
		p16(c, extra.length);
		p16(c, 0);
		p16(c, 0);
		p16(c, 0);
		p32(c, extAttr);
		p32(c, off);
		c.write(nb, 0, nb.length);
		c.write(extra, 0, extra.length);
		return c.toByteArray();
	}

	// per-entry encryption state
	private PZip.ZipCryptoKeys zk;
	private PZip.AesCtr ctr;
	private Mac mac;
	private long csize;

	private void emit(byte[] b, int len) throws IOException {
		if (zk != null) {
			for (int i = 0; i < len; i++)
				b[i] = (byte) zk.encrypt(b[i] & 0xff);
		} else if (ctr != null) {
			ctr.xor(b, 0, len);
			mac.update(b, 0, len);
		}
		raw(b, 0, len);
		csize += len;
	}

	public void addFile(String name, long time, InputStream in) throws IOException {
		byte[] nb = name.getBytes("UTF-8");
		int[] d = dos(time);
		boolean enc = pw != null;
		boolean useAes = enc && aes;
		int flags = 0x0800 | 0x0008 | (enc ? 1 : 0);
		int method = useAes ? 99 : 8;
		byte[] extra = useAes ? aesExtra() : new byte[0];
		int ver = useAes ? 51 : 20;
		long off = pos;
		check32(off);
		ByteArrayOutputStream h = new ByteArrayOutputStream();
		p32(h, 0x04034b50L);
		p16(h, ver);
		p16(h, flags);
		p16(h, method);
		p16(h, d[1]);
		p16(h, d[0]);
		p32(h, 0);
		p32(h, 0);
		p32(h, 0);
		p16(h, nb.length);
		p16(h, extra.length);
		h.write(nb, 0, nb.length);
		h.write(extra, 0, extra.length);
		raw(h);

		zk = null;
		ctr = null;
		mac = null;
		csize = 0;
		if (enc && !useAes) {
			zk = new PZip.ZipCryptoKeys(pw);
			byte[] hd = new byte[12];
			rnd.nextBytes(hd);
			hd[11] = (byte) ((d[1] >> 8) & 0xff);
			emit(hd, 12);
		} else if (useAes) {
			byte[] salt = new byte[16];
			rnd.nextBytes(salt);
			byte[] dk = PZip.pbkdf2(pw.getBytes("UTF-8"), salt, 1000, 66);
			byte[] key = new byte[32], auth = new byte[32];
			System.arraycopy(dk, 0, key, 0, 32);
			System.arraycopy(dk, 32, auth, 0, 32);
			raw(salt, 0, 16);
			raw(dk, 64, 2);
			csize += 18;
			ctr = new PZip.AesCtr(key);
			try {
				mac = Mac.getInstance("HmacSHA1");
				mac.init(new SecretKeySpec(auth, "HmacSHA1"));
			} catch (GeneralSecurityException e) {
				throw new IOException("Crypto error: " + e.getMessage());
			}
		}

		CRC32 crc = new CRC32();
		long usize = 0;
		Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
		try {
			byte[] inb = new byte[65536], ob = new byte[65536];
			int n;
			while ((n = in.read(inb)) > 0) {
				crc.update(inb, 0, n);
				usize += n;
				def.setInput(inb, 0, n);
				while (!def.needsInput()) {
					int k = def.deflate(ob, 0, ob.length);
					if (k > 0)
						emit(ob, k);
				}
			}
			def.finish();
			while (!def.finished()) {
				int k = def.deflate(ob, 0, ob.length);
				if (k > 0)
					emit(ob, k);
			}
		} finally {
			def.end();
		}
		if (useAes) {
			byte[] code = mac.doFinal();
			raw(code, 0, 10);
			csize += 10;
		}
		check32(csize);
		check32(usize);
		long crcOut = useAes ? 0 : crc.getValue();
		ByteArrayOutputStream dd = new ByteArrayOutputStream();
		p32(dd, 0x08074b50L);
		p32(dd, crcOut);
		p32(dd, csize);
		p32(dd, usize);
		raw(dd);
		cds.add(central(nb, ver, flags, method, d, crcOut, csize, usize, off, extra, 0));
		zk = null;
		ctr = null;
		mac = null;
	}

	public void close() throws IOException {
		long start = pos;
		long size = 0;
		for (int i = 0; i < cds.size(); i++) {
			byte[] c = cds.get(i);
			raw(c, 0, c.length);
			size += c.length;
		}
		if (cds.size() > 65534)
			throw new IOException("Too many files for a zip");
		check32(start);
		ByteArrayOutputStream e = new ByteArrayOutputStream();
		p32(e, 0x06054b50L);
		p16(e, 0);
		p16(e, 0);
		p16(e, cds.size());
		p16(e, cds.size());
		p32(e, size);
		p32(e, start);
		p16(e, 0);
		raw(e);
		out.flush();
		out.close();
	}
}
