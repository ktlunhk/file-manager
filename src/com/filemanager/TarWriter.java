package com.filemanager;

import java.io.*;

/** Minimal tar writer (ustar, with GNU long-name entries for names over 100 bytes). */
class TarWriter implements Closeable {
	private final OutputStream out;

	TarWriter(OutputStream out) {
		this.out = out;
	}

	private static void putOctal(byte[] h, int off, int len, long v) {
		// len-1 octal digits followed by a NUL
		String s = Long.toOctalString(v);
		int digits = len - 1;
		for (int i = 0; i < digits; i++)
			h[off + i] = '0';
		for (int i = 0; i < s.length() && i < digits; i++)
			h[off + digits - s.length() + i] = (byte) s.charAt(i);
		h[off + digits] = 0;
	}

	private static void putSize(byte[] h, long size) {
		if (size < 077777777777L) { // fits in 11 octal digits
			putOctal(h, 124, 12, size);
		} else { // base-256 for files of 8 GB and more
			h[124] = (byte) 0x80;
			for (int i = 11; i >= 1; i--) {
				h[124 + i] = (byte) (size & 0xff);
				size >>= 8;
			}
		}
	}

	private void header(String name, long size, long mtimeMs, char type, int mode) throws IOException {
		byte[] nameBytes = name.getBytes("UTF-8");
		if (nameBytes.length > 100) {
			// GNU long name: a pseudo entry that carries the full name
			byte[] data = new byte[nameBytes.length + 1];
			System.arraycopy(nameBytes, 0, data, 0, nameBytes.length);
			rawHeader("././@LongLink", data.length, 0, 'L', 0, null);
			out.write(data);
			pad(data.length);
		}
		rawHeader(name, size, mtimeMs, type, mode, nameBytes);
	}

	private void rawHeader(String name, long size, long mtimeMs, char type, int mode, byte[] nameBytes)
			throws IOException {
		byte[] h = new byte[512];
		if (nameBytes == null)
			nameBytes = name.getBytes("UTF-8");
		System.arraycopy(nameBytes, 0, h, 0, Math.min(100, nameBytes.length));
		putOctal(h, 100, 8, mode);
		putOctal(h, 108, 8, 0);
		putOctal(h, 116, 8, 0);
		putSize(h, size);
		putOctal(h, 136, 12, Math.max(0L, mtimeMs / 1000L));
		for (int i = 148; i < 156; i++)
			h[i] = ' ';
		h[156] = (byte) type;
		h[257] = 'u';
		h[258] = 's';
		h[259] = 't';
		h[260] = 'a';
		h[261] = 'r';
		h[262] = 0;
		h[263] = '0';
		h[264] = '0';
		long sum = 0;
		for (int i = 0; i < 512; i++)
			sum += h[i] & 0xff;
		String cs = Long.toOctalString(sum);
		for (int i = 0; i < 6; i++)
			h[148 + i] = '0';
		for (int i = 0; i < cs.length() && i < 6; i++)
			h[148 + 6 - cs.length() + i] = (byte) cs.charAt(i);
		h[154] = 0;
		h[155] = ' ';
		out.write(h);
	}

	private void pad(long len) throws IOException {
		int rest = (int) (len % 512);
		if (rest != 0)
			out.write(new byte[512 - rest]);
	}

	void addDir(String name, long mtimeMs) throws IOException {
		if (!name.endsWith("/"))
			name = name + "/";
		header(name, 0, mtimeMs, '5', 0755);
	}

	/** writes exactly size bytes: a file that changed while being read is cut or zero-filled */
	void addFile(String name, long mtimeMs, long size, InputStream in) throws IOException {
		header(name, size, mtimeMs, '0', 0644);
		byte[] buf = new byte[65536];
		long left = size;
		while (left > 0) {
			int n = in.read(buf, 0, (int) Math.min(buf.length, left));
			if (n < 0)
				break;
			out.write(buf, 0, n);
			left -= n;
		}
		while (left > 0) {
			int n = (int) Math.min(buf.length, left);
			out.write(new byte[n]);
			left -= n;
		}
		pad(size);
	}

	public void close() throws IOException {
		out.write(new byte[1024]); // end of archive
		out.close();
	}
}
