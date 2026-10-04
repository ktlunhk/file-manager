package com.filemanager;

import java.io.*;
import java.util.*;

/** A file or folder in the user's MEGA cloud, shown in the tree like a local item . */
class MegaItem extends File {
	interface Done {
		void done(File f);
	}

	final String handle; // "" = the virtual MEGA root

	static String pathOf(String h) {
		if (h.length() == 0)
			return "/mega:";
		ArrayList<String> chain = new ArrayList<String>();
		String cur = h;
		for (int guard = 0; cur != null && cur.length() > 0 && guard < 64; guard++) {
			chain.add(0, cur);
			MegaClient.Node n = MegaClient.node(cur);
			cur = n == null ? null : n.p;
		}
		StringBuilder sb = new StringBuilder("/mega:");
		for (int i = 0; i < chain.size(); i++)
			sb.append('/').append(chain.get(i));
		return sb.toString();
	}

	MegaItem(String handle) {
		super(pathOf(handle));
		this.handle = handle;
	}

	static MegaItem root() {
		return new MegaItem("");
	}

	/** the virtual MEGA root, Cloud Drive, Inbox and Rubbish Bin: they cannot be renamed, moved or deleted */
	boolean isSystemNode() {
		if (isRootNode())
			return true;
		MegaClient.Node n = n();
		return n != null && n.t >= 2;
	}

	boolean isRootNode() {
		return handle.length() == 0;
	}

	private MegaClient.Node n() {
		return MegaClient.node(handle);
	}

	public String getName() {
		if (isRootNode())
			return "MEGA";
		MegaClient.Node n = n();
		return n == null ? handle : n.name;
	}

	public File getParentFile() {
		if (isRootNode())
			return null;
		MegaClient.Node n = n();
		if (n == null || n.p.length() == 0)
			return root();
		return new MegaItem(n.p);
	}

	public String getParent() {
		File p = getParentFile();
		return p == null ? null : p.getPath();
	}

	public boolean isDirectory() {
		if (isRootNode())
			return true;
		MegaClient.Node n = n();
		return n != null && n.t != 0;
	}

	public boolean isFile() {
		MegaClient.Node n = n();
		return n != null && n.t == 0;
	}

	public boolean exists() {
		return isRootNode() || n() != null;
	}

	public long length() {
		MegaClient.Node n = n();
		return n == null ? 0 : n.size;
	}

	public long lastModified() {
		MegaClient.Node n = n();
		return n == null ? 0 : n.ts * 1000L;
	}

	public boolean canRead() {
		return true;
	}

	public boolean canWrite() {
		return false;
	}

	public boolean isHidden() {
		return false;
	}

	public File[] listFiles() {
		ArrayList<String> hs = new ArrayList<String>();
		synchronized (MegaClient.class) {
			if (isRootNode()) {
				if (!MegaClient.isReady())
					return new File[0];
				for (int t = 2; t <= 4; t++)
					for (MegaClient.Node n : MegaClient.nodes.values())
						if (n.t == t)
							hs.add(n.h);
			} else {
				ArrayList<String> k = MegaClient.kids.get(handle);
				if (k != null)
					hs.addAll(k);
			}
		}
		File[] out = new File[hs.size()];
		for (int i = 0; i < out.length; i++)
			out[i] = new MegaItem(hs.get(i));
		return out;
	}

	public String[] list() {
		File[] f = listFiles();
		String[] s = new String[f.length];
		for (int i = 0; i < s.length; i++)
			s[i] = f[i].getName();
		return s;
	}

	InputStream openStream() throws IOException {
		return MegaClient.download(handle);
	}
}
