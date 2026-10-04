package com.filemanager;

import java.io.*;
import java.util.*;
import java.util.zip.*;

class ZipItem extends File {
	final File zip;
	final String entry;
	final boolean dir;
	final long size, time;
	boolean nested; // a zip/apk/jar stored inside another zip: browsable as a folder
	static File cacheDir;

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
		return (dir || nested) && zip.exists();
	}
	public boolean isFile() {
		return !dir && !nested;
	}

	// extracts a nested archive to the cache once and returns the copy
	File nestedCopy() {
		if (cacheDir == null)
			return null;
		File d = new File(cacheDir, "nested/" + Integer.toHexString((zip.getAbsolutePath() + "!/" + entry).hashCode()) + "_" + Long.toHexString(zip.lastModified()));
		File out = new File(d, getName());
		if (out.exists() && out.length() > 0)
			return out;
		PZip zf = null;
		InputStream in = null;
		OutputStream os = null;
		try {
			d.mkdirs();
			zf = new PZip(zip);
			PEntry ze = zf.getEntry(entry);
			if (ze == null)
				return null;
			in = zf.getInputStream(ze);
			File tmp = new File(d, getName() + ".part");
			os = new FileOutputStream(tmp);
			byte[] x = new byte[65536];
			int n;
			while ((n = in.read(x)) > 0)
				os.write(x, 0, n);
			os.close();
			os = null;
			if (!tmp.renameTo(out))
				return null;
			return out;
		} catch (Exception e) {
			return null;
		} finally {
			try { if (in != null) in.close(); } catch (IOException e) {}
			try { if (os != null) os.close(); } catch (IOException e) {}
			try { if (zf != null) zf.close(); } catch (IOException e) {}
		}
	}

	static boolean isZipLike(File f) {
		return isZipRoot(f) || (f instanceof ZipItem && ((ZipItem) f).nested);
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
		if (nested) {
			File c = nestedCopy();
			return c == null ? new File[0] : new ZipItem(c, "", true, 0, time).listFiles();
		}
		if (!dir)
			return null;
		String prefix = isRoot() ? "" : entry + "/";
		LinkedHashMap<String, File> seen = new LinkedHashMap<String, File>();
		PZip zf = null;
		try {
			zf = new PZip(zip);
			Enumeration<PEntry> en = zf.entries();
			while (en.hasMoreElements()) {
				PEntry ze = en.nextElement();
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
					else {
						ZipItem zi = new ZipItem(zip, fullEntry, cd, Math.max(sz, 0L), ze.getTime());
						zi.nested = !cd && isZipName(cn);
						seen.put(cn, zi);
					}
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
		PZip zf = null;
		try {
			zf = new PZip(zip);
			Enumeration<PEntry> en = zf.entries();
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
            PZip zf = new PZip(zi.zip);
            try {
                PEntry ze = zf.getEntry(zi.entry);
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
        final ZipItem fzi = zi; final File fdst = dstDir; final MainActivity fa = a;
        ensurePassword(a, zi, new Runnable() { public void run() { fa.startTransfer(fzi, fdst, false, "Extracting", "Extracted"); } });
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
        if (PZip.needsPassword(zip)) throw new IOException("Editing password-protected zips is not supported");
        File tmp = new File(zip.getParentFile(), zip.getName() + ".tmp");
        PZip zf = null; ZipOutputStream zo = null; boolean ok = false;
        try {
            zf = new PZip(zip); zo = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)));
            String dirPrefix = entry + "/"; int slash = entry.lastIndexOf('/');
            String parentPart = slash < 0 ? "" : entry.substring(0, slash + 1);
            Enumeration<PEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                PEntry ze = en.nextElement(); String name = ze.getName();
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
        if (PZip.needsPassword(zip)) throw new IOException("Editing password-protected zips is not supported");
        File tmp = new File(zip.getParentFile(), zip.getName() + ".tmp");
        PZip zf = null; ZipOutputStream zo = null; boolean ok = false;
        try {
            zf = new PZip(zip); zo = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)));
            Enumeration<PEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                PEntry ze = en.nextElement(); String name = ze.getName(); boolean hit = false;
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

    private static void copyEntry(PZip zf, ZipOutputStream zo, PEntry ze, String outName) throws IOException {
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
        if (a.hasMega(items)) { a.toast("Copy MEGA files out first, they cannot be zipped"); return; }
        for (int i = 0; i < items.size(); i++) if (items.get(i) instanceof ZipItem) { a.toast("Extract zip entries first, they cannot be zipped"); return; }
        if (items.isEmpty()) return;
        File dstDir = a.multiLeft ? a.rightCur : a.leftCur;
        if (dstDir instanceof ZipItem) { a.toast("Cannot create a zip inside a zip"); return; }
        File parent = items.get(0).getParentFile(); boolean same = true;
        for (int i = 1; i < items.size(); i++) { File p = items.get(i).getParentFile(); if (p == null || !p.equals(parent)) same = false; }
        String base = "Archive";
        if (items.size() == 1) base = items.get(0).getName();
        else if (same && parent != null && !a.isPaneRoot(parent) && parent.getName().length() > 0) base = parent.getName();
        askZipOptions(a, items, a.uniqueFile(dstDir, base, ".zip"));
    }

    static void zip(MainActivity a) {
        File src = a.selected; File dstDir = a.selectedLeft ? a.rightCur : a.leftCur;
        if (dstDir instanceof ZipItem) { a.toast("Cannot create a zip inside a zip"); return; }
        String base = src.getName();
        if (!src.isDirectory()) { int dot = base.lastIndexOf('.'); if (dot > 0) base = base.substring(0, dot); }
        ArrayList<File> one = new ArrayList<File>(); one.add(src);
        askZipOptions(a, one, a.uniqueFile(dstDir, base, ".zip"));
    }

    // ---------- password protected zips ----------
    static void askZipOptions(final MainActivity a, final ArrayList<File> items, final File out) {
        android.widget.LinearLayout box = new android.widget.LinearLayout(a);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(20 * a.dp, 8 * a.dp, 20 * a.dp, 0);
        final android.widget.EditText pw = new android.widget.EditText(a);
        pw.setHint("Password (leave empty for none)");
        pw.setSingleLine(true);
        pw.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        box.addView(pw, new android.widget.LinearLayout.LayoutParams(-1, -2));
        final android.widget.CheckBox aes = new android.widget.CheckBox(a);
        aes.setText("Strong AES-256 encryption (7-Zip, WinRAR and phones can open it; Windows Explorer cannot). Untick for classic encryption that Windows Explorer opens.");
        aes.setTextSize(12);
        aes.setChecked(true);
        box.addView(aes, new android.widget.LinearLayout.LayoutParams(-1, -2));
        a.createDialog("Create ZIP", out.getName()).setView(box)
            .setPositiveButton("Create", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    String p = pw.getText().toString();
                    zipItems(a, items, out, p.length() > 0 ? p : null, aes.isChecked());
                }
            }).setNegativeButton("Cancel", null).show();
    }

    /** makes sure the password of an encrypted zip is known before it is opened; asks for it if not */
    static void ensurePassword(final MainActivity a, final ZipItem zi, final Runnable ok) {
        new Thread(new Runnable() { public void run() {
            File cf = zi.nested ? zi.nestedCopy() : zi.zip;
            boolean need = false;
            if (cf != null) {
                need = PZip.needsPassword(cf);
                if (need) { String pw = PZip.getPassword(cf); if (pw != null && PZip.verifyPassword(cf, pw)) need = false; }
            }
            final boolean n = need; final File fcf = cf;
            a.runOnUiThread(new Runnable() { public void run() {
                if (!n) ok.run(); else askPassword(a, fcf, ok, false);
            }});
        }}).start();
    }

    static void askPassword(final MainActivity a, final File cf, final Runnable ok, boolean wrong) {
        final android.widget.EditText pw = new android.widget.EditText(a);
        pw.setHint("Password");
        pw.setSingleLine(true);
        pw.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        android.widget.FrameLayout box = new android.widget.FrameLayout(a);
        box.setPadding(20 * a.dp, 8 * a.dp, 20 * a.dp, 0);
        box.addView(pw, new android.widget.FrameLayout.LayoutParams(-1, -2));
        a.createDialog("Password required", cf.getName() + (wrong ? "\nWrong password, try again." : "")).setView(box)
            .setPositiveButton("Open", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    final String p = pw.getText().toString();
                    new Thread(new Runnable() { public void run() {
                        final boolean good = PZip.verifyPassword(cf, p);
                        a.runOnUiThread(new Runnable() { public void run() {
                            if (good) { PZip.setPassword(cf, p); a.listCache.clear(); ok.run(); }
                            else askPassword(a, cf, ok, true);
                        }});
                    }}).start();
                }
            }).setNegativeButton("Cancel", null).show();
    }

    static void zipItems(final MainActivity a, final ArrayList<File> items, final File out) {
        zipItems(a, items, out, null, false);
    }

    static void zipItems(final MainActivity a, final ArrayList<File> items, final File out, final String password, final boolean aes) {
        final android.app.AlertDialog wait = a.busyDialog("Creating zip", out.getName());
        new Thread(new Runnable() { public void run() {
            String msg; ZipOutputStream zo = null; PZipWriter pz = null;
            try {
                if (password != null) {
                    pz = new PZipWriter(new FileOutputStream(out), password, aes); HashSet<String> used2 = new HashSet<String>();
                    for (int i = 0; i < items.size(); i++) {
                        File f = items.get(i); String nm = f.getName(); int k = 2;
                        while (used2.contains(nm)) { nm = f.getName() + " (" + k + ")"; k++; }
                        used2.add(nm); addZipEnc(pz, f, nm, out);
                    }
                    pz.close(); pz = null; msg = "Created " + out.getName() + " (password protected)";
                } else {
                zo = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out))); HashSet<String> used = new HashSet<String>();
                for (int i = 0; i < items.size(); i++) {
                    File f = items.get(i); String nm = f.getName(); int k = 2;
                    while (used.contains(nm)) { nm = f.getName() + " (" + k + ")"; k++; }
                    used.add(nm); addZip(zo, f, nm, out);
                }
                zo.close(); zo = null; msg = "Created " + out.getName();
                }
            } catch (Exception e) {
                if (zo != null) try { zo.close(); } catch (IOException x) {}
                if (pz != null) try { pz.close(); } catch (IOException x) {}
                out.delete(); msg = "Zip error: " + e.getMessage();
            }
            final String m = msg;
            a.runOnUiThread(new Runnable() { public void run() {
                try { wait.dismiss(); } catch (Exception e) {}
                a.exitMulti(); a.toast(m); a.refresh();
            }});
        }}).start();
    }

    static void addZipEnc(PZipWriter w, File f, String entry, File skip) throws IOException {
        if (f.equals(skip)) return;
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c == null || c.length == 0) { w.addDir(entry + "/", f.lastModified()); return; }
            for (int i = 0; i < c.length; i++) addZipEnc(w, c[i], entry + "/" + c[i].getName(), skip);
        } else {
            InputStream in = null;
            try { in = new FileInputStream(f); w.addFile(entry, f.lastModified(), in); }
            finally { if (in != null) try { in.close(); } catch (IOException e) {} }
        }
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
