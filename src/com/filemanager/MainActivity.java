package com.filemanager;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.Signature;
import android.graphics.BitmapFactory;
import android.webkit.MimeTypeMap;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import android.graphics.Color;
import android.net.Uri;
import android.provider.Settings;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.io.*;
import java.text.*;
import java.util.*;
import java.security.MessageDigest;

public class MainActivity extends Activity {
	FileOperations fileOperations;
	LinearLayout leftTree, rightTree;
	TreeLinesOverlay leftLines, rightLines;
	TextView status;
	LinearLayout leftCrumbs, rightCrumbs;
	HorizontalScrollView leftCrumbScroll, rightCrumbScroll;
	ScrollView leftTreeScroll, rightTreeScroll;
	String pendingBookmarkScrollPath;
	boolean pendingBookmarkScrollLeft;
	File leftCur, rightCur; 
	File leftRoot, rightRoot, selected;
	boolean selectedLeft;
	HashSet<String> leftOpen = new HashSet<String>();
	HashSet<String> rightOpen = new HashSet<String>();
	java.util.concurrent.ConcurrentHashMap<String, File[]> listCache = new java.util.concurrent.ConcurrentHashMap<String, File[]>();
	int leftGen, rightGen;
	boolean multiMode, multiLeft;
	ArrayList<File> multi = new ArrayList<File>();
	LinearLayout selBar;
	TextView selCount;
	SharedPreferences prefs;
	static final int SORT_NAME = 0, SORT_SIZE = 1, SORT_DATE = 2, SORT_TYPE = 3;
	int leftSort = SORT_NAME, rightSort = SORT_NAME;
	boolean leftSortRev, rightSortRev;
	boolean showHidden = false;
	int leftScrollY = -1, rightScrollY = -1; // scroll position restored after the first build
	boolean showMega = false; // MEGA cloud storage in the main screen, off by default
	LinearLayout bodyRef;
	LinearLayout.LayoutParams leftLpRef, rightLpRef;
	float splitWeight = 1f; 
	static final int THEME_SYSTEM = 0, THEME_LIGHT = 1, THEME_DARK = 2;
	int themeMode = THEME_SYSTEM;
	boolean dark;
	int colBg, colSurface, colSurfaceAlt, colCrumbBg, colText, colTextMuted, colDivider,
			colRootRow, colSelectedRow, colMultiRow, colSearchRow, colSelBarBg, colSelBarText,
			colSearchBarBg, colCrumbFillLast, colCrumbFillOther, colCrumbStroke, colCrumbText, colLine;
	View mainRoot; 
	boolean showingSettings;
	PreviewManager previewManager;
	LinearLayout searchBar;
	EditText searchBox;
	TextView searchScopeLabel;
	HashSet<String> searchMatches = new HashSet<String>(); 
	boolean searching;
	Thread searchThread; 
	volatile boolean searchCancelled;
	int searchGen;
	boolean advSearchRecursive = true, advSearchCase = false;
	int advSearchKind = 0; // 0 all, 1 files, 2 folders
	String advSearchExt = "";
	long advSearchMin = -1, advSearchMax = -1, advSearchAfter = -1, advSearchDays = -1; 
	int dp;
	ArrayList<String> bookmarks = new ArrayList<String>();
	static final String TRASH_NAME = ".trash";
	LinearLayout undoBar;
	TextView undoText;
	Handler mainHandler = new Handler(Looper.getMainLooper());
	Runnable hideUndo;
	ArrayList<File[]> lastTrashBatch; 
	
	AlertDialog.Builder themedDialogBuilder() {
		int theme = dark ? android.R.style.Theme_Material_Dialog_Alert
				: android.R.style.Theme_Material_Light_Dialog_Alert;
		return new DragBuilder(this, theme); // dialogs made by it can be dragged
	}

	AlertDialog.Builder createDialog(String title, String message) {
		AlertDialog.Builder builder = themedDialogBuilder();
		if (title != null) builder.setTitle(title);
		if (message != null) builder.setMessage(message);
		return builder;
	}

	void showMessageDialog(String title, String message) {
		createDialog(title, message)
				.setPositiveButton("OK", null)
				.show();
	}

	void showConfirmDialog(String title, String message, String positiveText, String negativeText,
			DialogInterface.OnClickListener listener) {
		createDialog(title, message)
				.setPositiveButton(positiveText, listener)
				.setNegativeButton(negativeText, null)
				.show();
	}

	/** left/right margin of dialog content: same as the dialog title and message (24dp) */
	void padDialogBox(View box) {
		box.setPadding(24 * dp, 8 * dp, 24 * dp, 0);
	}

	/** removes the inner side padding of an input so its text starts exactly under the title */
	void alignInput(EditText e) {
		e.setPadding(0, e.getPaddingTop(), 0, e.getPaddingBottom());
	}

