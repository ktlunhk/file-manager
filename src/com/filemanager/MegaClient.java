package com.filemanager;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

class MegaException extends IOException {
	final int code;

	MegaException(int code, String msg) {
		super(msg);
		this.code = code;
	}
}

/**
 * Minimal MEGA client written against MEGA's public web API: log in, read the file tree, download files.
 * Log in, read the tree, download, upload, create folders, rename, move, copy and delete.
 */
class MegaClient {
	static final String API = "https://g.api.mega.co.nz/cs";

	static class Node {
		String h, p = "", name;
		int t; // 0 file, 1 folder, 2 cloud drive, 3 inbox, 4 rubbish bin
		long size, ts;
		byte[] key;
		String attr; // decrypted attribute json, kept so a rename does not drop the other attributes
	}

	// one MegaClient object is one logged in MEGA account; several of them can be alive at the same time
	String id = ""; // short key of the account, used in paths and in the saved preferences
	String sid, email, uid;
	byte[] masterKey;
	volatile boolean ready;
	volatile long usedBytes = -1, maxBytes = -1; // storage use of the account, -1 = not known yet
	static volatile Runnable onQuota; // set by the screen: called (on a background thread) when the usage changed
	HashMap<String, Node> nodes = new HashMap<String, Node>();
	HashMap<String, ArrayList<String>> kids = new HashMap<String, ArrayList<String>>();
	private static int seq = (int) (System.currentTimeMillis() % 1000000);

	private static synchronized int nextSeq() {
		return seq++;
	}

	boolean isReady() {
		return ready;
	}

	boolean hasSession() {
		return sid != null && masterKey != null && uid != null;
	}

	String statusText() {
		if (ready)
			return "Connected";
		if (hasSession())
			return "tap to load";
		return "Tap to connect";
	}

	synchronized Node node(String h) {
		return nodes.get(h);
	}

	static String errText(int code) {
		switch (code) {
		case -9:
			return "Wrong e-mail or password";
		case -26:
			return "Two-factor code required";
		case -15:
			return "Session expired, please log in again";
		case -16:
			return "This MEGA account is blocked";
		case -4:
			return "Too many attempts, please wait a few minutes";
		case -3:
			return "MEGA is busy, try again";
		case -11:
			return "Access denied";
		case -17:
			return "Your MEGA transfer quota is used up";
		case -8:
			return "Link expired";
		}
		return "MEGA error " + code;
	}

	// ---- debug log, shown in the login window (secrets are masked) ----
	private static final StringBuilder dbg = new StringBuilder();

