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
 * Can log in, read the tree, download, upload files and create folders (no rename or delete).
 */
class MegaClient {
	static final String API = "https://g.api.mega.co.nz/cs";

	static class Node {
		String h, p = "", name;
		int t; // 0 file, 1 folder, 2 cloud drive, 3 inbox, 4 rubbish bin
		long size, ts;
		byte[] key;
	}

	static String sid, email, uid;
	static byte[] masterKey;
	static volatile boolean ready;
	static HashMap<String, Node> nodes = new HashMap<String, Node>();
	static HashMap<String, ArrayList<String>> kids = new HashMap<String, ArrayList<String>>();
	private static int seq = (int) (System.currentTimeMillis() % 1000000);

	static boolean isReady() {
		return ready;
	}

	static boolean hasSession() {
		return sid != null && masterKey != null && uid != null;
	}

	static String statusText() {
		if (ready)
			return email == null ? "Connected" : email;
		if (hasSession())
			return (email == null ? "" : email + " - ") + "tap to load";
		return "Tap to connect";
	}

	static synchronized Node node(String h) {
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
	static Object api(JSONObject req) throws IOException {
		StringBuilder ks = new StringBuilder();
		java.util.Iterator<?> it = req.keys();
		while (it.hasNext())
			ks.append(' ').append(it.next());
		log("-> request a=" + req.optString("a", "?") + " fields:" + ks);
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

	static Object api0(JSONObject req) throws IOException {
		String hcHeader = null;
		int hcRounds = 0;
		// the MEGA SDK resends the very same request (same id) when it answers 402 + X-Hashcash, so the id is
		// taken once per call and not once per attempt
		final String urlText = API + "?id=" + (seq++) + (sid != null ? "&sid=" + URLEncoder.encode(sid, "UTF-8") : "");
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

	static JSONObject apiObj(JSONObject req) throws IOException {
		Object o = api(req);
		if (!(o instanceof JSONObject))
			throw bad("expected an object, got " + describe(o));
		return (JSONObject) o;
	}

	static void login(String mail, String pw, String mfa) throws IOException {
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

	static void loadTree() throws IOException {
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
			synchronized (MegaClient.class) {
				nodes = nn;
				kids = kk;
			}
			ready = true;
			log("tree loaded: " + nn.size() + " nodes");
		} catch (JSONException e) {
			throw bad("JSON " + e.getMessage());
		}
	}

	// ---------------- upload ----------------
	interface Progress {
		void bytes(long n) throws IOException; // negative = a failed attempt is being sent again
	}

	static String https(String u) {
		return u.startsWith("http://") ? "https://" + u.substring(7) : u;
	}

	static synchronized String child(String parent, String name, boolean folder) {
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

	static synchronized void addNode(String h, String parent, int t, String name, long size, long ts, byte[] key) {
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
	private static String putNode(String token, int t, String name, byte[] nodeKey, String parent, long size) throws IOException {
		try {
			String attr = MegaCrypto.encryptAttr(new JSONObject().put("n", name).toString(), nodeKey);
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
			return h;
		} catch (JSONException e) {
			throw bad("JSON " + e.getMessage());
		}
	}

	static String makeFolder(String name, String parent) throws IOException {
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
	static String uploadFile(File f, String parent, Progress pg) throws IOException {
		if (!hasSession())
			throw new IOException("Not logged in to MEGA");
		long size = f.length();
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
			InputStream in = new FileInputStream(f);
			String token = null;
			try {
				long pos = 0;
				int chunk = 131072; // chunk sizes grow 128K, 256K ... 1M; the MAC depends on these boundaries
				do {
					int want = (int) Math.min((long) chunk, size - pos);
					byte[] buf = new byte[want];
					int got = 0;
					while (got < want) {
						int n = in.read(buf, got, want - got);
						if (n < 0)
							throw new IOException("File changed while uploading: " + f.getName());
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
			} finally {
				try {
					in.close();
				} catch (IOException e) {
				}
			}
			if (token == null)
				throw new IOException("MEGA did not confirm the upload");
			byte[] nodeKey = MegaCrypto.fileNodeKey(aesKey, nonce, fileMac);
			return putNode(token, 0, f.getName(), nodeKey, parent, size);
		} catch (JSONException e) {
			throw bad("JSON " + e.getMessage());
		}
	}

	static void logout() {
		sid = null;
		masterKey = null;
		uid = null;
		email = null;
		ready = false;
		synchronized (MegaClient.class) {
			nodes = new HashMap<String, Node>();
			kids = new HashMap<String, ArrayList<String>>();
		}
	}

	static InputStream download(String h) throws IOException {
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

	// ---- keep the login between app starts (stored in the app's private preferences) ----
	static void save(android.content.SharedPreferences p) {
		android.content.SharedPreferences.Editor e = p.edit();
		if (hasSession()) {
			e.putString("mega_sid", sid).putString("mega_mk", MegaCrypto.b64e(masterKey)).putString("mega_uid", uid)
					.putString("mega_email", email == null ? "" : email);
		} else {
			e.remove("mega_sid").remove("mega_mk").remove("mega_uid").remove("mega_email");
		}
		e.commit();
	}

	static void restore(android.content.SharedPreferences p) {
		String s = p.getString("mega_sid", null), m = p.getString("mega_mk", null), u = p.getString("mega_uid", null);
		if (s == null || m == null || u == null)
			return;
		sid = s;
		masterKey = MegaCrypto.b64d(m);
		uid = u;
		email = p.getString("mega_email", "");
	}
}