	void themeDialogView(View view) {
		if (view == null) return;
		if (view instanceof TextView) {
			TextView tv = (TextView) view;
			tv.setTextColor(colText);
			if (tv instanceof EditText) {
				((EditText) tv).setHintTextColor(colTextMuted);
				if (android.os.Build.VERSION.SDK_INT >= 21)
					tv.setBackgroundTintList(android.content.res.ColorStateList.valueOf(dark ? 0xFFB0B0B0 : 0xFF666666));
			}
		}
		if (view instanceof android.widget.CompoundButton && android.os.Build.VERSION.SDK_INT >= 21) {
			int normal = dark ? 0xFFBDBDBD : 0xFF616161;
			int checked = dark ? 0xFF80CBC4 : 0xFF00796B;
			int[][] states = new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} };
			int[] colors = new int[] { checked, normal };
			((android.widget.CompoundButton) view).setButtonTintList(new android.content.res.ColorStateList(states, colors));
		}
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) themeDialogView(group.getChildAt(i));
		}
	}

	void computeTheme() {
		dark = themeMode == THEME_DARK || (themeMode == THEME_SYSTEM
				&& (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
						== android.content.res.Configuration.UI_MODE_NIGHT_YES);
		if (dark) {
			colBg = Color.rgb(18, 20, 22);
			colSurface = Color.rgb(30, 33, 36);
			colSurfaceAlt = Color.rgb(40, 44, 48);
			colCrumbBg = Color.rgb(32, 48, 58);
			colText = Color.rgb(225, 230, 232);
			colTextMuted = Color.rgb(150, 160, 165);
			colDivider = Color.rgb(60, 64, 68);
			colRootRow = Color.rgb(28, 58, 60);
			colSelectedRow = Color.rgb(35, 70, 90);
			colMultiRow = Color.rgb(90, 70, 30);
			colSearchRow = Color.rgb(95, 85, 25);
			colSelBarBg = Color.rgb(70, 55, 25);
			colSelBarText = Color.rgb(255, 225, 180);
			colSearchBarBg = Color.rgb(55, 55, 30);
			colCrumbFillLast = Color.rgb(60, 95, 140);
			colCrumbFillOther = Color.rgb(45, 75, 95);
			colCrumbStroke = Color.rgb(90, 130, 160);
			colCrumbText = Color.rgb(210, 225, 235);
			colLine = Color.rgb(140, 150, 155);
		} else {
			colBg = Color.WHITE;
			colSurface = Color.WHITE;
			colSurfaceAlt = Color.rgb(238, 241, 243);
			colCrumbBg = Color.rgb(210, 235, 248);
			colText = Color.rgb(20, 25, 30);
			colTextMuted = Color.rgb(115, 130, 140);
			colDivider = Color.rgb(210, 210, 210);
			colRootRow = Color.rgb(218, 245, 247);
			colSelectedRow = Color.rgb(205, 235, 250);
			colMultiRow = Color.rgb(255, 228, 170);
			colSearchRow = Color.rgb(255, 244, 160);
			colSelBarBg = Color.rgb(255, 236, 190);
			colSelBarText = Color.rgb(60, 45, 10);
			colSearchBarBg = Color.rgb(255, 255, 235);
			colCrumbFillLast = Color.rgb(150, 190, 240);
			colCrumbFillOther = Color.rgb(190, 222, 238);
			colCrumbStroke = Color.rgb(110, 150, 180);
			colCrumbText = Color.rgb(20, 45, 65);
			colLine = Color.rgb(125, 145, 155);
		}
	}

	void applyRipple(View v) {
		try {
			android.util.TypedValue tv = new android.util.TypedValue();
			getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
			v.setForeground(getDrawable(tv.resourceId));
		} catch (Exception e) {
		}
	}

	static ArrayList<File> one(File f) {
		ArrayList<File> l = new ArrayList<File>();
		l.add(f);
		return l;
	}

	public void onCreate(Bundle b) {
		super.onCreate(b);
		ZipItem.cacheDir = getCacheDir();
		// old downloads, previews and temp files are removed: older than 3 days, or beyond 500 MB (oldest first)
		new Thread(new Runnable() {
			public void run() {
				try {
					CacheCleaner.clean(getCacheDir(), 3L * 24 * 3600 * 1000, 500L * 1024 * 1024);
				} catch (Throwable t) {
				}
			}
		}).start();
		MegaClient.onQuota = new Runnable() {
			public void run() {
				uiPost(new Runnable() {
					public void run() {
						refresh(true, true, false); // shows the new "Free x/y" on the MEGA rows
					}
				});
			}
		};
		fileOperations = new FileOperations(this);
		if (Build.VERSION.SDK_INT >= 30) {
		
			getWindow().setDecorFitsSystemWindows(true);
		}
		dp = (int) getResources().getDisplayMetrics().density;
		prefs = getSharedPreferences("dfm", MODE_PRIVATE);
		MegaClient.restoreAll(prefs);
		leftRoot = Environment.getExternalStorageDirectory();
		rightRoot = Environment.getExternalStorageDirectory();
		leftCur = leftRoot;
		rightCur = rightRoot;
		
		leftOpen.add(leftRoot.getAbsolutePath());
		rightOpen.add(rightRoot.getAbsolutePath());
		registerStorageReceiver();
		volCache = storageVolumes();
		volSig = volSignature(volCache);
		loadState();
		computeTheme();
		makeUi();
		requestAccess();
		refresh();
	}

	void loadState() {
		splitWeight = prefs.getFloat("splitWeight", 1f);
		if (splitWeight < 0.3f || splitWeight > 1.7f)
			splitWeight = 1f;
		leftSort = prefs.getInt("leftSort", SORT_NAME);
		rightSort = prefs.getInt("rightSort", SORT_NAME);
		leftSortRev = prefs.getBoolean("leftSortRev", false);
		rightSortRev = prefs.getBoolean("rightSortRev", false);
		showHidden = prefs.getBoolean("showHidden", false);
		showMega = prefs.getBoolean("showMega", false);
		themeMode = prefs.getInt("themeMode", THEME_SYSTEM);
		advSearchKind = prefs.getInt("advSearchKind", 0);
		advSearchExt = prefs.getString("advSearchExt", "");
		advSearchMin = prefs.getLong("advSearchMin", -1);
		advSearchMax = prefs.getLong("advSearchMax", -1);
		advSearchDays = prefs.getLong("advSearchDays", -1);
		advSearchAfter = advSearchDays >= 0 ? System.currentTimeMillis() - advSearchDays * 86400000L : -1;
		advSearchRecursive = prefs.getBoolean("advSearchRecursive", true);
		advSearchCase = prefs.getBoolean("advSearchCase", false);
		File lc = new File(prefs.getString("leftCur", ""));
		File rc = new File(prefs.getString("rightCur", ""));
		if (lc.getPath().length() > 0 && lc.isDirectory()) leftCur = lc;
		if (rc.getPath().length() > 0 && rc.isDirectory()) rightCur = rc;
		leftScrollY = prefs.getInt("leftScrollY", 0);
		rightScrollY = prefs.getInt("rightScrollY", 0);
		restoreOpen(leftOpen, prefs.getString("leftOpen", ""));
		restoreOpen(rightOpen, prefs.getString("rightOpen", ""));
		bookmarks.clear();
		String b = prefs.getString("bookmarks", "");
		if (b.length() > 0)
			for (String s : b.split("\n"))
				if (s.length() > 0)
					bookmarks.add(s);
	}

	void restoreOpen(HashSet<String> set, String saved) {
		if (saved == null || saved.length() == 0)
			return;
		for (String p : saved.split("\n"))
			if (p.length() > 0 && new File(p).isDirectory())
				set.add(p);
	}

	void saveState() {
		if (prefs == null)
			return;
		SharedPreferences.Editor e = prefs.edit();
		e.putFloat("splitWeight", splitWeight);
		e.putInt("leftSort", leftSort);
		e.putInt("rightSort", rightSort);
		e.putBoolean("leftSortRev", leftSortRev);
		e.putBoolean("rightSortRev", rightSortRev);
		e.putBoolean("showHidden", showHidden);
		e.putBoolean("showMega", showMega);
		e.putInt("themeMode", themeMode);
		e.putInt("advSearchKind", advSearchKind);
		e.putString("advSearchExt", advSearchExt);
		e.putLong("advSearchMin", advSearchMin);
		e.putLong("advSearchMax", advSearchMax);
		e.putLong("advSearchDays", advSearchDays);
		e.putBoolean("advSearchRecursive", advSearchRecursive);
		e.putBoolean("advSearchCase", advSearchCase);
		if (leftTreeScroll != null) leftScrollY = leftTreeScroll.getScrollY();
		if (rightTreeScroll != null) rightScrollY = rightTreeScroll.getScrollY();
		e.putInt("leftScrollY", Math.max(0, leftScrollY));
		e.putInt("rightScrollY", Math.max(0, rightScrollY));
		e.putString("leftCur", savedCur(leftCur));
		e.putString("rightCur", savedCur(rightCur));
		e.putString("leftOpen", joinPaths(leftOpen));
		e.putString("rightOpen", joinPaths(rightOpen));
		e.putString("bookmarks", joinList(bookmarks));
		e.apply();
	}

	/** only normal folders are remembered (not archives or cloud folders) */
	String savedCur(File f) {
		if (f == null || f instanceof ZipItem || f instanceof MegaItem)
			return "";
		return f.getAbsolutePath();
	}

	String joinPaths(HashSet<String> set) {
		StringBuilder sb = new StringBuilder();
		for (String s : set) {
			sb.append(s);
			sb.append('\n');
		}
		return sb.toString();
	}

	String joinList(ArrayList<String> list) {
		StringBuilder sb = new StringBuilder();
		for (String s : list) {
			sb.append(s);
			sb.append('\n');
		}
		return sb.toString();
	}

	protected void onPause() {
		super.onPause();
		volHandler.removeCallbacks(volPoll);
		saveState();
	}

	/** background threads post here; nothing is posted once the screen is closing or recreated (rotation, theme switch) */
	void uiPost(Runnable r) {
		if (isFinishing() || isDestroyed())
			return;
		runOnUiThread(r);
	}

	protected void onDestroy() {
		try {
			unregisterReceiver(storageReceiver);
		} catch (Exception e) {
		}
		super.onDestroy();
	}

	// ---------- USB OTG / SD card support ----------
	static class VolInfo {
		String label;
		File dir;
		boolean removable;
	}

	BroadcastReceiver storageReceiver = new BroadcastReceiver() {
		public void onReceive(Context c, Intent i) {
			pollVolumes();
		}
	};

	// ---- automatic drive detection (broadcast + 3 second poll, because USB OTG often sends no broadcast) ----
	ArrayList<VolInfo> volCache = new ArrayList<VolInfo>();
	String volSig = "";
	boolean volScanning = false;
	Handler volHandler = new Handler();
	Runnable volPoll = new Runnable() {
		public void run() {
			pollVolumes();
			volHandler.postDelayed(this, 3000);
		}
	};

	String volSignature(ArrayList<VolInfo> l) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < l.size(); i++)
			sb.append(l.get(i).dir.getAbsolutePath()).append('|').append(l.get(i).label).append(';');
		return sb.toString();
	}

	void pollVolumes() {
		if (volScanning)
			return;
		volScanning = true;
		new Thread(new Runnable() {
			public void run() {
				final ArrayList<VolInfo> nv = storageVolumes();
				uiPost(new Runnable() {
					public void run() {
						volScanning = false;
						applyVolumes(nv);
					}
				});
			}
		}).start();
	}

	void applyVolumes(ArrayList<VolInfo> nv) {
		String sig = volSignature(nv);
		if (sig.equals(volSig))
			return;
		ArrayList<VolInfo> old = volCache;
		volCache = nv;
		volSig = sig;
		for (int i = 0; i < old.size(); i++) {
			boolean still = false;
			for (int k = 0; k < nv.size(); k++)
				if (nv.get(k).dir.equals(old.get(i).dir))
					still = true;
			if (!still && old.get(i).removable) {
				dropPath(old.get(i).dir.getAbsolutePath());
				toast("Drive removed: " + old.get(i).label);
			}
		}
		for (int i = 0; i < nv.size(); i++) {
			boolean was = false;
			for (int k = 0; k < old.size(); k++)
				if (old.get(k).dir.equals(nv.get(i).dir))
					was = true;
			if (!was && nv.get(i).removable)
				toast("Drive connected: " + nv.get(i).label);
		}
		listCache.clear();
		if (leftTree != null)
			refresh();
	}

	boolean sameOrUnderPath(File child, File parent) {
		if (child == null || parent == null) return false;
		try {
			String c = child.getCanonicalPath();
			String p = parent.getCanonicalPath();
			return c.equals(p) || c.startsWith(p + File.separator);
		} catch (IOException e) {
			String c = child.getAbsolutePath();
			String p = parent.getAbsolutePath();
			return c.equals(p) || c.startsWith(p + File.separator);
		}
	}

	void dropPath(String path) {
		File internal = Environment.getExternalStorageDirectory();
		File removed = new File(path);
		if (sameOrUnderPath(leftRoot, removed)) {
			leftRoot = internal;
			leftOpen.clear();
			leftOpen.add(internal.getAbsolutePath());
		}
		if (sameOrUnderPath(rightRoot, removed)) {
			rightRoot = internal;
			rightOpen.clear();
			rightOpen.add(internal.getAbsolutePath());
		}
		if (sameOrUnderPath(leftCur, removed))
			leftCur = leftRoot;
		if (sameOrUnderPath(rightCur, removed))
			rightCur = rightRoot;
		if (selected != null && sameOrUnderPath(selected, removed))
			selected = null;
		if (multiMode)
			exitMulti();
	}

	void registerStorageReceiver() {
		try {
			IntentFilter f = new IntentFilter();
			f.addAction(Intent.ACTION_MEDIA_MOUNTED);
			f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
			f.addAction(Intent.ACTION_MEDIA_EJECT);
			f.addAction(Intent.ACTION_MEDIA_REMOVED);
			f.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
			f.addDataScheme("file");
			registerReceiver(storageReceiver, f);
		} catch (Exception e) {
		}
	}

	ArrayList<VolInfo> storageVolumes() {
		ArrayList<VolInfo> out = new ArrayList<VolInfo>();
		HashSet<String> seen = new HashSet<String>();
		VolInfo in = new VolInfo();
		in.label = "Internal storage";
		in.dir = Environment.getExternalStorageDirectory();
		out.add(in);
		seen.add(in.dir.getAbsolutePath());
		try {
			if (Build.VERSION.SDK_INT >= 24) {
				android.os.storage.StorageManager sm = (android.os.storage.StorageManager) getSystemService(Context.STORAGE_SERVICE);
				java.util.List<android.os.storage.StorageVolume> vs = sm.getStorageVolumes();
				for (int k = 0; k < vs.size(); k++) {
					android.os.storage.StorageVolume v = vs.get(k);
					if (v.isPrimary())
						continue;
					String st = v.getState();
					if (!"mounted".equals(st) && !"mounted_ro".equals(st))
						continue;
					File d = null;
					if (Build.VERSION.SDK_INT >= 30)
						d = v.getDirectory();
					else {
						try {
							d = (File) v.getClass().getMethod("getPathFile").invoke(v);
						} catch (Exception e) {
						}
					}
					if (d == null) {
						String uuid = v.getUuid();
						if (uuid != null)
							d = new File("/storage/" + uuid);
					}
					if (d == null)
						continue;
					if (d.list() == null) {
						File alt = new File("/mnt/media_rw/" + d.getName());
						if (alt.list() != null)
							d = alt;
						else
							continue;
					}
					if (!seen.add(d.getAbsolutePath()))
						continue;
					VolInfo vi = new VolInfo();
					String desc = v.getDescription(this);
					vi.label = desc != null && desc.length() > 0 ? desc : d.getName();
					vi.dir = d;
					vi.removable = true;
					out.add(vi);
				}
			}
		} catch (Exception e) {
		}
		// fallback / extra: anything mounted directly under /storage or /mnt/media_rw
		String[] bases = { "/storage", "/mnt/media_rw" };
		for (int bi = 0; bi < bases.length; bi++) {
			try {
				File[] s = new File(bases[bi]).listFiles();
				if (s != null)
					for (int k = 0; k < s.length; k++) {
						File d = s[k];
						String n = d.getName();
						if (n.equals("emulated") || n.equals("self") || !d.isDirectory() || d.list() == null)
							continue;
						boolean dup = false;
						for (int q = 0; q < out.size(); q++)
							if (out.get(q).dir.getName().equals(n))
								dup = true;
						if (dup || !seen.add(d.getAbsolutePath()))
							continue;
						VolInfo vi = new VolInfo();
						vi.label = "USB drive (" + n + ")";
						vi.dir = d;
						vi.removable = true;
						out.add(vi);
					}
			} catch (Exception e) {
			}
		}
		return out;
	}

	void showStorageList() {
		final ArrayList<VolInfo> vols = storageVolumes();
		final ArrayList<String> labels = new ArrayList<String>();
		final ArrayList<String> icons = new ArrayList<String>();
		for (int i = 0; i < vols.size(); i++) {
			VolInfo v = vols.get(i);
			String free = "";
			try {
				android.os.StatFs st = new android.os.StatFs(v.dir.getAbsolutePath());
				free = "   " + gb(st.getAvailableBytes()) + " free of " + gb(st.getTotalBytes());
			} catch (Exception e) {
			}
			labels.add(v.label + free + "\n" + v.dir.getAbsolutePath());
			icons.add(v.removable ? "\uD83D\uDD0C" : "\uD83D\uDCF1");
		}
		labels.add("USB drive not listed?");
		icons.add("\u2139\uFE0F");
		createDialog("Storage", null).setAdapter(menuAdapter(labels, icons), new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface d, int which) {
				if (which >= vols.size()) {
					showMessageDialog("USB drive not listed?",
						"The drive must be mounted by Android first. Connect it through the OTG adapter, wait a few seconds, then open this list again.\n\n"
							+ "Android mounts FAT32 and exFAT drives. If the drive is NTFS-only or has no usable partition, Android will not mount it and no file manager can read it without a special driver.\n\n"
							+ "Also check that \"All files access\" is allowed for this app.");
					return;
				}
				boolean left = selected != null ? selectedLeft : true;
				jumpToRoot(vols.get(which).dir, left);
			}
		}).show();
	}

	void jumpToRoot(File dir, boolean left) {
		if (!dir.isDirectory()) {
			toast("That drive is not available");
			return;
		}
		listCache.clear();
		if (left) {
			leftRoot = dir;
			leftCur = dir;
			leftOpen.clear();
			leftOpen.add(dir.getAbsolutePath());
		} else {
			rightRoot = dir;
			rightCur = dir;
			rightOpen.clear();
			rightOpen.add(dir.getAbsolutePath());
		}
		if (multiMode)
			exitMulti();
		selected = null;
		refresh();
	}

	void makeUi() {
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundColor(colBg);

		TextView bar = new TextView(this);
		bar.setText("myFiles");
		bar.setTextSize(20);
		bar.setTextColor(Color.WHITE);
		bar.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL);
		bar.setGravity(Gravity.CENTER_VERTICAL);
		bar.setPadding(10 * dp, 0, 0, 0);
		bar.setBackgroundColor(Color.BLUE);
		root.addView(bar, new LinearLayout.LayoutParams(-1, 58 * dp));
		root.addView(toolBar(), new LinearLayout.LayoutParams(-1, 70 * dp));
		root.addView(utilBar(), new LinearLayout.LayoutParams(-1, 42 * dp));

		searchBar = (LinearLayout) buildSearchBar();
		root.addView(searchBar, new LinearLayout.LayoutParams(-1, -2));

		selBar = new LinearLayout(this);
		selBar.setOrientation(LinearLayout.HORIZONTAL);
		selBar.setGravity(Gravity.CENTER_VERTICAL);
		selBar.setBackgroundColor(colSelBarBg);
		selBar.setPadding(10 * dp, 2 * dp, 4 * dp, 2 * dp);
		selBar.setVisibility(View.GONE);
		selCount = new TextView(this);
		selCount.setTextSize(14);
		selCount.setTextColor(colSelBarText);
		selBar.addView(selCount, new LinearLayout.LayoutParams(0, -2, 1));
		selBar.addView(selButton("All", new View.OnClickListener() {
			public void onClick(View v) {
				selectAllCur();
			}
		}), new LinearLayout.LayoutParams(-2, 38 * dp));
		selBar.addView(selButton("None", new View.OnClickListener() {
			public void onClick(View v) {
				multi.clear();
				updateSelBar();
				refreshPane(multiLeft);
			}
		}), new LinearLayout.LayoutParams(-2, 38 * dp));
		selBar.addView(selButton("Done", new View.OnClickListener() {
			public void onClick(View v) {
				exitMulti();
				refreshPane(multiLeft);
			}
		}), new LinearLayout.LayoutParams(-2, 38 * dp));
		root.addView(selBar, new LinearLayout.LayoutParams(-1, -2));

		LinearLayout body = new LinearLayout(this);
		body.setOrientation(LinearLayout.HORIZONTAL);

		leftTree = new LinearLayout(this);
		leftTree.setOrientation(LinearLayout.VERTICAL);
		rightTree = new LinearLayout(this);
		rightTree.setOrientation(LinearLayout.VERTICAL);
		leftLines = new TreeLinesOverlay(this);
		rightLines = new TreeLinesOverlay(this);
		leftCrumbs = new LinearLayout(this);
		rightCrumbs = new LinearLayout(this);
		leftCrumbScroll = crumbScroll(leftCrumbs);
		rightCrumbScroll = crumbScroll(rightCrumbs);

		final LinearLayout.LayoutParams leftPanelLp = new LinearLayout.LayoutParams(0, -1, splitWeight);
		final LinearLayout.LayoutParams rightPanelLp = new LinearLayout.LayoutParams(0, -1, 2f - splitWeight);
		bodyRef = body;
		leftLpRef = leftPanelLp;
		rightLpRef = rightPanelLp;
		body.addView(panel(leftCrumbScroll, leftTree, leftLines, true), leftPanelLp);
		body.addView(divider(body, leftPanelLp, rightPanelLp), new LinearLayout.LayoutParams(18 * dp, -1));
		body.addView(panel(rightCrumbScroll, rightTree, rightLines, false), rightPanelLp);
		root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
	
		undoBar = new LinearLayout(this);
		undoBar.setOrientation(LinearLayout.HORIZONTAL);
		undoBar.setGravity(Gravity.CENTER_VERTICAL);
		undoBar.setBackgroundColor(Color.rgb(50, 50, 55));
		undoBar.setPadding(14 * dp, 6 * dp, 6 * dp, 6 * dp);
		undoBar.setVisibility(View.GONE);
		undoText = new TextView(this);
		undoText.setTextColor(Color.WHITE);
		undoText.setTextSize(13);
		undoBar.addView(undoText, new LinearLayout.LayoutParams(0, -2, 1));
		TextView undoBtn = new TextView(this);
		undoBtn.setText("UNDO");
		undoBtn.setTextColor(Color.rgb(120, 190, 255));
		undoBtn.setTextSize(13);
		undoBtn.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
		undoBtn.setPadding(14 * dp, 10 * dp, 14 * dp, 10 * dp);
		undoBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				undoTrash();
			}
		});
		undoBar.addView(undoBtn, new LinearLayout.LayoutParams(-2, -2));
		root.addView(undoBar, new LinearLayout.LayoutParams(-1, -2));

		status = new TextView(this);
		status.setText("Tap name to select/open file. Tap triangle to expand/collapse folders.");
		status.setTextSize(12);
		status.setTextColor(colTextMuted);
		status.setPadding(8 * dp, 4 * dp, 8 * dp, 4 * dp);
		root.addView(status, new LinearLayout.LayoutParams(-1, 38 * dp));
		mainRoot = root;
		setContentView(root);

		root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
			public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
				v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
						insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
				return insets;
			}
		});
	}

	void applySystemInsets(final View v) {
		v.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
			public WindowInsets onApplyWindowInsets(View v2, WindowInsets insets) {
				v2.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
						insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
				return insets;
			}
		});
	}

	View divider(final LinearLayout body, final LinearLayout.LayoutParams leftLp,
			final LinearLayout.LayoutParams rightLp) {
		LinearLayout d = new LinearLayout(this);
		d.setOrientation(LinearLayout.VERTICAL);
		d.setGravity(Gravity.CENTER);
		d.setBackgroundColor(colDivider);
		TextView grip = new TextView(this);
		grip.setText("\u22ee\n\u22ee"); 
		grip.setTextColor(colTextMuted);
		grip.setTextSize(15);
		grip.setGravity(Gravity.CENTER);
		d.addView(grip, new LinearLayout.LayoutParams(-2, -2));

		final float totalWeight = leftLp.weight + rightLp.weight; 
		final float minWeight = totalWeight * 0.15f, maxWeight = totalWeight * 0.85f;
		d.setOnTouchListener(new View.OnTouchListener() {
			float startRawX, startLeftWeight;
			public boolean onTouch(View v, MotionEvent e) {
				switch (e.getActionMasked()) {
					case MotionEvent.ACTION_DOWN :
						startRawX = e.getRawX();
						startLeftWeight = leftLp.weight;
						return true;
					case MotionEvent.ACTION_MOVE :
						int avail = body.getWidth() - v.getWidth();
						if (avail <= 0)
							return true;
						float dx = e.getRawX() - startRawX;
						float newLeft = startLeftWeight + dx / avail * totalWeight;
						if (newLeft < minWeight)
							newLeft = minWeight;
						if (newLeft > maxWeight)
							newLeft = maxWeight;
						leftLp.weight = newLeft;
						rightLp.weight = totalWeight - newLeft;
						body.requestLayout();
						return true;
					case MotionEvent.ACTION_UP :
					case MotionEvent.ACTION_CANCEL :
						splitWeight = leftLp.weight; // remembered across restarts, see saveState()
						return true;
				}
				return false;
			}
		});
		return d;
	}

	HorizontalScrollView crumbScroll(LinearLayout crumbs) {
		crumbs.setOrientation(LinearLayout.HORIZONTAL);
		crumbs.setGravity(Gravity.CENTER_VERTICAL);
		crumbs.setPadding(4 * dp, 4 * dp, 4 * dp, 4 * dp);
		HorizontalScrollView sv = new HorizontalScrollView(this);
		sv.setHorizontalScrollBarEnabled(false);
		sv.setBackgroundColor(colCrumbBg);
		sv.addView(crumbs, new FrameLayout.LayoutParams(-2, -1));
		return sv;
	}

	class Crumb extends View {
		String text;
		boolean first, last;
		android.graphics.Paint fill, stroke, tp;
		float tip;

		Crumb(Context c, String t, boolean first, boolean last) {
			super(c);
			this.text = t;
			this.first = first;
			this.last = last;
			tip = 10 * dp;
			fill = new android.graphics.Paint();
			fill.setAntiAlias(true);
			fill.setStyle(android.graphics.Paint.Style.FILL);
			fill.setColor(last ? colCrumbFillLast : colCrumbFillOther);
			stroke = new android.graphics.Paint();
			stroke.setAntiAlias(true);
			stroke.setStyle(android.graphics.Paint.Style.STROKE);
			stroke.setStrokeWidth(Math.max(1f, 1.2f * dp));
			stroke.setColor(colCrumbStroke);
			tp = new android.graphics.Paint();
			tp.setAntiAlias(true);
			tp.setColor(colCrumbText);
			tp.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 15,
					getResources().getDisplayMetrics()));
			if (last)
				tp.setFakeBoldText(true);
		}

		float textX() {
			return first ? 10 * dp : tip + 6 * dp;
		}

		protected void onMeasure(int wSpec, int hSpec) {
			int w = (int) (textX() + tp.measureText(text) + tip + 6 * dp);
			setMeasuredDimension(w, 32 * dp);
		}

		protected void onDraw(android.graphics.Canvas c) {
			float w = getWidth(), h = getHeight(), o = stroke.getStrokeWidth() / 2f;
			android.graphics.Path p = new android.graphics.Path();
			p.moveTo(o, o);
			p.lineTo(w - tip, o);
			p.lineTo(w - o, h / 2f);
			p.lineTo(w - tip, h - o);
			p.lineTo(o, h - o);
			if (!first)
				p.lineTo(tip, h / 2f);
			p.close();
			c.drawPath(p, fill);
			c.drawPath(p, stroke);
			android.graphics.Paint.FontMetrics fm = tp.getFontMetrics();
			c.drawText(text, textX(), h / 2f - (fm.ascent + fm.descent) / 2f, tp);
		}
	}

	void updateCrumbs(final LinearLayout host, final HorizontalScrollView sv, File root, File cur, final boolean left) {
		host.removeAllViews();
		if (cur instanceof MegaItem)
			root = MegaItem.rootOf(((MegaItem) cur).acc);
		else if (cur instanceof ZipItem && ((ZipItem) cur).mega != null)
			root = MegaItem.rootOf(((ZipItem) cur).mega.acc);
		else if (cur != null && !cur.equals(root) && !underPath(cur, root)) {
			ArrayList<VolInfo> vl = volCache;
			for (int i = 0; i < vl.size(); i++)
				if (vl.get(i).dir.equals(cur) || underPath(cur, vl.get(i).dir))
					root = vl.get(i).dir;
		}
		ArrayList<File> chain = new ArrayList<File>();
		File f = cur;
		while (f != null && !f.equals(root)) {
			chain.add(0, f);
			f = f.getParentFile();
		}
		if (f == null) {
			chain.clear();
		} 
		
		chain.add(0, root);
		for (int i = 0; i < chain.size(); i++) {
			final File dir = chain.get(i);
			String label = (i == 0) ? displayRoot(dir) : dir.getName();
			Crumb cr = new Crumb(this, label, i == 0, i == chain.size() - 1);
			cr.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					HashSet<String> opened = left ? leftOpen : rightOpen;
					File a = dir;
					while (a != null) {
						opened.add(a.getAbsolutePath());
						a = a.getParentFile();
					}
					markSelected(dir, left);
					refreshPane(left);
				}
			});
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
			if (i > 0)
				lp.leftMargin = -(10 * dp) + 2 * dp;
			host.addView(cr, lp);
		}
		sv.post(new Runnable() {
			public void run() {
				sv.fullScroll(View.FOCUS_RIGHT);
			}
		});
	}

	/** takes the highlight off the row that is selected now (without rebuilding the tree) */
	void unhighlightSelected(View keepRow) {
		LinearLayout oldTree = selectedLeft ? leftTree : rightTree;
		if (selected == null || oldTree == null)
			return;
		View oldRow = oldTree.findViewWithTag(selected.getAbsolutePath());
		if (oldRow != null && oldRow != keepRow) {
			if (searching && searchMatches.contains(selected.getAbsolutePath()))
				oldRow.setBackgroundColor(colSearchRow);
			else
				oldRow.setBackgroundColor(colSurface);
		}
	}

	void markSelected(File f, boolean left) {
		selected = f;
		selectedLeft = left;
		File dir = f.isDirectory() ? f : f.getParentFile();
		if (dir == null)
			dir = f;
		if (left)
			leftCur = dir;
		else
			rightCur = dir;
		if (searchBar != null && searchBar.getVisibility() == View.VISIBLE)
			updateSearchScopeLabel();
	}

	View panel(View crumbBar, LinearLayout tree, TreeLinesOverlay lines, final boolean left) {
		LinearLayout p = new LinearLayout(this);
		p.setOrientation(LinearLayout.VERTICAL);

		LinearLayout crumbRow = new LinearLayout(this);
		crumbRow.setOrientation(LinearLayout.HORIZONTAL);
		crumbRow.setGravity(Gravity.CENTER_VERTICAL);
		crumbRow.setBackgroundColor(colCrumbBg);
		crumbRow.addView(crumbBar, new LinearLayout.LayoutParams(0, -1, 1));
		TextView sortBtn = new TextView(this);
		sortBtn.setText("\u21C5"); // up/down arrows glyph, doubles as the sort icon
		sortBtn.setTextSize(17);
		sortBtn.setTextColor(colCrumbText);
		sortBtn.setGravity(Gravity.CENTER);
		sortBtn.setPadding(8 * dp, 0, 8 * dp, 0);
		sortBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				showSortMenu(left);
			}
		});
		sortBtn.setContentDescription("Sort order");
		applyRipple(sortBtn);
		crumbRow.addView(sortBtn, new LinearLayout.LayoutParams(-2, 44 * dp));
		p.addView(crumbRow, new LinearLayout.LayoutParams(-1, 44 * dp));

		FrameLayout stack = new FrameLayout(this);
		stack.addView(tree, new FrameLayout.LayoutParams(-1, -2));
		stack.addView(lines, new FrameLayout.LayoutParams(-1, 0));

		ScrollView scroll = new ScrollView(this);
		scroll.setFillViewport(true);
		scroll.addView(stack);
		if (left)
			leftTreeScroll = scroll;
		else
			rightTreeScroll = scroll;
		p.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
		return p;
	}

	View toolBar() {
		LinearLayout b = new LinearLayout(this);
		b.setOrientation(LinearLayout.HORIZONTAL);
		b.setBackgroundColor(colSurfaceAlt);
		
		tool(b, "\uD83D\uDCCB", "Copy", 1);
		tool(b, "\u2722", "Move", 2);
		tool(b, "\u270F\uFE0F", "Rename", 3);
		tool(b, "\uD83D\uDCC1", "Folder", 4);
		tool(b, "\u274C", "Delete", 5);
		tool(b, "\uD83D\uDD04", "Refresh", 6);
		tool(b, "\u2139\uFE0F", "Info", 7);
		return b;
	}
	
	View utilBar() {
		LinearLayout b = new LinearLayout(this);
		b.setOrientation(LinearLayout.HORIZONTAL);
		b.setGravity(Gravity.CENTER_VERTICAL);
		b.setBackgroundColor(colSurfaceAlt);
		b.setPadding(6 * dp, 0, 6 * dp, 0);

		final TextView hiddenBtn = new TextView(this);
		hiddenBtn.setTextSize(12);
		hiddenBtn.setPadding(8 * dp, 6 * dp, 8 * dp, 6 * dp);
		hiddenBtn.setTextColor(colText);
		hiddenBtn.setText((showHidden ? "\u2611" : "\u2610") + "  Hidden files");
		hiddenBtn.setContentDescription("Toggle showing hidden files");
		hiddenBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				showHidden = !showHidden;
				hiddenBtn.setText((showHidden ? "\u2611" : "\u2610") + "  Hidden files");
				saveState();
				refresh(true, true, false);
			}
		});
		b.addView(hiddenBtn, new LinearLayout.LayoutParams(0, -1, 1));

		TextView storageBtn = new TextView(this);
		storageBtn.setText("\uD83D\uDCBE");
		storageBtn.setTextSize(16);
		storageBtn.setGravity(Gravity.CENTER);
		storageBtn.setTextColor(colText);
		storageBtn.setPadding(10 * dp, 0, 10 * dp, 0);
		storageBtn.setContentDescription("Storage: internal, SD card, USB drive");
		applyRipple(storageBtn);
		storageBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				showStorageList();
			}
		});
		b.addView(storageBtn, new LinearLayout.LayoutParams(-2, -1));

		TextView searchBtn = new TextView(this);
		searchBtn.setText("\uD83D\uDD0D");
		searchBtn.setTextSize(16);
		searchBtn.setGravity(Gravity.CENTER);
		searchBtn.setTextColor(colText);
		searchBtn.setPadding(10 * dp, 0, 10 * dp, 0);
		searchBtn.setContentDescription("Search files and folders");
		applyRipple(searchBtn);
		searchBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				toggleSearchBar();
			}
		});
		b.addView(searchBtn, new LinearLayout.LayoutParams(-2, -1));

		TextView bmBtn = new TextView(this);
		bmBtn.setText("\u2605");
		bmBtn.setTextSize(16);
		bmBtn.setGravity(Gravity.CENTER);
		bmBtn.setTextColor(Color.rgb(210, 150, 20));
		bmBtn.setPadding(10 * dp, 0, 10 * dp, 0);
		bmBtn.setContentDescription("Bookmarks");
		applyRipple(bmBtn);
		bmBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				showBookmarks();
			}
		});
		b.addView(bmBtn, new LinearLayout.LayoutParams(-2, -1));

		TextView trashBtn = new TextView(this);
		trashBtn.setText("\uD83D\uDDD1");
		trashBtn.setTextSize(16);
		trashBtn.setGravity(Gravity.CENTER);
		trashBtn.setTextColor(colText);
		trashBtn.setPadding(10 * dp, 0, 10 * dp, 0);
		trashBtn.setContentDescription("Trash");
		applyRipple(trashBtn);
		trashBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				showTrashMenu();
			}
		});
		b.addView(trashBtn, new LinearLayout.LayoutParams(-2, -1));

		TextView compareBtn = new TextView(this);
		compareBtn.setText("\u21D4");
		compareBtn.setTextSize(18);
		compareBtn.setGravity(Gravity.CENTER);
		compareBtn.setTextColor(colText);
		compareBtn.setPadding(10 * dp, 0, 10 * dp, 0);
		compareBtn.setContentDescription("Compare left and right folders");
		applyRipple(compareBtn);
		compareBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				showFolderCompare();
			}
		});
		b.addView(compareBtn, new LinearLayout.LayoutParams(-2, -1));

		TextView settingsBtn = new TextView(this);
		settingsBtn.setText("\u2699");
		settingsBtn.setTextSize(16);
		settingsBtn.setGravity(Gravity.CENTER);
		settingsBtn.setTextColor(colText);
		settingsBtn.setPadding(10 * dp, 0, 10 * dp, 0);
		settingsBtn.setContentDescription("Settings");
		applyRipple(settingsBtn);
		settingsBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				showSettings();
			}
		});
		b.addView(settingsBtn, new LinearLayout.LayoutParams(-2, -1));
		return b;
	}


	static final int CMP_SAME = 0, CMP_LEFT_ONLY = 1, CMP_RIGHT_ONLY = 2, CMP_LEFT_NEWER = 3,
			CMP_RIGHT_NEWER = 4, CMP_DIFFERENT = 5, CMP_TYPE_CONFLICT = 6;

	static class CompareItem {
		String name;
		File left, right;
		int status;
		CompareItem(String name, File left, File right, int status) {
			this.name = name; this.left = left; this.right = right; this.status = status;
		}
	}

	String compareStatus(int s) {
		if (s == CMP_LEFT_ONLY) return "LEFT ONLY";
		if (s == CMP_RIGHT_ONLY) return "RIGHT ONLY";
		if (s == CMP_LEFT_NEWER) return "LEFT NEWER";
		if (s == CMP_RIGHT_NEWER) return "RIGHT NEWER";
		if (s == CMP_DIFFERENT) return "DIFFERENT";
		if (s == CMP_TYPE_CONFLICT) return "TYPE CONFLICT";
		return "SAME";
	}

	void showFolderCompare() {
		final File l = leftCur, r = rightCur;
		if (l == null || r == null || !l.isDirectory() || !r.isDirectory()) {
			toast("Both panes must be local folders"); return;
		}
		if (l instanceof ZipItem || r instanceof ZipItem || l instanceof MegaItem || r instanceof MegaItem) {
			toast("Compare currently supports local folders only"); return;
		}
		final boolean[] cancel = new boolean[] { false };
		final OperationProgress prog = new OperationProgress(this, "Comparing folders", true, false, dp, new Runnable() {
			public void run() { cancel[0] = true; }
		});
		prog.update("Reading folders...", 0, 0, "");
		new Thread(new Runnable() {
			public void run() {
				final ArrayList<CompareItem> result = new ArrayList<CompareItem>();
				String err = null;
				try {
					TreeMap<String, File> lm = compareMap(l), rm = compareMap(r);
					TreeSet<String> names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
					names.addAll(lm.keySet()); names.addAll(rm.keySet());
					int done = 0, total = names.size();
					for (String name : names) {
						if (cancel[0]) break;
						File a = lm.get(name), b = rm.get(name);
						int st = comparePair(a, b);
						result.add(new CompareItem(name, a, b, st));
						done++;
						if ((done & 15) == 0 || done == total) {
							final int fd = done, ft = total;
							uiPost(new Runnable() { public void run() { prog.update("Comparing...", fd, ft, fd + " / " + ft); } });
						}
					}
				} catch (Exception e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
				final String ferr = err;
				uiPost(new Runnable() { public void run() {
					try { prog.dialog.dismiss(); } catch (Exception e) {}
					if (cancel[0]) { toast("Compare cancelled"); return; }
					if (ferr != null) { showMessageDialog("Compare folders", ferr); return; }
					showCompareResults(l, r, result);
				} });
			}
		}).start();
	}

	TreeMap<String, File> compareMap(File dir) {
		TreeMap<String, File> m = new TreeMap<String, File>(String.CASE_INSENSITIVE_ORDER);
		File[] a = dir.listFiles();
		if (a != null) for (int i = 0; i < a.length; i++) {
			if (!showHidden && a[i].getName().startsWith(".")) continue;
			m.put(a[i].getName(), a[i]);
		}
		return m;
	}

	int comparePair(File a, File b) {
		if (a == null) return CMP_RIGHT_ONLY;
		if (b == null) return CMP_LEFT_ONLY;
		if (a.isDirectory() != b.isDirectory()) return CMP_TYPE_CONFLICT;
		if (a.isDirectory()) {
			long am = a.lastModified(), bm = b.lastModified();
			if (Math.abs(am - bm) <= 2000) return CMP_SAME;
			return am > bm ? CMP_LEFT_NEWER : CMP_RIGHT_NEWER;
		}
		if (a.length() != b.length()) return CMP_DIFFERENT;
		long am = a.lastModified(), bm = b.lastModified();
		if (Math.abs(am - bm) <= 2000) return CMP_SAME;
		return am > bm ? CMP_LEFT_NEWER : CMP_RIGHT_NEWER;
	}

	void showCompareResults(final File l, final File r, final ArrayList<CompareItem> all) {
		int[] c = new int[7];
		for (int i = 0; i < all.size(); i++) c[all.get(i).status]++;
		LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(16*dp, 8*dp, 16*dp, 8*dp);
		TextView summary = new TextView(this); summary.setTextColor(colText); summary.setTextSize(13);
		summary.setText("Left: " + l.getAbsolutePath() + "\nRight: " + r.getAbsolutePath() + "\n\n" +
				"Same " + c[CMP_SAME] + "   Left only " + c[CMP_LEFT_ONLY] + "   Right only " + c[CMP_RIGHT_ONLY] + "\n" +
				"Left newer " + c[CMP_LEFT_NEWER] + "   Right newer " + c[CMP_RIGHT_NEWER] + "   Different " + c[CMP_DIFFERENT] +
				"   Conflicts " + c[CMP_TYPE_CONFLICT]);
		root.addView(summary, new LinearLayout.LayoutParams(-1, -2));
		final CheckBox same = new CheckBox(this); same.setText("Show SAME items"); same.setTextColor(colText); root.addView(same);
		final LinearLayout rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
		ScrollView sv = new ScrollView(this); sv.addView(rows); root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
		final AlertDialog dlg = createDialog("Compare folders", null).setView(root).setPositiveButton("Close", null).create();
		final Runnable rebuild = new Runnable() { public void run() { buildCompareRows(rows, all, same.isChecked(), dlg); } };
		same.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { public void onCheckedChanged(CompoundButton b, boolean v) { rebuild.run(); } });
		rebuild.run(); dlg.show();
	}

	void buildCompareRows(LinearLayout rows, final ArrayList<CompareItem> all, boolean showSame, final AlertDialog dlg) {
		rows.removeAllViews();
		for (int i = 0; i < all.size(); i++) {
			final CompareItem ci = all.get(i);
			if (!showSame && ci.status == CMP_SAME) continue;
			LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(4*dp, 7*dp, 4*dp, 7*dp);
			TextView t = new TextView(this); t.setTextColor(colText); t.setTextSize(13); t.setText(ci.name + "\n" + compareStatus(ci.status)); row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
			if (ci.left != null && ci.status != CMP_SAME) {
				Button b = new Button(this); b.setText("\u2192"); b.setContentDescription("Copy left to right"); b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { compareCopy(ci, true, dlg); } }); row.addView(b, new LinearLayout.LayoutParams(54*dp, -2));
			}
			if (ci.right != null && ci.status != CMP_SAME) {
				Button b = new Button(this); b.setText("\u2190"); b.setContentDescription("Copy right to left"); b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { compareCopy(ci, false, dlg); } }); row.addView(b, new LinearLayout.LayoutParams(54*dp, -2));
			}
			rows.addView(row, new LinearLayout.LayoutParams(-1, -2));
		}
	}

	void compareCopy(final CompareItem ci, final boolean leftToRight, final AlertDialog dlg) {
		final File src = leftToRight ? ci.left : ci.right;
		final File base = leftToRight ? rightCur : leftCur;
		if (src == null || base == null) return;
		final File dst = new File(base, src.getName());
		String direction = leftToRight ? "Left to Right" : "Right to Left";
		String extra = dst.exists() ? "\n\nAn item with this name already exists. The normal conflict dialog will let you Overwrite, Skip, Cancel, or Keep both." : "";
		createDialog("Copy " + direction, "Copy:\n" + src.getName() + "\n\nTo:\n" + base.getAbsolutePath() + extra)
			.setPositiveButton("Copy", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
				try { dlg.dismiss(); } catch (Exception e) {}
				fileOperations.startTransfer(src, dst, false, "Compare copy", "Copied");
			} }).setNegativeButton("Cancel", null).show();
	}

	void showSettings() {
		showingSettings = true;
		View v = settingsView();
		applySystemInsets(v);
		setContentView(v);
	}

	void showMain() {
		showingSettings = false;
		if (previewManager != null) previewManager.onMainShown();
		setContentView(mainRoot);
	}

	View settingsView() {
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundColor(colBg);

		LinearLayout bar = new LinearLayout(this);
		bar.setOrientation(LinearLayout.HORIZONTAL);
		bar.setGravity(Gravity.CENTER_VERTICAL);
		bar.setBackgroundColor(Color.rgb(45, 105, 160));
		TextView back = new TextView(this);
		back.setText("\u2190");
		back.setTextColor(Color.WHITE);
		back.setTextSize(20);
		back.setGravity(Gravity.CENTER);
		back.setPadding(16 * dp, 0, 16 * dp, 0);
		back.setContentDescription("Back");
		back.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				showMain();
			}
		});
		bar.addView(back, new LinearLayout.LayoutParams(-2, -1));
		TextView title = new TextView(this);
		title.setText("Settings");
		title.setTextSize(20);
		title.setTextColor(Color.WHITE);
		title.setGravity(Gravity.CENTER_VERTICAL);
		bar.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
		root.addView(bar, new LinearLayout.LayoutParams(-1, 52 * dp));

		TextView section = new TextView(this);
		section.setText("THEME");
		section.setTextSize(12);
		section.setTextColor(colTextMuted);
		section.setPadding(18 * dp, 18 * dp, 18 * dp, 6 * dp);
		root.addView(section, new LinearLayout.LayoutParams(-1, -2));

		root.addView(themeOptionRow("System default", "Follows your device's Light/Dark setting", THEME_SYSTEM),
				new LinearLayout.LayoutParams(-1, -2));
		root.addView(themeOptionRow("Light", "Always use the light theme", THEME_LIGHT),
				new LinearLayout.LayoutParams(-1, -2));
		root.addView(themeOptionRow("Dark", "Always use the dark theme", THEME_DARK),
				new LinearLayout.LayoutParams(-1, -2));

		TextView section2 = new TextView(this);
		section2.setText("CLOUD STORAGE");
		section2.setTextSize(12);
		section2.setTextColor(colTextMuted);
		section2.setPadding(18 * dp, 18 * dp, 18 * dp, 6 * dp);
		root.addView(section2, new LinearLayout.LayoutParams(-1, -2));
		root.addView(megaToggleRow(), new LinearLayout.LayoutParams(-1, -2));

		TextView section3 = new TextView(this);
		section3.setText("STORAGE");
		section3.setTextSize(12);
		section3.setTextColor(colTextMuted);
		section3.setPadding(18 * dp, 18 * dp, 18 * dp, 6 * dp);
		root.addView(section3, new LinearLayout.LayoutParams(-1, -2));
		root.addView(cacheRow(), new LinearLayout.LayoutParams(-1, -2));
		return root;
	}

	/** "Clear cache" row of the settings page with the current cache size */
	View cacheRow() {
		LinearLayout row = new LinearLayout(this);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(18 * dp, 14 * dp, 18 * dp, 14 * dp);
		row.setBackgroundColor(colSurface);
		applyRipple(row);

		LinearLayout textBox = new LinearLayout(this);
		textBox.setOrientation(LinearLayout.VERTICAL);
		TextView t = new TextView(this);
		t.setText("Clear cache");
		t.setTextSize(15);
		t.setTextColor(colText);
		textBox.addView(t, new LinearLayout.LayoutParams(-2, -2));
		final TextView s = new TextView(this);
		s.setText("Calculating...");
		s.setTextSize(12);
		s.setTextColor(colTextMuted);
		textBox.addView(s, new LinearLayout.LayoutParams(-2, -2));
		row.addView(textBox, new LinearLayout.LayoutParams(0, -2, 1));
		showCacheSize(s);

		row.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				createDialog("Clear cache", "Downloaded MEGA files, opened archives and previews are removed. They are downloaded again when you need them.")
						.setPositiveButton("Clear", new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface d, int w) {
								s.setText("Clearing...");
								new Thread(new Runnable() {
									public void run() {
										long freed = 0;
										try {
											freed = CacheCleaner.clearAll(getCacheDir());
										} catch (Throwable e) {
										}
										final long f = freed;
										uiPost(new Runnable() {
											public void run() {
												DexItem.clearCache();
												listCache.clear();
												refresh(); // MEGA archives that were open close, they download again on the next tap
												showCacheSize(s);
												toast("Cache cleared: " + human(f) + " freed");
											}
										});
									}
								}).start();
							}
						}).setNegativeButton("Cancel", null).show();
			}
		});
		return row;
	}

	void showCacheSize(final TextView s) {
		new Thread(new Runnable() {
			public void run() {
				long n = 0;
				try {
					n = CacheCleaner.size(getCacheDir());
				} catch (Throwable e) {
				}
				final String text = "Cache: " + human(n) + ". Files older than 3 days are removed automatically.";
				uiPost(new Runnable() {
					public void run() {
						s.setText(text);
					}
				});
			}
		}).start();
	}

	View megaToggleRow() {
		LinearLayout row = new LinearLayout(this);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(18 * dp, 14 * dp, 18 * dp, 14 * dp);
		row.setBackgroundColor(colSurface);
		applyRipple(row);

		LinearLayout textBox = new LinearLayout(this);
		textBox.setOrientation(LinearLayout.VERTICAL);
		TextView t = new TextView(this);
		t.setText("Show MEGA cloud storage");
		t.setTextSize(15);
		t.setTextColor(colText);
		textBox.addView(t, new LinearLayout.LayoutParams(-2, -2));
		TextView s = new TextView(this);
		s.setText(showMega ? "MEGA is shown in the main screen" : "MEGA is hidden from the main screen");
		s.setTextSize(12);
		s.setTextColor(colTextMuted);
		textBox.addView(s, new LinearLayout.LayoutParams(-2, -2));
		row.addView(textBox, new LinearLayout.LayoutParams(0, -2, 1));

		TextView box = new TextView(this);
		box.setText(showMega ? "\u2611" : "\u2610");
		box.setTextSize(24);
		box.setTextColor(showMega ? Color.rgb(45, 105, 160) : colTextMuted);
		row.addView(box, new LinearLayout.LayoutParams(-2, -2));

		row.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				showMega = !showMega;
				if (!showMega) {
					// a pane that is inside MEGA goes back to its normal root
					if (leftCur instanceof MegaItem) leftCur = leftRoot;
					if (rightCur instanceof MegaItem) rightCur = rightRoot;
					if (selected instanceof MegaItem) selected = null;
				}
				saveState();
				refresh();
				showSettings(); // redraw the settings page with the new state
			}
		});
		return row;
	}

	View themeOptionRow(String title, String subtitle, final int mode) {
		LinearLayout row = new LinearLayout(this);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(18 * dp, 14 * dp, 18 * dp, 14 * dp);
		row.setBackgroundColor(colSurface);
		applyRipple(row);

		LinearLayout textBox = new LinearLayout(this);
		textBox.setOrientation(LinearLayout.VERTICAL);
		TextView t = new TextView(this);
		t.setText(title);
		t.setTextSize(15);
		t.setTextColor(colText);
		textBox.addView(t, new LinearLayout.LayoutParams(-2, -2));
		TextView s = new TextView(this);
		s.setText(subtitle);
		s.setTextSize(12);
		s.setTextColor(colTextMuted);
		textBox.addView(s, new LinearLayout.LayoutParams(-2, -2));
		row.addView(textBox, new LinearLayout.LayoutParams(0, -2, 1));

		if (mode == themeMode) {
			TextView check = new TextView(this);
			check.setText("\u2713");
			check.setTextSize(18);
			check.setTextColor(Color.rgb(45, 105, 160));
			check.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
			row.addView(check, new LinearLayout.LayoutParams(-2, -2));
		}

		row.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				if (mode == themeMode)
					return;
				themeMode = mode;
				saveState();
				recreate(); 
			}
		});
		return row;
	}

	View buildSearchBar() {
		LinearLayout col = new LinearLayout(this);
		col.setOrientation(LinearLayout.VERTICAL);
		col.setBackgroundColor(colSearchBarBg);
		col.setVisibility(View.GONE);

		searchScopeLabel = new TextView(this);
		searchScopeLabel.setTextSize(11);
		searchScopeLabel.setTextColor(Color.rgb(120, 110, 60));
		searchScopeLabel.setPadding(10 * dp, 4 * dp, 10 * dp, 0);
		searchScopeLabel.setSingleLine(true);
		searchScopeLabel.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
		col.addView(searchScopeLabel, new LinearLayout.LayoutParams(-1, -2));

		LinearLayout row = new LinearLayout(this);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(8 * dp, 4 * dp, 8 * dp, 6 * dp);
		searchBox = new EditText(this);
		searchBox.setHint("Search file/folder name\u2026");
		searchBox.setTextSize(14);
		searchBox.setTextColor(colText);
		searchBox.setHintTextColor(colTextMuted);
		searchBox.setSingleLine(true);
		searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
		searchBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
			public boolean onEditorAction(TextView v, int actionId, KeyEvent ev) {
				runSearch();
				return true;
			}
		});
		row.addView(searchBox, new LinearLayout.LayoutParams(0, -2, 1));
		TextView go = new TextView(this);
		go.setText("Go");
		go.setTextSize(13);
		go.setTextColor(colText);
		go.setPadding(14 * dp, 0, 10 * dp, 0);
		go.setContentDescription("Run search");
		applyRipple(go);
		go.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				runSearch();
			}
		});
		row.addView(go, new LinearLayout.LayoutParams(-2, -2));
		TextView adv = new TextView(this);
		adv.setText("Filter"); adv.setTextSize(12); adv.setTextColor(colText); adv.setPadding(8*dp,0,8*dp,0); applyRipple(adv);
		adv.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ showAdvancedSearchOptions(); }});
		row.addView(adv, new LinearLayout.LayoutParams(-2,-2));
		TextView clear = new TextView(this);
		clear.setText("\u2715");
		clear.setTextSize(15);
		clear.setTextColor(colText);
		clear.setPadding(10 * dp, 0, 4 * dp, 0);
		clear.setContentDescription("Close search");
		applyRipple(clear);
		clear.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				clearSearch();
			}
		});
		row.addView(clear, new LinearLayout.LayoutParams(-2, -2));
		col.addView(row, new LinearLayout.LayoutParams(-1, -2));
		return col;
	}

	void toggleSearchBar() {
		if (searchBar.getVisibility() == View.VISIBLE) {
			clearSearch();
			return;
		}
		searchBar.setVisibility(View.VISIBLE);
		updateSearchScopeLabel();
		searchBox.requestFocus();
	}

	void clearSearch() {
		searchCancelled = true;
		searchThread = null;
		searchGen++;
		searching = false;
		searchMatches.clear();
		searchBox.setText("");
		searchBar.setVisibility(View.GONE);
		status.setText("Tap name to select/open file. Tap triangle to expand/collapse folders.");
		refresh(true, true, false);
	}

	File searchScope() {
		if (selected != null)
			return selected.isDirectory() ? selected : selected.getParentFile();
		return selectedLeft ? leftCur : rightCur;
	}

	void updateSearchScopeLabel() {
		if (searchScopeLabel == null)
			return;
		File scope = searchScope();
		boolean left = selectedLeft;
		String name = scope == null
				? ""
				: (scope.equals(left ? leftRoot : rightRoot) ? displayRoot(scope) : scope.getName());
		searchScopeLabel.setText("Search in " + (left ? "left" : "right") + " pane: " + name + (advSearchExt.length()>0 ? "  *."+advSearchExt : ""));
	}

	void runSearch() {
		final String q = searchBox.getText().toString().trim();
		if (q.length() == 0) {
			clearSearch();
			return;
		}
		final File scope = searchScope();
		if (scope == null || !scope.isDirectory()) {
			toast("Select a folder first");
			return;
		}
		final boolean left = selectedLeft;
		final String scopeName = scope.equals(left ? leftRoot : rightRoot) ? displayRoot(scope) : scope.getName();
		
		if (searchThread != null) {
			searchCancelled = true;
		}
		final String qLower = advSearchCase ? q : q.toLowerCase(Locale.US);
		final int gen = ++searchGen;
		searchCancelled = false;
		searching = true;
		searchMatches.clear();
		status.setText("Searching \"" + scopeName + "\" for \"" + q + "\"...");
		final HashSet<String> newOpen = new HashSet<String>();
		final HashSet<String> matches = new HashSet<String>();
		final int[] scanned = {0};
		searchThread = new Thread(new Runnable() {
			public void run() {
				findMatches(scope, qLower, newOpen, matches, gen, scanned);
				mainHandler.post(new Runnable() {
					public void run() {
						if (gen != searchGen)
							return; // superseded by a newer search or a clear
						searchThread = null;
						(left ? leftOpen : rightOpen).addAll(newOpen);
						(left ? leftOpen : rightOpen).add(scope.getAbsolutePath());
						searchMatches.clear();
						searchMatches.addAll(matches);
						status.setText(matches.size() == 0
								? "No matches for \"" + q + "\" in " + scopeName
								: matches.size() + " match" + (matches.size() == 1 ? "" : "es") + " for \"" + q
										+ "\" in " + scopeName);
						refresh(true, true, false);
					}
				});
			}
		});
		searchThread.setDaemon(true);
		searchThread.start();
		
		mainHandler.postDelayed(new Runnable() {
			public void run() {
				if (gen != searchGen || searchThread == null)
					return;
				status.setText("Searching \"" + scopeName + "\" for \"" + q + "\"... (" + scanned[0] + " checked)");
				mainHandler.postDelayed(this, 400);
			}
		}, 400);
	}

	boolean findMatches(File f, String qLower, HashSet<String> opened, HashSet<String> matches, int gen,
			int[] scanned) {
		if (searchCancelled || gen != searchGen || matches.size() >= 2000) return false;
		scanned[0]++;
		String name = advSearchCase ? f.getName() : f.getName().toLowerCase(Locale.US);
		boolean hit = name.contains(qLower);
		if (hit && advSearchKind == 1 && f.isDirectory()) hit = false;
		if (hit && advSearchKind == 2 && !f.isDirectory()) hit = false;
		if (hit && advSearchExt.length() > 0 && !f.isDirectory()) hit = extOf(f).equals(advSearchExt);
		if (hit && advSearchExt.length() > 0 && f.isDirectory()) hit = false;
		if (hit && !f.isDirectory() && advSearchMin >= 0 && f.length() < advSearchMin) hit = false;
		if (hit && !f.isDirectory() && advSearchMax >= 0 && f.length() > advSearchMax) hit = false;
		if (hit && advSearchAfter > 0 && f.lastModified() > 0 && f.lastModified() < advSearchAfter) hit = false;
		boolean childHit = false;
		if (advSearchRecursive && f.isDirectory() && !(!(f instanceof ZipItem) && isSymlink(f))) {
			File[] c = children(f);
			if (c != null) for (int i=0;i<c.length;i++) {
				if (searchCancelled || gen != searchGen || matches.size() >= 2000) break;
				if (findMatches(c[i], qLower, opened, matches, gen, scanned)) childHit = true;
			}
		}
		if (hit) matches.add(f.getAbsolutePath());
		if ((hit || childHit) && f.getParentFile() != null) opened.add(f.getParentFile().getAbsolutePath());
		if (childHit) opened.add(f.getAbsolutePath());
		return hit || childHit;
	}

	String formatSearchMb(long bytes) {
		double mb = bytes / (1024.0 * 1024.0);
		if (Math.abs(mb - Math.rint(mb)) < 0.000001) return String.valueOf((long)Math.rint(mb));
		String v = String.format(Locale.US, "%.3f", mb);
		while (v.endsWith("0")) v = v.substring(0, v.length()-1);
		if (v.endsWith(".")) v = v.substring(0, v.length()-1);
		return v;
	}

	void showAdvancedSearchOptions() {
		final AlertDialog.Builder builder = createDialog("Advanced search filters", null);
		android.content.Context cx = builder.getContext();
		final LinearLayout box=new LinearLayout(cx); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(16*dp,8*dp,16*dp,4*dp);
		final Spinner kind=new Spinner(cx);
		final String[] kinds=new String[]{"Files and folders","Files only","Folders only"};
		ArrayAdapter<String> kindAdapter=new ArrayAdapter<String>(cx, android.R.layout.simple_spinner_item, kinds) {
			public View getView(int pos, View convert, ViewGroup parent) { View v=super.getView(pos,convert,parent); themeDialogView(v); return v; }
			public View getDropDownView(int pos, View convert, ViewGroup parent) { View v=super.getDropDownView(pos,convert,parent); themeDialogView(v); if(dark)v.setBackgroundColor(0xFF424242); return v; }
		};
		kindAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); kind.setAdapter(kindAdapter); kind.setSelection(advSearchKind); box.addView(kind);
		final EditText ext=new EditText(cx); ext.setHint("Extension, e.g. pdf (optional)"); ext.setSingleLine(true); ext.setText(advSearchExt); box.addView(ext);
		final EditText min=new EditText(cx); min.setHint("Minimum size MB (optional)"); min.setInputType(2|8192); if(advSearchMin>=0) min.setText(formatSearchMb(advSearchMin)); box.addView(min);
		final EditText max=new EditText(cx); max.setHint("Maximum size MB (optional)"); max.setInputType(2|8192); if(advSearchMax>=0) max.setText(formatSearchMb(advSearchMax)); box.addView(max);
		final EditText days=new EditText(cx); days.setHint("Modified within days (optional)"); days.setInputType(2); if(advSearchDays>=0) days.setText(String.valueOf(advSearchDays)); box.addView(days);
		final CheckBox rec=new CheckBox(cx); rec.setText("Search subfolders / archive entries"); rec.setChecked(advSearchRecursive); box.addView(rec);
		final CheckBox cs=new CheckBox(cx); cs.setText("Case sensitive"); cs.setChecked(advSearchCase); box.addView(cs);
		themeDialogView(box);
		builder.setView(box).setPositiveButton("Apply", new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){
			advSearchKind=kind.getSelectedItemPosition(); advSearchExt=ext.getText().toString().trim().toLowerCase(Locale.US); if(advSearchExt.startsWith("."))advSearchExt=advSearchExt.substring(1);
			try{advSearchMin=min.getText().length()==0?-1:(long)(Double.parseDouble(min.getText().toString())*1024*1024);}catch(Exception e){advSearchMin=-1;}
			try{advSearchMax=max.getText().length()==0?-1:(long)(Double.parseDouble(max.getText().toString())*1024*1024);}catch(Exception e){advSearchMax=-1;}
			try{advSearchDays=days.getText().length()==0?-1:Long.parseLong(days.getText().toString()); advSearchAfter=advSearchDays>=0?System.currentTimeMillis()-advSearchDays*86400000L:-1;}catch(Exception e){advSearchDays=-1;advSearchAfter=-1;}
			advSearchRecursive=rec.isChecked(); advSearchCase=cs.isChecked(); saveState(); updateSearchScopeLabel();
		}}).setNeutralButton("Reset", new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){advSearchKind=0;advSearchExt="";advSearchMin=-1;advSearchMax=-1;advSearchAfter=-1;advSearchDays=-1;advSearchRecursive=true;advSearchCase=false;saveState();}}).setNegativeButton("Cancel",null).show();
	}

	boolean isBookmarked(File f) {
		return bookmarks.contains(f.getAbsolutePath());
	}

	void toggleBookmark(File f) {
		String p = f.getAbsolutePath();
		if (bookmarks.contains(p)) {
			bookmarks.remove(p);
			toast("Bookmark removed");
		} else {
			bookmarks.add(p);
			toast("Bookmarked");
		}
		saveState();
	}

	void showBookmarks() {
		if (bookmarks.isEmpty()) {
			toast("No bookmarks yet - long-press a folder and choose Bookmark");
			return;
		}
		final LinearLayout list = new LinearLayout(this);
		list.setOrientation(LinearLayout.VERTICAL);
		ScrollView sv = new ScrollView(this);
		sv.addView(list, new LinearLayout.LayoutParams(-1, -2));
		final AlertDialog dlg = createDialog("Bookmarks", null).setView(sv)
				.setNeutralButton("Remove all", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						showConfirmDialog("Remove all bookmarks", "Remove all " + bookmarks.size() + " bookmarks?",
								"Remove all", "Cancel", new DialogInterface.OnClickListener() {
									public void onClick(DialogInterface d2, int w2) {
										bookmarks.clear();
										saveState();
										refresh(true, true, false); // stars disappear from the tree
										toast("All bookmarks removed");
									}
								});
					}
				})
				.setNegativeButton("Close", null).create();
		rebuildBookmarkRows(list, dlg);
		dlg.show();
	}

	void rebuildBookmarkRows(final LinearLayout list, final AlertDialog dlg) {
		list.removeAllViews();
		if (bookmarks.isEmpty()) {
			TextView empty = new TextView(this);
			empty.setText("No bookmarks left");
			empty.setTextColor(colTextMuted);
			empty.setPadding(24 * dp, 16 * dp, 24 * dp, 16 * dp);
			list.addView(empty);
			return;
		}
		for (int i = 0; i < bookmarks.size(); i++) {
			final String path = bookmarks.get(i) == null ? "" : bookmarks.get(i).trim();
			if (path.length() == 0) {
				bookmarks.remove(i);
				i--;
				saveState();
				continue;
			}
			File f = new File(path);
			String bookmarkName = f.getName();
			if (bookmarkName == null || bookmarkName.trim().length() == 0) bookmarkName = f.getAbsolutePath();
			if (bookmarkName == null || bookmarkName.trim().length() == 0) bookmarkName = path;
			if (bookmarkName == null || bookmarkName.trim().length() == 0) bookmarkName = "(Unnamed bookmark)";
			final String label = bookmarkName;

			LinearLayout row = new LinearLayout(this);
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setPadding(24 * dp, 0, 8 * dp, 0);

			TextView tv = new TextView(this);
			tv.setText(label);
			tv.setTextSize(16);
			tv.setTextColor(colText);
			tv.setSingleLine(true);
			tv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
			tv.setPadding(0, 14 * dp, 0, 14 * dp);
			tv.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					dlg.dismiss();
					jumpToBookmark(path);
				}
			});
			row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1));
			
			TextView del = new TextView(this);
			del.setText("\u274c");
			del.setTextSize(18);
			del.setTextColor(Color.rgb(180, 60, 60));
			del.setGravity(Gravity.CENTER);
			del.setPadding(16 * dp, 10 * dp, 16 * dp, 10 * dp);
			del.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					bookmarks.remove(path);
					saveState();
					rebuildBookmarkRows(list, dlg);
					refresh(true, true, false); // the star of this folder disappears from the tree
				}
			});
			row.addView(del, new LinearLayout.LayoutParams(-2, -1));

			list.addView(row, new LinearLayout.LayoutParams(-1, -2));
			if (i < bookmarks.size() - 1) {
				View div = new View(this);
				div.setBackgroundColor(colDivider);
				list.addView(div, new LinearLayout.LayoutParams(-1, 1));
			}
		}
	}
	
	void jumpToBookmark(String path) {
		File target = new File(path);
		if (!target.isDirectory()) {
			toast("That folder no longer exists");
			return;
		}
		boolean left = selected != null ? selectedLeft : true;
		File root = left ? leftRoot : rightRoot;
		if (!sameOrUnderPath(target, root)) {		
			if (left) {
				leftRoot = target;
				leftOpen.clear();
			} else {
				rightRoot = target;
				rightOpen.clear();
			}
		}
		HashSet<String> opened = left ? leftOpen : rightOpen;
		File a = target;
		while (a != null) {
			opened.add(a.getAbsolutePath());
			a = a.getParentFile();
		}
		markSelected(target, left);
		pendingBookmarkScrollPath = target.getAbsolutePath();
		pendingBookmarkScrollLeft = left;
		refresh();
	}

	void scrollBookmarkIntoView(final boolean left) {
		if (pendingBookmarkScrollPath == null || pendingBookmarkScrollLeft != left) return;
		final LinearLayout tree = left ? leftTree : rightTree;
		final ScrollView scroll = left ? leftTreeScroll : rightTreeScroll;
		if (tree == null || scroll == null) return;
		final String wanted = pendingBookmarkScrollPath;
		View targetRow = null;
		for (int i = 0; i < tree.getChildCount(); i++) {
			View child = tree.getChildAt(i);
			Object tag = child.getTag();
			if (tag != null && wanted.equals(tag.toString())) {
				targetRow = child;
				break;
			}
		}
		if (targetRow == null) return;
		pendingBookmarkScrollPath = null;
		final View row = targetRow;
		scroll.post(new Runnable() {
			public void run() {
				int y = row.getTop() - Math.max(0, (scroll.getHeight() - row.getHeight()) / 2);
				scroll.smoothScrollTo(0, Math.max(0, y));
			}
		});
	}

	File trashDir(File root) {
		return new File(root, TRASH_NAME);
	}

	void showTrashMenu() {
		// the window opens at once; the (possibly slow) count of a big trash runs in the background
		final File lt = trashDir(leftRoot), rt = trashDir(rightRoot);
		final boolean same = leftRoot.equals(rightRoot);
		final AlertDialog dlg = createDialog("Trash", "Counting...")
				.setPositiveButton("Empty Trash", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						emptyTrash();
					}
				}).setNegativeButton("Close", null).show();
		final boolean[] stop = { false };
		dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
			public void onDismiss(DialogInterface d) {
				stop[0] = true;
			}
		});
		new Thread(new Runnable() {
			public void run() {
				long[] t = { 0, 0, 0, 0 }; // files, folders, bytes, last screen update
				File[] dirs = same ? new File[] { lt } : new File[] { lt, rt };
				for (int d = 0; d < dirs.length && !stop[0]; d++) {
					File[] c = dirs[d].isDirectory() ? dirs[d].listFiles() : null;
					if (c != null)
						for (int i = 0; i < c.length && !stop[0]; i++)
							trashWalk(c[i], t, stop, dlg);
				}
				if (stop[0])
					return;
				final String msg = t[0] + t[1] == 0 ? "Trash is empty."
						: (t[0] + t[1]) + " item" + (t[0] + t[1] == 1 ? "" : "s") + ", " + human(t[2]);
				uiPost(new Runnable() {
					public void run() {
						if (!stop[0])
							dlg.setMessage(msg);
					}
				});
			}
		}).start();
	}

	/** like tally(), but can be stopped and shows the running count in the trash window */
	void trashWalk(File f, long[] t, final boolean[] stop, final AlertDialog dlg) {
		if (stop[0])
			return;
		if (f.isDirectory() && !isSymlink(f)) {
			t[1]++;
			File[] c = f.listFiles();
			if (c != null)
				for (int i = 0; i < c.length && !stop[0]; i++)
					trashWalk(c[i], t, stop, dlg);
		} else {
			t[0]++;
			t[2] += Math.max(f.length(), 0L);
		}
		long now = System.currentTimeMillis();
		if (now - t[3] > 300) {
			t[3] = now;
			final String m = "Counting... " + (t[0] + t[1]) + " items, " + human(t[2]);
			uiPost(new Runnable() {
				public void run() {
					if (!stop[0])
						dlg.setMessage(m);
				}
			});
		}
	}

	void trashInfo(File t, long[] out) {
		if (!t.isDirectory())
			return;
		File[] c = t.listFiles();
		if (c == null)
			return;
		for (int i = 0; i < c.length; i++) {
			long[] tot = {0, 0, 0};
			tally(c[i], tot);
			out[0] += tot[0] + tot[1];
			out[1] += tot[2];
		}
	}

	void emptyTrash() {
		final ArrayList<File> targets = new ArrayList<File>();
		File lt = trashDir(leftRoot), rt = trashDir(rightRoot);
		if (lt.isDirectory()) targets.add(lt);
		if (!rt.getAbsolutePath().equals(lt.getAbsolutePath()) && rt.isDirectory()) targets.add(rt);
		if (targets.isEmpty()) { toast("Trash is already empty"); return; }
		showConfirmDialog("Empty Trash",
				"Permanently delete everything in the trash? This cannot be undone.",
				"Delete Forever", "Cancel", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) { fileOperations.emptyTrash(targets); }
				});
	}

	void offerUndo(ArrayList<File[]> batch) {
		lastTrashBatch = batch;
		int count = batch.size();
		undoText.setText("Moved " + count + (count == 1 ? " item" : " items") + " to trash");
		undoBar.setVisibility(View.VISIBLE);
		if (hideUndo != null)
			mainHandler.removeCallbacks(hideUndo);
		hideUndo = new Runnable() {
			public void run() {
				hideUndoBar();
			}
		};
		mainHandler.postDelayed(hideUndo, 6000);
	}

	void hideUndoBar() {
		if (undoBar != null)
			undoBar.setVisibility(View.GONE);
		if (hideUndo != null) {
			mainHandler.removeCallbacks(hideUndo);
			hideUndo = null;
		}
	}

	void undoTrash() {
		hideUndoBar();
		if (lastTrashBatch == null) {
			return;
		}
		int restored = 0;
		for (int i = lastTrashBatch.size() - 1; i >= 0; i--) {
			File[] pair = lastTrashBatch.get(i);
			File orig = pair[0], trashed = pair[1];
			if (trashed == null || !trashed.exists())
				continue;
			orig.getParentFile().mkdirs();
			if (!orig.exists() && trashed.renameTo(orig))
				restored++;
		}
		lastTrashBatch = null;
		refresh();
		toast("Restored " + restored + (restored == 1 ? " item" : " items"));
	}

	void tool(LinearLayout p, String icon, String label, final int a) {
		Button b = new Button(this);
		android.text.SpannableString sp = new android.text.SpannableString(icon + "\n" + label);
		sp.setSpan(new android.text.style.RelativeSizeSpan(1.7f), 0, icon.length(),
				android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		b.setText(sp);
		b.setTextSize(11);
		b.setAllCaps(false);
		b.setGravity(Gravity.CENTER);
		b.setMinWidth(0);
		b.setMinimumWidth(0);
		b.setPadding(0, 9 * dp, 0, 9 * dp); // balanced top/bottom space around the icon+label
		b.setMaxLines(2);
		b.setHorizontallyScrolling(true);
		b.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				action(a);
			}
		});
		p.addView(b, new LinearLayout.LayoutParams(0, -1, 1));
	}

	File validCur(File cur, File root) {
		while (cur != null && (!cur.isDirectory() || (cur instanceof ZipItem && !((ZipItem) cur).stillExists())))
			cur = cur.getParentFile();
		return cur == null ? root : cur;
	}

	boolean isPaneRoot(File f) {
		return f.equals(leftRoot) || f.equals(rightRoot) || isVolumeRoot(f)
				|| (f instanceof MegaItem && ((MegaItem) f).isRootNode());
	}

	void refresh() {
		refresh(true, true, true);
	}

	void refreshPane(boolean left) {
		refresh(left, !left, false);
	}

	void refresh(boolean left, boolean right, boolean invalidate) {
		if (invalidate) {
			listCache.clear();
			DexItem.clearCache();
		}
		if (multiMode) {
			for (int i = multi.size() - 1; i >= 0; i--) {
				File m = multi.get(i);
				boolean ok = m instanceof ZipItem ? ((ZipItem) m).zip.exists() : m.exists();
				if (!ok)
					multi.remove(i);
			}
			updateSelBar();
		}
		leftCur = validCur(leftCur, leftRoot);
		rightCur = validCur(rightCur, rightRoot);
		if (left)
			build(leftTree, leftLines, leftRoot, true);
		if (right)
			build(rightTree, rightLines, rightRoot, false);
		updateCrumbs(leftCrumbs, leftCrumbScroll, leftRoot, leftCur, true);
		updateCrumbs(rightCrumbs, rightCrumbScroll, rightRoot, rightCur, false);
	}

	void prefetch(File f, HashSet<String> opened) {
		if (!(expandable(f) && opened.contains(f.getAbsolutePath())))
			return;
		File[] arr = children(f);
		if (arr == null || arr.length == 0)
			return;
		int limit = Math.min(arr.length, 400);
		for (int i = 0; i < limit; i++)
			prefetch(arr[i], opened);
	}

	void build(final LinearLayout host, final TreeLinesOverlay lines, final File root, final boolean left) {
		final HashSet<String> opened = new HashSet<String>(left ? leftOpen : rightOpen);
		final int gen = left ? ++leftGen : ++rightGen;
		new Thread(new Runnable() {
			public void run() {
				prefetch(root, opened);
				ArrayList<VolInfo> pv = volCache;
				for (int pi = 0; pi < pv.size(); pi++)
					prefetch(pv.get(pi).dir, opened);
				uiPost(new Runnable() {
					public void run() {
					
						if ((left ? leftGen : rightGen) != gen)
							return;
						buildUi(host, lines, root, left);
					}
				});
			}
		}).start();
	}

	void buildUi(LinearLayout host, TreeLinesOverlay lines, File root, boolean left) {
		host.removeAllViews();
		ArrayList<LineSpec> specs = new ArrayList<LineSpec>();
		int[] yCursor = {0};
		addNode(host, specs, yCursor, root, left, 0, true, new ArrayList<Boolean>(), true);
		ArrayList<VolInfo> vols = volCache;
		for (int i = 0; i < vols.size(); i++) {
			File vd = vols.get(i).dir;
			if (vd.equals(root) || underPath(vd, root))
				continue;
			addNode(host, specs, yCursor, vd, left, 0, true, new ArrayList<Boolean>(), true);
		}
		if (showMega) {
			// one entry per logged in account; before the first login a single placeholder entry
			if (MegaClient.accounts.isEmpty())
				addNode(host, specs, yCursor, MegaItem.root(), left, 0, true, new ArrayList<Boolean>(), true);
			for (MegaClient mc : MegaClient.accounts)
				addNode(host, specs, yCursor, MegaItem.rootOf(mc), left, 0, true, new ArrayList<Boolean>(), true);
		}

		ViewGroup.LayoutParams lp = lines.getLayoutParams();
		if (lp == null)
			lp = new FrameLayout.LayoutParams(-1, yCursor[0]);
		else
			lp.height = yCursor[0];
		lines.setLayoutParams(lp);
		lines.setSpecs(specs);
		scrollBookmarkIntoView(left);
		restoreScroll(left);
	}

	void addNode(LinearLayout host, ArrayList<LineSpec> specs, int[] yCursor, final File f, final boolean left,
			final int level, boolean rootNode, ArrayList<Boolean> ancestorLast, boolean isLast) {
		int rowH = rootNode ? 84 * dp : 48 * dp;
		HashSet<String> opened = left ? leftOpen : rightOpen;
		boolean expanded = expandable(f) && opened.contains(f.getAbsolutePath());
		if (f instanceof MegaItem && ((MegaItem) f).isRootNode() && !((MegaItem) f).isReady())
			expanded = false;
		if (expanded && isMegaArchive(f) && megaArchiveLocal((MegaItem) f) == null)
			expanded = false; // the downloaded copy was cleaned up: the next tap on the arrow downloads it again
		File[] arr = null;
		if (expanded) {
			arr = filterHidden(children(f));
			if (arr != null)
				arr = arr.clone();
		}
		
		boolean showStem = expanded;
		specs.add(new LineSpec(level, isLast, showStem, ancestorLast, yCursor[0], rowH));
		host.addView(nodeRow(f, left, level, rootNode, expanded), new LinearLayout.LayoutParams(-1, rowH));
		yCursor[0] += rowH;

		if (!expanded)
			return;
		if (arr == null || arr.length == 0) {
			String msg;
			if (arr == null)
				msg = "Can't read this folder";
			else {
				File[] raw = children(f); // cached; only re-checked to phrase the placeholder correctly
				msg = (raw != null && raw.length > 0) ? "All items hidden" : "Empty folder";
			}
			ArrayList<Boolean> childFlags = new ArrayList<Boolean>(ancestorLast);
			if (!rootNode)
				childFlags.add(Boolean.valueOf(isLast));
			int emptyRowH = 40 * dp;
			specs.add(new LineSpec(level + 1, true, false, childFlags, yCursor[0], emptyRowH));
			host.addView(emptyStateRow(msg, level + 1), new LinearLayout.LayoutParams(-1, emptyRowH));
			yCursor[0] += emptyRowH;
			return;
		}
		Arrays.sort(arr, sortComparator(left));
		int limit = Math.min(arr.length, 400);
		for (int i = 0; i < limit; i++) {
			ArrayList<Boolean> childFlags = new ArrayList<Boolean>(ancestorLast);
			if (!rootNode)
				childFlags.add(Boolean.valueOf(isLast));
			addNode(host, specs, yCursor, arr[i], left, level + 1, false, childFlags, i == limit - 1);
		}
	}

	View emptyStateRow(String msg, int level) {
		LinearLayout row = new LinearLayout(this);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		View spacer = new View(this);
		row.addView(spacer, new LinearLayout.LayoutParams((6 + level * 16) * dp, -1));
		TextView tv = new TextView(this);
		tv.setText(msg);
		tv.setTextColor(colTextMuted);
		tv.setTextSize(12);
		tv.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.ITALIC);
		row.addView(tv, new LinearLayout.LayoutParams(-2, -2));
		return row;
	}

	class ArrowView extends View {
		boolean isDir, expanded;
		android.graphics.Paint fill;
		ArrowView(Context c, boolean isDir, boolean expanded) {
			super(c);
			this.isDir = isDir;
			this.expanded = expanded;
			fill = new android.graphics.Paint();
			fill.setAntiAlias(true);
			fill.setStyle(android.graphics.Paint.Style.FILL);
			fill.setColor(isDir ? Color.rgb(0, 145, 195) : Color.rgb(150, 160, 165));
		}
		
		protected void onDraw(android.graphics.Canvas c) {
			float cy = getHeight() / 2f;
			if (!isDir) {
				c.drawCircle(4 * dp + 3 * dp, cy, 2.3f * dp, fill);
				return;
			}
			
			float x0 = 4 * dp, triW = 7 * dp, halfH = 5 * dp;
			android.graphics.Path p = new android.graphics.Path();
			if (expanded) {
				p.moveTo(x0, cy - halfH * 0.7f);
				p.lineTo(x0 + triW, cy - halfH * 0.7f);
				p.lineTo(x0 + triW / 2f, cy + halfH * 0.7f);
			} else {			
				p.moveTo(x0, cy - halfH);
				p.lineTo(x0, cy + halfH);
				p.lineTo(x0 + triW, cy);
			}
			p.close();
			c.drawPath(p, fill);
		}
	}

	View nodeRow(final File f, final boolean left, final int level, final boolean rootNode, final boolean expanded) {
		final LinearLayout row = new LinearLayout(this);
		row.setTag(f.getAbsolutePath());
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(0, 0, 4 * dp, 0);
		if (rootNode)
			row.setBackgroundColor(colRootRow);
		else if (searching && searchMatches.contains(f.getAbsolutePath()))
			row.setBackgroundColor(colSearchRow);
		else
			row.setBackgroundColor(
					selected != null && left == selectedLeft && selected.equals(f)
							? colSelectedRow
							: colSurface);
		if (!rootNode)
			applyRipple(row); 
		View spacer = new View(this);
		row.addView(spacer, new LinearLayout.LayoutParams((level == 0 ? 0 : (6 + level * 16)) * dp, -1));

		ArrowView exp = new ArrowView(this, expandable(f), expanded);
		exp.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				if (expandable(f))
					toggleFolder(f, left);
			}
		});
		row.addView(exp, new LinearLayout.LayoutParams(16 * dp, -1));

		TextView icon = new TextView(this);
		icon.setGravity(Gravity.CENTER);
		String iconText = rootNode ? (f instanceof MegaItem ? "☁" : isRemovableVol(f) ? "💾" : "📱")
				: (f instanceof DexItem ? dexIcon((DexItem) f)
						: ((ZipItem.isZipLike(f) || isMegaArchive(f)) ? "📦" : (f.isDirectory() ? "📁" : fileTypeIcon(f.getName()))));
		icon.setText(iconText);
		icon.setTextSize(rootNode ? 22 : 18);
		int iconW = iconText.length() == 0 ? 0 : (rootNode ? 34 * dp : 26 * dp);
		row.addView(icon, new LinearLayout.LayoutParams(iconW, -1));

		if (!rootNode && f.isDirectory() && !(f instanceof ZipItem) && isBookmarked(f)) {
			TextView star = new TextView(this);
			star.setText("\u2605");
			star.setTextColor(Color.rgb(210, 150, 20));
			star.setTextSize(11);
			star.setGravity(Gravity.CENTER);
			row.addView(star, new LinearLayout.LayoutParams(16 * dp, -1));
		}

		LinearLayout textBox = new LinearLayout(this);
		textBox.setOrientation(LinearLayout.VERTICAL);
		textBox.setGravity(Gravity.CENTER_VERTICAL);

		TextView name = new TextView(this);
		name.setText(rootNode ? displayRoot(f) : f.getName());
		name.setTextColor(colText);
		name.setTextSize(rootNode ? 14 : 13);
		if (rootNode || f.isDirectory())
			name.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
		name.setSingleLine(true);
		name.setEllipsize(android.text.TextUtils.TruncateAt.END);
		textBox.addView(name, new LinearLayout.LayoutParams(-1, 0, 1));

		TextView sub = new TextView(this);
		if (rootNode && f instanceof MegaItem)
			sub.setText(((MegaItem) f).statusText());
		else if (rootNode)
			sub.setText(f.getAbsolutePath());
		else
			sub.setText(f.isDirectory() ? detail(f).trim()
					: (expanded && isMegaArchive(f) ? "[" + filterHidden(children(f)).length + "]   " + human(f.length()) : human(f.length())));
		sub.setTextColor(colTextMuted);
		sub.setTextSize(rootNode ? 11 : 10);
		sub.setSingleLine(true);
		sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
		textBox.addView(sub, new LinearLayout.LayoutParams(-1, 0, 1));

		View.OnClickListener click = new View.OnClickListener() {
			public void onClick(View v) {
				if (!rootNode) {
					unhighlightSelected(row);
					row.setBackgroundColor(colSelectedRow);
				}
				markSelected(f, left);
				if (f.isDirectory() || isMegaArchive(f))
					toggleFolder(f, left);
				else {
					status.setText(f.getAbsolutePath() + "   " + human(f.length()));
					refresh();
					if (getPreviewManager().isImageFile(f.getName()) || getPreviewManager().isTextFile(f.getName()) || getPreviewManager().isVideoFile(f.getName())
							|| getPreviewManager().isAudioFile(f.getName()) || getPreviewManager().isPdfFile(f.getName()) || getPreviewManager().isCsvFile(f.getName())
							|| getPreviewManager().isApkFile(f.getName()))
						previewFile(f);
				}
			}
		};
		name.setOnClickListener(click);
		sub.setOnClickListener(click);
		textBox.setOnClickListener(click);
		icon.setOnClickListener(click);

		View.OnLongClickListener lc = new View.OnLongClickListener() {
			public boolean onLongClick(View v) {
				v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
				if (!rootNode) {
					unhighlightSelected(row);
					markSelected(f, left);
					row.setBackgroundColor(colSelectedRow);
				}
				if (rootNode) {
					if (f instanceof MegaItem)
						showMegaMenu(((MegaItem) f).acc);
					return true;
				}
				if (multiMode) {
					
					if (left == multiLeft) {
						if (!multi.contains(f))
							multi.add(f);
						updateSelBar();
						refreshPane(left);
						showMultiMenu();
					}
					return true;
				}
				showMenu(f, left);
				return true;
			}
		};
		row.setOnLongClickListener(lc);
		exp.setOnLongClickListener(lc);
		icon.setOnLongClickListener(lc);
		name.setOnLongClickListener(lc);
		sub.setOnLongClickListener(lc);
		textBox.setOnLongClickListener(lc);

		if (rootNode) {
			long tot = 0, free = 0;
			if (f instanceof MegaItem) {
				MegaClient qa = ((MegaItem) f).acc;
				if (qa != null && qa.isReady() && qa.maxBytes > 0 && qa.usedBytes >= 0) {
					tot = qa.maxBytes;
					free = Math.max(0L, qa.maxBytes - qa.usedBytes);
				}
			} else {
				try {
					android.os.StatFs st = new android.os.StatFs(f.getAbsolutePath());
					tot = st.getTotalBytes();
					free = st.getAvailableBytes();
				} catch (Exception e) {
				}
			}
			if (tot > 0) {
				TextView diskTxt = new TextView(this);
				diskTxt.setText("Free " + capText(free) + "/" + capText(tot));
				diskTxt.setTextColor(colTextMuted);
				diskTxt.setTextSize(11);
				diskTxt.setSingleLine(true);
				diskTxt.setOnClickListener(click);
				diskTxt.setOnLongClickListener(lc);
				textBox.addView(diskTxt, new LinearLayout.LayoutParams(-1, 0, 1));
				DiskBar db = new DiskBar(this, (tot - free) / (float) tot);
				db.setOnClickListener(click);
				db.setOnLongClickListener(lc);
				LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, 6 * dp);
				bp.bottomMargin = 8 * dp;
				textBox.addView(db, bp);
			}
		}
		row.addView(textBox, new LinearLayout.LayoutParams(0, -1, 1));
		if (!rootNode) {
			CheckMark cm = new CheckMark(this, inMulti(f, left));
			cm.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					tickToggle(f, left);
				}
			});
			cm.setOnLongClickListener(lc);
			row.addView(cm, new LinearLayout.LayoutParams(40 * dp, -1));
		}
		if (rootNode && f instanceof MegaItem && ((MegaItem) f).acc != null) {
			final MegaClient rowAcc = ((MegaItem) f).acc;
			TextView add = new TextView(this);
			add.setText("+");
			add.setTextSize(22);
			add.setTextColor(colText);
			add.setGravity(Gravity.CENTER);
			add.setPadding(10 * dp, 0, 10 * dp, 0);
			applyRipple(add);
			add.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					showMegaLogin(null, new Runnable() {
						public void run() {
							refresh();
						}
					}, null);
				}
			});
			row.addView(add, new LinearLayout.LayoutParams(-2, -1));
			if (rowAcc.hasSession()) {
				TextView out = new TextView(this);
				out.setText("Log out");
				out.setTextSize(12);
				out.setTextColor(colText);
				out.setGravity(Gravity.CENTER);
				out.setPadding(10 * dp, 0, 10 * dp, 0);
				applyRipple(out);
				out.setOnClickListener(new View.OnClickListener() {
					public void onClick(View v) {
						showMegaLogout(rowAcc);
					}
				});
				row.addView(out, new LinearLayout.LayoutParams(-2, -1));
			}
		}
		return row;
	}
	
	static class LineSpec {
		int level;
		boolean isLast, expanded;
		ArrayList<Boolean> ancestorLast;
		int yTop, rowH;
		LineSpec(int level, boolean isLast, boolean expanded, ArrayList<Boolean> ancestorLast, int yTop, int rowH) {
			this.level = level;
			this.isLast = isLast;
			this.expanded = expanded;
			this.ancestorLast = ancestorLast;
			this.yTop = yTop;
			this.rowH = rowH;
		}
	}

	class TreeLinesOverlay extends View {
		ArrayList<LineSpec> specs = new ArrayList<LineSpec>();
		android.graphics.Paint paint;

		TreeLinesOverlay(Context c) {
			super(c);
			paint = new android.graphics.Paint();
			paint.setColor(colLine);
			paint.setStrokeWidth(Math.max(1.5f, 1.2f * dp));
			paint.setAntiAlias(true);
		}

		void setSpecs(ArrayList<LineSpec> s) {
			specs = s;
			invalidate();
		}

		protected void onDraw(android.graphics.Canvas c) {
			super.onDraw(c);
			for (int k = 0; k < specs.size(); k++) {
				LineSpec sp = specs.get(k);
				float top = sp.yTop, h = sp.rowH, mid = top + h / 2.0f, bottom = top + h;

				if (sp.level == 0) {
							
					if (sp.expanded) {
						float rx = 8 * dp;
						float arrowHalf = android.util.TypedValue.applyDimension(
								android.util.TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics()) * 0.33f
								+ 1.5f * dp;
						c.drawLine(rx, mid + arrowHalf, rx, bottom, paint);
					}
					continue;
				}

				for (int depth = 1; depth < sp.level; depth++) {
					boolean ancestorIsLast = false;
					int index = depth - 1;
					if (index < sp.ancestorLast.size())
						ancestorIsLast = sp.ancestorLast.get(index).booleanValue();
					if (!ancestorIsLast) {
						float x = (depth * 16 - 8) * dp;
						c.drawLine(x, top, x, bottom, paint);
					}
				}

				float x = (sp.level * 16 - 8) * dp;
			
				float stubEnd = (sp.level == 0 ? 4 : (6 + sp.level * 16)) * dp + 4 * dp;
				c.drawLine(x, top, x, mid, paint);
				if (!sp.isLast)
					c.drawLine(x, mid, x, bottom, paint);
				c.drawLine(x, mid, stubEnd, mid, paint);
				c.drawCircle(x, mid, 2.4f * dp, paint);
		
				 if (sp.expanded) {
					float childX = ((sp.level + 1) * 16 - 8) * dp;
					c.drawLine(x, mid, childX, mid, paint);
					c.drawLine(childX, mid, childX, bottom, paint);
				}
			}
		}
	}

	/** puts a pane back at the scroll position it had when the app was closed (once, after the first build) */
	void restoreScroll(final boolean left) {
		final int y = left ? leftScrollY : rightScrollY;
		final ScrollView sc = left ? leftTreeScroll : rightTreeScroll;
		if (left) leftScrollY = -1; else rightScrollY = -1;
		if (y <= 0 || sc == null || pendingBookmarkScrollPath != null)
			return;
		sc.post(new Runnable() {
			public void run() {
				sc.scrollTo(0, y);
			}
		});
	}

	void scrollTreesToTop() {
		final ScrollView l = leftTreeScroll, r = rightTreeScroll;
		if (l != null)
			l.post(new Runnable() {
				public void run() {
					l.scrollTo(0, 0);
				}
			});
		if (r != null)
			r.post(new Runnable() {
				public void run() {
					r.scrollTo(0, 0);
				}
			});
	}

	void expandFirstLevel() {
		expandFirstLevel(leftRoot, leftOpen);
		expandFirstLevel(rightRoot, rightOpen);
	}

	void expandFirstLevel(File root, HashSet<String> opened) {	
		opened.clear();
		opened.add(root.getAbsolutePath());
	}

	/** a zip/apk/jar/tar that lives in MEGA: it is shown like a folder, its content comes from a downloaded copy */
	boolean isMegaArchive(File f) {
		return f instanceof MegaItem && f.isFile() && ZipItem.isZipName(f.getName());
	}

	boolean expandable(File f) {
		return f.isDirectory() || isMegaArchive(f);
	}

	/** the downloaded copy of a MEGA archive (same cache place megaFetch uses), or null when it is not downloaded yet */
	File megaArchiveLocal(MegaItem mi) {
		File out = new File(getCacheDir(), "mega/" + mi.handle + "_" + mi.lastModified() + "/" + mi.getName());
		// a finished download is renamed from .part, so any non-empty file is complete
		return out.exists() && out.length() > 0 ? out : null;
	}

	File[] megaArchiveChildren(MegaItem mi) {
		File local = megaArchiveLocal(mi);
		if (local == null)
			return new File[0];
		ZipItem root = new ZipItem(local, "", true, local.length(), mi.lastModified());
		root.mega = mi;
		File[] kids = root.listFiles();
		String err = ZipItem.lastError;
		if (err != null) {
			ZipItem.lastError = null;
			toast(err);
		}
		return kids == null ? new File[0] : kids;
	}

	void toggleFolder(final File f, final boolean left) {
		HashSet<String> op = left ? leftOpen : rightOpen;
		if (isMegaArchive(f) && !op.contains(f.getAbsolutePath())) {
			// download the archive once, then open it like a folder
			final MegaItem mi = (MegaItem) f;
			megaFetch(mi, new MegaItem.Done() {
				public void done(File local) {
					if (local == null || !local.exists() || local.length() == 0) {
						toast("Cannot open " + mi.getName() + ": the download is empty");
						return;
					}
					ZipItem root = new ZipItem(local, "", true, local.length(), mi.lastModified());
					root.mega = mi;
					ZipItem.ensurePassword(MainActivity.this, root, new Runnable() {
						public void run() {
							listCache.remove(mi.getAbsolutePath());
							toggleFolderNow(f, left);
						}
					});
				}
			});
			return;
		}
		if (f instanceof MegaItem && ((MegaItem) f).isRootNode() && !((MegaItem) f).isReady()) {
			megaConnect(((MegaItem) f).acc, new Runnable() {
				public void run() {
					File target = f;
					// the placeholder row has just become the first account
					if (((MegaItem) f).acc == null && !MegaClient.accounts.isEmpty())
						target = MegaItem.rootOf(MegaClient.accounts.get(MegaClient.accounts.size() - 1));
					(left ? leftOpen : rightOpen).remove(target.getAbsolutePath());
					toggleFolderNow(target, left);
				}
			});
			return;
		}
		if (f instanceof ZipItem && ZipItem.isZipLike(f) && !op.contains(f.getAbsolutePath())) {
			ZipItem.ensurePassword(this, (ZipItem) f, new Runnable() {
				public void run() {
					toggleFolderNow(f, left);
				}
			});
			return;
		}
		toggleFolderNow(f, left);
	}

	void toggleFolderNow(File f, boolean left) {
		HashSet<String> opened = left ? leftOpen : rightOpen;
		String p = f.getAbsolutePath();
		if (opened.contains(p))
			opened.remove(p);
		else {
			collapseSiblings(f, opened); // only one folder per tree level stays open
			opened.add(p);
		}
		markSelected(f, left);
		refreshPane(left);
	}

	/** opening a folder closes the other open folders at the same level (and everything that was open below them) */
	void collapseSiblings(File f, HashSet<String> opened) {
		if (isPaneRoot(f))
			return; // storage roots and MEGA accounts can stay open side by side
		File parent = f.getParentFile();
		if (parent == null)
			return;
		File[] sib = children(parent);
		if (sib == null)
			return;
		String me = f.getAbsolutePath();
		for (int i = 0; i < sib.length; i++) {
			String sp = sib[i].getAbsolutePath();
			if (!sp.equals(me) && opened.contains(sp))
				removeOpenedUnder(opened, sp);
		}
	}

	void removeOpenedUnder(HashSet<String> opened, String base) {
		ArrayList<String> gone = new ArrayList<String>();
		for (String o : opened) {
			if (o.equals(base))
				gone.add(o);
			else if (o.length() > base.length() && o.startsWith(base)
					&& (o.charAt(base.length()) == '/' || o.charAt(base.length()) == '!'))
				gone.add(o);
		}
		opened.removeAll(gone);
	}

	/** 123GB, and 1.8TB once it is more than a terabyte */
	String capText(long bytes) {
		double g = bytes / 1073741824.0;
		if (g >= 1024)
			return String.format(Locale.US, "%.1fTB", g / 1024.0);
		return gb(bytes);
	}

	/** the MEGA usage changes with every upload, copy or delete */
	void refreshMegaQuota() {
		for (MegaClient c : MegaClient.accounts)
			if (c.isReady())
				c.refreshQuota();
	}

	String gb(long bytes) {
		double g = bytes / 1073741824.0;
		return g < 10 ? String.format(Locale.US, "%.1fGB", g) : String.format(Locale.US, "%.0fGB", g);
	}

	class DiskBar extends View {
		float used;
		android.graphics.Paint track, fill;
		DiskBar(Context c, float used) {
			super(c);
			this.used = Math.max(0f, Math.min(1f, used));
			track = new android.graphics.Paint();
			track.setAntiAlias(true);
			track.setColor(Color.rgb(178, 222, 222));
			fill = new android.graphics.Paint();
			fill.setAntiAlias(true);
			fill.setColor(Color.rgb(50, 125, 115));
		}
		protected void onDraw(android.graphics.Canvas c) {
			float w = getWidth(), h = getHeight(), r = h / 2f;
			c.drawRoundRect(new android.graphics.RectF(0, 0, w, h), r, r, track);
			if (used > 0f)
				c.drawRoundRect(new android.graphics.RectF(0, 0, Math.max(h, w * used), h), r, r, fill);
		}
	}

	boolean isRemovableVol(File f) {
		ArrayList<VolInfo> l = volCache;
		for (int i = 0; i < l.size(); i++)
			if (l.get(i).dir.equals(f))
				return l.get(i).removable;
		return false;
	}

	boolean isVolumeRoot(File f) {
		ArrayList<VolInfo> l = volCache;
		for (int i = 0; i < l.size(); i++)
			if (l.get(i).dir.equals(f))
				return true;
		return false;
	}

	String displayRoot(File f) {
		if (f instanceof MegaItem && ((MegaItem) f).isRootNode())
			return ((MegaItem) f).rootTitle();
		String p = f.getAbsolutePath();
		ArrayList<VolInfo> l = volCache;
		for (int i = 0; i < l.size(); i++)
			if (l.get(i).removable && l.get(i).dir.equals(f))
				return l.get(i).label;
		if (p.equals(Environment.getExternalStorageDirectory().getAbsolutePath()))
			return "Internal storage";
		String n = f.getName();
		if (n == null || n.length() == 0)
			return "/";
		return n;
	}

	String detail(File f) {
		if (f.isDirectory()) {
			File[] a = filterHidden(children(f));
			String cnt = a == null ? "" : "   [" + a.length + "]";
			if (ZipItem.isZipRoot(f))
				cnt += "   " + human(((ZipItem) f).zip.length());
			return cnt;
		}
		return "   " + human(f.length());
	}

	String fileTypeIcon(String name) {
		int dot = name.lastIndexOf('.');
		if (dot < 0 || dot == name.length() - 1)
			return "";
		String ext = name.substring(dot + 1).toLowerCase(Locale.US);
		if (ext.matches("jpg|jpeg|png|gif|bmp|webp|heic|svg"))
			return "\uD83D\uDDBC"; // 🖼
		if (ext.matches("mp4|mkv|avi|mov|webm|3gp|m4v"))
			return "\uD83C\uDFAC"; // 🎬
		if (ext.matches("mp3|wav|flac|aac|ogg|m4a|opus"))
			return "\uD83C\uDFB5"; // 🎵
		if (ext.equals("apk"))
			return "\uD83E\uDD16"; // 🤖
		if (ext.matches("zip|rar|7z|tar|gz|bz2|xz|jar"))
			return "\uD83D\uDDDC"; // 🗜
		if (ext.equals("pdf"))
			return "\uD83D\uDCD5"; // 📕
		if (ext.matches("doc|docx|xls|xlsx|ppt|pptx|txt|md|csv"))
			return "\uD83D\uDCC4"; // 📄
		if (ext.matches("java|kt|py|js|ts|c|cpp|h|xml|json|gradle|html|css|sh|rs|go"))
			return "\uD83D\uDCDD"; // 📝
		return "";
	}

	String dexIcon(DexItem d) {
		if (d.isDexRoot())
			return "\uD83E\uDDEC"; // 🧬 - the classes.dex entry itself
		if (d.kind == 0)
			return "\uD83D\uDCC1"; // 📁 - a package, same folder icon as a real directory
		if (d.kind == 1)
			return "\uD83D\uDD37"; // 🔷 - a class
		if (d.kind == 2)
			return "\uD83D\uDD16"; // 🔖 - the "Fields" grouping
		if (d.kind == 3)
			return "\u2699\uFE0F"; // ⚙️ - the "Methods" grouping
		return "\uD83D\uDD39"; // 🔹 - an individual field or method leaf
	}

	String human(long n) {
		if (n < 1024)
			return n + " B";
		if (n < 1048576)
			return String.format(Locale.US, "%.1f KB", n / 1024.0);
		if (n < 1073741824)
			return String.format(Locale.US, "%.1f MB", n / 1048576.0);
		return String.format(Locale.US, "%.1f GB", n / 1073741824.0);
	}

	void up(boolean left) {
		File r = left ? leftRoot : rightRoot;
		if (r.getParentFile() != null) {
			if (left) {
				leftRoot = r.getParentFile();
				leftCur = leftRoot;
				leftOpen.add(leftRoot.getAbsolutePath());
			} else {
				rightRoot = r.getParentFile();
				rightCur = rightRoot;
				rightOpen.add(rightRoot.getAbsolutePath());
			}
			selected = null;
			refreshPane(left);
		}
	}

	boolean inMulti(File f, boolean left) {
		return multiMode && left == multiLeft && multi.contains(f);
	}

	void startMulti(File f, boolean left) {
		multiMode = true;
		multiLeft = left;
		multi.clear();
		if (!isPaneRoot(f))
			multi.add(f);
		updateSelBar();
		refreshPane(left);
	}

	void toggleMulti(File f) {
		selectedLeft = multiLeft;
		if (multi.contains(f))
			multi.remove(f);
		else
			multi.add(f);
		updateSelBar();
		refreshPane(multiLeft);
	}

	boolean underPath(File x, File dir) {
		return x != null && dir != null && !x.equals(dir) && sameOrUnderPath(x, dir);
	}

	boolean hasMultiBelow(File dir) {
		for (int i = 0; i < multi.size(); i++)
			if (underPath(multi.get(i), dir))
				return true;
		return false;
	}

	// Folder cycle: 1) folder ticked  2) folder unticked, children ticked (only when the folder is expanded)  3) all unticked
	void tickToggle(File f, boolean left) {
		if (isPaneRoot(f))
			return;
		if (!multiMode) {
			multiMode = true;
			multiLeft = left;
			multi.clear();
		} else if (left != multiLeft) {
			boolean old = multiLeft;
			multi.clear();
			multiMode = true;
			multiLeft = left;
			refreshPane(old);
		}
		boolean isDir = f.isDirectory(); // zip roots and zip entries count as folders
		if (!isDir) {
			if (multi.contains(f))
				multi.remove(f);
			else
				multi.add(f);
		} else if (multi.contains(f)) {
			multi.remove(f);
			// the children are ticked only while the folder is expanded; a collapsed folder is simply unticked
			boolean expanded = (left ? leftOpen : rightOpen).contains(f.getAbsolutePath());
			File[] c = expanded ? filterHidden(children(f)) : null;
			if (c != null)
				for (int i = 0; i < c.length; i++)
					if (!multi.contains(c[i]))
						multi.add(c[i]);
		} else if (hasMultiBelow(f)) {
			for (int i = multi.size() - 1; i >= 0; i--)
				if (underPath(multi.get(i), f))
					multi.remove(i);
		} else {
			multi.add(f);
		}
		if (multi.isEmpty())
			multiMode = false;
		updateSelBar();
		refreshPane(left);
	}

	void exitMulti() {
		multiMode = false;
		multi.clear();
		updateSelBar();
	}
	
	void selectAllCur() {
		File cur = multiLeft ? leftCur : rightCur;
		File[] c = filterHidden(children(cur));
		if (c != null)
			for (int i = 0; i < c.length; i++) {
				if (!multi.contains(c[i]))
					multi.add(c[i]);
			}
		updateSelBar();
		refreshPane(multiLeft);
	}

	void updateSelBar() {
		if (selBar == null)
			return;
		selBar.setVisibility(View.GONE);
		selCount.setText(multi.size() + (multi.size() == 1 ? " item selected" : " items selected"));
	}

	Button selButton(String label, View.OnClickListener l) {
		Button b = new Button(this);
		b.setText(label);
		b.setAllCaps(false);
		b.setTextSize(12);
		b.setMinWidth(0);
		b.setMinimumWidth(0);
		b.setPadding(14 * dp, 0, 14 * dp, 0);
		b.setOnClickListener(l);
		return b;
	}

	class CheckMark extends View {
		boolean on;
		android.graphics.Paint tick;
		CheckMark(Context c, boolean on) {
			super(c);
			this.on = on;
			tick = new android.graphics.Paint();
			tick.setAntiAlias(true);
			tick.setStyle(android.graphics.Paint.Style.STROKE);
			tick.setStrokeCap(android.graphics.Paint.Cap.ROUND);
			tick.setStrokeJoin(android.graphics.Paint.Join.ROUND);
			tick.setStrokeWidth((on ? 5.5f : 5f) * dp);
			tick.setColor(on ? Color.rgb(215, 125, 30) : Color.argb(90, 150, 165, 175));
		}

		protected void onDraw(android.graphics.Canvas c) {
			float sz = 20 * dp, cx = getWidth() / 2f, cy = getHeight() / 2f, l = cx - sz / 2f;
			android.graphics.Path p = new android.graphics.Path();
			p.moveTo(l + 0.08f * sz, cy + 0.02f * sz);
			p.lineTo(l + 0.38f * sz, cy + 0.32f * sz);
			p.lineTo(l + 0.92f * sz, cy - 0.30f * sz);
			c.drawPath(p, tick);
		}
	}

	public void onBackPressed() {
		if (previewManager != null && previewManager.isShowingPreview() && previewManager.handleBack())
			return;
		if (showingSettings || (previewManager != null && previewManager.isShowingPreview())) {
			showMain();
			return;
		}
		if (multiMode) {
			boolean side = multiLeft;
			exitMulti();
			refreshPane(side);
			return;
		}
		super.onBackPressed();
	}

	ArrayList<File> multiItems() {
		ArrayList<File> out = new ArrayList<File>();
		for (int i = 0; i < multi.size(); i++) {
			File m = multi.get(i);
			if (ZipItem.isZipRoot(m))
				m = ((ZipItem) m).zip;
			if (isPaneRoot(m) || out.contains(m))
				continue;
			out.add(m);
		}
		return out;
	}

	void multiAction(int a) {
		if (multi.isEmpty()) {
			toast("Tap items in the tree to select them");
			return;
		}
		if (a == 1)
			transferMulti(false);
		else if (a == 2)
			transferMulti(true);
		else if (a == 3)
			toast("Rename works on one item. Press Done first.");
		else if (a == 5)
			delConfirmMulti();
		else if (a == 7)
			infoMulti();
		else if (a == 8)
			duplicateMulti();
		else if (a == 9)
			ZipItem.zipMulti(this);
		else if (a == 13)
			shareMulti();
		else if (a == 28)
			batchRename();
		else
			toast("Not available for several items");
	}

	/** copy or move into a MEGA folder: local items are uploaded, MEGA items of the same account are copied/moved inside
	 * MEGA, MEGA items of another account are streamed over (download, decrypt, encrypt, upload) without a temp file */
	void uploadToMega(ArrayList<File> srcs, MegaItem dst, boolean move) {
		if (dst.isRootNode()) {
			toast("Open a MEGA folder (for example Cloud Drive) first");
			return;
		}
		if (!dst.isReady()) {
			toast("Connect to MEGA first");
			return;
		}
		ArrayList<File> up = new ArrayList<File>(), same = new ArrayList<File>();
		for (int i = 0; i < srcs.size(); i++) {
			File s = srcs.get(i);
			if (s instanceof MegaItem) {
				MegaItem ms = (MegaItem) s;
				if (ms.acc == dst.acc)
					same.add(s);
				else if (ms.isReady())
					up.add(s);
				else {
					toast("Connect to the other MEGA account first");
					return;
				}
			} else if (!(s instanceof ZipItem))
				up.add(s);
		}
		if (up.isEmpty() && same.isEmpty()) {
			toast("Items inside a zip cannot be sent to MEGA directly. Extract them first");
			return;
		}
		if (!same.isEmpty())
			megaCopyMove(same, dst, move);
		if (!up.isEmpty())
			fileOperations.startUploadToMega(up, dst, move);
	}

	void transferMulti(boolean move) {
		ArrayList<File> srcs = multiItems();
		if (srcs.isEmpty()) {
			toast("Nothing to " + (move ? "move" : "copy"));
			return;
		}
		
		File dstDir = multiLeft ? rightCur : leftCur;
		if (dstDir instanceof MegaItem) {
			uploadToMega(srcs, (MegaItem) dstDir, move);
			return;
		}
		if (hasMega(srcs) && dstDir instanceof ZipItem) {
			toast("Copy MEGA files into a normal folder first");
			return;
		}
		if (dstDir instanceof ZipItem) {
			ZipItem dz = (ZipItem) dstDir;
			ArrayList<File> ok = new ArrayList<File>();
			for (int i = 0; i < srcs.size(); i++) {
				File s = srcs.get(i);
				if (s.getAbsolutePath().equals(dz.zip.getAbsolutePath()))
					continue;
				if (move && s instanceof ZipItem)
					continue;
				ok.add(s);
			}
			if (ok.isEmpty()) {
				toast("Nothing can be added to this zip");
				return;
			}
			fileOperations.startTransferToZip(ok, move, move ? "Moving into zip" : "Adding to zip", move ? "Moved" : "Copied", dz);
			return;
		}
		ArrayList<File> s2 = new ArrayList<File>(), d2 = new ArrayList<File>();
		int renamed = 0, bad = 0;
		for (int i = 0; i < srcs.size(); i++) {
			File s = srcs.get(i);
			File dst = new File(dstDir, s.getName());
			String sp = s.getAbsolutePath(), dp2 = dst.getAbsolutePath();
			if (dp2.equals(sp) || (s.isDirectory() && dp2.startsWith(sp + File.separator))) {
				bad++;
				continue;
			}
			
			if (move && !(s instanceof ZipItem) && !(s instanceof MegaItem) && !dst.exists() && s.renameTo(dst)) {
				renamed++;
				continue;
			}
			s2.add(s);
			d2.add(dst);
		}
		if (s2.isEmpty()) {
			exitMulti();
			refresh();
			toast(renamed > 0
					? "Moved " + renamed + (renamed == 1 ? " item" : " items")
					: "Nothing to " + (move ? "move" : "copy") + " (same location)");
			return;
		}
		fileOperations.startTransfer(s2, d2, move, move ? "Moving" : "Copying", move ? "Moved" : "Copied", renamed);
	}

	void delConfirmMulti() {
		final ArrayList<File> items = multiItems();
		if (items.isEmpty()) {
			toast("Nothing to delete");
			return;
		}
		final ArrayList<ZipItem> zipEntries = new ArrayList<ZipItem>();
		final ArrayList<File> normal = new ArrayList<File>();
		final ArrayList<File> mega = new ArrayList<File>();
		for (int i = 0; i < items.size(); i++) {
			File f = items.get(i);
			if (f instanceof MegaItem) {
				if (!((MegaItem) f).isSystemNode())
					mega.add(f);
			} else if (f instanceof ZipItem)
				zipEntries.add((ZipItem) f);
			else
				normal.add(f);
		}
		if (zipEntries.isEmpty() && normal.isEmpty() && mega.isEmpty()) {
			toast("These MEGA folders cannot be deleted");
			return;
		}
		boolean allPerm = !mega.isEmpty();
		for (int i = 0; i < mega.size(); i++) {
			MegaItem mm = (MegaItem) mega.get(i);
			if (!mm.acc.inRubbish(mm.handle))
				allPerm = false;
		}
		String msg = "Delete " + items.size() + (items.size() == 1 ? " item?" : " items?");
		if (!mega.isEmpty())
			msg += allPerm ? "\n\nItems in the MEGA Rubbish Bin are deleted permanently." : "\n\nMEGA items go to the MEGA Rubbish Bin.";
		createDialog("Delete", msg)
				.setPositiveButton("Delete", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						if (!mega.isEmpty())
							megaDelete(mega);
						if (zipEntries.isEmpty() && normal.isEmpty()) {
							selected = null;
							return;
						}
						if (!zipEntries.isEmpty() && !normal.isEmpty()) {
							exitMulti();
							ZipItem.deleteZipEntriesThenFiles(
								MainActivity.this,
								zipEntries,
								normal
							);
							return;
						}
						if (!zipEntries.isEmpty()) {
							exitMulti();
							selected = null;
							ZipItem.zipDeleteMany(
								MainActivity.this,
								zipEntries,
								"Deleted"
							);
							return;
						}
						selected = null;
						fileOperations.deleteToTrash(normal);
					}
				}).setNegativeButton("Cancel", null).show();
	}

	void tally(File f, long[] t) {
		boolean link = !(f instanceof ZipItem) && !(f instanceof MegaItem) && isSymlink(f);
		if (f.isDirectory() && !link) {
			t[1]++;
			File[] c = f.listFiles();
			if (c != null)
				for (int i = 0; i < c.length; i++)
					tally(c[i], t);
		} else {
			t[0]++;
			t[2] += Math.max(f.length(), 0L);
		}
	}

	void infoMulti() {
		final ArrayList<File> items = multiItems();
		final AlertDialog wait = busyDialog("Info", "Calculating...");
		new Thread(new Runnable() {
			public void run() {
				final long[] t = new long[3]; // files, folders, bytes
				for (int i = 0; i < items.size(); i++)
					tally(items.get(i), t);
				uiPost(new Runnable() {
					public void run() {
						try {
							wait.dismiss();
						} catch (Exception e) {
						}
						createDialog(items.size() + " items selected", "Files: " + t[0] + "\nFolders: " + t[1] + "\nTotal size: " + human(t[2]))
								.setPositiveButton("OK", null).show();
					}
				});
			}
		}).start();
	}

	void duplicateMulti() {
		ArrayList<File> items = multiItems();
		ArrayList<File> s2 = new ArrayList<File>(), d2 = new ArrayList<File>(), mega = new ArrayList<File>();
		for (int i = 0; i < items.size(); i++) {
			File s = items.get(i);
			if (s instanceof MegaItem) {
				if (!((MegaItem) s).isSystemNode())
					mega.add(s);
				continue;
			}
			if (s instanceof ZipItem)
				continue;
			s2.add(s);
			d2.add(uniqueSibling(s, "copy", null));
		}
		if (!mega.isEmpty())
			megaDuplicate(mega);
		if (s2.isEmpty()) {
			if (mega.isEmpty())
				toast("Nothing to duplicate");
			return;
		}
		fileOperations.startTransfer(s2, d2, false, "Duplicating", "Duplicated", 0);
	}

	File uniqueFile(File dir, String base, String ext) {
		File out = new File(dir, base + ext);
		int i = 2;
		while (out.exists()) {
			out = new File(dir, base + " " + i + ext);
			i++;
		}
		return out;
	}

	void shareMulti() {
		ArrayList<File> items = multiItems();
		final ArrayList<File> files = new ArrayList<File>();
		final ArrayList<MegaItem> mega = new ArrayList<MegaItem>();
		for (int i = 0; i < items.size(); i++) {
			File s = items.get(i);
			if (s instanceof MegaItem) {
				if (s.isFile())
					mega.add((MegaItem) s);
				continue;
			}
			if (s instanceof ZipItem || !s.isFile())
				continue;
			files.add(s);
		}
		if (mega.isEmpty()) {
			shareFilesNow(files);
			return;
		}
		// MEGA files are downloaded into the cache first
		final AlertDialog wait = busyDialog("Share", "Downloading from MEGA...");
		new Thread(new Runnable() {
			public void run() {
				String err = null;
				for (int i = 0; i < mega.size() && err == null; i++) {
					MegaItem mi = mega.get(i);
					File dir = new File(getCacheDir(), "mega/" + mi.handle + "_" + mi.lastModified());
					File out = new File(dir, mi.getName());
					try {
						if (!(out.exists() && out.length() == mi.length())) {
							dir.mkdirs();
							File tmp = new File(dir, mi.getName() + ".part");
							InputStream in = mi.openStream();
							OutputStream os = new FileOutputStream(tmp);
							try {
								byte[] b = new byte[65536];
								int n;
								while ((n = in.read(b)) > 0)
									os.write(b, 0, n);
							} finally {
								os.close();
								in.close();
							}
							if (!tmp.renameTo(out))
								throw new IOException("Cannot save the downloaded file");
						}
						files.add(out);
					} catch (Exception e) {
						err = e.getMessage() == null ? e.toString() : e.getMessage();
					}
				}
				final String m = err;
				uiPost(new Runnable() {
					public void run() {
						try {
							wait.dismiss();
						} catch (Exception e) {
						}
						if (m != null)
							toast("MEGA: " + m);
						else
							shareFilesNow(files);
					}
				});
			}
		}).start();
	}

	void shareFilesNow(ArrayList<File> items) {
		ArrayList<Uri> uris = new ArrayList<Uri>();
		for (int i = 0; i < items.size(); i++) {
			File s = items.get(i);
			uris.add(new Uri.Builder().scheme("content").authority(FileShareProvider.AUTH).path(s.getAbsolutePath())
					.build());
		}
		if (uris.isEmpty()) {
			toast("Only files can be shared (not folders)");
			return;
		}
		Intent it = new Intent(Intent.ACTION_SEND_MULTIPLE);
		it.setType("*/*");
		it.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
		it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		ClipData cd = ClipData.newRawUri("files", uris.get(0));
		for (int i = 1; i < uris.size(); i++)
			cd.addItem(new ClipData.Item(uris.get(i)));
		it.setClipData(cd);
		try {
			startActivity(Intent.createChooser(it, "Share " + uris.size() + (uris.size() == 1 ? " file" : " files")));
		} catch (ActivityNotFoundException e) {
			toast("No app found to share files");
		} catch (Exception e) {
			toast("Cannot share: " + e.getMessage());
		}
	}

	void action(int a) {
		if (a == 6) {
			if (multiMode || !multi.isEmpty()) {
				multiMode = false;
				multi.clear();
				updateSelBar();
			}
			if (leftLpRef != null && rightLpRef != null) {
				leftLpRef.weight = 1f;
				rightLpRef.weight = 1f;
				splitWeight = 1f;
				bodyRef.requestLayout();
			}
			// both panels go back to the Internal storage tree, with only its first level open and the top in view
			File internal = Environment.getExternalStorageDirectory();
			leftRoot = internal;
			rightRoot = internal;
			expandFirstLevel();
			leftCur = leftRoot;
			rightCur = rightRoot;
			selected = null;
			pendingBookmarkScrollPath = null;
			refresh(true, true, false);
			scrollTreesToTop();
			return;
		}
		if (a == 4) {
			File base = selectedLeft ? leftCur : rightCur;
			if (base instanceof ZipItem) {
				toast("Zip contents are read-only");
				return;
			}
			ask("New folder", "Folder name", 4);
			return;
		}
		if (a == 40) {
			File base = selectedLeft ? leftCur : rightCur;
			if (base instanceof ZipItem) {
				toast("Zip contents are read-only");
				return;
			}
			if (base instanceof MegaItem) {
				megaNewText((MegaItem) base);
				return;
			}
			File nf = uniqueFile(base, "untitled", ".txt");
			boolean ok = false;
			try {
				ok = nf.createNewFile();
			} catch (IOException e) {
			}
			toast(ok ? "Created " + nf.getName() : "Create failed");
			if (ok) {
				HashSet<String> op = selectedLeft ? leftOpen : rightOpen;
				op.add(base.getAbsolutePath());
				markSelected(nf, selectedLeft);
			}
			refresh();
			return;
		}
		if (a == 20) {
			if (selected != null)
				startMulti(selected, selectedLeft);
			return;
		}
		if (a == 24) {
			if (selected != null) {
				toggleBookmark(selected);
				// Rebuild the affected pane so the bookmark star is added/removed immediately.
				refreshPane(selectedLeft);
			}
			return;
		}
		if (a == 25) { // bookmark / un-bookmark all selected folders
			ArrayList<File> fl = multiBookmarkFolders();
			boolean allMarked = !fl.isEmpty();
			for (int i = 0; i < fl.size(); i++)
				if (!isBookmarked(fl.get(i)))
					allMarked = false;
			for (int i = 0; i < fl.size(); i++) {
				String path = fl.get(i).getAbsolutePath();
				if (allMarked)
					bookmarks.remove(path);
				else if (!bookmarks.contains(path))
					bookmarks.add(path);
			}
			saveState();
			toast(allMarked ? "Bookmarks removed" : "Bookmarked " + fl.size() + (fl.size() == 1 ? " folder" : " folders"));
			refresh(true, true, false);
			return;
		}
		if (multiMode) {
			multiAction(a);
			return;
		}
		if (selected == null) {
			if (a == 7) { showAbout(); return; }
			toast("Select a file or folder first");
			return;
		}
		if (selected instanceof MegaItem) {
			megaAction(a);
			return;
		}
		if (a == 10) {
			if (ZipItem.isZipRoot(selected))
				ZipItem.extractRoot(this);
			return;
		}
		if (a == 12) {
			openItem(selected, true);
			return;
		}
		if (a == 11) {
			// Open = same as a short tap: built-in previewer if there is one, otherwise the app list
			if (selected.isDirectory())
				openItem(selected, false);
			else
				previewFile(selected);
			return;
		}
		if (a == 13) {
			shareItem(selected);
			return;
		}
		if (a == 26) { showChecksums(selected); return; }
		if (a == 27) { showStorageAnalyzer(selected); return; }
		if (a == 29) { showDuplicateFinder(selected); return; }
		if (a == 30) { testArchive(selected); return; }
		if (selected instanceof ZipItem) {
			ZipItem zi = (ZipItem) selected;
			if (zi.isRoot()) {			
				markSelected(zi.zip, selectedLeft);
			} else {
				ZipItem.zipEntryAction(this, a);
				return;
			}
		}
		if (a == 1)
			transfer(false);
		if (a == 2)
			transfer(true);
		if ((a == 3 || a == 5 || a == 8 || a == 9) && isPaneRoot(selected)) {
			toast("Select a file or folder inside the tree");
			return;
		}
		if (a == 3)
			ask("Rename", "New name", 3);
		if (a == 5)
			delConfirm();
		if (a == 7)
			info();
		if (a == 8)
			duplicate();
		if (a == 9)
			ZipItem.zip(this);
	}

	void showAbout() {
		String version = "1.0 RC1";
		try {
			PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
			if (pi.versionName != null && pi.versionName.length() > 0) version = pi.versionName;
		} catch (Exception e) { }
		createDialog("myFiles", null)
			.setMessage("Version " + version)
			.setPositiveButton("OK", null)
			.show();
	}

	File[] children(File f) {
		if (!f.isDirectory() && !isMegaArchive(f))
			return null;
		String key = f.getAbsolutePath();
		File[] cached = listCache.get(key);
		if (cached != null)
			return cached;
		File[] a = isMegaArchive(f) ? megaArchiveChildren((MegaItem) f) : f.listFiles();
		String zerr = ZipItem.lastError;
		if (zerr != null && f instanceof ZipItem) {
			ZipItem.lastError = null;
			toast(zerr);
		}
		if (a != null && !(f instanceof ZipItem) && !isMegaArchive(f)) {
			for (int i = 0; i < a.length; i++) {
				// MEGA files are not on the phone, so an archive in MEGA stays a plain file (open/copy downloads it)
				if (a[i].isFile() && !(a[i] instanceof MegaItem) && ZipItem.isZipName(a[i].getName()))
					a[i] = new ZipItem(a[i], "", true, a[i].length(), a[i].lastModified());
			}
		}
			
		if (a != null)
			listCache.put(key, a);
		return a;
	}

	File[] filterHidden(File[] arr) {
		if (arr == null || showHidden)
			return arr;
		int n = 0;
		for (int i = 0; i < arr.length; i++)
			if (!arr[i].getName().startsWith("."))
				n++;
		if (n == arr.length)
			return arr;
		File[] out = new File[n];
		int j = 0;
		for (int i = 0; i < arr.length; i++)
			if (!arr[i].getName().startsWith("."))
				out[j++] = arr[i];
		return out;
	}

	/** lower-case file extension without the dot ("" for none) */
	static String extOf(File f) {
		if (f.isDirectory())
			return "";
		String n = f.getName();
		int dot = n.lastIndexOf('.');
		return dot < 0 || dot == n.length() - 1 ? "" : n.substring(dot + 1).toLowerCase(Locale.US);
	}

	Comparator<File> sortComparator(final boolean left) {
		final int mode = left ? leftSort : rightSort;
		final boolean rev = left ? leftSortRev : rightSortRev;
		return new Comparator<File>() {
			public int compare(File a, File b) {
				if (a.isDirectory() != b.isDirectory())
					return a.isDirectory() ? -1 : 1;
				int c;
				if (mode == SORT_SIZE)
					c = Long.valueOf(a.length()).compareTo(Long.valueOf(b.length()));
				else if (mode == SORT_DATE)
					c = Long.valueOf(a.lastModified()).compareTo(Long.valueOf(b.lastModified()));
				else if (mode == SORT_TYPE)
					c = extOf(a).compareTo(extOf(b));
				else
					c = a.getName().compareToIgnoreCase(b.getName());
				if (c == 0)
					c = a.getName().compareToIgnoreCase(b.getName());
				return rev ? -c : c;
			}
		};
	}

	void showSortMenu(final boolean left) {
		int curMode = left ? leftSort : rightSort;
		boolean curRev = left ? leftSortRev : rightSortRev;
		final String[] labels = {"Name (A\u2192Z)", "Name (Z\u2192A)", "Size (small\u2192large)", "Size (large\u2192small)",
				"Date modified (old\u2192new)", "Date modified (new\u2192old)",
				"Type (A\u2192Z)", "Type (Z\u2192A)"};
		int checked;
		if (curMode == SORT_SIZE)
			checked = curRev ? 3 : 2;
		else if (curMode == SORT_DATE)
			checked = curRev ? 5 : 4;
		else if (curMode == SORT_TYPE)
			checked = curRev ? 7 : 6;
		else
			checked = curRev ? 1 : 0;
		createDialog("Sort by", null)
				.setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int which) {
						int mode;
						boolean rev;
						switch (which) {
							case 1 :
								mode = SORT_NAME;
								rev = true;
								break;
							case 2 :
								mode = SORT_SIZE;
								rev = false;
								break;
							case 3 :
								mode = SORT_SIZE;
								rev = true;
								break;
							case 4 :
								mode = SORT_DATE;
								rev = false;
								break;
							case 5 :
								mode = SORT_DATE;
								rev = true;
								break;
							case 6 :
								mode = SORT_TYPE;
								rev = false;
								break;
							case 7 :
								mode = SORT_TYPE;
								rev = true;
								break;
							default :
								mode = SORT_NAME;
								rev = false;
						}
						if (left) {
							leftSort = mode;
							leftSortRev = rev;
						} else {
							rightSort = mode;
							rightSortRev = rev;
						}
						saveState();
						d.dismiss();
						refreshPane(left);
					}
				}).setNegativeButton("Cancel", null).show();
	}

	AlertDialog busyDialog(String title, String text) {
		OperationProgress p = new OperationProgress(this, title, false, false, dp);
		p.update(text, 0, 0, "");
		return p.dialog;
	}

	void showMenu(File f, boolean left) {
		markSelected(f, left);
		refresh();
		final ArrayList<String> labels = new ArrayList<String>();
		final ArrayList<String> icons = new ArrayList<String>();
		final ArrayList<Integer> codes = new ArrayList<Integer>();
		if (f instanceof MegaItem) {
			boolean sys = ((MegaItem) f).isSystemNode();
			addItem(labels, icons, codes, "\uD83D\uDC41\uFE0F", "Open", 11);
			if (!f.isDirectory()) {
				addItem(labels, icons, codes, "\u2197\uFE0F", "Open with", 12);
				addItem(labels, icons, codes, "\uD83D\uDCE4", "Share", 13);
			}
			addItem(labels, icons, codes, "\uD83D\uDCCB", "Copy", 1);
			if (!sys) {
				addItem(labels, icons, codes, "\u2722", "Move", 2);
				addItem(labels, icons, codes, "\u270F\uFE0F", "Rename", 3);
			}
			if (f.isDirectory()) { // new items are created inside the long-pressed folder, so only folders offer it
				addItem(labels, icons, codes, "\uD83D\uDCC1", "New folder", 4);
				addItem(labels, icons, codes, "\uD83D\uDCC4", "New text file", 40);
			}
			if (!sys)
				addItem(labels, icons, codes, "\u274C", "Delete", 5);
			addItem(labels, icons, codes, "\u2139\uFE0F", "Info", 7);
			if (!sys)
				addItem(labels, icons, codes, "\uD83D\uDCD1", "Duplicate", 8);
			addItem(labels, icons, codes, "\uD83D\uDCE6", "Zip", 9);
			createDialog(f.getName(), null).setAdapter(menuAdapter(labels, icons), new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface d, int which) {
					action(codes.get(which).intValue());
				}
			}).show();
			return;
		}
		boolean zipEntry = f instanceof ZipItem && !((ZipItem) f).isRoot();
		boolean plainFolder = f.isDirectory() && !(f instanceof ZipItem);
		if (!isPaneRoot(f)) {
			addItem(labels, icons, codes, "\uD83D\uDC41\uFE0F", "Open", 11);
			if (!(f.isDirectory() && !ZipItem.isZipRoot(f)))
				addItem(labels, icons, codes, "\u2197\uFE0F", "Open with", 12);
			if (!f.isDirectory() || ZipItem.isZipRoot(f))
				addItem(labels, icons, codes, "\uD83D\uDCE4", "Share", 13);
		}
		if (zipEntry) {
			// inside a zip: extract (Copy/Move), Rename, Delete, Info
			addItem(labels, icons, codes, "\uD83D\uDCCB", "Copy (extract)", 1);
			addItem(labels, icons, codes, "\u2722", "Move (extract)", 2);
			addItem(labels, icons, codes, "\u270F\uFE0F", "Rename", 3);
			addItem(labels, icons, codes, "\u274C", "Delete", 5);
			addItem(labels, icons, codes, "\u2139\uFE0F", "Info", 7);
		} else {
			if (ZipItem.isZipRoot(f))
				addItem(labels, icons, codes, "\u27A1\uFE0F", "Extract to other pane", 10);
			addItem(labels, icons, codes, "\uD83D\uDCCB", "Copy", 1);
			addItem(labels, icons, codes, "\u2722", "Move", 2);
			addItem(labels, icons, codes, "\u270F\uFE0F", "Rename", 3);
			if (plainFolder) { // new items are created inside the long-pressed folder, so only folders offer it
				addItem(labels, icons, codes, "\uD83D\uDCC1", "New folder", 4);
				addItem(labels, icons, codes, "\uD83D\uDCC4", "New text file", 40);
			}
			addItem(labels, icons, codes, "\u274C", "Delete", 5);
			addItem(labels, icons, codes, "\u2139\uFE0F", "Info", 7);
			if (!f.isDirectory() || ZipItem.isZipRoot(f)) addItem(labels, icons, codes, "#", "Checksums", 26);
			if (ZipItem.isZipRoot(f)) addItem(labels, icons, codes, "T", "Test archive integrity", 30);
			if (plainFolder) {
				addItem(labels, icons, codes, "\uD83D\uDCCA", "Storage analyzer", 27);
				addItem(labels, icons, codes, "=", "Find duplicate files", 29);
			}
			addItem(labels, icons, codes, "\uD83D\uDCD1", "Duplicate", 8);
			addItem(labels, icons, codes, "\uD83D\uDCE6", "Zip", 9);
			if (plainFolder)
				addItem(labels, icons, codes, isBookmarked(f) ? "\u2606" : "\u2605",
						isBookmarked(f) ? "Remove bookmark" : "Bookmark", 24);
		}
		createDialog(f.equals(leftRoot) || f.equals(rightRoot) ? displayRoot(f) : f.getName(), null)
				.setAdapter(menuAdapter(labels, icons), new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int which) {
						action(codes.get(which).intValue());
					}
				}).show();
	}

	void addItem(ArrayList<String> labels, ArrayList<String> icons, ArrayList<Integer> codes, String icon, String label,
			int code) {
		icons.add(icon);
		labels.add(label);
		codes.add(Integer.valueOf(code));
	}

	ArrayAdapter<String> menuAdapter(final ArrayList<String> labels, final ArrayList<String> icons) {
		return new ArrayAdapter<String>(this, 0, labels) {
			public View getView(int pos, View convertView, ViewGroup parent) {
				LinearLayout row;
				TextView icon, text;
				if (convertView instanceof LinearLayout) {
					row = (LinearLayout) convertView;
					icon = (TextView) row.getChildAt(0);
					text = (TextView) row.getChildAt(1);
				} else {
					row = new LinearLayout(MainActivity.this);
					row.setOrientation(LinearLayout.HORIZONTAL);
					row.setGravity(Gravity.CENTER_VERTICAL);
					row.setPadding(20 * dp, 14 * dp, 20 * dp, 14 * dp);
					icon = new TextView(MainActivity.this);
					icon.setTextSize(17);
					icon.setGravity(Gravity.CENTER);
					icon.setTextColor(Color.rgb(90, 90, 90));
					row.addView(icon, new LinearLayout.LayoutParams(34 * dp, -2));
					text = new TextView(MainActivity.this);
					text.setTextSize(16);
					text.setTextColor(dark ? Color.WHITE : Color.BLACK);
					row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
				}
				String menuIcon = icons.get(pos);
				icon.setText(menuIcon);
				// Use the same gold as the toolbar bookmark star.
				if ("\u2605".equals(menuIcon) || "\u2606".equals(menuIcon))
					icon.setTextColor(Color.rgb(210, 150, 20));
				else
					icon.setTextColor(dark ? Color.LTGRAY : Color.rgb(90, 90, 90));
				text.setText(labels.get(pos));
				text.setTextColor(dark ? Color.WHITE : Color.BLACK);
				return row;
			}
		};
	}

	/** the selected normal folders (archives, cloud items and files cannot be bookmarked); empty unless all selected items are such folders */
	ArrayList<File> multiBookmarkFolders() {
		ArrayList<File> out = new ArrayList<File>();
		for (int i = 0; i < multi.size(); i++) {
			File m = multi.get(i);
			if (m instanceof ZipItem || m instanceof MegaItem || !m.isDirectory())
				return new ArrayList<File>();
			out.add(m);
		}
		return out;
	}

	void showMultiMenu() {
		if (multi.isEmpty())
			return;
		final ArrayList<String> labels = new ArrayList<String>();
		final ArrayList<String> icons = new ArrayList<String>();
		final ArrayList<Integer> codes = new ArrayList<Integer>();
		addItem(labels, icons, codes, "\uD83D\uDCCB", "Copy", 1);
		addItem(labels, icons, codes, "\u2722", "Move", 2);
		addItem(labels, icons, codes, "\u270F\uFE0F", "Batch rename", 28);
		addItem(labels, icons, codes, "\u274C", "Delete", 5);
		addItem(labels, icons, codes, "\u2139\uFE0F", "Info", 7);
		addItem(labels, icons, codes, "\uD83D\uDCD1", "Duplicate", 8);
		addItem(labels, icons, codes, "\uD83D\uDCE6", "Zip", 9);
		addItem(labels, icons, codes, "\uD83D\uDCE4", "Share", 13);
		ArrayList<File> bmFolders = multiBookmarkFolders();
		if (!bmFolders.isEmpty()) {
			boolean allMarked = true;
			for (int i = 0; i < bmFolders.size(); i++)
				if (!isBookmarked(bmFolders.get(i)))
					allMarked = false;
			addItem(labels, icons, codes, allMarked ? "\u2606" : "\u2605",
					allMarked ? "Remove bookmark" : "Bookmark", 25);
		}
		createDialog(multi.size() + " selected", null)
				.setAdapter(menuAdapter(labels, icons), new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int which) {
						action(codes.get(which).intValue());
					}
				}).show();
	}

	void openItem(File f, final boolean chooser) {
		if (f instanceof MegaItem && !f.isDirectory()) {
			megaFetch((MegaItem) f, new MegaItem.Done() {
				public void done(File t) {
					launchFile(t, chooser);
				}
			});
			return;
		}
		if (f.isDirectory() && !ZipItem.isZipRoot(f)) {
			toggleFolder(f, selectedLeft);
			return;
		}
		if (ZipItem.isZipRoot(f))
			f = ((ZipItem) f).zip;
		if (f instanceof ZipItem) {
			final ZipItem zi = (ZipItem) f;
			final boolean ch = chooser;
			final File dir = new File(getCacheDir(), "open/" + Integer.toHexString(zi.getAbsolutePath().hashCode()));
			final File tmp = new File(dir, zi.getName());
			toast("Extracting...");
			new Thread(new Runnable() {
				public void run() {
					String err = null;
					try {
						dir.mkdirs();
						ZipItem.extract(zi, tmp);
					} catch (Exception e) {
						err = "Error: " + e.getMessage();
					}
					final String m = err;
					uiPost(new Runnable() {
						public void run() {
							if (m != null)
								toast(m);
							else
								launchFile(tmp, ch);
						}
					});
				}
			}).start();
			return;
		}
		launchFile(f, chooser);
	}

	String mimeOf(File f) {
		String n = f.getName();
		int dot = n.lastIndexOf('.');
		if (dot >= 0) {
			String m = android.webkit.MimeTypeMap.getSingleton()
					.getMimeTypeFromExtension(n.substring(dot + 1).toLowerCase(Locale.US));
			if (m != null)
				return m;
		}
		return "*/*";
	}

	void launchFile(File f, boolean chooser) {
		if (!f.isFile()) {
			toast("File not found");
			return;
		}
		
		Uri uri = new Uri.Builder().scheme("content").authority(FileShareProvider.AUTH).path(f.getAbsolutePath())
				.build();
		Intent i = new Intent(Intent.ACTION_VIEW);
		i.setDataAndType(uri, mimeOf(f));
		i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		i.setClipData(ClipData.newRawUri(f.getName(), uri));
		try {
			// plain ACTION_VIEW: Android's own app resolver lists every app that can open the file
			startActivity(i);
		} catch (ActivityNotFoundException e) {
			toast("No app found to open this file");
		} catch (Exception e) {
			toast("Cannot open: " + e.getMessage());
		}
	}

	PreviewManager getPreviewManager() {
		if (previewManager == null) previewManager = new PreviewManager(this);
		return previewManager;
	}

	// ---------------- MEGA ----------------
	boolean hasMega(ArrayList<File> l) {
		for (int i = 0; i < l.size(); i++)
			if (l.get(i) instanceof MegaItem)
				return true;
		return false;
	}

	void saveMega() {
		MegaClient.saveAll(prefs);
	}

	/** loads the tree of one account; acc == null means there is no account yet, so the login window is shown */
	void megaConnect(final MegaClient acc, final Runnable ok) {
		if (acc == null) {
			showMegaLogin(null, ok, null);
			return;
		}
		if (acc.isReady()) {
			ok.run();
			return;
		}
		if (!acc.hasSession()) {
			showMegaLogin(acc, ok, null);
			return;
		}
		toast("Connecting to MEGA...");
		new Thread(new Runnable() {
			public void run() {
				String err = null;
				boolean relogin = false;
				try {
					acc.loadTree();
				} catch (MegaException e) {
					err = e.getMessage();
					relogin = e.code == -15 || e.code == -9 || e.code == -11;
				} catch (Exception e) {
					err = e.getMessage() == null ? e.toString() : e.getMessage();
				}
				final String m = err;
				final boolean rl = relogin;
				uiPost(new Runnable() {
					public void run() {
						if (m == null) {
							listCache.clear();
							ok.run();
						} else if (rl) {
							acc.dropSession();
							saveMega();
							showMegaLogin(acc, ok, m);
						} else
							toast(m);
					}
				});
			}
		}).start();
	}

	/** login window. acc == null adds a new account, otherwise that account logs in again (its session expired) */
	void showMegaLogin(final MegaClient acc, final Runnable ok, final String msg) {
		AlertDialog.Builder b = createDialog(acc == null ? "Add a MEGA account" : "Log in to MEGA", null);
		Context cx = b.getContext(); // dialog context, same as the Rename popup
		LinearLayout box = new LinearLayout(cx);
		box.setOrientation(LinearLayout.VERTICAL);
		padDialogBox(box);
		final EditText em = new EditText(cx);
		alignInput(em);
		em.setHint("E-mail");
		em.setSingleLine(true);
		em.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
		String lastMail = prefs.getString("mega_last_email", "");
		if (acc != null && acc.email != null && acc.email.length() > 0)
			lastMail = acc.email;
		else if (MegaClient.byEmail(lastMail) != null)
			lastMail = ""; // that one is already added, the new account is a different one
		em.setText(lastMail);
		box.addView(em, new LinearLayout.LayoutParams(-1, -2));
		final EditText pw = new EditText(cx);
		alignInput(pw);
		pw.setHint("Password");
		pw.setSingleLine(true);
		pw.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
		box.addView(pw, new LinearLayout.LayoutParams(-1, -2));
		final EditText mfa = new EditText(cx);
		alignInput(mfa);
		mfa.setHint("2FA code (only if you use it)");
		mfa.setSingleLine(true);
		mfa.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
		box.addView(mfa, new LinearLayout.LayoutParams(-1, -2));
		themeDialogView(box);
		if (msg != null) {
			TextView err = new TextView(cx);
			err.setText(msg);
			err.setTextColor(Color.rgb(230, 80, 70));
			err.setTextSize(13);
			err.setTextIsSelectable(true);
			err.setPadding(0, 10 * dp, 0, 4 * dp);
			box.addView(err, new LinearLayout.LayoutParams(-1, -2));
		}
		ScrollView outer = new ScrollView(cx);
		outer.addView(box);
		b.setView(outer)
				.setPositiveButton("Log in", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						doMegaLogin(acc, em.getText().toString(), pw.getText().toString(), mfa.getText().toString(), ok);
					}
				}).setNegativeButton("Cancel", null)
				.show();
	}

	void doMegaLogin(final MegaClient acc, final String mail, final String pass, final String code, final Runnable ok) {
		prefs.edit().putString("mega_last_email", mail.trim()).commit();
		MegaClient.clearLog();
		MegaClient.log("login start");
		// a new account object is only added to the list once the login worked
		final MegaClient target = acc != null ? acc : new MegaClient();
		final AlertDialog wait = busyDialog("MEGA", "Logging in...");
		new Thread(new Runnable() {
			public void run() {
				String err = null;
				int errCode = 0;
				try {
					target.login(mail, pass, code);
					MegaClient dup = MegaClient.byEmail(target.email);
					if (dup != null && dup != target) {
						String who = target.email;
						target.logout();
						throw new MegaException(0, who + " is already added");
					}
					target.loadTree();
					MegaClient.register(target);
					saveMega();
				} catch (MegaException e) {
					err = e.getMessage();
					errCode = e.code;
				} catch (Throwable e) {
					err = e.toString();
					MegaClient.log("!! " + e + (e.getStackTrace().length > 0 ? " at " + e.getStackTrace()[0] : ""));
				}
				final String m = err;
				final int ec = errCode;
				uiPost(new Runnable() {
					public void run() {
						try {
							wait.dismiss();
						} catch (Exception e) {
						}
						if (m == null) {
							listCache.clear();
							ok.run();
						} else
							showMegaLogin(acc, ok, ec == -26 ? "Enter your two-factor (2FA) code and log in again" : m);
					}
				});
			}
		}).start();
	}

	void showMegaLogout(final MegaClient acc) {
		String who = acc.email == null || acc.email.length() == 0 ? "your MEGA account" : acc.email;
		showConfirmDialog("Log out of MEGA", "Log out of " + who + "?", "Log out", "Cancel",
				new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						doMegaLogout(acc);
					}
				});
	}

	/** removes the paths inside one account from a set of open folders */
	void forgetMegaPaths(HashSet<String> open, String prefix) {
		for (Iterator<String> it = open.iterator(); it.hasNext();) {
			String p = it.next();
			if (p.equals(prefix) || p.startsWith(prefix + "/"))
				it.remove();
		}
	}

	void doMegaLogout(MegaClient acc) {
		String prefix = MegaItem.rootOf(acc).getAbsolutePath();
		acc.logout();
		MegaClient.unregister(acc);
		saveMega();
		listCache.clear();
		forgetMegaPaths(leftOpen, prefix);
		forgetMegaPaths(rightOpen, prefix);
		if (leftCur instanceof MegaItem && ((MegaItem) leftCur).acc == acc)
			leftCur = leftRoot;
		if (rightCur instanceof MegaItem && ((MegaItem) rightCur).acc == acc)
			rightCur = rightRoot;
		if (selected instanceof MegaItem && ((MegaItem) selected).acc == acc)
			selected = null;
		exitMulti();
		refresh();
		toast("Logged out of MEGA");
	}

	void showMegaMenu(final MegaClient acc) {
		final ArrayList<String> labels = new ArrayList<String>();
		final ArrayList<String> icons = new ArrayList<String>();
		final ArrayList<Integer> codes = new ArrayList<Integer>();
		if (acc != null && acc.hasSession()) {
			addItem(labels, icons, codes, "\uD83D\uDD04", "Reload MEGA", 1);
			addItem(labels, icons, codes, "\uD83D\uDEAA", "Log out", 2);
		} else {
			addItem(labels, icons, codes, "\uD83D\uDD11", "Log in", 3);
			if (acc != null) // the session expired: the row can be taken out of the list
				addItem(labels, icons, codes, "\uD83D\uDEAA", "Remove account", 2);
		}
		addItem(labels, icons, codes, "\u2795", "Add another account", 4);
		String who = acc == null ? "No account yet" : (acc.email == null ? "" : acc.email + " - ") + acc.statusText();
		createDialog("MEGA", who).setAdapter(menuAdapter(labels, icons), new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface d, int which) {
				int c = codes.get(which).intValue();
				if (c == 2) {
					showMegaLogout(acc);
				} else if (c == 4) {
					showMegaLogin(null, new Runnable() {
						public void run() {
							refresh();
						}
					}, null);
				} else {
					if (acc != null)
						acc.ready = false;
					listCache.clear();
					megaConnect(acc, new Runnable() {
						public void run() {
							refresh();
						}
					});
				}
			}
		}).show();
	}

	/** downloads a MEGA file into the cache (once) and hands the local copy to cb */
	void megaFetch(final MegaItem mi, final MegaItem.Done cb) {
		final File dir = new File(getCacheDir(), "mega/" + mi.handle + "_" + mi.lastModified());
		final File out = new File(dir, mi.getName());
		if (out.exists() && out.length() == mi.length()) {
			cb.done(out);
			return;
		}
		toast("Downloading " + mi.getName() + "...");
		new Thread(new Runnable() {
			public void run() {
				String err = null;
				InputStream in = null;
				OutputStream os = null;
				try {
					dir.mkdirs();
					File tmp = new File(dir, mi.getName() + ".part");
					in = mi.openStream();
					os = new FileOutputStream(tmp);
					byte[] b = new byte[65536];
					int n;
					while ((n = in.read(b)) > 0)
						os.write(b, 0, n);
					os.close();
					os = null;
					if (!tmp.renameTo(out))
						throw new IOException("Cannot save the downloaded file");
				} catch (Exception e) {
					err = e.getMessage() == null ? e.toString() : e.getMessage();
				} finally {
					try {
						if (in != null)
							in.close();
					} catch (IOException e) {
					}
					try {
						if (os != null)
							os.close();
					} catch (IOException e) {
					}
				}
				final String m = err;
				uiPost(new Runnable() {
					public void run() {
						if (m != null)
							toast("MEGA: " + m);
						else
							cb.done(out);
					}
				});
			}
		}).start();
	}

	void megaAction(int a) {
		final MegaItem mi = (MegaItem) selected;
		boolean sys = mi.isSystemNode();
		if (a == 1) {
			transfer(false);
			return;
		}
		if (a == 2) {
			if (sys)
				toast("This MEGA folder cannot be moved");
			else
				transfer(true);
			return;
		}
		if (a == 3) {
			if (sys)
				toast("This MEGA folder cannot be renamed");
			else
				ask("Rename", "New name", 3);
			return;
		}
		if (a == 5) {
			if (sys)
				toast("This MEGA folder cannot be deleted");
			else
				delConfirm();
			return;
		}
		if (a == 7) {
			showMessageDialog(mi.getName(), "In MEGA" + (mi.acc.email == null || mi.acc.email.length() == 0 ? "" : " (" + mi.acc.email + ")") + (mi.acc.inRubbish(mi.handle) ? " (Rubbish Bin)" : "") + "\nType: "
					+ (mi.isDirectory() ? "Folder" : "File")
					+ (mi.isDirectory() ? "" : "\nSize: " + human(mi.length())) + "\nModified: "
					+ (new Date(mi.lastModified())));
			return;
		}
		if (a == 8) {
			if (sys)
				toast("This MEGA folder cannot be duplicated");
			else
				duplicate();
			return;
		}
		if (a == 9) {
			ZipItem.zip(this);
			return;
		}
		if (a == 11 || a == 12) {
			if (mi.isDirectory())
				toggleFolder(mi, selectedLeft);
			else if (a == 11)
				previewFile(mi);
			else
				openItem(mi, true);
			return;
		}
		if (a == 13) {
			if (!mi.isDirectory())
				megaFetch(mi, new MegaItem.Done() {
					public void done(File f) {
						shareItem(f);
					}
				});
			return;
		}
		toast("Not available for MEGA items");
	}

	// ---- MEGA changes: they run in the background with a small waiting dialog ----
	interface MegaWork {
		String run() throws Exception;
	}

	void megaTask(String title, final MegaWork w) {
		final AlertDialog wait = busyDialog("MEGA", title + "...");
		new Thread(new Runnable() {
			public void run() {
				String msg;
				try {
					msg = w.run();
				} catch (Exception e) {
					msg = "MEGA: " + (e.getMessage() == null ? e.toString() : e.getMessage());
				}
				final String m = msg;
				uiPost(new Runnable() {
					public void run() {
						try {
							wait.dismiss();
						} catch (Exception e) {
						}
						selected = null;
						exitMulti();
						listCache.clear();
						refresh();
						toast(m);
						refreshMegaQuota();
					}
				});
			}
		}).start();
	}

	boolean megaBase(MegaItem base) {
		if (base.isRootNode()) {
			toast("Open a MEGA folder (for example Cloud Drive) first");
			return false;
		}
		if (!base.isReady()) {
			toast("Connect to MEGA first");
			return false;
		}
		return true;
	}

	void megaRename(final MegaItem mi, final String n) {
		if (n.indexOf('/') >= 0) {
			toast("Invalid name");
			return;
		}
		final MegaClient ac = mi.acc;
		MegaClient.Node node = ac.node(mi.handle);
		if (node == null)
			return;
		String ex = ac.child(node.p, n, mi.isDirectory());
		if (ex != null && !ex.equals(mi.handle)) {
			toast("Already exists");
			return;
		}
		megaTask("Renaming", new MegaWork() {
			public String run() throws Exception {
				ac.rename(mi.handle, n);
				return "Renamed";
			}
		});
	}

	void megaNewFolder(final MegaItem base, final String n) {
		if (!megaBase(base))
			return;
		if (n.indexOf('/') >= 0) {
			toast("Invalid name");
			return;
		}
		if (base.acc.child(base.handle, n, true) != null || base.acc.child(base.handle, n, false) != null) {
			toast("Already exists");
			return;
		}
		(selectedLeft ? leftOpen : rightOpen).add(base.getAbsolutePath());
		megaTask("Creating folder", new MegaWork() {
			public String run() throws Exception {
				base.acc.makeFolder(n, base.handle);
				return "Folder created";
			}
		});
	}

	void megaNewText(final MegaItem base) {
		if (!megaBase(base))
			return;
		(selectedLeft ? leftOpen : rightOpen).add(base.getAbsolutePath());
		megaTask("Creating file", new MegaWork() {
			public String run() throws Exception {
				String name = base.acc.freeName(base.handle, "untitled", ".txt");
				File dir = new File(getCacheDir(), "mega-new");
				dir.mkdirs();
				File f = new File(dir, name);
				new FileOutputStream(f).close();
				MegaClient.Progress none = new MegaClient.Progress() {
					public void bytes(long n) {
					}
				};
				try {
					try {
						base.acc.uploadFile(f, base.handle, none);
					} catch (IOException e) {
						// in case the server refuses an empty upload, put a single line break in the file
						FileOutputStream os = new FileOutputStream(f);
						os.write('\n');
						os.close();
						base.acc.uploadFile(f, base.handle, none);
					}
				} finally {
					f.delete();
				}
				return "Created " + name;
			}
		});
	}

	void megaDelete(final ArrayList<File> items) {
		megaTask("Deleting", new MegaWork() {
			public String run() throws Exception {
				int n = 0, perm = 0;
				for (int i = 0; i < items.size(); i++) {
					if (!(items.get(i) instanceof MegaItem))
						continue;
					String h = ((MegaItem) items.get(i)).handle;
					MegaClient ac = ((MegaItem) items.get(i)).acc;
					boolean covered = false; // already inside another selected folder of the same account
					for (int j = 0; j < items.size(); j++)
						if (j != i && items.get(j) instanceof MegaItem && ((MegaItem) items.get(j)).acc == ac
								&& ac.isInside(h, ((MegaItem) items.get(j)).handle))
							covered = true;
					if (covered || ac.node(h) == null)
						continue;
					if (ac.inRubbish(h))
						perm++;
					ac.trashOrDelete(h);
					n++;
				}
				if (n > 0 && perm == n)
					return "Deleted " + n + (n == 1 ? " item" : " items");
				return "Moved " + n + (n == 1 ? " item" : " items") + " to the MEGA Rubbish Bin";
			}
		});
	}

	void megaDuplicate(final ArrayList<File> items) {
		megaTask("Duplicating", new MegaWork() {
			public String run() throws Exception {
				int n = 0;
				for (int i = 0; i < items.size(); i++) {
					if (!(items.get(i) instanceof MegaItem))
						continue;
					MegaClient ac = ((MegaItem) items.get(i)).acc;
					MegaClient.Node node = ac.node(((MegaItem) items.get(i)).handle);
					if (node == null || node.t >= 2)
						continue;
					ac.copyTree(node.h, node.p, ac.copyName(node.p, node.name, node.t != 0));
					n++;
				}
				return "Duplicated " + n + (n == 1 ? " item" : " items");
			}
		});
	}

	/** copies or moves MEGA items into a MEGA folder; nothing is downloaded */
	void megaCopyMove(final ArrayList<File> srcs, final MegaItem dst, final boolean move) {
		if (!megaBase(dst))
			return;
		megaTask(move ? "Moving" : "Copying", new MegaWork() {
			public String run() throws Exception {
				int[] st = new int[3]; // done, skipped, refused
				for (int i = 0; i < srcs.size(); i++) {
					if (!(srcs.get(i) instanceof MegaItem))
						continue;
					MegaItem s = (MegaItem) srcs.get(i);
					if (s.acc != dst.acc || s.isSystemNode() || dst.acc.node(s.handle) == null) {
						st[2]++;
						continue;
					}
					if (dst.acc.isInside(dst.handle, s.handle)) {
						st[2]++; // into itself
						continue;
					}
					megaCopyInto(dst.acc, s.handle, dst.handle, move, st);
				}
				String verb = move ? "Moved " : "Copied ";
				return verb + st[0] + (st[0] == 1 ? " item" : " items") + (st[1] > 0 ? ", skipped " + st[1] + " already there" : "")
						+ (st[2] > 0 ? ", " + st[2] + " not possible (into itself or protected)" : "");
			}
		});
	}

	void megaCopyInto(MegaClient ac, String srcH, String dstParent, boolean move, int[] st) throws IOException {
		MegaClient.Node n = ac.node(srcH);
		if (n == null)
			return;
		boolean folder = n.t != 0;
		String existing = ac.child(dstParent, n.name, folder);
		if (move && n.p.equals(dstParent)) {
			st[1]++;
			return;
		}
		if (!folder) {
			if (existing != null) {
				st[1]++;
				return;
			}
			if (move)
				ac.move(srcH, dstParent);
			else
				ac.copyFileTo(srcH, dstParent, n.name);
			st[0]++;
			return;
		}
		if (existing == null) {
			if (move) {
				ac.move(srcH, dstParent);
			} else {
				ac.copyTree(srcH, dstParent, n.name);
			}
			st[0]++;
			return;
		}
		// the folder exists already: merge the content
		ArrayList<String> k = ac.kidsOf(srcH);
		for (int i = 0; i < k.size(); i++)
			megaCopyInto(ac, k.get(i), existing, move, st);
		if (move && ac.kidsOf(srcH).isEmpty())
			ac.trashOrDelete(srcH);
	}

	void previewFile(final File f) {
		getPreviewManager().preview(f);
	}

	void shareItem(File f) {
		if (ZipItem.isZipRoot(f)) {
			shareFile(((ZipItem) f).zip);
			return;
		} 
		if (f.isDirectory())
			return; 
		if (f instanceof ZipItem) {
			final ZipItem zi = (ZipItem) f;
			final File dir = new File(getCacheDir(), "share/" + Integer.toHexString(zi.getAbsolutePath().hashCode()));
			final File tmp = new File(dir, zi.getName());
			toast("Extracting...");
			new Thread(new Runnable() {
				public void run() {
					String err = null;
					try {
						dir.mkdirs();
						ZipItem.extract(zi, tmp);
					} catch (Exception e) {
						err = "Error: " + e.getMessage();
					}
					final String m = err;
					uiPost(new Runnable() {
						public void run() {
							if (m != null)
								toast(m);
							else
								shareFile(tmp);
						}
					});
				}
			}).start();
			return;
		}
		shareFile(f);
	}

	void shareFile(File f) {
		if (!f.isFile()) {
			toast("File not found");
			return;
		}
		Uri uri = new Uri.Builder().scheme("content").authority(FileShareProvider.AUTH).path(f.getAbsolutePath())
				.build();
		Intent i = new Intent(Intent.ACTION_SEND);
		i.setType(mimeOf(f));
		i.putExtra(Intent.EXTRA_STREAM, uri);
		i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		i.setClipData(ClipData.newRawUri(f.getName(), uri));
		try {
			startActivity(Intent.createChooser(i, "Share"));
		} catch (ActivityNotFoundException e) {
			toast("No app found to share this file");
		} catch (Exception e) {
			toast("Cannot share: " + e.getMessage());
		}
	}

	File uniqueSibling(File src, String suffix, String newExt) {
		String n = src.getName(), ext = "";
		int dot = n.lastIndexOf('.');
		if (!src.isDirectory() && dot > 0) {
			ext = n.substring(dot);
			n = n.substring(0, dot);
		}
		if (newExt != null)
			ext = newExt;
		File out = new File(src.getParentFile(), n + (suffix.length() > 0 ? " " + suffix : "") + ext);
		int i = 2;
		while (out.exists()) {
			out = new File(src.getParentFile(), n + (suffix.length() > 0 ? " " + suffix : "") + " " + i + ext);
			i++;
		}
		return out;
	}

	void duplicate() {
		if (selected instanceof MegaItem) {
			megaDuplicate(one(selected));
			return;
		}
		File dst = uniqueSibling(selected, "copy", null);
		startTransfer(selected, dst, false, "Duplicating", "Duplicated");
	}

	void transfer(boolean move) {
		if (isPaneRoot(selected)) {
			toast("Select a file or folder inside the tree");
			return;
		}
		
		File dstDir = selectedLeft ? rightCur : leftCur;
		File src = selected;
		if (dstDir instanceof MegaItem) {
			uploadToMega(one(src), (MegaItem) dstDir, move);
			return;
		}
		if (src instanceof MegaItem && dstDir instanceof ZipItem) {
			toast("Copy MEGA files into a normal folder first");
			return;
		}
		if (dstDir instanceof ZipItem) {
			ZipItem dz = (ZipItem) dstDir;
			if (src.getAbsolutePath().equals(dz.zip.getAbsolutePath())) {
				toast("Cannot add a zip to itself");
				return;
			}
			if (move && src instanceof ZipItem) {
				toast("Move from a zip into a zip is not supported. Use Copy.");
				return;
			}
			fileOperations.startTransferToZip(one(src), move, move ? "Moving into zip" : "Adding to zip", move ? "Moved" : "Copied", dz);
			return;
		}
		File dst = new File(dstDir, src.getName());
		String sp = src.getAbsolutePath();
		String dp2 = dst.getAbsolutePath();
		if (dp2.equals(sp)) {
			toast("Source and destination are the same");
			return;
		}
		if (src.isDirectory() && dp2.startsWith(sp + File.separator)) {
			toast("Cannot copy a folder into itself");
			return;
		}
		
		if (move && !(src instanceof ZipItem) && !(src instanceof MegaItem) && !dst.exists() && src.renameTo(dst)) {
			selected = null;
			refresh();
			toast("Moved");
			return;
		}
		startTransfer(src, dst, move, move ? "Moving" : "Copying", move ? "Moved" : "Copied");
	}

	void startTransfer(File src, File dst, boolean move, String title, String verb) {
		fileOperations.startTransfer(src, dst, move, title, verb);
	}

	boolean isSymlink(File f) {
		try {
			File p = f.getParentFile();
			File canon = p == null ? f : new File(p.getCanonicalFile(), f.getName());
			return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
		} catch (IOException e) {
			return false;
		}
	}

	/** true when `parent` already holds an item called n (ignoring `self`, the item that is being renamed) */
	boolean nameTaken(File parent, String n, File self) {
		if (parent == null || n.length() == 0)
			return false;
		if (parent instanceof MegaItem) {
			MegaClient pa = ((MegaItem) parent).acc;
			if (pa == null)
				return false;
			String ph = ((MegaItem) parent).handle;
			String h = self instanceof MegaItem ? ((MegaItem) self).handle : null;
			String same = pa.child(ph, n, self != null ? self.isDirectory() : true);
			if (self == null && same == null)
				same = pa.child(ph, n, false); // new folder: a file with that name blocks it too
			return same != null && !same.equals(h);
		}
		if (parent instanceof ZipItem || self instanceof ZipItem) {
			File[] c = parent.listFiles();
			if (c != null)
				for (int i = 0; i < c.length; i++)
					if (c[i].getName().equals(n) && (self == null || !c[i].equals(self)))
						return true;
			return false;
		}
		File x = new File(parent, n);
		return x.exists() && (self == null || !x.equals(self));
	}

	void ask(String title, String hint, final int a) {
		AlertDialog.Builder dialogBuilder = createDialog(title, null);
		android.content.Context cx = dialogBuilder.getContext();
		final EditText e = new EditText(cx);
		e.setHint(hint);
		e.setSingleLine(true);
		themeDialogView(e);
		final String original = a == 3 && selected != null ? selected.getName() : "";
		if (a == 3 && selected != null)
			e.setText(original);
		// the input box with a red warning line under it
		LinearLayout box = new LinearLayout(cx);
		box.setOrientation(LinearLayout.VERTICAL);
		padDialogBox(box);
		alignInput(e);
		box.addView(e, new LinearLayout.LayoutParams(-1, -2));
		final TextView warn = new TextView(cx);
		warn.setText("Already exists");
		warn.setTextColor(Color.rgb(230, 80, 70));
		warn.setTextSize(13);
		warn.setPadding(0, 2 * dp, 0, 0);
		warn.setVisibility(View.GONE);
		box.addView(warn, new LinearLayout.LayoutParams(-1, -2));
		final File parentDir = a == 4 ? (selectedLeft ? leftCur : rightCur)
				: (selected == null ? null : selected.getParentFile());
		final File self = a == 3 ? selected : null;
		final AlertDialog[] dlg = new AlertDialog[1];
		e.addTextChangedListener(new android.text.TextWatcher() {
			public void beforeTextChanged(CharSequence s, int st, int c, int af) {
			}

			public void onTextChanged(CharSequence s, int st, int b, int c) {
			}

			public void afterTextChanged(android.text.Editable s) {
				String n = s.toString().trim();
				boolean dup = !n.equals(original) && nameTaken(parentDir, n, self);
				warn.setVisibility(dup ? View.VISIBLE : View.GONE);
				if (dlg[0] != null) {
					Button ok = dlg[0].getButton(DialogInterface.BUTTON_POSITIVE);
					if (ok != null)
						ok.setEnabled(!dup);
				}
			}
		});
		dialogBuilder.setView(box)
				.setPositiveButton("OK", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						String n = e.getText().toString().trim();
						if (n.length() == 0)
							return;
						if (a == 4) {
							File base = selectedLeft ? leftCur : rightCur;
							if (base instanceof MegaItem) {
								megaNewFolder((MegaItem) base, n);
								return;
							}
							File nf = new File(base, n);
							if (nf.exists())
								toast("Already exists");
							else
								toast(nf.mkdir() ? "Folder created" : "Create failed");
							HashSet<String> op = selectedLeft ? leftOpen : rightOpen;
							op.add(base.getAbsolutePath());
						} else if (selected instanceof MegaItem) {
							megaRename((MegaItem) selected, n);
							return;
						} else if (selected instanceof ZipItem) {
							ZipItem zi = (ZipItem) selected;
							if (n.indexOf('/') >= 0) {
								toast("Invalid name");
								return;
							}
							selected = null;
							ZipItem.zipModify(MainActivity.this, zi.zip, zi.entry, n, "Renamed");
							return;
						} else {
							File x = new File(selected.getParentFile(), n);
							if (x.exists())
								toast("Already exists");
							else if (selected.renameTo(x)) {
								markSelected(x, selectedLeft);
								toast("Renamed");
							} else
								toast("Rename failed");
						}
						refresh();
					}
				}).setNegativeButton("Cancel", null);
		dlg[0] = dialogBuilder.show();
		// cursor in the input box with the keyboard up; a rename puts it just before the extension (.xxx)
		dlg[0].getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
		int caret = original.length();
		int dot = original.lastIndexOf('.');
		if (a == 3 && selected != null && !selected.isDirectory() && dot > 0)
			caret = dot;
		final int pos = caret;
		e.requestFocus();
		e.setSelection(pos);
		e.post(new Runnable() {
			public void run() {
				e.requestFocus();
				e.setSelection(Math.min(pos, e.getText().length()));
			}
		});
	}

	void delConfirm() {
		if (selected instanceof MegaItem) {
			final MegaItem mi = (MegaItem) selected;
			boolean perm = mi.acc.inRubbish(mi.handle);
			createDialog("Delete", (perm ? "Delete permanently: " : "Move to the MEGA Rubbish Bin: ") + mi.getName() + "?")
					.setPositiveButton("Delete", new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface d, int w) {
							selected = null;
							megaDelete(one(mi));
						}
					}).setNegativeButton("Cancel", null).show();
			return;
		}
		createDialog("Delete", (selected instanceof ZipItem ? "Delete " : "Move to trash: ") + selected.getName() + "?")
				.setPositiveButton("Delete", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						if (selected instanceof ZipItem) {
							ZipItem zi = (ZipItem) selected;
							selected = null;
							ZipItem.zipModify(MainActivity.this, zi.zip, zi.entry, null, "Deleted");
							return;
						}
						File t = selected;
						selected = null;
						fileOperations.deleteToTrash(one(t));
					}
				}).setNegativeButton("Cancel", null).show();
	}

	void showChecksums(final File f) {
		if (f == null || f instanceof MegaItem) { toast("Checksums are available for local files"); return; }
		final File checksumFile;
		if (f instanceof ZipItem) {
			ZipItem zi = (ZipItem) f;
			if (!zi.isRoot()) { toast("Select the ZIP/APK file itself to calculate its checksum"); return; }
			checksumFile = zi.zip;
		} else {
			checksumFile = f;
		}
		if (checksumFile == null || !checksumFile.isFile()) { toast("Checksums are available for local files"); return; }
		final AlertDialog wait = busyDialog("Checksums", "Calculating SHA-256 and MD5...");
		new Thread(new Runnable() { public void run() {
			String out;
			try { out = "SHA-256:\n" + digestFile(checksumFile, "SHA-256") + "\n\nMD5:\n" + digestFile(checksumFile, "MD5"); }
			catch (Exception e) { out = "Error: " + (e.getMessage()==null?e.toString():e.getMessage()); }
			final String msg=out; uiPost(new Runnable(){ public void run(){
				try{wait.dismiss();}catch(Exception e){}
				createDialog(checksumFile.getName(), msg).setPositiveButton("Copy", new DialogInterface.OnClickListener(){ public void onClick(DialogInterface d,int w){
					ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE); cm.setPrimaryClip(ClipData.newPlainText("Checksums",msg)); toast("Checksums copied");
				}}).setNegativeButton("Close",null).show();
			}});
		}}).start();
	}

	String digestFile(File f, String alg) throws Exception {
		MessageDigest md=MessageDigest.getInstance(alg); InputStream in=new FileInputStream(f);
		try { byte[] b=new byte[65536]; int n; while((n=in.read(b))>0) md.update(b,0,n); } finally { in.close(); }
		byte[] d=md.digest(); StringBuilder x=new StringBuilder(); for(int i=0;i<d.length;i++){ String h=Integer.toHexString(d[i]&255); if(h.length()<2)x.append('0'); x.append(h); } return x.toString();
	}

	String fileCategory(File f) {
		String n=f.getName().toLowerCase(Locale.US); int dot=n.lastIndexOf('.'); String e=dot<0?"":n.substring(dot+1);
		if (e.equals("jpg")||e.equals("jpeg")||e.equals("png")||e.equals("gif")||e.equals("webp")||e.equals("bmp")) return "Images";
		if (e.equals("mp4")||e.equals("mkv")||e.equals("avi")||e.equals("mov")||e.equals("webm")||e.equals("3gp")) return "Video";
		if (e.equals("mp3")||e.equals("wav")||e.equals("m4a")||e.equals("aac")||e.equals("flac")||e.equals("ogg")) return "Audio";
		if (e.equals("pdf")||e.equals("txt")||e.equals("doc")||e.equals("docx")||e.equals("xls")||e.equals("xlsx")||e.equals("ppt")||e.equals("pptx")||e.equals("csv")) return "Documents";
		if (e.equals("apk")) return "APK";
		if (e.equals("zip")||e.equals("rar")||e.equals("7z")||e.equals("tar")||e.equals("gz")||e.equals("tgz")) return "Archives";
		return "Other";
	}

	void showStorageAnalyzer(final File root) {
		if (root == null || !root.isDirectory() || root instanceof MegaItem || root instanceof ZipItem) { toast("Select a local folder"); return; }
		final boolean[] cancel={false}; final TextView progress=new TextView(this); progress.setPadding(dp*20,dp*14,dp*20,dp*14); progress.setText("Scanning..."); themeDialogView(progress);
		final AlertDialog scan=createDialog("Storage analyzer", null).setView(progress).setNegativeButton("Cancel", new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){cancel[0]=true;}}).create(); scan.show();
		new Thread(new Runnable(){ public void run(){
			final long[] t=new long[4]; final long[] cat=new long[7]; final String[] cn={"Images","Video","Audio","Documents","APK","Archives","Other"};
			final ArrayList<File> biggest=new ArrayList<File>(); ArrayList<File> stack=new ArrayList<File>(); stack.add(root); long last=0;
			while(!stack.isEmpty()&&!cancel[0]){ File d=stack.remove(stack.size()-1); if(isSymlink(d)) continue; t[1]++; File[] c=d.listFiles(); if(c==null)continue; for(int i=0;i<c.length&&!cancel[0];i++){ File q=c[i]; if(q.isDirectory()&&!isSymlink(q)) stack.add(q); else if(q.isFile()){ t[0]++; long z=Math.max(0,q.length()); t[2]+=z; String k=fileCategory(q); for(int j=0;j<cn.length;j++)if(cn[j].equals(k)){cat[j]+=z;break;} int pos=0; while(pos<biggest.size()&&biggest.get(pos).length()>=z)pos++; biggest.add(pos,q); if(biggest.size()>10)biggest.remove(biggest.size()-1); if(System.currentTimeMillis()-last>300){last=System.currentTimeMillis(); final long fc=t[0],bc=t[2]; uiPost(new Runnable(){public void run(){if(!cancel[0])progress.setText("Scanning...\n"+fc+" files\n"+human(bc));}}); } } } }
			if(cancel[0])return; final StringBuilder m=new StringBuilder(); m.append("Files: ").append(t[0]).append("\nFolders: ").append(t[1]).append("\nTotal size: ").append(human(t[2])).append("\n\nCategories:\n");
			for(int j=0;j<cn.length;j++){ if(cat[j]>0){ long pc=t[2]==0?0:(cat[j]*100/t[2]); m.append(cn[j]).append(": ").append(human(cat[j])).append(" (").append(pc).append("%)\n"); }}
			m.append("\nLargest files:\n"); for(int j=0;j<biggest.size();j++)m.append(j+1).append(". ").append(human(biggest.get(j).length())).append("  ").append(biggest.get(j).getAbsolutePath()).append("\n");
			uiPost(new Runnable(){public void run(){try{scan.dismiss();}catch(Exception e){} showMessageDialog("Storage analyzer",m.toString());}});
		}}).start();
	}

	void showDuplicateFinder(final File root) {
		if(root==null||!root.isDirectory()||root instanceof MegaItem||root instanceof ZipItem){toast("Select a local folder");return;}
		final boolean[] cancel={false}; final TextView progress=new TextView(this); progress.setPadding(dp*20,dp*14,dp*20,dp*14); progress.setText("Scanning file sizes..."); themeDialogView(progress);
		final AlertDialog scan=createDialog("Duplicate finder",null).setView(progress).setNegativeButton("Cancel",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){cancel[0]=true;}}).create(); scan.show();
		new Thread(new Runnable(){public void run(){
			HashMap<Long,ArrayList<File> > sizes=new HashMap<Long,ArrayList<File> >(); ArrayList<File> stack=new ArrayList<File>(); stack.add(root); long files=0;
			while(!stack.isEmpty()&&!cancel[0]){File d=stack.remove(stack.size()-1);if(isSymlink(d))continue;File[] a=d.listFiles();if(a==null)continue;for(int i=0;i<a.length;i++){File f=a[i];if(f.isDirectory()&&!isSymlink(f))stack.add(f);else if(f.isFile()&&f.length()>0){files++;Long z=Long.valueOf(f.length());ArrayList<File> g=sizes.get(z);if(g==null){g=new ArrayList<File>();sizes.put(z,g);}g.add(f);}}}
			if(cancel[0])return; HashMap<String,ArrayList<File> > groups=new HashMap<String,ArrayList<File> >(); long hashed=0;
			for(Map.Entry<Long,ArrayList<File> > e:sizes.entrySet()){ArrayList<File> g=e.getValue();if(g.size()<2)continue;for(int i=0;i<g.size()&&!cancel[0];i++){File f=g.get(i);try{String h=e.getKey()+":"+digestFile(f,"SHA-256");ArrayList<File> q=groups.get(h);if(q==null){q=new ArrayList<File>();groups.put(h,q);}q.add(f);}catch(Exception ex){} hashed++; if(hashed%5==0){final long hh=hashed;uiPost(new Runnable(){public void run(){if(!cancel[0])progress.setText("Verifying same-size files...\nHashed: "+hh);}});}}}
			if(cancel[0])return; final StringBuilder out=new StringBuilder(); long reclaim=0;int ng=0;for(Map.Entry<String,ArrayList<File> > e:groups.entrySet()){ArrayList<File> g=e.getValue();if(g.size()<2)continue;ng++;long z=g.get(0).length();reclaim+=z*(g.size()-1);out.append("Group ").append(ng).append(" - ").append(human(z)).append(" each\n");for(int i=0;i<g.size();i++)out.append("  ").append(g.get(i).getAbsolutePath()).append("\n");out.append("\n");}
			final int fg=ng;final long fr=reclaim,ff=files;uiPost(new Runnable(){public void run(){try{scan.dismiss();}catch(Exception e){} if(fg==0)showMessageDialog("Duplicate finder","No duplicate files found.\nScanned "+ff+" files.");else showMessageDialog("Duplicate finder",fg+" duplicate groups\nPotentially reclaimable: "+human(fr)+"\n\n"+out.toString()+"\nNothing is deleted automatically.");}});
		}}).start();
	}

	void batchRename() {
		final ArrayList<File> items=multiItems(); if(items.isEmpty()){toast("Nothing selected");return;}
		for(int i=0;i<items.size();i++) if(items.get(i) instanceof MegaItem || items.get(i) instanceof ZipItem){toast("Batch rename supports local items only");return;}
		final EditText input=new EditText(this); input.setHint("Prefix (example: trip_)"); input.setSingleLine(true); themeDialogView(input);
		createDialog("Batch rename", "Add a prefix to " + items.size() + " selected items. Existing names are kept.").setView(input).setPositiveButton("Rename", new DialogInterface.OnClickListener(){ public void onClick(DialogInterface d,int w){ String pre=input.getText().toString(); if(pre.length()==0){toast("Prefix is empty");return;} int ok=0,bad=0; for(int i=0;i<items.size();i++){File f=items.get(i); File to=new File(f.getParentFile(),pre+f.getName()); if(to.exists()||!f.renameTo(to))bad++;else ok++;} exitMulti(); listCache.clear(); refresh(); toast("Renamed "+ok+(bad>0?", failed "+bad:"")); }}).setNegativeButton("Cancel",null).show();
	}

	void testArchive(final File item) {
		if(item==null||item instanceof MegaItem){toast("Select a local ZIP/APK");return;} final File z=item instanceof ZipItem?((ZipItem)item).zip:item;
		if(z==null||!z.isFile()||!ZipItem.isZipName(z.getName())){toast("Select a ZIP/APK file");return;}
		final boolean[] cancel={false};final TextView p=new TextView(this);p.setPadding(20*dp,14*dp,20*dp,14*dp);p.setText("Testing archive...");themeDialogView(p);
		final AlertDialog dlg=createDialog("Archive integrity",null).setView(p).setNegativeButton("Cancel",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){cancel[0]=true;}}).create();dlg.show();
		new Thread(new Runnable(){public void run(){String result;ZipFile zip=null;try{zip=new ZipFile(z);java.util.Enumeration<? extends ZipEntry> en=zip.entries();byte[] b=new byte[65536];int entries=0;long bytes=0;while(en.hasMoreElements()&&!cancel[0]){ZipEntry e=en.nextElement();entries++;if(!e.isDirectory()){InputStream in=zip.getInputStream(e);try{int n;while((n=in.read(b))>0){bytes+=n;if(cancel[0])break;}}finally{in.close();}}if(entries%20==0){final int fe=entries;final long fb=bytes;uiPost(new Runnable(){public void run(){if(!cancel[0])p.setText("Testing...\n"+fe+" entries\n"+human(fb)+" read");}});}}result=cancel[0]?"Cancelled":"Archive passed integrity test.\nEntries tested: "+entries+"\nUncompressed data read: "+human(bytes);}catch(Exception e){result="Archive test FAILED.\n"+(e.getMessage()==null?e.toString():e.getMessage());}finally{if(zip!=null)try{zip.close();}catch(Exception e){}}final String r=result;uiPost(new Runnable(){public void run(){try{dlg.dismiss();}catch(Exception e){}if(!cancel[0])showMessageDialog("Archive integrity - "+z.getName(),r);}});}}).start();
	}

	String yesNo(boolean v) { return v ? "Yes" : "No"; }

	String mimeFor(File f) {
		String e = extOf(f);
		String m = e.length() == 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(e);
		return m == null ? "Unknown" : m;
	}

	String digestBytes(byte[] data, String alg) throws Exception {
		MessageDigest md = MessageDigest.getInstance(alg);
		byte[] d = md.digest(data);
		StringBuilder x = new StringBuilder();
		for (int i = 0; i < d.length; i++) {
			String h = Integer.toHexString(d[i] & 255);
			if (h.length() < 2) x.append('0');
			x.append(h);
		}
		return x.toString();
	}

	String imageDetails(File f) {
		String e = extOf(f);
		if (!(e.equals("jpg") || e.equals("jpeg") || e.equals("png") || e.equals("gif") || e.equals("webp") || e.equals("bmp"))) return "";
		try {
			BitmapFactory.Options o = new BitmapFactory.Options();
			o.inJustDecodeBounds = true;
			BitmapFactory.decodeFile(f.getAbsolutePath(), o);
			if (o.outWidth > 0 && o.outHeight > 0) return "\nDimensions: " + o.outWidth + " x " + o.outHeight;
		} catch (Exception ex) {}
		return "";
	}

	String zipDetails(File f) {
		if (!ZipItem.isZipName(f.getName())) return "";
		ZipFile z = null;
		try {
			z = new ZipFile(f);
			int files = 0, folders = 0;
			long unpacked = 0;
			Enumeration<? extends ZipEntry> en = z.entries();
			while (en.hasMoreElements()) {
				ZipEntry e = en.nextElement();
				if (e.isDirectory()) folders++; else { files++; if (e.getSize() > 0) unpacked += e.getSize(); }
			}
			String ratio = f.length() > 0 && unpacked > 0 ? "\nCompression ratio: " + ((f.length() * 100L) / unpacked) + "% of original" : "";
			return "\nArchive entries: " + files + " files, " + folders + " folders\nUncompressed size: " + human(unpacked) + ratio;
		} catch (Exception ex) {
			return "\nArchive status: Cannot read archive (" + (ex.getMessage() == null ? "error" : ex.getMessage()) + ")";
		} finally { try { if (z != null) z.close(); } catch (Exception ex) {} }
	}

	String apkDetails(File apk) {
		if (!extOf(apk).equals("apk")) return "";
		StringBuilder m = new StringBuilder();
		try {
			PackageManager pm = getPackageManager();
			PackageInfo pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_PERMISSIONS | PackageManager.GET_SIGNATURES);
			if (pi == null) return "\nAPK: Package information unavailable";
			ApplicationInfo ai = pi.applicationInfo;
			if (ai != null) { ai.sourceDir = apk.getAbsolutePath(); ai.publicSourceDir = apk.getAbsolutePath(); }
			String label = null;
			try { if (ai != null) label = String.valueOf(pm.getApplicationLabel(ai)); } catch (Exception ex) {}
			if (label != null && label.length() > 0) m.append("\nApp name: ").append(label);
			m.append("\nPackage: ").append(pi.packageName == null ? "Unknown" : pi.packageName);
			m.append("\nVersion: ").append(pi.versionName == null ? "Unknown" : pi.versionName).append(" (").append(pi.versionCode).append(")");
			if (ai != null) {
				if (Build.VERSION.SDK_INT >= 24) m.append("\nMin SDK: ").append(ai.minSdkVersion);
				m.append("\nTarget SDK: ").append(ai.targetSdkVersion);
			}
			boolean installed = false;
			try { pm.getPackageInfo(pi.packageName, 0); installed = true; } catch (Exception ex) {}
			m.append("\nInstalled: ").append(yesNo(installed));
			if (pi.requestedPermissions != null) {
				m.append("\nPermissions: ").append(pi.requestedPermissions.length);
				int lim = Math.min(pi.requestedPermissions.length, 12);
				for (int i = 0; i < lim; i++) m.append("\n  ").append(pi.requestedPermissions[i]);
				if (pi.requestedPermissions.length > lim) m.append("\n  ... +").append(pi.requestedPermissions.length - lim).append(" more");
			}
			if (pi.signatures != null && pi.signatures.length > 0) {
				m.append("\nSignatures: ").append(pi.signatures.length);
				m.append("\nCertificate SHA-256: ").append(digestBytes(pi.signatures[0].toByteArray(), "SHA-256"));
			}
		} catch (Exception ex) { m.append("\nAPK info error: ").append(ex.getMessage() == null ? ex.toString() : ex.getMessage()); }
		return m.toString();
	}

	void showLocalAdvancedInfo(final File f) {
		final AlertDialog wait = busyDialog("Info", f.isDirectory() ? "Calculating folder information..." : "Reading file information...");
		new Thread(new Runnable() { public void run() {
			DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
			final StringBuilder m = new StringBuilder();
			m.append("Path: ").append(f.getAbsolutePath());
			m.append("\nType: ").append(f.isDirectory() ? "Folder" : "File");
			if (!f.isDirectory()) m.append("\nMIME: ").append(mimeFor(f));
			if (f.isDirectory()) {
				long[] t = new long[3];
				try { tally(f, t); } catch (Exception ex) {}
				m.append("\nFiles: ").append(t[0]).append("\nFolders: ").append(t[1]).append("\nTotal size: ").append(human(t[2]));
			} else m.append("\nSize: ").append(human(f.length())).append(" (").append(f.length()).append(" bytes)");
			m.append("\nModified: ").append(df.format(new Date(f.lastModified())));
			m.append("\nReadable: ").append(yesNo(f.canRead())).append("\nWritable: ").append(yesNo(f.canWrite()));
			m.append("\nHidden: ").append(yesNo(f.isHidden()));
			if (!f.isDirectory()) {
				m.append(imageDetails(f));
				m.append(zipDetails(f));
				m.append(apkDetails(f));
				try { m.append("\n\nSHA-256:\n").append(digestFile(f, "SHA-256")); } catch (Exception ex) { m.append("\n\nSHA-256: unavailable"); }
				try { m.append("\n\nMD5:\n").append(digestFile(f, "MD5")); } catch (Exception ex) { m.append("\n\nMD5: unavailable"); }
			}
			uiPost(new Runnable() { public void run() {
				try { wait.dismiss(); } catch (Exception ex) {}
				createDialog(f.getName(), m.toString()).setPositiveButton("Copy", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
					ClipboardManager cm = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
					cm.setPrimaryClip(ClipData.newPlainText("File information", m.toString())); toast("Information copied");
				}}).setNegativeButton("Close", null).show();
			} });
		} }).start();
	}

	void info() {
		DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
		if (selected instanceof DexItem) {
			DexItem di = (DexItem) selected;
			String kind = di.isDexRoot() ? "Dex file" : di.kind == 1 ? "Class" : di.kind == 2 ? "Fields" : di.kind == 3 ? "Methods" : "Field or method";
			StringBuilder msg = new StringBuilder();
			msg.append("In: ").append(di.zip.getAbsolutePath()).append("!/").append(di.dexEntry).append("\nType: ").append(kind);
			if (!di.isDexRoot()) msg.append("\nPath: ").append(di.virtualPath);
			if (di.kind == 4) msg.append("\n\n").append(di.getName());
			msg.append("\nBrowse-only"); showMessageDialog(di.getName(), msg.toString()); return;
		}
		if (selected instanceof ZipItem) {
			ZipItem zi = (ZipItem) selected;
			if (zi.isRoot()) { showLocalAdvancedInfo(zi.zip); return; }
			showMessageDialog(zi.getName(), "In zip: " + zi.zip.getAbsolutePath() + "\nEntry: " + zi.entry + "\nType: " + (zi.isDirectory() ? "Folder" : "File") + (zi.isDirectory() ? "" : "\nSize: " + human(zi.length())) + "\nModified: " + df.format(new Date(zi.lastModified())) + "\nRead-only"); return;
		}
		if (selected instanceof MegaItem) {
			showMessageDialog(selected.getName(), "Path: " + selected.getAbsolutePath() + "\nType: " + (selected.isDirectory() ? "Folder" : "File") + "\nSize: " + human(selected.length()) + "\nModified: " + df.format(new Date(selected.lastModified())) + "\nCloud item"); return;
		}
		showLocalAdvancedInfo(selected);
	}

	void requestAccess() {
		if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
			try {
				Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
				i.setData(Uri.parse("package:" + getPackageName()));
				startActivity(i);
			} catch (Exception e) {
				startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
			}
		} else if (Build.VERSION.SDK_INT >= 23) {
			requestPermissions(new String[]{"android.permission.READ_EXTERNAL_STORAGE",
					"android.permission.WRITE_EXTERNAL_STORAGE"}, 7);
		}
	}

	protected void onResume() {
		super.onResume();
		volHandler.removeCallbacks(volPoll);
		volHandler.post(volPoll);
		if (leftTree != null)
			refresh();
	}
	void toast(final String s) {
		uiPost(new Runnable() {
			public void run() {
				Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show();
			}
		});
	}
}

