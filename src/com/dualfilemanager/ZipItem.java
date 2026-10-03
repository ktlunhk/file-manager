package com.dualfilemanager;

import java.io.*;
import java.util.*;
import java.util.zip.*;

class ZipItem extends File {
	final File zip;
	final String entry;
	final boolean dir;
	final long size, time;

	ZipItem(File zip, String entry, boolean dir, long size, long time) {
		super(zip.getAbsolutePath() + "!/" + entry);
		this.zip = zip;
		this.entry = entry;
		this.dir = dir;
		this.size = size;
		this.time = time;
	}
	boolean isRoot() {
		return entry.length() == 0;
	}
	public String getName() {
		if (isRoot())
			return zip.getName();
		int i = entry.lastIndexOf('/');
		return i < 0 ? entry : entry.substring(i + 1);
	}
	public File getParentFile() {
		if (isRoot())
			return zip.getParentFile();
		int i = entry.lastIndexOf('/');
		return new ZipItem(zip, i < 0 ? "" : entry.substring(0, i), true, 0, zip.lastModified());
	}
	public String getParent() {
		File p = getParentFile();
		return p == null ? null : p.getPath();
	}
	public boolean isDirectory() {
		return dir && zip.exists();
	}
	public boolean isFile() {
		return !dir;
	}
	public boolean exists() {
		return zip.exists();
	}
	public long length() {
		return size;
	}
	public long lastModified() {
		return time;
	}
	public boolean canRead() {
		return true;
	}
	public boolean canWrite() {
		return false;
	}

	public File[] listFiles() {
		if (!dir)
			return null;
		String prefix = isRoot() ? "" : entry + "/";
		LinkedHashMap<String, File> seen = new LinkedHashMap<String, File>();
		ZipFile zf = null;
		try {
			zf = new ZipFile(zip);
			Enumeration<? extends ZipEntry> en = zf.entries();
			while (en.hasMoreElements()) {
				ZipEntry ze = en.nextElement();
				String n = ze.getName();
				if (!n.startsWith(prefix) || n.length() <= prefix.length())
					continue;
				String rest = n.substring(prefix.length());
				int sl = rest.indexOf('/');
				String cn;
				boolean cd;
				long sz;
				if (sl < 0) {
					cn = rest;
					cd = false;
					sz = ze.getSize();
				} else {
					cn = rest.substring(0, sl);
					cd = true;
					sz = 0;
				}
				if (cn.length() == 0 || cn.equals(".") || cn.equals(".."))
					continue;
				if (!seen.containsKey(cn)) {
					String fullEntry = prefix + cn;
					if (!cd && cn.toLowerCase(Locale.US).endsWith(".dex"))
						seen.put(cn, new DexItem(zip, fullEntry, "", true, Math.max(sz, 0L), ze.getTime()));
					else
						seen.put(cn, new ZipItem(zip, fullEntry, cd, Math.max(sz, 0L), ze.getTime()));
				}
			}
		} catch (Exception e) {
			// unreadable / corrupt zip: show as empty
		} finally {
			if (zf != null)
				try {
					zf.close();
				} catch (IOException e) {
				}
		}
		return seen.values().toArray(new File[0]);
	}

	boolean stillExists() {
		if (!zip.exists())
			return false;
		if (isRoot())
			return true;
		ZipFile zf = null;
		try {
			zf = new ZipFile(zip);
			Enumeration<? extends ZipEntry> en = zf.entries();
			while (en.hasMoreElements()) {
				String n = en.nextElement().getName();
				if (n.equals(entry) || n.startsWith(entry + "/"))
					return true;
			}
		} catch (Exception e) {
		} finally {
			if (zf != null)
				try {
					zf.close();
				} catch (IOException e) {
				}
		}
		return false;
	}