	static synchronized void log(String m) {
		dbg.append(new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())).append(' ').append(m).append('\n');
		if (dbg.length() > 8000)
			dbg.delete(0, dbg.length() - 8000);
	}

	static synchronized String getLog() {
		return dbg.toString();
	}

	static synchronized void clearLog() {
		dbg.setLength(0);
	}

	/** long base64-like values (keys, session ids, hashes) are cut to 6 characters */
	static String mask(String t) {
		if (t == null)
			return "";
		String r = t.replaceAll("\"([A-Za-z0-9_+/=,\\-]{24,})\"", "\"$1\"");
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([A-Za-z0-9_+/=,\\-]{24,})\"").matcher(t);
		StringBuffer sb = new StringBuffer();
		while (m.find()) {
			String v = m.group(1);
			m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("\"" + v.substring(0, 6) + "..(" + v.length() + ")\""));
		}
		m.appendTail(sb);
		String o = sb.toString();
		return o.length() > 400 ? o.substring(0, 400) + "..." : o;
	}

	/** returns {token, timestamp, answer}. Finds a 4 byte prefix so that SHA-256(prefix + token x 262144) starts with a
	 * big-endian 32 bit value <= threshold (same as hashcash.cpp in the MEGA SDK). */
	static String[] solveHashcash(String chal) throws IOException {
		String[] p = chal.trim().split(":");
		if (p.length < 4 || !p[0].equals("1"))
			throw bad("unknown hashcash challenge");
		int easiness;
		try {
			easiness = Integer.parseInt(p[1]);
		} catch (NumberFormatException e) {
			throw bad("bad hashcash easiness");
		}
		String ts = p[2], token = p[3];
		long threshold = (((((long) (easiness & 63)) << 1) + 1) << ((easiness >> 6) * 7 + 3)) & 0xFFFFFFFFL;
		byte[] tb = MegaCrypto.b64d(token);
		if (tb.length != 48)
			throw bad("hashcash token is " + tb.length + " bytes, expected 48");
		byte[] chunk = new byte[tb.length * 4096];
		for (int i = 0; i < 4096; i++)
			System.arraycopy(tb, 0, chunk, i * tb.length, tb.length);
		long t0 = System.currentTimeMillis();
		try {
			java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
			byte[] prefix = new byte[4];
			for (long counter = 0; counter < 0xFFFFFFFFL; counter++) {
				prefix[0] = (byte) (counter >>> 24);
				prefix[1] = (byte) (counter >>> 16);
				prefix[2] = (byte) (counter >>> 8);
				prefix[3] = (byte) counter;
				md.update(prefix);
				for (int i = 0; i < 64; i++) // 64 x 4096 = 262144 copies of the token
					md.update(chunk);
				byte[] h = md.digest();
				long v = ((h[0] & 0xffL) << 24) | ((h[1] & 0xffL) << 16) | ((h[2] & 0xffL) << 8) | (h[3] & 0xffL);
				if (v <= threshold) {
					log("hashcash solved after " + (counter + 1) + " tries, " + (System.currentTimeMillis() - t0) + " ms");
					return new String[] { token, ts, MegaCrypto.b64e(prefix) };
				}
				if (System.currentTimeMillis() - t0 > 120000)
					throw bad("hashcash took too long");
			}
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException("SHA-256 not available");
		}
		throw bad("hashcash not solved");
	}

	static String lastText = "";
	static String step = "";

	/** a safe description of a reply: key names only, never the values */
	static String describe(Object o) {
		if (o instanceof JSONObject) {
			StringBuilder sb = new StringBuilder("keys:");
			java.util.Iterator<?> it = ((JSONObject) o).keys();
			while (it.hasNext())
				sb.append(' ').append(it.next());
			return sb.toString();
		}
		return String.valueOf(o);
	}

	static IOException bad(String why) {
		return new IOException("Unexpected reply from MEGA [" + step + ": " + why + "]");
	}

	static String readAll(InputStream in) throws IOException {
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		byte[] b = new byte[8192];
		int n;
		while (in != null && (n = in.read(b)) > 0)
			o.write(b, 0, n);
		return o.toString("UTF-8");
	}

	/** one API request; returns JSONObject / String / Long, throws MegaException for negative error codes */
	Object api(JSONObject req) throws IOException {
		StringBuilder ks = new StringBuilder();
		java.util.Iterator<?> it = req.keys();
		while (it.hasNext())
			ks.append(' ').append(it.next());
		log("request a=" + req.optString("a", "?") + " fields:" + ks);
		try {
			Object o = api0(req);
			log("<- ok " + (o instanceof JSONObject ? describe(o) : String.valueOf(o)));
			return o;
		} catch (IOException e) {
			log("!! " + e.getClass().getSimpleName() + ": " + e.getMessage());
			throw e;
		} catch (RuntimeException e) {
			log("!! " + e);
			throw e;
		}
	}

	Object api0(JSONObject req) throws IOException {
		String hcHeader = null;
		int hcRounds = 0;
		// the MEGA SDK resends the very same request (same id) when it answers 402 + X-Hashcash, so the id is
		// taken once per call and not once per attempt
		final String urlText = API + "?id=" + nextSeq() + (sid != null ? "&sid=" + URLEncoder.encode(sid, "UTF-8") : "");
		try {
			for (int attempt = 0; attempt < 4; attempt++) {
				URL url = new URL(urlText);
				HttpURLConnection c = (HttpURLConnection) url.openConnection();
				c.setConnectTimeout(20000);
				c.setReadTimeout(60000);
				c.setDoOutput(true);
				c.setRequestMethod("POST");
				c.setRequestProperty("Content-Type", "application/json");
				if (hcHeader != null)
					c.setRequestProperty("X-Hashcash", hcHeader);
				byte[] body = ("[" + req.toString() + "]").getBytes("UTF-8");
				c.setFixedLengthStreamingMode(body.length);
				OutputStream o = c.getOutputStream();
				o.write(body);
				o.close();
				String text;
				int http = 0;
				String chal = null, hdrNames = "";
				try {
					http = c.getResponseCode();
					chal = c.getHeaderField("X-Hashcash");
					hdrNames = String.valueOf(c.getHeaderFields().keySet());
					text = readAll(http >= 400 ? c.getErrorStream() : c.getInputStream()).trim();
				} finally {
					c.disconnect();
				}
				log("<- HTTP " + http + " " + text.length() + " bytes: " + mask(text));
				lastText = text.length() > 80 ? text.substring(0, 80) : text;
				if (http == 402) {
					if (chal == null)
						throw bad("HTTP 402 without a hashcash challenge; headers " + hdrNames);
					if (++hcRounds > 4)
						throw bad("hashcash rejected " + (hcRounds - 1) + " times; headers " + hdrNames);
					String[] p = chal.trim().split(":");
					if (p.length != 4)
						throw bad("odd hashcash header (" + p.length + " parts)");
					log("hashcash challenge: easiness=" + p[1] + " ts=" + p[2] + " tokenChars=" + p[3].length()
							+ " sentProof=" + (hcHeader != null));
					String[] sol = solveHashcash(chal);
					// exactly what the SDK sends: X-Hashcash: 1:<token>:<nonce>, token from the newest 402
					hcHeader = "1:" + sol[0] + ":" + sol[2];
					attempt--;
					continue;
				}
				if (text.indexOf('{') < 0 && text.indexOf('[') != 0 && !text.matches("-?\\d+"))
					throw bad("HTTP text " + lastText.replaceAll("\\s+", " "));
				Object v;
				if (text.startsWith("["))
					v = new JSONArray(text).get(0);
				else
					v = Long.valueOf(Long.parseLong(text));
				if (v instanceof Number && !(v instanceof Double)) {
					int code = ((Number) v).intValue();
					if (code < 0) {
						if (code == -3 && attempt < 3) {
							try {
								Thread.sleep(500L << attempt);
							} catch (InterruptedException e) {
							}
							continue;
						}
						throw new MegaException(code, errText(code));
					}
				}
				return v;
			}
			throw new MegaException(-3, errText(-3));
		} catch (JSONException e) {
			throw bad("JSON " + e.getMessage());
		} catch (NumberFormatException e) {
			throw bad("not a number: " + lastText.replaceAll("\\s+", " "));
		}
	}

	JSONObject apiObj(JSONObject req) throws IOException {
		Object o = api(req);
		if (!(o instanceof JSONObject))
			throw bad("expected an object, got " + describe(o));
		return (JSONObject) o;
	}

	void login(String mail, String pw, String mfa) throws IOException {
		mail = mail.trim().toLowerCase(Locale.US);
		if (mail.length() == 0 || pw.length() == 0)
			throw new IOException("Enter your e-mail and password");
		try {
			sid = null;
			ready = false;
			step = "us0";
			int ver = 1;
			String salt = null;
			try {
				Object o = api(new JSONObject().put("a", "us0").put("user", mail));
				if (o instanceof JSONObject) {
					ver = ((JSONObject) o).optInt("v", 1);
					salt = ((JSONObject) o).optString("s", null);
				}
			} catch (MegaException e) {
				if (e.code == -9)
					throw new MegaException(-9, errText(-9));
				throw e;
			}
			log("account version " + ver + ", salt " + (salt == null ? "none" : salt.length() + " chars"));
			byte[] passKey;
			String uh;
			if (ver >= 2 && salt != null) {
				byte[] dk = MegaCrypto.pbkdf2Sha512(pw.getBytes("UTF-8"), MegaCrypto.b64d(salt), 100000, 32);
				passKey = new byte[16];
				byte[] h = new byte[16];
				System.arraycopy(dk, 0, passKey, 0, 16);
				System.arraycopy(dk, 16, h, 0, 16);
				uh = MegaCrypto.b64e(h);
			} else {
				passKey = MegaCrypto.prepareKeyV1(pw);
				uh = MegaCrypto.stringHashV1(mail, passKey);
			}
			log("password key ready, " + (ver >= 2 && salt != null ? "PBKDF2 (v2)" : "legacy (v1)"));
			step = "us";
			JSONObject req = new JSONObject().put("a", "us").put("user", mail).put("uh", uh);
			if (mfa != null && mfa.trim().length() > 0)
				req.put("mfa", mfa.trim());
			JSONObject r = apiObj(req);
			step = "us reply (" + describe(r) + ")";
			byte[] mk = MegaCrypto.decryptKey(MegaCrypto.padTo16(MegaCrypto.b64d(r.getString("k"))), passKey);
			byte[] mk16 = new byte[16];
			System.arraycopy(mk, 0, mk16, 0, 16);
			String newSid;
			if (r.has("tsid"))
				newSid = r.getString("tsid");
			else if (r.has("csid") && r.has("privk"))
				newSid = MegaCrypto.sessionIdFromCsid(r.getString("csid"), r.getString("privk"), mk16);
			else if (r.has("sid"))
				newSid = r.getString("sid");
			else
				throw bad("no session id in reply");
			log("session id obtained (" + newSid.length() + " chars)");
			sid = newSid;
			masterKey = mk16;
			email = mail;
			step = "ug";
			uid = apiObj(new JSONObject().put("a", "ug")).getString("u");
		} catch (JSONException e) {
			sid = null;
			throw bad("JSON " + e.getMessage());
		} catch (IOException e) {
			sid = null;
			throw e;
		}
	}

	void loadTree() throws IOException {
		step = "f";
		try {
			JSONObject r = apiObj(new JSONObject().put("a", "f").put("c", 1));
			JSONArray f = r.getJSONArray("f");
			HashMap<String, Node> nn = new HashMap<String, Node>();
			for (int i = 0; i < f.length(); i++) {
				JSONObject o = f.getJSONObject(i);
				Node n = new Node();
				n.h = o.getString("h");
				n.p = o.optString("p", "");
				n.t = o.getInt("t");
				n.size = o.optLong("s", 0);
				n.ts = o.optLong("ts", 0);
				if (n.t >= 2 && n.t <= 4) {
					n.name = n.t == 2 ? "Cloud Drive" : n.t == 3 ? "Inbox" : "Rubbish Bin";
					n.p = "";
				} else {
					byte[] nk = null;
					String ks = o.optString("k", "");
					String[] parts = ks.split("/");
					for (int k = 0; k < parts.length; k++) {
						int c = parts[k].indexOf(':');
						if (c < 0 || !parts[k].substring(0, c).equals(uid))
							continue;
						nk = MegaCrypto.decryptKey(MegaCrypto.padTo16(MegaCrypto.b64d(parts[k].substring(c + 1))), masterKey);
						break;
					}
					if (nk == null)
						continue; // not ours (shared by someone else)
					n.key = nk;
					String json = MegaCrypto.decryptAttr(o.optString("a", ""), nk);
					n.attr = json;
					n.name = json == null ? n.h : new JSONObject(json).optString("n", n.h);
					n.name = n.name.replace('/', '_');
					if (n.name.length() == 0)
						n.name = n.h;
				}
				nn.put(n.h, n);
			}
			HashMap<String, ArrayList<String>> kk = new HashMap<String, ArrayList<String>>();
			for (Node n : nn.values()) {
				if (n.p.length() == 0 || !nn.containsKey(n.p))
					continue;
				ArrayList<String> l = kk.get(n.p);
				if (l == null) {
					l = new ArrayList<String>();
					kk.put(n.p, l);
				}
				l.add(n.h);
			}
			synchronized (this) {
				nodes = nn;
				kids = kk;
			}
			ready = true;
			log("tree loaded: " + nn.size() + " nodes");
			refreshQuota();
		} catch (JSONException e) {
			throw bad("JSON " + e.getMessage());
		}
	}

	/** asks MEGA how much storage is used and how much the plan allows (a=uq) */
	void loadQuota() {
		try {
			JSONObject r = apiObj(new JSONObject().put("a", "uq").put("strg", 1));
			long used = r.optLong("cstrg", -1), max = r.optLong("mstrg", -1);
			if (max > 0 && used >= 0) {
				usedBytes = used;
				maxBytes = max;
			}
			log("storage: " + used + " of " + max + " bytes");
		} catch (Exception e) {
			log("storage usage not available: " + e.getMessage());
		}
	}

	/** loads the usage in the background and tells the screen when it is there */
	void refreshQuota() {
		new Thread(new Runnable() {
			public void run() {
				long before = usedBytes + maxBytes;
				loadQuota();
				Runnable r = onQuota;
				if (r != null && usedBytes + maxBytes != before)
					r.run();
			}
		}).start();
	}

	// ---------------- upload ----------------
	interface Progress {
		void bytes(long n) throws IOException; // negative = a failed attempt is being sent again
	}

	static String https(String u) {
		return u.startsWith("http://") ? "https://" + u.substring(7) : u;
	}

	synchronized String child(String parent, String name, boolean folder) {
		ArrayList<String> k = kids.get(parent);
		if (k == null)
			return null;
		for (int i = 0; i < k.size(); i++) {
			Node n = nodes.get(k.get(i));
			if (n != null && n.name != null && n.name.equals(name) && (n.t != 0) == folder)
				return n.h;
		}
		return null;
	}

	synchronized void addNode(String h, String parent, int t, String name, long size, long ts, byte[] key) {
		Node n = new Node();
		n.h = h;
		n.p = parent;
		n.t = t;
		n.name = name;
		n.size = size;
		n.ts = ts;
		n.key = key;
		nodes.put(h, n);
		ArrayList<String> l = kids.get(parent);
		if (l == null) {
			l = new ArrayList<String>();
			kids.put(parent, l);
		}
		if (!l.contains(h))
			l.add(h);
	}

	/** puts a new node under parent (a=p) and adds it to the local tree; returns its handle */
	private String putNode(String token, int t, String name, byte[] nodeKey, String parent, long size) throws IOException {
		try {
			String attrJson = new JSONObject().put("n", name).toString();
			String attr = MegaCrypto.encryptAttr(attrJson, nodeKey);
			String k = MegaCrypto.b64e(MegaCrypto.aesEcb(nodeKey, masterKey, true));
			JSONObject node = new JSONObject().put("h", token).put("t", t).put("a", attr).put("k", k);
			step = "p";
			JSONObject r = apiObj(new JSONObject().put("a", "p").put("t", parent).put("n", new JSONArray().put(node)));
			JSONArray f = r.optJSONArray("f");
			if (f == null || f.length() == 0)
				throw bad("no node in reply (" + describe(r) + ")");
			JSONObject o = f.getJSONObject(0);
			String h = o.getString("h");
			addNode(h, parent, t, name, o.optLong("s", size), o.optLong("ts", System.currentTimeMillis() / 1000), nodeKey);
			Node added = node(h);
			if (added != null)
				added.attr = attrJson;
			return h;
		} catch (JSONException e) {
			throw bad("JSON " + e.getMessage());
		}
	}

	String makeFolder(String name, String parent) throws IOException {
		if (!hasSession())
			throw new IOException("Not logged in to MEGA");
		return putNode("xxxxxxxx", 1, name, MegaCrypto.random(16), parent, 0);
	}

	private static byte[] readBytes(InputStream in) throws IOException {
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		byte[] b = new byte[4096];
		int n;
		while (in != null && (n = in.read(b)) > 0)
			o.write(b, 0, n);
		return o.toByteArray();
	}

	private static boolean b64text(byte[] b) {
		for (int i = 0; i < b.length; i++) {
			int c = b[i] & 0xff;
			if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_'))
				return false;
		}
		return true;
	}

	/** sends one encrypted chunk; returns the upload token when this was the last chunk, else null */
	private static String postChunk(String url, long pos, byte[] data, Progress pg) throws IOException {
		IOException last = null;
		for (int attempt = 0; attempt < 4; attempt++) {
			long sent = 0;
			HttpURLConnection c = null;
			try {
				c = (HttpURLConnection) new URL(url + "/" + pos).openConnection();
				c.setConnectTimeout(20000);
				c.setReadTimeout(120000);
				c.setDoOutput(true);
				c.setRequestMethod("POST");
				c.setRequestProperty("Content-Type", "application/octet-stream");
				c.setFixedLengthStreamingMode(data.length);
				OutputStream o = c.getOutputStream();
				int off = 0;
				while (off < data.length) {
					int n = Math.min(65536, data.length - off);
					o.write(data, off, n);
					off += n;
					sent += n;
					pg.bytes(n);
				}
				o.close();
				int code = c.getResponseCode();
				byte[] body = readBytes(code >= 400 ? c.getErrorStream() : c.getInputStream());
				if (code != 200)
					throw new IOException("Upload failed (HTTP " + code + ")");
				if (body.length == 0)
					return null;
				String txt = new String(body, "ISO-8859-1").trim();
				if (txt.matches("-?\\d+")) {
					int e = Integer.parseInt(txt);
					if (e < 0)
						throw new MegaException(e, errText(e));
					return null;
				}
				if (body.length == 36)
					return b64text(body) ? txt : MegaCrypto.b64e(body);
				throw new IOException("Unexpected upload reply from MEGA (" + body.length + " bytes)");
			} catch (MegaException e) {
				throw e;
			} catch (java.net.SocketTimeoutException e) {
				last = e;
			} catch (InterruptedIOException e) {
				throw e; // cancelled by the user
			} catch (IOException e) {
				last = e;
			} finally {
				if (c != null)
					c.disconnect();
			}
			if (sent > 0)
				pg.bytes(-sent);
			try {
				Thread.sleep(800L * (attempt + 1));
			} catch (InterruptedException e) {
			}
		}
		throw last;
	}

	/** uploads a local file into the MEGA folder `parent`; returns the new node handle */
	String uploadFile(File f, String parent, Progress pg) throws IOException {
		InputStream in = new FileInputStream(f);
		try {
			return uploadStream(in, f.getName(), f.length(), parent, pg);
		} finally {
			try {
				in.close();
			} catch (IOException e) {
			}
		}
	}

	/** uploads `size` bytes read from `in` as a new file called `name` into the MEGA folder `parent` of this account.
	 * The data is encrypted chunk by chunk while it is read, so nothing is written to disk. This is also what moves a
	 * file from one MEGA account to another: the source account's download() stream is the input here. The caller
	 * closes `in`. Returns the new node handle. */
	String uploadStream(InputStream in, String name, long size, String parent, Progress pg) throws IOException {
		if (!hasSession())
			throw new IOException("Not logged in to MEGA");
		try {
			step = "u";
			JSONObject r = apiObj(new JSONObject().put("a", "u").put("ssl", 2).put("s", size));
			if (!r.has("p"))
				throw bad("no upload address (" + describe(r) + ")");
			String url = https(r.getString("p"));
			byte[] aesKey = MegaCrypto.random(16);
			byte[] nonce = MegaCrypto.random(8);
			javax.crypto.Cipher ctr = MegaCrypto.ctrEncryptor(aesKey, nonce);
			byte[] fileMac = new byte[16];
			String token = null;
			long pos = 0;
			int chunk = 131072; // chunk sizes grow 128K, 256K ... 1M; the MAC depends on these boundaries
			do {
				int want = (int) Math.min((long) chunk, size - pos);
				byte[] buf = new byte[want];
				int got = 0;
				while (got < want) {
					int n = in.read(buf, got, want - got);
					if (n < 0)
						throw new IOException("File ended early or changed while copying: " + name);
					got += n;
				}
				if (want > 0)
					fileMac = MegaCrypto.foldMac(fileMac, MegaCrypto.chunkMac(buf, want, aesKey, nonce), aesKey);
				byte[] enc = want > 0 ? ctr.update(buf) : buf;
				token = postChunk(url, pos, enc, pg);
				pos += want;
				if (chunk < 1048576)
					chunk += 131072;
			} while (pos < size);
			if (token == null)
				throw new IOException("MEGA did not confirm the upload");
			byte[] nodeKey = MegaCrypto.fileNodeKey(aesKey, nonce, fileMac);
			return putNode(token, 0, name, nodeKey, parent, size);
		} catch (JSONException e) {
			throw bad("JSON " + e.getMessage());
		}
	}

	// ---------------- change the tree: rename, move, delete, copy ----------------
	synchronized String rubbishHandle() {
		for (Node n : nodes.values())
			if (n.t == 4)
				return n.h;
		return null;
	}

	/** true when h is in the Rubbish Bin (or is it) */
	synchronized boolean inRubbish(String h) {
		String cur = h;
		for (int g = 0; cur != null && cur.length() > 0 && g < 64; g++) {
			Node n = nodes.get(cur);
			if (n == null)
				return false;
			if (n.t == 4)
				return true;
			cur = n.p;
		}
		return false;
	}

	/** true when h is `ancestor` itself or somewhere below it */
	synchronized boolean isInside(String h, String ancestor) {
		String cur = h;
		for (int g = 0; cur != null && cur.length() > 0 && g < 64; g++) {
			if (cur.equals(ancestor))
				return true;
			Node n = nodes.get(cur);
			cur = n == null ? null : n.p;
		}
		return false;
	}

	synchronized ArrayList<String> kidsOf(String h) {
		ArrayList<String> l = kids.get(h);
		return l == null ? new ArrayList<String>() : new ArrayList<String>(l);
	}

	private synchronized void detach(String h) {
		Node n = nodes.get(h);
		if (n == null)
			return;
		ArrayList<String> l = kids.get(n.p);
		if (l != null)
			l.remove(h);
	}

	private synchronized void dropTree(String h) {
		ArrayList<String> k = kids.remove(h);
		if (k != null)
			for (String c : new ArrayList<String>(k))
				dropTree(c);
		nodes.remove(h);
	}

	/** a free name in `parent`: base+ext, then "base 2"+ext ... */
	String freeName(String parent, String base, String ext) {
		String out = base + ext;
		int i = 2;
		while (child(parent, out, false) != null || child(parent, out, true) != null) {
			out = base + " " + i + ext;
			i++;
		}
		return out;
	}

	/** "name copy.ext", "name copy 2.ext" ... that is free in `parent` */
	String copyName(String parent, String name, boolean folder) {
		String base = name, ext = "";
		int dot = name.lastIndexOf('.');
		if (!folder && dot > 0) {
			base = name.substring(0, dot);
			ext = name.substring(dot);
		}
		String out = base + " copy" + ext;
		int i = 2;
		while (child(parent, out, folder) != null) {
			out = base + " copy " + i + ext;
			i++;
		}
		return out;
	}

	void rename(String h, String newName) throws IOException {
		Node n = node(h);
		if (n == null || n.key == null)
			throw new IOException("Cannot rename this item");
		try {
			JSONObject j = n.attr != null && n.attr.length() > 0 ? new JSONObject(n.attr) : new JSONObject();
			j.put("n", newName);
			String json = j.toString();
			String attr = MegaCrypto.encryptAttr(json, n.key);
			String k = MegaCrypto.b64e(MegaCrypto.aesEcb(n.key, masterKey, true));
			step = "a";
			api(new JSONObject().put("a", "a").put("n", h).put("attr", attr).put("key", k));
			synchronized (this) {
				n.name = newName;
				n.attr = json;
			}
		} catch (JSONException e) {
			throw bad("JSON " + e.getMessage());
		}
	}

	void move(String h, String newParent) throws IOException {
		try {
			step = "m";
			api(new JSONObject().put("a", "m").put("n", h).put("t", newParent));
		} catch (JSONException e) {
			throw bad("JSON " + e.getMessage());
		}
		synchronized (this) {
			Node n = nodes.get(h);
			if (n == null)
				return;
			detach(h);
			n.p = newParent;
			ArrayList<String> l = kids.get(newParent);
			if (l == null) {
				l = new ArrayList<String>();
				kids.put(newParent, l);
			}
			if (!l.contains(h))
				l.add(h);
		}
	}

	/** permanent delete */
	void remove(String h) throws IOException {
		try {
			step = "d";
			api(new JSONObject().put("a", "d").put("n", h));
		} catch (JSONException e) {
			throw bad("JSON " + e.getMessage());
		}
		synchronized (this) {
			detach(h);
			dropTree(h);
		}
	}

	/** to the Rubbish Bin, or gone for good when it already is in there */
	void trashOrDelete(String h) throws IOException {
		if (inRubbish(h)) {
			remove(h);
			return;
		}
		String rb = rubbishHandle();
		if (rb == null)
			throw new IOException("Rubbish Bin not found");
		move(h, rb);
	}

	/** server side copy of a file (no data is transferred); the new node gets newName */
	String copyFileTo(String srcH, String parent, String newName) throws IOException {
		Node n = node(srcH);
		if (n == null || n.key == null)
			throw new IOException("Cannot copy this item");
		try {
			JSONObject j = n.attr != null && n.attr.length() > 0 ? new JSONObject(n.attr) : new JSONObject();
			j.put("n", newName);
			String json = j.toString();
			String attr = MegaCrypto.encryptAttr(json, n.key);
			String k = MegaCrypto.b64e(MegaCrypto.aesEcb(n.key, masterKey, true));
			JSONObject node = new JSONObject().put("h", srcH).put("t", 0).put("a", attr).put("k", k);
			step = "p";
			JSONObject r = apiObj(new JSONObject().put("a", "p").put("t", parent).put("n", new JSONArray().put(node)));
			JSONArray f = r.optJSONArray("f");
			if (f == null || f.length() == 0)
				throw bad("no node in reply (" + describe(r) + ")");
			JSONObject o = f.getJSONObject(0);
			String h = o.getString("h");
			addNode(h, parent, 0, newName, o.optLong("s", n.size), o.optLong("ts", System.currentTimeMillis() / 1000), n.key);
			Node added = node(h);
			if (added != null)
				added.attr = json;
			return h;
		} catch (JSONException e) {
			throw bad("JSON " + e.getMessage());
		}
	}

	/** copies a file or a whole folder inside MEGA under a new name */
	String copyTree(String srcH, String parent, String newName) throws IOException {
		Node n = node(srcH);
		if (n == null)
			throw new IOException("Item not found");
		if (n.t == 0)
			return copyFileTo(srcH, parent, newName);
		String dir = makeFolder(newName, parent);
		ArrayList<String> k = kidsOf(srcH);
		for (int i = 0; i < k.size(); i++) {
			Node c = node(k.get(i));
			if (c != null)
				copyTree(c.h, dir, c.name);
		}
		return dir;
	}

	/** forgets the session (it expired) but keeps the account object and its e-mail, so the same account can log in again */
	void dropSession() {
		sid = null;
		masterKey = null;
		uid = null;
		ready = false;
		synchronized (this) {
			nodes = new HashMap<String, Node>();
			kids = new HashMap<String, ArrayList<String>>();
		}
	}

	void logout() {
		sid = null;
		masterKey = null;
		uid = null;
		email = null;
		ready = false;
		usedBytes = -1;
		maxBytes = -1;
		synchronized (this) {
			nodes = new HashMap<String, Node>();
			kids = new HashMap<String, ArrayList<String>>();
		}
	}

	InputStream download(String h) throws IOException {
		Node n = node(h);
		if (n == null || n.t != 0 || n.key == null)
			throw new IOException("Not a downloadable file");
		try {
			JSONObject r = apiObj(new JSONObject().put("a", "g").put("g", 1).put("n", h));
			if (!r.has("g"))
				throw new IOException("MEGA did not give a download link");
			String link = r.getString("g");
			// MEGA hands out plain http:// links; Android blocks cleartext traffic, and the storage servers also speak https
			if (link.startsWith("http://"))
				link = "https://" + link.substring(7);
			final HttpURLConnection c = (HttpURLConnection) new URL(link).openConnection();
			c.setConnectTimeout(20000);
			c.setReadTimeout(60000);
			int code = c.getResponseCode();
			if (code == 509)
				throw new MegaException(-17, errText(-17));
			if (code != 200) {
				c.disconnect();
				throw new IOException("Download failed (HTTP " + code + ")");
			}
			InputStream dec = MegaCrypto.ctrStream(new BufferedInputStream(c.getInputStream(), 65536), n.key);
			return new FilterInputStream(dec) {
				public void close() throws IOException {
					try {
						super.close();
					} finally {
						c.disconnect();
					}
				}
			};
		} catch (JSONException e) {
			throw new IOException("Unexpected reply from MEGA");
		}
	}

	// ---- the list of all accounts ----
	static final java.util.concurrent.CopyOnWriteArrayList<MegaClient> accounts = new java.util.concurrent.CopyOnWriteArrayList<MegaClient>();
	private static int nextId = 1;

	static MegaClient byId(String id) {
		for (MegaClient c : accounts)
			if (c.id.equals(id))
				return c;
		return null;
	}

	static MegaClient byEmail(String mail) {
		if (mail == null)
			return null;
		for (MegaClient c : accounts)
			if (c.email != null && c.email.equalsIgnoreCase(mail.trim()))
				return c;
		return null;
	}

	/** gives a new account its id and adds it to the list */
	static synchronized void register(MegaClient c) {
		if (accounts.contains(c))
			return;
		while (byId(String.valueOf(nextId)) != null)
			nextId++;
		c.id = String.valueOf(nextId++);
		accounts.add(c);
	}

	static synchronized void unregister(MegaClient c) {
		accounts.remove(c);
	}

	// ---- keep the logins between app starts (stored in the app's private preferences) ----
	static void saveAll(android.content.SharedPreferences p) {
		android.content.SharedPreferences.Editor e = p.edit();
		// the single account preferences of older versions
		e.remove("mega_sid").remove("mega_mk").remove("mega_uid").remove("mega_email");
		String old = p.getString("mega_ids", "");
		if (old.length() > 0) {
			String[] parts = old.split(",");
			for (int i = 0; i < parts.length; i++)
				e.remove("mega_" + parts[i] + "_sid").remove("mega_" + parts[i] + "_mk").remove("mega_" + parts[i] + "_uid")
						.remove("mega_" + parts[i] + "_email");
		}
		StringBuilder ids = new StringBuilder();
		for (MegaClient c : accounts) {
			if (!c.hasSession())
				continue;
			if (ids.length() > 0)
				ids.append(',');
			ids.append(c.id);
			e.putString("mega_" + c.id + "_sid", c.sid).putString("mega_" + c.id + "_mk", MegaCrypto.b64e(c.masterKey))
					.putString("mega_" + c.id + "_uid", c.uid).putString("mega_" + c.id + "_email", c.email == null ? "" : c.email);
		}
		e.putString("mega_ids", ids.toString());
		e.putInt("mega_next", nextId);
		e.commit();
	}

	static void restoreAll(android.content.SharedPreferences p) {
		accounts.clear();
		nextId = Math.max(1, p.getInt("mega_next", 1));
		String ids = p.getString("mega_ids", null);
		if (ids == null) {
			// the login of an older version with a single account becomes account 1
			String s = p.getString("mega_sid", null), m = p.getString("mega_mk", null), u = p.getString("mega_uid", null);
			if (s == null || m == null || u == null)
				return;
			MegaClient c = new MegaClient();
			c.sid = s;
			c.masterKey = MegaCrypto.b64d(m);
			c.uid = u;
			c.email = p.getString("mega_email", "");
			register(c);
			return;
		}
		String[] parts = ids.split(",");
		for (int i = 0; i < parts.length; i++) {
			String id = parts[i].trim();
			if (id.length() == 0)
				continue;
			String s = p.getString("mega_" + id + "_sid", null), m = p.getString("mega_" + id + "_mk", null);
			String u = p.getString("mega_" + id + "_uid", null);
			if (s == null || m == null || u == null)
				continue;
			MegaClient c = new MegaClient();
			c.id = id;
			c.sid = s;
			c.masterKey = MegaCrypto.b64d(m);
			c.uid = u;
			c.email = p.getString("mega_" + id + "_email", "");
			accounts.add(c);
			try {
				nextId = Math.max(nextId, Integer.parseInt(id) + 1);
			} catch (NumberFormatException e) {
			}
		}
	}
}
