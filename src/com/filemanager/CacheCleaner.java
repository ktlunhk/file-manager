package com.filemanager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/** Removes old files from the app cache (downloaded MEGA files, opened archives, previews, temp zips). */
class CacheCleaner {
	/** files this young are never touched: a download or a preview may be using them right now */
	static final long KEEP_RECENT_MS = 10L * 60 * 1000;

	static long size(File f) {
		if (f == null)
			return 0;
		if (f.isFile())
			return f.length();
		long t = 0;
		File[] c = f.listFiles();
		if (c != null)
			for (int i = 0; i < c.length; i++)
				t += size(c[i]);
		return t;
	}

	private static void collect(File dir, ArrayList<File> out) {
		File[] c = dir.listFiles();
		if (c == null)
			return;
		for (int i = 0; i < c.length; i++) {
			if (c[i].isDirectory())
				collect(c[i], out);
			else
				out.add(c[i]);
		}
	}

	private static void removeEmptyDirs(File dir, boolean keepSelf) {
		File[] c = dir.listFiles();
		if (c != null)
			for (int i = 0; i < c.length; i++)
				if (c[i].isDirectory())
					removeEmptyDirs(c[i], false);
		if (!keepSelf) {
			String[] left = dir.list();
			if (left != null && left.length == 0)
				dir.delete();
		}
	}

	/** deletes files older than maxAgeMs, then the oldest remaining ones until at most maxBytes are left; returns the bytes freed */
	static long clean(File root, long maxAgeMs, long maxBytes) {
		long now = System.currentTimeMillis(), freed = 0, total = 0;
		ArrayList<File> files = new ArrayList<File>();
		collect(root, files);
		ArrayList<File> keep = new ArrayList<File>();
		for (int i = 0; i < files.size(); i++) {
			File f = files.get(i);
			long len = f.length();
			if (now - f.lastModified() > maxAgeMs && f.delete())
				freed += len;
			else {
				keep.add(f);
				total += len;
			}
		}
		if (total > maxBytes) {
			Collections.sort(keep, new Comparator<File>() {
				public int compare(File a, File b) {
					long x = a.lastModified(), y = b.lastModified();
					return x < y ? -1 : (x > y ? 1 : 0);
				}
			});
			for (int i = 0; i < keep.size() && total > maxBytes; i++) {
				File f = keep.get(i);
				if (now - f.lastModified() < KEEP_RECENT_MS)
					continue;
				long len = f.length();
				if (f.delete()) {
					freed += len;
					total -= len;
				}
			}
		}
		removeEmptyDirs(root, true);
		return freed;
	}

	/** deletes everything except files that were changed in the last few minutes; returns the bytes freed */
	static long clearAll(File root) {
		return clean(root, KEEP_RECENT_MS, 0);
	}
}