	static boolean isZipName(String n) {
        n = n.toLowerCase(Locale.US);
        return n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".apk");
    }

    static boolean isZipRoot(File f) {
        return f instanceof ZipItem && ((ZipItem) f).isRoot();
    }

    static void zipEntryAction(MainActivity a, int action) {
        if (a.selected instanceof DexItem && !((DexItem) a.selected).isDexRoot()) {
            if (action == 7) a.info(); else a.toast("DEX package/class entries are browse-only");
            return;
        }
        if (action == 1) a.transfer(false);
        else if (action == 2) a.transfer(true);
        else if (action == 3) a.ask("Rename", "New name", 3);
        else if (action == 5) a.delConfirm();
        else if (action == 7) a.info();
        else a.toast("Zip contents are read-only. Use Copy to extract first.");
    }

    static void extract(ZipItem zi, File dst) throws IOException {
        if (zi.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) throw new IOException("Cannot create " + dst.getName());
            File[] c = zi.listFiles();
            if (c != null) for (int i = 0; i < c.length; i++) extract((ZipItem)c[i], new File(dst, c[i].getName()));
        } else {
            ZipFile zf = new ZipFile(zi.zip);
            try {
                ZipEntry ze = zf.getEntry(zi.entry);
                if (ze == null) throw new IOException("Entry not found");
                InputStream in = zf.getInputStream(ze); OutputStream out = null;
                try {
                    out = new FileOutputStream(dst); byte[] x = new byte[65536]; int n;
                    while ((n = in.read(x)) > 0) out.write(x, 0, n);
                } finally {
                    try { in.close(); } catch (IOException e) {}
                    if (out != null) try { out.close(); } catch (IOException e) {}
                }
            } finally { zf.close(); }
        }
    }

    static void extractRoot(MainActivity a) {
        ZipItem zi = (ZipItem)a.selected;
        File dstDir = a.selectedLeft ? a.rightCur : a.leftCur;
        if (dstDir instanceof ZipItem) { a.toast("Cannot extract into a zip"); return; }
        String base = zi.zip.getName(); int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        a.startTransfer(zi, new File(dstDir, base), false, "Extracting", "Extracted");
    }

    static void zipModify(final MainActivity a, final File zip, final String entry, final String newName, final String okMsg) {
        final android.app.AlertDialog wait = a.busyDialog(newName == null ? "Deleting from zip" : "Renaming in zip", zip.getName());
        new Thread(new Runnable() { public void run() {
            String msg;
            try { rewriteZip(zip, entry, newName); msg = okMsg; }
            catch (Exception e) { msg = "Zip error: " + e.getMessage(); }
            final String m = msg;
            a.runOnUiThread(new Runnable() { public void run() {
                try { wait.dismiss(); } catch (Exception e) {}
                a.toast(m); a.refresh();
            }});
        }}).start();
    }

    static void rewriteZip(File zip, String entry, String newName) throws IOException {
        File tmp = new File(zip.getParentFile(), zip.getName() + ".tmp");
        ZipFile zf = null; ZipOutputStream zo = null; boolean ok = false;
        try {
            zf = new ZipFile(zip); zo = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)));
            String dirPrefix = entry + "/"; int slash = entry.lastIndexOf('/');
            String parentPart = slash < 0 ? "" : entry.substring(0, slash + 1);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement(); String name = ze.getName();
                boolean hit = name.equals(entry) || name.startsWith(dirPrefix); String outName = name;
                if (hit) { if (newName == null) continue; outName = parentPart + newName + name.substring(entry.length()); }
                copyEntry(zf, zo, ze, outName);
            }
            zo.close(); zo = null; zf.close(); zf = null;
            replaceArchive(zip, tmp); ok = true;
        } finally {
            if (zo != null) try { zo.close(); } catch (IOException e) {}
            if (zf != null) try { zf.close(); } catch (IOException e) {}
            if (!ok) tmp.delete();
        }
    }

    static void rewriteZipDelete(File zip, ArrayList<String> entries) throws IOException {
        File tmp = new File(zip.getParentFile(), zip.getName() + ".tmp");
        ZipFile zf = null; ZipOutputStream zo = null; boolean ok = false;
        try {
            zf = new ZipFile(zip); zo = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)));
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement(); String name = ze.getName(); boolean hit = false;
                for (int i = 0; i < entries.size(); i++) { String e = entries.get(i); if (name.equals(e) || name.startsWith(e + "/")) { hit = true; break; } }
                if (!hit) copyEntry(zf, zo, ze, name);
            }
            zo.close(); zo = null; zf.close(); zf = null;
            replaceArchive(zip, tmp); ok = true;
        } finally {
            if (zo != null) try { zo.close(); } catch (IOException e) {}
            if (zf != null) try { zf.close(); } catch (IOException e) {}
            if (!ok) tmp.delete();
        }
    }

    private static void copyEntry(ZipFile zf, ZipOutputStream zo, ZipEntry ze, String outName) throws IOException {
        ZipEntry ne = new ZipEntry(outName); if (ze.getTime() >= 0) ne.setTime(ze.getTime()); zo.putNextEntry(ne);
        if (!ze.isDirectory()) {
            InputStream in = zf.getInputStream(ze);
            try { byte[] x = new byte[65536]; int n; while ((n = in.read(x)) > 0) zo.write(x, 0, n); }
            finally { try { in.close(); } catch (IOException e) {} }
        }
        zo.closeEntry();
    }

    private static void replaceArchive(File zip, File tmp) throws IOException {
        if (!zip.delete()) throw new IOException("Cannot replace zip");
        if (!tmp.renameTo(zip)) throw new IOException("Cannot rename temp zip");
    }

    static void zipDeleteMany(final MainActivity a, final ArrayList<ZipItem> items, final String okMsg) {
        final android.app.AlertDialog wait = a.busyDialog("Deleting from zip", "Rewriting zip...");
        new Thread(new Runnable() { public void run() {
            String msg = okMsg;
            try { deleteGrouped(items); } catch (Exception e) { msg = "Zip error: " + e.getMessage(); }
            final String m = msg;
            a.runOnUiThread(new Runnable() { public void run() {
                try { wait.dismiss(); } catch (Exception e) {}
                a.exitMulti(); a.toast(m); a.refresh();
            }});
        }}).start();
    }

    static void deleteZipEntriesThenFiles(final MainActivity a, final ArrayList<ZipItem> zipEntries, final ArrayList<File> normal) {
        final android.app.AlertDialog wait = a.busyDialog("Deleting", "Rewriting zip...");
        new Thread(new Runnable() { public void run() {
            String err = null;
            try { deleteGrouped(zipEntries); } catch (Exception e) { err = "Zip error: " + e.getMessage(); }
            final String zerr = err;
            a.runOnUiThread(new Runnable() { public void run() {
                try { wait.dismiss(); } catch (Exception e) {}
                if (zerr != null) a.toast(zerr);
                a.selected = null; a.fileOperations.deleteToTrash(normal);
            }});
        }}).start();
    }

    private static void deleteGrouped(ArrayList<ZipItem> items) throws IOException {
        HashMap<String, ArrayList<String>> byZip = new HashMap<String, ArrayList<String>>();
        HashMap<String, File> zipFile = new HashMap<String, File>();
        for (int i = 0; i < items.size(); i++) {
            ZipItem z = items.get(i); String k = z.zip.getAbsolutePath();
            if (!byZip.containsKey(k)) { byZip.put(k, new ArrayList<String>()); zipFile.put(k, z.zip); }
            byZip.get(k).add(z.entry);
        }
        for (String k : byZip.keySet()) rewriteZipDelete(zipFile.get(k), byZip.get(k));
    }

    static void zipMulti(MainActivity a) {
        ArrayList<File> items = a.multiItems();
        for (int i = 0; i < items.size(); i++) if (items.get(i) instanceof ZipItem) { a.toast("Extract zip entries first, they cannot be zipped"); return; }
        if (items.isEmpty()) return;
        File dstDir = a.multiLeft ? a.rightCur : a.leftCur;
        if (dstDir instanceof ZipItem) { a.toast("Cannot create a zip inside a zip"); return; }
        File parent = items.get(0).getParentFile(); boolean same = true;
        for (int i = 1; i < items.size(); i++) { File p = items.get(i).getParentFile(); if (p == null || !p.equals(parent)) same = false; }
        String base = "Archive";
        if (items.size() == 1) base = items.get(0).getName();
        else if (same && parent != null && !a.isPaneRoot(parent) && parent.getName().length() > 0) base = parent.getName();
        zipItems(a, items, a.uniqueFile(dstDir, base, ".zip"));
    }

    static void zip(MainActivity a) {
        File src = a.selected; File dstDir = a.selectedLeft ? a.rightCur : a.leftCur;
        if (dstDir instanceof ZipItem) { a.toast("Cannot create a zip inside a zip"); return; }
        String base = src.getName();
        if (!src.isDirectory()) { int dot = base.lastIndexOf('.'); if (dot > 0) base = base.substring(0, dot); }
        ArrayList<File> one = new ArrayList<File>(); one.add(src);
        zipItems(a, one, a.uniqueFile(dstDir, base, ".zip"));
    }

    static void zipItems(final MainActivity a, final ArrayList<File> items, final File out) {
        final android.app.AlertDialog wait = a.busyDialog("Creating zip", out.getName());
        new Thread(new Runnable() { public void run() {
            String msg; ZipOutputStream zo = null;
            try {
                zo = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out))); HashSet<String> used = new HashSet<String>();
                for (int i = 0; i < items.size(); i++) {
                    File f = items.get(i); String nm = f.getName(); int k = 2;
                    while (used.contains(nm)) { nm = f.getName() + " (" + k + ")"; k++; }
                    used.add(nm); addZip(zo, f, nm, out);
                }
                zo.close(); zo = null; msg = "Created " + out.getName();
            } catch (Exception e) {
                if (zo != null) try { zo.close(); } catch (IOException x) {}
                out.delete(); msg = "Zip error: " + e.getMessage();
            }
            final String m = msg;
            a.runOnUiThread(new Runnable() { public void run() {
                try { wait.dismiss(); } catch (Exception e) {}
                a.exitMulti(); a.toast(m); a.refresh();
            }});
        }}).start();
    }

    static void addZip(ZipOutputStream zo, File f, String entry, File skip) throws IOException {
        if (f.equals(skip)) return;
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c == null || c.length == 0) { zo.putNextEntry(new ZipEntry(entry + "/")); zo.closeEntry(); return; }
            for (int i = 0; i < c.length; i++) addZip(zo, c[i], entry + "/" + c[i].getName(), skip);
        } else {
            InputStream in = null;
            try {
                in = new FileInputStream(f); zo.putNextEntry(new ZipEntry(entry)); byte[] x = new byte[65536]; int n;
                while ((n = in.read(x)) > 0) zo.write(x, 0, n); zo.closeEntry();
            } finally { if (in != null) try { in.close(); } catch (IOException e) {} }
        }
    }

}
