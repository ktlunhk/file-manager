package com.example.dualfilemanager;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.util.TypedValue;
import android.text.Editable;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.text.*;
import java.util.*;
import java.util.zip.*;

class PreviewManager {
	private static final long PREVIEW_TEXT_MAX = 2L * 1024L * 1024L;
    private final MainActivity activity;
    private boolean showingPreview;
    private int previewGen;
    private Runnable previewCleanup;
    private TextViewer activeViewer;

    PreviewManager(MainActivity activity) { this.activity = activity; }

    boolean isShowingPreview() { return showingPreview; }

    /** Lets the text viewer swallow Back to close its search bar first. */
    boolean handleBack() {
        if (activeViewer != null && activeViewer.searchBar != null
                && activeViewer.searchBar.getVisibility() == View.VISIBLE) {
            activeViewer.closeSearch();
            return true;
        }
        return false;
    }

    void onMainShown() {
        showingPreview = false;
        previewGen++;
        if (previewCleanup != null) {
            previewCleanup.run();
            previewCleanup = null;
        }
    }

    void preview(final File file) {
        if (file instanceof ZipItem) { previewZipEntry((ZipItem) file); return; }
        String name = file.getName();
        if (isImageFile(name)) showImagePreview(file);
        else if (isCsvFile(name)) showCsvPreview(file);
        else if (isTextFile(name)) showTextPreview(file);
        else if (isVideoFile(name)) showVideoPreview(file);
        else if (isAudioFile(name)) showAudioPreview(file);
        else if (isPdfFile(name)) showPdfPreview(file);
        else if (isApkFile(name)) showApkPreview(file);
        else activity.openItem(file, false);
    }

	boolean isImageFile(String name) {
		int dot = name.lastIndexOf('.');
		if (dot < 0)
			return false;
		String ext = name.substring(dot + 1).toLowerCase(Locale.US);
		return ext.matches("jpg|jpeg|png|gif|bmp|webp");
	}

	boolean isTextFile(String name) {
		int dot = name.lastIndexOf('.');
		if (dot < 0)
			return false;
		String ext = name.substring(dot + 1).toLowerCase(Locale.US);
		return ext.matches("txt|md|markdown|java|kt|kts|py|js|jsx|ts|tsx|json|xml|html|htm|css"
				+ "|c|cpp|h|hpp|sh|bash|gradle|properties|ini|yaml|yml|log|conf|cfg|toml|rs|go|rb|php|sql");
	}

	boolean isCsvFile(String name) {
		int dot = name.lastIndexOf('.');
		return dot >= 0 && name.substring(dot + 1).equalsIgnoreCase("csv");
	}

	boolean isApkFile(String name) {
		int dot = name.lastIndexOf('.');
		return dot >= 0 && name.substring(dot + 1).equalsIgnoreCase("apk");
	}

	
	boolean isVideoFile(String name) {
		int dot = name.lastIndexOf('.');
		if (dot < 0)
			return false;
		String ext = name.substring(dot + 1).toLowerCase(Locale.US);
		return ext.matches("mp4|m4v|3gp|3g2|webm|mkv|ts");
	}


	boolean isAudioFile(String name) {
		int dot = name.lastIndexOf('.');
		if (dot < 0)
			return false;
		String ext = name.substring(dot + 1).toLowerCase(Locale.US);
		return ext.matches("mp3|m4a|aac|wav|ogg|flac");
	}

	boolean isPdfFile(String name) {
		int dot = name.lastIndexOf('.');
		return dot >= 0 && name.substring(dot + 1).equalsIgnoreCase("pdf");
	}

	void previewZipEntry(final ZipItem zi) {
		final File dir = new File(activity.getCacheDir(), "preview/" + Integer.toHexString(zi.getAbsolutePath().hashCode()));
		final File tmp = new File(dir, zi.getName());
		activity.toast("Extracting...");
		new Thread(new Runnable() {
			public void run() {
				String err = null;
				try { dir.mkdirs(); ZipItem.extract(zi, tmp); }
				catch (Exception e) { err = "Error: " + e.getMessage(); }
				final String m = err;
				activity.runOnUiThread(new Runnable() {
					public void run() {
						if (m != null) activity.toast(m); else preview(tmp);
					}
				});
			}
		}).start();
	}

	void showImagePreview(final File f) {
		FrameLayout holder = new FrameLayout(activity);
		holder.setBackgroundColor(activity.colSurface);
		ProgressBar spinner = new ProgressBar(activity);
		FrameLayout.LayoutParams spinnerLp = new FrameLayout.LayoutParams(-2, -2);
		spinnerLp.gravity = Gravity.CENTER;
		holder.addView(spinner, spinnerLp);
		showPreviewScreen(f.getName(), holder, f);
		final FrameLayout fHolder = holder;
		final int gen = previewGen; // showPreviewScreen() just bumped this

		new Thread(new Runnable() {
			public void run() {
				String err = null;
				android.graphics.Bitmap bmp = null;
				try {
					android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
					bounds.inJustDecodeBounds = true;
					android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
					if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
						err = "Can't preview this image";
					} else {
						android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
						int targetW = dm.widthPixels, targetH = dm.heightPixels;
						int sample = 1;
						while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH)
							sample *= 2;
						android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
						opts.inSampleSize = sample;
						bmp = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
						if (bmp == null)
							err = "Can't preview this image";
					}
				} catch (OutOfMemoryError e) {
					err = "Image too large to preview";
				} catch (Exception e) {
					err = "Can't preview this image";
				}
				final String ferr = err;
				final android.graphics.Bitmap fbmp = bmp;
				activity.runOnUiThread(new Runnable() {
					public void run() {
					
						if (gen != previewGen)
							return;
						if (ferr != null) {
							activity.toast(ferr);
							activity.showMain();
							activity.openItem(f, false);
							return;
						}
						fHolder.removeAllViews();
						fHolder.addView(new ZoomImageView(activity, fbmp), new FrameLayout.LayoutParams(-1, -1));
					}
				});
			}
		}).start();
	}

	void showCsvPreview(final File f) {
		final FrameLayout holder = new FrameLayout(activity);
		holder.setBackgroundColor(activity.colSurface);
		ProgressBar spinner = new ProgressBar(activity);
		FrameLayout.LayoutParams spinnerLp = new FrameLayout.LayoutParams(-2, -2);
		spinnerLp.gravity = Gravity.CENTER;
		holder.addView(spinner, spinnerLp);

		showPreviewScreen(f.getName(), holder, f);
		final int gen = previewGen;

		new Thread(new Runnable() {
			public void run() {
				String err = null;
				List<String[]> rows = null;
				try {
					if (f.length() > PREVIEW_TEXT_MAX)
						throw new IOException("File too large to preview as a table - opening externally");
					byte[] bytes = readAllBytes(f);
					if (looksBinary(bytes))
						throw new IOException("This looks like a binary file, not a CSV - opening externally");
					rows = parseCsv(new String(bytes, "UTF-8"));
				} catch (Exception e) {
					err = e.getMessage() == null ? "Can't read this file" : e.getMessage();
				}
				final String errF = err;
				final List<String[]> rowsF = rows;
				activity.runOnUiThread(new Runnable() {
					public void run() {
						if (gen != previewGen)
							return;
						if (errF != null || rowsF == null || rowsF.isEmpty()) {
							activity.toast(errF != null ? errF : "Empty file");
							activity.showMain();
							activity.openItem(f, false);
							return;
						}
						holder.removeAllViews();

						// Horizontal scrolling wraps BOTH the fixed header and the body, so
						// they always move together left/right.  Only the body is inside the
						// vertical ScrollView, which keeps row 0 visible while scrolling down.
						HorizontalScrollView hScroll = new HorizontalScrollView(activity);
						hScroll.setFillViewport(true);
						LinearLayout csvColumn = new LinearLayout(activity);
						csvColumn.setOrientation(LinearLayout.VERTICAL);
						csvColumn.setBackgroundColor(activity.colSurface);

						List<TextView> cells = new ArrayList<TextView>();
						int[] widths = getCsvColumnWidths(rowsF);
						TableLayout header = buildCsvTable(rowsF, 0, 1, cells, widths, true);
						csvColumn.addView(header, new LinearLayout.LayoutParams(-2, -2));

						ScrollView vScroll = new ScrollView(activity);
						vScroll.setFillViewport(true);
						TableLayout body = buildCsvTable(rowsF, 1, Math.min(rowsF.size(), 2000), cells, widths, false);
						vScroll.addView(body, new ScrollView.LayoutParams(-2, -2));
						LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-2, 0, 1f);
						csvColumn.addView(vScroll, bodyLp);

						hScroll.addView(csvColumn, new HorizontalScrollView.LayoutParams(-2, -1));
						ZoomTextContainer zoomWrap = new ZoomTextContainer(activity, cells);
						zoomWrap.addView(hScroll, new FrameLayout.LayoutParams(-1, -1));
						holder.addView(zoomWrap, new FrameLayout.LayoutParams(-1, -1));
					}
				});
			}
		}).start();
	}

	int[] getCsvColumnWidths(List<String[]> rows) {
		int maxCols = 0;
		for (int i = 0; i < rows.size() && i < 2000; i++)
			maxCols = Math.max(maxCols, rows.get(i).length);
		int[] widths = new int[maxCols];
		for (int c = 0; c < maxCols; c++)
			widths[c] = 64 * activity.dp;
		for (int i = 0; i < rows.size() && i < 2000; i++) {
			String[] r = rows.get(i);
			for (int c = 0; c < r.length; c++) {
				String value = r[c] == null ? "" : r[c];
				int chars = Math.min(value.length(), 30);
				int wanted = (chars * 8 + 28) * activity.dp;
				if (wanted > widths[c]) widths[c] = wanted;
				if (widths[c] > 240 * activity.dp) widths[c] = 240 * activity.dp;
			}
		}
		return widths;
	}

	TableLayout buildCsvTable(List<String[]> rows, int from, int to, List<TextView> outCells,
			int[] widths, boolean headerTable) {
		TableLayout table = new TableLayout(activity);
		table.setBackgroundColor(activity.colSurface);
		int maxCols = widths.length;
		for (int i = from; i < to; i++) {
			String[] r = rows.get(i);
			boolean header = headerTable && i == 0;
			TableRow tr = new TableRow(activity);
			tr.setBackgroundColor(header ? activity.colSurfaceAlt : (i % 2 == 0 ? activity.colSurface : activity.colSurfaceAlt));
			for (int c = 0; c < maxCols; c++) {
				TextView cell = new TextView(activity);
				cell.setText(c < r.length ? r[c] : "");
				cell.setTextSize(12);
				cell.setTextColor(activity.colText);
				cell.setTypeface(header ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
				cell.setPadding(12 * activity.dp, 8 * activity.dp, 12 * activity.dp, 8 * activity.dp);
				cell.setSingleLine(true);
				cell.setEllipsize(android.text.TextUtils.TruncateAt.END);
				TableRow.LayoutParams cp = new TableRow.LayoutParams(widths[c], -2);
				tr.addView(cell, cp);
				outCells.add(cell);
			}
			table.addView(tr, new TableLayout.LayoutParams(-2, -2));
		}
		if (!headerTable && rows.size() > 2000) {
			TextView more = new TextView(activity);
			more.setText("\u2026 " + (rows.size() - 2000) + " more rows not shown");
			more.setTextColor(activity.colTextMuted);
			more.setTextSize(11);
			more.setPadding(12 * activity.dp, 10 * activity.dp, 12 * activity.dp, 10 * activity.dp);
			table.addView(more, new TableLayout.LayoutParams(-2, -2));
		}
		return table;
	}

	List<String[]> parseCsv(String content) {
		List<String[]> rows = new ArrayList<String[]>();
		List<String> cur = new ArrayList<String>();
		StringBuilder field = new StringBuilder();
		boolean inQuotes = false;
		int i = 0, n = content.length();
		while (i < n) {
			char c = content.charAt(i);
			if (inQuotes) {
				if (c == '"') {
					if (i + 1 < n && content.charAt(i + 1) == '"') {
						field.append('"');
						i++;
					} else
						inQuotes = false;
				} else
					field.append(c);
			} else {
				if (c == '"')
					inQuotes = true;
				else if (c == ',') {
					cur.add(field.toString());
					field.setLength(0);
				} else if (c == '\n' || c == '\r') {
					if (c == '\r' && i + 1 < n && content.charAt(i + 1) == '\n')
						i++;
					cur.add(field.toString());
					field.setLength(0);
					rows.add(cur.toArray(new String[0]));
					cur = new ArrayList<String>();
				} else
					field.append(c);
			}
			i++;
		}
		if (field.length() > 0 || !cur.isEmpty()) {
			cur.add(field.toString());
			rows.add(cur.toArray(new String[0]));
		}
		return rows;
	}

	void showApkPreview(final File f) {
		final FrameLayout holder = new FrameLayout(activity);
		holder.setBackgroundColor(activity.colSurface);
		ProgressBar spinner = new ProgressBar(activity);
		FrameLayout.LayoutParams spinnerLp = new FrameLayout.LayoutParams(-2, -2);
		spinnerLp.gravity = Gravity.CENTER;
		holder.addView(spinner, spinnerLp);

		showPreviewScreen(f.getName(), holder, f);
		final int gen = previewGen;

		new Thread(new Runnable() {
			public void run() {
				String err = null;
				android.graphics.drawable.Drawable icon = null;
				String label = null, pkg = null, versionName = null;
				long versionCode = -1;
				String[] perms = null;
				try {
					android.content.pm.PackageManager pm = activity.getPackageManager();
					android.content.pm.PackageInfo info = pm.getPackageArchiveInfo(f.getAbsolutePath(),
							android.content.pm.PackageManager.GET_PERMISSIONS);
					if (info == null)
						throw new Exception("Not a valid APK");
					info.applicationInfo.sourceDir = f.getAbsolutePath();
					info.applicationInfo.publicSourceDir = f.getAbsolutePath();
					icon = pm.getApplicationIcon(info.applicationInfo);
					label = String.valueOf(pm.getApplicationLabel(info.applicationInfo));
					pkg = info.packageName;
					versionName = info.versionName;
					versionCode = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
					perms = info.requestedPermissions;
				} catch (Exception e) {
					err = e.getMessage() == null ? "Can't read this APK" : e.getMessage();
				}
				final String errF = err;
				final android.graphics.drawable.Drawable iconF = icon;
				final String labelF = label, pkgF = pkg, versionNameF = versionName;
				final long versionCodeF = versionCode;
				final String[] permsF = perms;
				activity.runOnUiThread(new Runnable() {
					public void run() {
						if (gen != previewGen)
							return; // preview screen was left while this was parsing
						if (errF != null) {
							activity.toast(errF);
							activity.showMain();
							activity.openItem(f, false);
							return;
						}
						holder.removeAllViews();
						holder.addView(buildApkInfoView(f, iconF, labelF, pkgF, versionNameF, versionCodeF, permsF),
								new FrameLayout.LayoutParams(-1, -1));
					}
				});
			}
		}).start();
	}

	View buildApkInfoView(File f, android.graphics.drawable.Drawable icon, String label, String pkg,
			String versionName, long versionCode, String[] perms) {
		ScrollView sv = new ScrollView(activity);
		sv.setFillViewport(true);
		sv.setBackgroundColor(activity.colSurface);

		LinearLayout col = new LinearLayout(activity);
		col.setOrientation(LinearLayout.VERTICAL);
		col.setPadding(24 * activity.dp, 24 * activity.dp, 24 * activity.dp, 24 * activity.dp);

		ImageView iconView = new ImageView(activity);
		iconView.setImageDrawable(icon);
		LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(72 * activity.dp, 72 * activity.dp);
		iconLp.gravity = Gravity.CENTER_HORIZONTAL;
		col.addView(iconView, iconLp);

		TextView labelTv = new TextView(activity);
		labelTv.setText(label == null ? f.getName() : label);
		labelTv.setTextColor(activity.colText);
		labelTv.setTextSize(18);
		labelTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
		labelTv.setGravity(Gravity.CENTER);
		labelTv.setPadding(0, 14 * activity.dp, 0, 2 * activity.dp);
		col.addView(labelTv, new LinearLayout.LayoutParams(-1, -2));

		TextView pkgTv = new TextView(activity);
		pkgTv.setText(pkg == null ? "" : pkg);
		pkgTv.setTextColor(activity.colTextMuted);
		pkgTv.setTextSize(12);
		pkgTv.setGravity(Gravity.CENTER);
		col.addView(pkgTv, new LinearLayout.LayoutParams(-1, -2));

		TextView metaTv = new TextView(activity);
		StringBuilder meta = new StringBuilder();
		if (versionName != null)
			meta.append("Version ").append(versionName);
		if (versionCode >= 0)
			meta.append(meta.length() > 0 ? "  (" + versionCode + ")" : "Version code " + versionCode);
		meta.append(meta.length() > 0 ? "   \u00b7   " : "").append(activity.human(f.length()));
		metaTv.setText(meta.toString());
		metaTv.setTextColor(activity.colTextMuted);
		metaTv.setTextSize(12);
		metaTv.setGravity(Gravity.CENTER);
		metaTv.setPadding(0, 4 * activity.dp, 0, 20 * activity.dp);
		col.addView(metaTv, new LinearLayout.LayoutParams(-1, -2));

		TextView installBtn = new TextView(activity);
		installBtn.setText("Install");
		installBtn.setTextColor(Color.WHITE);
		installBtn.setTextSize(14);
		installBtn.setGravity(Gravity.CENTER);
		installBtn.setBackgroundColor(Color.rgb(45, 105, 160));
		installBtn.setPadding(0, 12 * activity.dp, 0, 12 * activity.dp);
		activity.applyRipple(installBtn);
		final File apkFile = f;
		installBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				activity.openItem(apkFile, false); // hands off to the system package installer
			}
		});
		col.addView(installBtn, new LinearLayout.LayoutParams(-1, -2));

		TextView permsHeader = new TextView(activity);
		permsHeader.setText(perms == null || perms.length == 0 ? "No permissions requested"
				: "Permissions requested (" + perms.length + ")");
		permsHeader.setTextColor(activity.colText);
		permsHeader.setTextSize(13);
		permsHeader.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
		permsHeader.setPadding(0, 24 * activity.dp, 0, 8 * activity.dp);
		col.addView(permsHeader, new LinearLayout.LayoutParams(-1, -2));

		if (perms != null) {
		
			for (String p : perms) {
				TextView permTv = new TextView(activity);
				int dot = p.lastIndexOf('.');
				permTv.setText("\u2022  " + (dot >= 0 ? p.substring(dot + 1) : p));
				permTv.setTextColor(activity.colTextMuted);
				permTv.setTextSize(12);
				permTv.setPadding(4 * activity.dp, 3 * activity.dp, 0, 3 * activity.dp);
				col.addView(permTv, new LinearLayout.LayoutParams(-1, -2));
			}
		}

		sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
		return sv;
	}

	void showTextPreview(final File f) {
		if (f.length() > PREVIEW_TEXT_MAX) {
			activity.toast("File too large to preview inline - opening externally");
			activity.openItem(f, false);
			return;
		}
		byte[] bytes;
		try {
			bytes = readAllBytes(f);
		} catch (Exception e) {
			activity.toast("Can't preview this file");
			activity.openItem(f, false);
			return;
		}
		String content;
		if (looksBinary(bytes)) {
			
			String decoded = null;
			if (f.getName().toLowerCase(Locale.US).endsWith(".xml")) {
				try {
					decoded = decodeAxml(bytes);
				} catch (Exception e) {
					decoded = null;
				}
			}
			if (decoded == null) {
				activity.toast("This looks like a binary file, not text - opening externally");
				activity.openItem(f, false);
				return;
			}
			content = decoded;
			activity.toast("Decoded from compiled binary XML (reconstructed, not the exact original formatting)");
		} else {
			try {
				content = new String(bytes, "UTF-8");
			} catch (Exception e) {
				activity.toast("Can't preview this file");
				activity.openItem(f, false);
				return;
			}
		}
		String nm = f.getName();
		int dot = nm.lastIndexOf('.');
		String ext = dot >= 0 ? nm.substring(dot + 1) : "";
		new TextViewer(f, content, ext).show();
	}

	/** Text preview with wrap toggle, find-in-text, copy and syntax colouring. */
	class TextViewer {
		final File file;
		final String content, ext;
		PreviewTextView tv;
		LineNumberView lineNums;
		TextView wrapBtn, countTv;
		LinearLayout searchBar;
		EditText searchBox;
		boolean wrap;
		final ArrayList<Integer> hits = new ArrayList<Integer>();
		final ArrayList<Object> hitSpans = new ArrayList<Object>();
		Object curSpan;
		int hitLen, cur = -1;
		boolean capped;
		static final int MAX_HITS = 5000;

		TextViewer(File f, String content, String ext) {
			this.file = f;
			this.content = content;
			this.ext = ext;
		}

		TextView toolBtn(String label) {
			TextView b = new TextView(activity);
			b.setText(label);
			b.setTextSize(13);
			b.setTextColor(activity.colText);
			b.setGravity(Gravity.CENTER);
			b.setPadding(10 * activity.dp, 0, 10 * activity.dp, 0);
			activity.applyRipple(b);
			return b;
		}

		void show() {
			int lineCount = 1;
			for (int i = 0; i < content.length(); i++)
				if (content.charAt(i) == '\n')
					lineCount++;

			// Same structure as the text editor: the text view scrolls itself and the gutter
			// paints only the visible rows from its layout, so zoom/scroll stay fast on big files.
			tv = new PreviewTextView(activity);
			tv.setText(content, TextView.BufferType.SPANNABLE);
			tv.setTypeface(android.graphics.Typeface.MONOSPACE);
			tv.setBaseSp(12f);
			tv.setTextColor(activity.colText);
			tv.setBackgroundColor(activity.colSurface);
			tv.setGravity(Gravity.TOP | Gravity.LEFT);
			tv.setPadding(10 * activity.dp, 10 * activity.dp, 14 * activity.dp, 10 * activity.dp);

			lineNums = new LineNumberView(activity);
			lineNums.setTypeface(android.graphics.Typeface.MONOSPACE);
			lineNums.setTextSize(12);
			lineNums.setNumberColor(activity.colTextMuted);
			lineNums.setBackgroundColor(activity.colSurfaceAlt);
			lineNums.setPadding(10 * activity.dp, 10 * activity.dp, 8 * activity.dp, 10 * activity.dp);
			lineNums.attach(tv);

			View divider = new View(activity);
			divider.setBackgroundColor(activity.colDivider);

			tv.setViewListener(new PreviewTextView.Listener() {
				public void onViewScrolled() {
					lineNums.invalidate();
				}
			});
			tv.setZoomHook(new Runnable() {
				public void run() {
					lineNums.setTextSize(TypedValue.COMPLEX_UNIT_PX, tv.getTextSize());
					lineNums.updateWidth();
					lineNums.invalidate();
				}
			});

			LinearLayout zoomWrap = new LinearLayout(activity);
			zoomWrap.setOrientation(LinearLayout.HORIZONTAL);
			zoomWrap.addView(lineNums, new LinearLayout.LayoutParams(40 * activity.dp, -1));
			zoomWrap.addView(divider, new LinearLayout.LayoutParams(1 * activity.dp, -1));
			zoomWrap.addView(tv, new LinearLayout.LayoutParams(0, -1, 1));
			lineNums.setTotalLines(lineCount);

			// ---- tool row: Wrap / Search / Copy ----
			LinearLayout tools = new LinearLayout(activity);
			tools.setOrientation(LinearLayout.HORIZONTAL);
			tools.setBackgroundColor(activity.colSurfaceAlt);
			wrapBtn = toolBtn("No wrap");
			TextView searchBtn = toolBtn("Search");
			TextView copyBtn = toolBtn("Copy");
			tools.addView(wrapBtn, new LinearLayout.LayoutParams(0, -1, 1));
			tools.addView(searchBtn, new LinearLayout.LayoutParams(0, -1, 1));
			tools.addView(copyBtn, new LinearLayout.LayoutParams(0, -1, 1));
			wrapBtn.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					setWrap(!wrap);
				}
			});
			searchBtn.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					if (searchBar.getVisibility() == View.VISIBLE) closeSearch();
					else openSearch();
				}
			});
			copyBtn.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					copyText();
				}
			});

			// ---- search bar (hidden until Search is tapped) ----
			searchBar = new LinearLayout(activity);
			searchBar.setOrientation(LinearLayout.HORIZONTAL);
			searchBar.setGravity(Gravity.CENTER_VERTICAL);
			searchBar.setBackgroundColor(activity.colSurfaceAlt);
			searchBar.setPadding(10 * activity.dp, 0, 0, 0);
			searchBar.setVisibility(View.GONE);
			searchBox = new EditText(activity);
			searchBox.setHint("Find in text");
			searchBox.setTextSize(14);
			searchBox.setTextColor(activity.colText);
			searchBox.setHintTextColor(activity.colTextMuted);
			searchBox.setSingleLine(true);
			searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
			searchBar.addView(searchBox, new LinearLayout.LayoutParams(0, -2, 1));
			countTv = new TextView(activity);
			countTv.setTextSize(12);
			countTv.setTextColor(activity.colTextMuted);
			countTv.setPadding(8 * activity.dp, 0, 4 * activity.dp, 0);
			searchBar.addView(countTv, new LinearLayout.LayoutParams(-2, -2));
			TextView prev = toolBtn("\u25B2");
			TextView next = toolBtn("\u25BC");
			TextView close = toolBtn("\u2715");
			prev.setContentDescription("Previous match");
			next.setContentDescription("Next match");
			close.setContentDescription("Close search");
			searchBar.addView(prev, new LinearLayout.LayoutParams(-2, 44 * activity.dp));
			searchBar.addView(next, new LinearLayout.LayoutParams(-2, 44 * activity.dp));
			searchBar.addView(close, new LinearLayout.LayoutParams(-2, 44 * activity.dp));
			prev.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					step(-1);
				}
			});
			next.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					step(1);
				}
			});
			close.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					closeSearch();
				}
			});
			searchBox.addTextChangedListener(new TextWatcher() {
				public void beforeTextChanged(CharSequence s, int a, int b, int c) {
				}

				public void onTextChanged(CharSequence s, int a, int b, int c) {
				}

				public void afterTextChanged(Editable e) {
					updateHits(true);
				}
			});
			searchBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
				public boolean onEditorAction(TextView v, int actionId, KeyEvent ev) {
					step(1);
					return true; // keep the keyboard up for repeated "next"
				}
			});

			LinearLayout screen = new LinearLayout(activity);
			screen.setOrientation(LinearLayout.VERTICAL);
			screen.addView(tools, new LinearLayout.LayoutParams(-1, 40 * activity.dp));
			screen.addView(searchBar, new LinearLayout.LayoutParams(-1, -2));
			screen.addView(zoomWrap, new LinearLayout.LayoutParams(-1, 0, 1));

			setWrap(false);
			showPreviewScreen(file.getName(), screen, file);
			final int gen = previewGen;
			activeViewer = this;
			previewCleanup = new Runnable() {
				public void run() {
					activeViewer = null;
					hideKeyboard();
				}
			};

			// Colour in the background so the file shows up instantly.
			new Thread(new Runnable() {
				public void run() {
					SpannableStringBuilder styled = null;
					try {
						styled = new SyntaxHighlighter(activity.dark).highlight(content, ext);
					} catch (Throwable t) {
						styled = null; // highlighting is cosmetic - never let it break the preview
					}
					final SpannableStringBuilder fs = styled;
					if (fs == null) return;
					activity.runOnUiThread(new Runnable() {
						public void run() {
							if (gen != previewGen) return;
							tv.setText(fs, TextView.BufferType.SPANNABLE);
							hitSpans.clear(); // setText dropped the old search spans
							curSpan = null;
							if (searchBar.getVisibility() == View.VISIBLE) updateHits(false);
						}
					});
				}
			}).start();
		}

		void setWrap(boolean w) {
			wrap = w;
			wrapBtn.setText(w ? "Wrap" : "No wrap");
			tv.setHorizontallyScrolling(!w); // gutter stays; wrapped rows simply get no number
			if (cur >= 0 && cur < hits.size()) {
				tv.post(new Runnable() {
					public void run() {
						tv.revealOffset(hits.get(cur));
					}
				});
			}
		}

		void openSearch() {
			searchBar.setVisibility(View.VISIBLE);
			int s = tv.getSelectionStart(), e = tv.getSelectionEnd();
			if (s >= 0 && e >= 0 && s != e) {
				String sel = content.substring(Math.min(s, e), Math.max(s, e));
				if (sel.length() <= 100 && sel.indexOf('\n') < 0) {
					searchBox.setText(sel);
					searchBox.setSelection(sel.length());
				}
			}
			searchBox.requestFocus();
			InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
			if (imm != null) imm.showSoftInput(searchBox, 0);
			updateHits(false);
		}

		void closeSearch() {
			hideKeyboard();
			clearHitSpans();
			hits.clear();
			cur = -1;
			searchBar.setVisibility(View.GONE);
		}

		void hideKeyboard() {
			try {
				InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
				if (imm != null) imm.hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(), 0);
			} catch (Exception e) {
			}
		}

		void clearHitSpans() {
			CharSequence t = tv.getText();
			if (t instanceof Spannable) {
				Spannable sp = (Spannable) t;
				for (Object o : hitSpans) sp.removeSpan(o);
				if (curSpan != null) sp.removeSpan(curSpan);
			}
			hitSpans.clear();
			curSpan = null;
		}

		void updateHits(boolean jump) {
			clearHitSpans();
			hits.clear();
			cur = -1;
			capped = false;
			String q = searchBox.getText().toString();
			if (q.length() == 0) {
				countTv.setText("");
				return;
			}
			hitLen = q.length();
			int n = content.length() - hitLen;
			for (int i = 0; i <= n; i++) {
				if (content.regionMatches(true, i, q, 0, hitLen)) {
					hits.add(i);
					if (hits.size() >= MAX_HITS) {
						capped = true;
						break;
					}
					i += hitLen - 1; // non-overlapping matches
				}
			}
			if (hits.isEmpty()) {
				countTv.setText("0/0");
				return;
			}
			Spannable sp = (Spannable) tv.getText();
			int hitColor = activity.dark ? Color.argb(110, 255, 214, 0) : Color.argb(120, 255, 235, 59);
			for (int h : hits) {
				BackgroundColorSpan b = new BackgroundColorSpan(hitColor);
				sp.setSpan(b, h, h + hitLen, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
				hitSpans.add(b);
			}
			goTo(0, jump);
		}

		void step(int dir) {
			if (hits.isEmpty()) return;
			goTo((cur + dir + hits.size()) % hits.size(), true);
		}

		void goTo(int idx, boolean scroll) {
			cur = idx;
			Spannable sp = (Spannable) tv.getText();
			if (curSpan != null) sp.removeSpan(curSpan);
			curSpan = new BackgroundColorSpan(Color.rgb(255, 143, 0));
			int h = hits.get(idx);
			sp.setSpan(curSpan, h, h + hitLen, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
			countTv.setText((idx + 1) + "/" + hits.size() + (capped ? "+" : ""));
			if (scroll) scrollToHit(h);
		}

		void scrollToHit(final int start) {
			if (tv.getLayout() == null) {
				tv.post(new Runnable() {
					public void run() {
						if (tv.getLayout() != null) tv.revealOffset(start);
					}
				});
				return;
			}
			tv.revealOffset(start);
		}

		void copyText() {
			int s = tv.getSelectionStart(), e = tv.getSelectionEnd();
			boolean sel = s >= 0 && e >= 0 && s != e;
			String t = sel ? content.substring(Math.min(s, e), Math.max(s, e)) : content;
			try {
				ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
				cm.setPrimaryClip(ClipData.newPlainText(file.getName(), t));
				activity.toast(sel ? "Copied selection" : "Copied all text");
			} catch (Exception ex) {
				activity.toast("Too large to copy - select part of it instead");
			}
		}
	}

	class ZoomTextContainer extends FrameLayout {
		ScaleGestureDetector scaleDetector;
		float textSp = 12f;
		float pending = 1f; // live pinch factor, applied as a cheap view transform until the fingers lift
		final List<TextView> targets;
		static final float MIN_SP = 8f, MAX_SP = 28f;

		ZoomTextContainer(Context c, final List<TextView> scalables) {
			super(c);
			targets = scalables;
			setBackgroundColor(activity.colSurface); // so any uncovered edge matches the text background
			scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
				public boolean onScaleBegin(ScaleGestureDetector d) {
					pending = 1f;
					View child = getChildAt(0);
					if (child != null) child.setLayerType(View.LAYER_TYPE_HARDWARE, null);
					return true;
				}

				public boolean onScale(ScaleGestureDetector d) {
					// Only scale the already-rendered view here. Re-laying out thousands of
					// lines on every event is what made pinching slow.
					float target = textSp * pending * d.getScaleFactor();
					target = Math.max(MIN_SP, Math.min(target, MAX_SP));
					pending = target / textSp;
					View child = getChildAt(0);
					if (child != null) {
						child.setPivotX(0f); // anchor to the left edge so the line numbers never slide off-screen
						child.setPivotY(d.getFocusY());
						child.setScaleX(pending);
						child.setScaleY(pending);
						// The view only has pixels for what was on screen when the pinch began, so zooming
						// out far would leave a blank border. Re-render for real every ~10% instead.
						if (pending < 0.9f || pending > 1.3f) {
							commit(d.getFocusX(), d.getFocusY());
							child.setLayerType(View.LAYER_TYPE_HARDWARE, null);
						}
					}
					return true;
				}

				public void onScaleEnd(ScaleGestureDetector d) {
					commit(d.getFocusX(), d.getFocusY());
				}
			});
		}

		void collectScrollers(View v, List<View> out) {
			if (v instanceof ScrollView || v instanceof HorizontalScrollView) out.add(v);
			if (v instanceof ViewGroup) {
				ViewGroup g = (ViewGroup) v;
				for (int i = 0; i < g.getChildCount(); i++) collectScrollers(g.getChildAt(i), out);
			}
		}

		/** Apply the pinch to the real text size once, with a single relayout. */
		void commit(final float fx, final float fy) {
			View child = getChildAt(0);
			if (child != null) {
				child.setScaleX(1f);
				child.setScaleY(1f);
				child.setLayerType(View.LAYER_TYPE_NONE, null);
			}
			final float ratio = pending;
			pending = 1f;
			if (Math.abs(ratio - 1f) < 0.01f) return;
			textSp = Math.max(MIN_SP, Math.min(textSp * ratio, MAX_SP));
			final List<View> scrollers = new ArrayList<View>();
			if (child != null) collectScrollers(child, scrollers);
			final int[] sx = new int[scrollers.size()], sy = new int[scrollers.size()];
			for (int i = 0; i < sx.length; i++) {
				sx[i] = scrollers.get(i).getScrollX();
				sy[i] = scrollers.get(i).getScrollY();
			}
			for (TextView tv : targets)
				tv.setTextSize(textSp);
			// keep the content under the fingers in place after the relayout
			post(new Runnable() {
				public void run() {
					for (int i = 0; i < sx.length; i++) {
						View sc = scrollers.get(i);
						int nx = Math.round(sx[i] * ratio); // pinch is anchored at the left edge
						int ny = Math.round((sy[i] + fy) * ratio - fy);
						sc.scrollTo(Math.max(0, nx), Math.max(0, ny));
					}
				}
			});
		}

		public boolean onInterceptTouchEvent(MotionEvent ev) {
			return ev.getPointerCount() >= 2;
		}

		public boolean onTouchEvent(MotionEvent ev) {
			scaleDetector.onTouchEvent(ev);
			return true;
		}
	}

	void showVideoPreview(final File f) {
		FrameLayout holder = new FrameLayout(activity);
		holder.setBackgroundColor(Color.BLACK);
		final VideoView vv = new VideoView(activity);
		FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(-1, -1);
		vlp.gravity = Gravity.CENTER;
		holder.addView(vv, vlp);

		LinearLayout controls = new LinearLayout(activity);
		controls.setOrientation(LinearLayout.HORIZONTAL);
		controls.setGravity(Gravity.CENTER_VERTICAL);
		controls.setBackgroundColor(Color.rgb(20, 20, 20));
		controls.setPadding(8 * activity.dp, 0, 16 * activity.dp, 0);

		final TextView playBtn = new TextView(activity);
		playBtn.setTextColor(Color.WHITE);
		playBtn.setTextSize(20);
		playBtn.setGravity(Gravity.CENTER);
		playBtn.setPadding(16 * activity.dp, 0, 16 * activity.dp, 0);
		playBtn.setText("\u23F8"); // starts autoplaying below, so this opens paused-to-play
		playBtn.setContentDescription("Play or pause");
		activity.applyRipple(playBtn);
		controls.addView(playBtn, new LinearLayout.LayoutParams(-2, -1));

		final TextView curTime = new TextView(activity);
		curTime.setTextColor(Color.WHITE);
		curTime.setTextSize(12);
		curTime.setText("0:00");
		controls.addView(curTime, new LinearLayout.LayoutParams(-2, -2));

		final SeekBar seek = new SeekBar(activity);
		LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, -2, 1);
		slp.leftMargin = 10 * activity.dp;
		slp.rightMargin = 10 * activity.dp;
		controls.addView(seek, slp);

		final TextView totalTime = new TextView(activity);
		totalTime.setTextColor(Color.rgb(200, 200, 200));
		totalTime.setTextSize(12);
		totalTime.setText("0:00");
		controls.addView(totalTime, new LinearLayout.LayoutParams(-2, -2));

		LinearLayout col = new LinearLayout(activity);
		col.setOrientation(LinearLayout.VERTICAL);
		col.addView(holder, new LinearLayout.LayoutParams(-1, 0, 1));
		col.addView(controls, new LinearLayout.LayoutParams(-1, 48 * activity.dp));

		showPreviewScreen(f.getName(), col, f);
		final int gen = previewGen;

		final boolean[] seeking = {false};
		final Handler tick = new Handler(Looper.getMainLooper());
		final Runnable[] poller = new Runnable[1];
		poller[0] = new Runnable() {
			public void run() {
				if (gen != previewGen)
					return; // preview screen was left - stop polling
				if (!seeking[0]) {
					seek.setProgress(vv.getCurrentPosition());
					curTime.setText(formatDuration(vv.getCurrentPosition()));
				}
				tick.postDelayed(this, 400);
			}
		};

		vv.setVideoURI(Uri.fromFile(f));
		vv.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
			public void onPrepared(android.media.MediaPlayer mp) {
				if (gen != previewGen)
					return;
				seek.setMax(vv.getDuration());
				totalTime.setText(formatDuration(vv.getDuration()));
				vv.start();
				playBtn.setText("\u23F8");
				tick.post(poller[0]);
			}
		});
		vv.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
			public void onCompletion(android.media.MediaPlayer mp) {
				playBtn.setText("\u25B6");
			}
		});
		vv.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
			public boolean onError(android.media.MediaPlayer mp, int what, int extra) {
				activity.toast("Can't play this video");
				activity.showMain();
				activity.openItem(f, false);
				return true; // we've handled it - stop VideoView's own error dialog from also showing
			}
		});
		playBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				if (vv.isPlaying()) {
					vv.pause();
					playBtn.setText("\u25B6");
				} else {
					vv.start();
					playBtn.setText("\u23F8");
				}
			}
		});
		seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			public void onStartTrackingTouch(SeekBar sb) {
				seeking[0] = true;
			}

			public void onStopTrackingTouch(SeekBar sb) {
				vv.seekTo(sb.getProgress());
				seeking[0] = false;
			}

			public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
				if (fromUser)
					curTime.setText(formatDuration(progress));
			}
		});
	}

	void showAudioPreview(final File f) {
		FrameLayout holder = new FrameLayout(activity);
		holder.setBackgroundColor(Color.BLACK);

		final ImageView art = new ImageView(activity);
		art.setScaleType(ImageView.ScaleType.CENTER_CROP);
		holder.addView(art, new FrameLayout.LayoutParams(-1, -1));

		// Shown until (and unless) embedded album art turns up.
		final TextView placeholder = new TextView(activity);
		placeholder.setText("\uD83C\uDFB5");
		placeholder.setTextSize(72);
		placeholder.setGravity(Gravity.CENTER);
		FrameLayout.LayoutParams phLp = new FrameLayout.LayoutParams(-2, -2);
		phLp.gravity = Gravity.CENTER;
		holder.addView(placeholder, phLp);

		final TextView titleTv = new TextView(activity);
		titleTv.setText(f.getName()); // replaced with the tagged title, if any, once metadata loads
		titleTv.setTextColor(Color.WHITE);
		titleTv.setTextSize(15);
		titleTv.setGravity(Gravity.CENTER);
		titleTv.setSingleLine(true);
		titleTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
		titleTv.setPadding(24 * activity.dp, 0, 24 * activity.dp, 4 * activity.dp);
		final TextView artistTv = new TextView(activity);
		artistTv.setTextColor(Color.rgb(190, 190, 190));
		artistTv.setTextSize(13);
		artistTv.setGravity(Gravity.CENTER);
		artistTv.setSingleLine(true);
		artistTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
		artistTv.setVisibility(View.GONE); // shown only if the file actually has an artist tag

		LinearLayout captions = new LinearLayout(activity);
		captions.setOrientation(LinearLayout.VERTICAL);
		captions.setGravity(Gravity.CENTER_HORIZONTAL);
		captions.addView(titleTv, new LinearLayout.LayoutParams(-2, -2));
		captions.addView(artistTv, new LinearLayout.LayoutParams(-2, -2));
		FrameLayout.LayoutParams capLp = new FrameLayout.LayoutParams(-1, -2);
		capLp.gravity = Gravity.BOTTOM;
		capLp.bottomMargin = 20 * activity.dp;
		holder.addView(captions, capLp);

		LinearLayout controls = new LinearLayout(activity);
		controls.setOrientation(LinearLayout.HORIZONTAL);
		controls.setGravity(Gravity.CENTER_VERTICAL);
		controls.setBackgroundColor(Color.rgb(20, 20, 20));
		controls.setPadding(8 * activity.dp, 0, 16 * activity.dp, 0);

		final TextView playBtn = new TextView(activity);
		playBtn.setTextColor(Color.WHITE);
		playBtn.setTextSize(20);
		playBtn.setGravity(Gravity.CENTER);
		playBtn.setPadding(16 * activity.dp, 0, 16 * activity.dp, 0);
		playBtn.setText("\u23F8"); // starts autoplaying once prepared, so this opens paused-to-play
		playBtn.setContentDescription("Play or pause");
		playBtn.setEnabled(false); // enabled once prepared; a click before then could throw
		activity.applyRipple(playBtn);
		controls.addView(playBtn, new LinearLayout.LayoutParams(-2, -1));

		final TextView curTime = new TextView(activity);
		curTime.setTextColor(Color.WHITE);
		curTime.setTextSize(12);
		curTime.setText("0:00");
		controls.addView(curTime, new LinearLayout.LayoutParams(-2, -2));

		final SeekBar seek = new SeekBar(activity);
		LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, -2, 1);
		slp.leftMargin = 10 * activity.dp;
		slp.rightMargin = 10 * activity.dp;
		controls.addView(seek, slp);

		final TextView totalTime = new TextView(activity);
		totalTime.setTextColor(Color.rgb(200, 200, 200));
		totalTime.setTextSize(12);
		totalTime.setText("0:00");
		controls.addView(totalTime, new LinearLayout.LayoutParams(-2, -2));

		LinearLayout col = new LinearLayout(activity);
		col.setOrientation(LinearLayout.VERTICAL);
		col.addView(holder, new LinearLayout.LayoutParams(-1, 0, 1));
		col.addView(controls, new LinearLayout.LayoutParams(-1, 48 * activity.dp));

		// showPreviewScreen() below bumps previewGen (and runs any leftover
		// previewCleanup from whatever was on screen before this) and swaps
		// in this whole screen; read previewGen back afterward so gen
		// reflects that bump, rather than guessing what it will become.
		showPreviewScreen(f.getName(), col, f);
		final int gen = previewGen;

		final android.media.MediaPlayer mp = new android.media.MediaPlayer();
		previewCleanup = new Runnable() {
			public void run() {
				try {
					mp.release();
				} catch (Exception e) {
				}
			}
		};

		final boolean[] seeking = {false};
		final Handler tick = new Handler(Looper.getMainLooper());
		final Runnable[] poller = new Runnable[1];
		poller[0] = new Runnable() {
			public void run() {
				if (gen != previewGen)
					return; // preview screen was left - stop polling
				if (!seeking[0]) {
					try {
						seek.setProgress(mp.getCurrentPosition());
						curTime.setText(formatDuration(mp.getCurrentPosition()));
					} catch (Exception e) {
						// mp can be mid-release right as a poll lands; skip this tick
					}
				}
				tick.postDelayed(this, 400);
			}
		};

		new Thread(new Runnable() {
			public void run() {
				String mtitle = null, martist = null;
				android.graphics.Bitmap artBmp = null;
				try {
					android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
					mmr.setDataSource(f.getAbsolutePath());
					mtitle = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE);
					martist = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST);
					byte[] pic = mmr.getEmbeddedPicture();
					if (pic != null)
						artBmp = android.graphics.BitmapFactory.decodeByteArray(pic, 0, pic.length);
					mmr.release();
				} catch (Exception e) {
					// no tags/art, or a format MediaMetadataRetriever can't parse -
					// the filename and music-note placeholder already cover this
				}
				final String ftitle = mtitle, fartist = martist;
				final android.graphics.Bitmap fart = artBmp;
				activity.runOnUiThread(new Runnable() {
					public void run() {
						if (gen != previewGen)
							return;
						if (ftitle != null && ftitle.length() > 0)
							titleTv.setText(ftitle);
						if (fartist != null && fartist.length() > 0) {
							artistTv.setText(fartist);
							artistTv.setVisibility(View.VISIBLE);
						}
						if (fart != null) {
							art.setImageBitmap(fart);
							placeholder.setVisibility(View.GONE);
						}
					}
				});
			}
		}).start();

		try {
			mp.setDataSource(f.getAbsolutePath());
			mp.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
				public void onPrepared(android.media.MediaPlayer m) {
					if (gen != previewGen)
						return;
					seek.setMax(mp.getDuration());
					totalTime.setText(formatDuration(mp.getDuration()));
					playBtn.setEnabled(true);
					mp.start();
					playBtn.setText("\u23F8");
					tick.post(poller[0]);
				}
			});
			mp.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
				public void onCompletion(android.media.MediaPlayer m) {
					playBtn.setText("\u25B6");
					seek.setProgress(0);
					curTime.setText("0:00");
				}
			});
			mp.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
				public boolean onError(android.media.MediaPlayer m, int what, int extra) {
					activity.toast("Can't play this audio file");
					activity.showMain();
					activity.openItem(f, false);
					return true; // we've handled it - stop MediaPlayer's own error dialog from also showing
				}
			});
			mp.prepareAsync();
		} catch (Exception e) {
			activity.toast("Can't play this audio file");
			activity.showMain();
			activity.openItem(f, false);
			return;
		}

		playBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				if (mp.isPlaying()) {
					mp.pause();
					playBtn.setText("\u25B6");
				} else {
					mp.start();
					playBtn.setText("\u23F8");
				}
			}
		});
		seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			public void onStartTrackingTouch(SeekBar sb) {
				seeking[0] = true;
			}

			public void onStopTrackingTouch(SeekBar sb) {
				mp.seekTo(sb.getProgress());
				seeking[0] = false;
			}

			public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
				if (fromUser)
					curTime.setText(formatDuration(progress));
			}
		});
	}

	void renderPdfPage(final android.graphics.pdf.PdfRenderer renderer, final int index, final int gen,
			final FrameLayout holder, final TextView pageLabel, final View prevBtn, final View nextBtn,
			final int pageCount) {
		prevBtn.setEnabled(false);
		nextBtn.setEnabled(false);
		holder.removeAllViews();
		ProgressBar spinner = new ProgressBar(activity);
		FrameLayout.LayoutParams spinnerLp = new FrameLayout.LayoutParams(-2, -2);
		spinnerLp.gravity = Gravity.CENTER;
		holder.addView(spinner, spinnerLp);

		new Thread(new Runnable() {
			public void run() {
				android.graphics.Bitmap bmp = null;
				String err = null;
				try {
					synchronized (renderer) {
						android.graphics.pdf.PdfRenderer.Page page = renderer.openPage(index);
						android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
						float scale = dm.widthPixels / (float) page.getWidth();
						int w = dm.widthPixels;
						int h = Math.max(1, Math.round(page.getHeight() * scale));
						bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
						bmp.eraseColor(Color.WHITE); // pages render transparent otherwise
						page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
						page.close();
					}
				} catch (Exception e) {
					err = "Can't render this page";
				}
				final android.graphics.Bitmap fbmp = bmp;
				final String ferr = err;
				activity.runOnUiThread(new Runnable() {
					public void run() {
						if (gen != previewGen)
							return; // preview screen was left while this page was rendering
						holder.removeAllViews();
						if (ferr != null || fbmp == null) {
							TextView msg = new TextView(activity);
							msg.setText(ferr != null ? ferr : "Can't render this page");
							msg.setTextColor(Color.WHITE);
							msg.setGravity(Gravity.CENTER);
							FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(-2, -2);
							mlp.gravity = Gravity.CENTER;
							holder.addView(msg, mlp);
						} else {
							holder.addView(new ZoomImageView(activity, fbmp), new FrameLayout.LayoutParams(-1, -1));
						}
						pageLabel.setText("Page " + (index + 1) + " / " + pageCount);
						prevBtn.setEnabled(index > 0);
						nextBtn.setEnabled(index < pageCount - 1);
					}
				});
			}
		}).start();
	}

	void showPdfPreview(final File f) {
		final FrameLayout holder = new FrameLayout(activity);
		holder.setBackgroundColor(Color.rgb(60, 60, 60));
		ProgressBar spinner = new ProgressBar(activity);
		FrameLayout.LayoutParams spinnerLp = new FrameLayout.LayoutParams(-2, -2);
		spinnerLp.gravity = Gravity.CENTER;
		holder.addView(spinner, spinnerLp);

		LinearLayout controls = new LinearLayout(activity);
		controls.setOrientation(LinearLayout.HORIZONTAL);
		controls.setGravity(Gravity.CENTER_VERTICAL);
		controls.setBackgroundColor(Color.rgb(20, 20, 20));
		controls.setPadding(8 * activity.dp, 0, 8 * activity.dp, 0);

		final TextView prevBtn = new TextView(activity);
		prevBtn.setText("\u2039");
		prevBtn.setTextColor(Color.WHITE);
		prevBtn.setTextSize(22);
		prevBtn.setGravity(Gravity.CENTER);
		prevBtn.setPadding(20 * activity.dp, 0, 20 * activity.dp, 0);
		prevBtn.setContentDescription("Previous page");
		prevBtn.setEnabled(false);
		activity.applyRipple(prevBtn);
		controls.addView(prevBtn, new LinearLayout.LayoutParams(-2, -1));

		final TextView pageLabel = new TextView(activity);
		pageLabel.setTextColor(Color.WHITE);
		pageLabel.setTextSize(13);
		pageLabel.setGravity(Gravity.CENTER);
		controls.addView(pageLabel, new LinearLayout.LayoutParams(0, -2, 1));

		final TextView nextBtn = new TextView(activity);
		nextBtn.setText("\u203A");
		nextBtn.setTextColor(Color.WHITE);
		nextBtn.setTextSize(22);
		nextBtn.setGravity(Gravity.CENTER);
		nextBtn.setPadding(20 * activity.dp, 0, 20 * activity.dp, 0);
		nextBtn.setContentDescription("Next page");
		nextBtn.setEnabled(false);
		activity.applyRipple(nextBtn);
		controls.addView(nextBtn, new LinearLayout.LayoutParams(-2, -1));

		LinearLayout col = new LinearLayout(activity);
		col.setOrientation(LinearLayout.VERTICAL);
		col.addView(holder, new LinearLayout.LayoutParams(-1, 0, 1));
		col.addView(controls, new LinearLayout.LayoutParams(-1, 48 * activity.dp));

		showPreviewScreen(f.getName(), col, f);
		final int gen = previewGen;

		final android.graphics.pdf.PdfRenderer[] rendererH = new android.graphics.pdf.PdfRenderer[1];
		final int[] pageCount = {0};
		final int[] curPage = {0};

		new Thread(new Runnable() {
			public void run() {
				String err = null;
				int count = 0;
				android.os.ParcelFileDescriptor pfd = null;
				android.graphics.pdf.PdfRenderer renderer = null;
				try {
					pfd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
					renderer = new android.graphics.pdf.PdfRenderer(pfd);
					count = renderer.getPageCount();
					if (count <= 0)
						err = "This PDF has no pages";
				} catch (Exception e) {
					err = "Can't preview this PDF";
				}
				final String ferr = err;
				final int fcount = count;
				final android.graphics.pdf.PdfRenderer frenderer = renderer;
				final android.os.ParcelFileDescriptor fpfd = pfd;
				activity.runOnUiThread(new Runnable() {
					public void run() {
						if (gen != previewGen || ferr != null) {
							if (frenderer != null)
								try {
									frenderer.close();
								} catch (Exception e) {
								}
							if (fpfd != null)
								try {
									fpfd.close();
								} catch (Exception e) {
								}
							if (ferr != null) {
								activity.toast(ferr);
								activity.showMain();
								activity.openItem(f, false);
							}
							return;
						}
						rendererH[0] = frenderer;
						pageCount[0] = fcount;
						previewCleanup = new Runnable() {
							public void run() {
								synchronized (frenderer) {
									try {
										frenderer.close();
									} catch (Exception e) {
									}
								}
								try {
									fpfd.close();
								} catch (Exception e) {
								}
							}
						};
						renderPdfPage(frenderer, 0, gen, holder, pageLabel, prevBtn, nextBtn, fcount);
					}
				});
			}
		}).start();

		prevBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				if (curPage[0] > 0 && rendererH[0] != null) {
					curPage[0]--;
					renderPdfPage(rendererH[0], curPage[0], gen, holder, pageLabel, prevBtn, nextBtn, pageCount[0]);
				}
			}
		});
		nextBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				if (curPage[0] < pageCount[0] - 1 && rendererH[0] != null) {
					curPage[0]++;
					renderPdfPage(rendererH[0], curPage[0], gen, holder, pageLabel, prevBtn, nextBtn, pageCount[0]);
				}
			}
		});
	}

	String formatDuration(int ms) {
		int totalSec = Math.max(0, ms) / 1000;
		int m = totalSec / 60, s = totalSec % 60;
		return m + ":" + (s < 10 ? "0" : "") + s;
	}

	String decodeAxml(byte[] d) throws IOException {
		if (d.length < 8 || readShortLE(d, 0) != 0x0003)
			throw new IOException("Not a compiled binary XML file");
		int pos = 8; // past the top-level ResChunk_header (type, headerSize, size)
		String[] strings = null;
		Map<String, String> nsPrefixByUri = new LinkedHashMap<String, String>();
		StringBuilder out = new StringBuilder();
		int depth = 0;
		StringBuilder pendingOpenTag = null; // attributes are only flushed once we know if it's self-closing

		while (pos + 8 <= d.length) {
			int type = readShortLE(d, pos);
			int headerSize = readShortLE(d, pos + 2);
			int size = readIntLE(d, pos + 4);
			if (size <= 0 || pos + size > d.length)
				break;
			int chunkStart = pos, bodyPos = pos + headerSize;

			if (type == 0x0001) { // string pool
				strings = parseStringPool(d, chunkStart, headerSize);
			} else if (type == 0x0100) { // start namespace
				String prefix = strAt(strings, readIntLE(d, bodyPos + 8));
				String uri = strAt(strings, readIntLE(d, bodyPos + 12));
				if (uri != null)
					nsPrefixByUri.put(uri, prefix == null ? "" : prefix);
			} else if (type == 0x0102) { // start element
				if (pendingOpenTag != null) {
					out.append(pendingOpenTag).append(">\n");
					pendingOpenTag = null;
				}
				int nsIdx = readIntLE(d, bodyPos + 8);
				int nameIdx = readIntLE(d, bodyPos + 12);
				int attrStart = readShortLE(d, bodyPos + 16);
				int attrSizeEach = readShortLE(d, bodyPos + 18);
				int attrCount = readShortLE(d, bodyPos + 20);
				StringBuilder tagSb = new StringBuilder();
				indent(tagSb, depth);
				tagSb.append('<').append(qualifiedName(strings, nsPrefixByUri, nsIdx, nameIdx));
				int ap = bodyPos + attrStart;
				for (int a = 0; a < attrCount; a++) {
					int aNsIdx = readIntLE(d, ap);
					int aNameIdx = readIntLE(d, ap + 4);
					int aRawValIdx = readIntLE(d, ap + 8);
					int aValType = d[ap + 15] & 0xFF;
					int aValData = readIntLE(d, ap + 16);
					tagSb.append(' ').append(qualifiedName(strings, nsPrefixByUri, aNsIdx, aNameIdx)).append("=\"")
							.append(xmlEscape(attrValueToString(strings, aRawValIdx, aValType, aValData)))
							.append('"');
					ap += attrSizeEach;
				}
				pendingOpenTag = tagSb;
				depth++;
			} else if (type == 0x0103) { // end element
				depth--;
				if (pendingOpenTag != null) {
					out.append(pendingOpenTag).append(" />\n");
					pendingOpenTag = null;
				} else {
					indent(out, depth);
					out.append("</")
							.append(qualifiedName(strings, nsPrefixByUri, readIntLE(d, bodyPos + 8), readIntLE(d, bodyPos + 12)))
							.append(">\n");
				}
			} else if (type == 0x0104) { // CDATA
				if (pendingOpenTag != null) {
					out.append(pendingOpenTag).append(">\n");
					pendingOpenTag = null;
				}
				String text = strAt(strings, readIntLE(d, bodyPos + 8));
				if (text != null && text.trim().length() > 0) {
					indent(out, depth);
					out.append(xmlEscape(text)).append('\n');
				}
			}
			
			pos = chunkStart + size;
		}
		if (out.length() == 0)
			throw new IOException("Couldn't decode this binary XML");
		return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" + out;
	}

	// =================================================================
	// COMPILED AndroidManifest.xml (AXML) PARSING
	// =================================================================
	String[] parseStringPool(byte[] d, int chunkStart, int headerSize) {
		int stringCount = readIntLE(d, chunkStart + 8);
		int flags = readIntLE(d, chunkStart + 16);
		int stringsStart = readIntLE(d, chunkStart + 20);
		boolean utf8 = (flags & 0x100) != 0;
		String[] out = new String[stringCount];
		int offsetsBase = chunkStart + headerSize;
		for (int i = 0; i < stringCount; i++) {
			int strOffset = readIntLE(d, offsetsBase + i * 4);
			int p = chunkStart + stringsStart + strOffset;
			out[i] = utf8 ? readUtf8PoolString(d, p) : readUtf16PoolString(d, p);
		}
		return out;
	}

	String readUtf8PoolString(byte[] d, int p) {
		int[] skip = readUtf8Len(d, p); // char-count length, not needed, only its width
		int[] byteLen = readUtf8Len(d, skip[1]);
		try {
			return new String(d, byteLen[1], byteLen[0], "UTF-8");
		} catch (Exception e) {
			return "";
		}
	}

	int[] readUtf8Len(byte[] d, int p) {
		int b0 = d[p] & 0xFF;
		if ((b0 & 0x80) == 0)
			return new int[] {b0, p + 1};
		int b1 = d[p + 1] & 0xFF;
		return new int[] {((b0 & 0x7F) << 8) | b1, p + 2};
	}

	String readUtf16PoolString(byte[] d, int p) {
		int u0 = readShortLE(d, p);
		int len, consumed;
		if ((u0 & 0x8000) != 0) {
			len = ((u0 & 0x7FFF) << 16) | readShortLE(d, p + 2);
			consumed = 4;
		} else {
			len = u0;
			consumed = 2;
		}
		try {
			return new String(d, p + consumed, len * 2, "UTF-16LE");
		} catch (Exception e) {
			return "";
		}
	}

	String strAt(String[] strings, int idx) {
		return (strings == null || idx < 0 || idx >= strings.length) ? null : strings[idx];
	}

	String qualifiedName(String[] strings, Map<String, String> nsPrefixByUri, int nsIdx, int nameIdx) {
		String name = strAt(strings, nameIdx);
		if (name == null)
			name = "?";
		if (nsIdx < 0)
			return name;
		String uri = strAt(strings, nsIdx);
		String prefix = uri == null ? null : nsPrefixByUri.get(uri);
		if (prefix == null && "http://schemas.android.com/apk/res/android".equals(uri))
			prefix = "android"; // defensive fallback if a start-namespace chunk was missed
		return (prefix == null || prefix.length() == 0) ? name : prefix + ":" + name;
	}

	String attrValueToString(String[] strings, int rawValIdx, int type, int data) {
		if (type == 0x03) { // TYPE_STRING
			String s = strAt(strings, rawValIdx >= 0 ? rawValIdx : data);
			return s == null ? "" : s;
		}
		switch (type) {
			case 0x01 :
				return "@0x" + Integer.toHexString(data); // TYPE_REFERENCE
			case 0x02 :
				return "?0x" + Integer.toHexString(data); // TYPE_ATTRIBUTE
			case 0x10 :
				return String.valueOf(data); // TYPE_INT_DEC
			case 0x11 :
				return "0x" + Integer.toHexString(data); // TYPE_INT_HEX
			case 0x12 :
				return data != 0 ? "true" : "false"; // TYPE_INT_BOOLEAN
			case 0x1c :
			case 0x1d :
			case 0x1e :
			case 0x1f :
				return "#" + String.format(Locale.US, "%08X", data); // color types
			case 0x04 :
				return String.valueOf(Float.intBitsToFloat(data)); // TYPE_FLOAT
			default :
				if (rawValIdx >= 0) {
					String s = strAt(strings, rawValIdx);
					if (s != null)
						return s;
				}
				return "0x" + Integer.toHexString(data);
		}
	}

	String xmlEscape(String s) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '&')
				b.append("&amp;");
			else if (c == '<')
				b.append("&lt;");
			else if (c == '>')
				b.append("&gt;");
			else if (c == '"')
				b.append("&quot;");
			else
				b.append(c);
		}
		return b.toString();
	}

	void indent(StringBuilder sb, int depth) {
		for (int i = 0; i < depth; i++)
			sb.append("  ");
	}

	int readShortLE(byte[] d, int p) {
		return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8);
	}

	int readIntLE(byte[] d, int p) {
		return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8) | ((d[p + 2] & 0xFF) << 16) | ((d[p + 3] & 0xFF) << 24);
	}

	boolean looksBinary(byte[] data) {
		int len = Math.min(data.length, 8000);
		if (len == 0)
			return false;
		int suspicious = 0;
		for (int i = 0; i < len; i++) {
			int b = data[i] & 0xFF;
			if (b == 0)
				return true;
			if (b < 0x09 || (b > 0x0D && b < 0x20))
				suspicious++;
		}
		return suspicious * 100 / len > 5;
	}

	byte[] readAllBytes(File f) throws IOException {
		FileInputStream in = new FileInputStream(f);
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) != -1)
				out.write(buf, 0, n);
			return out.toByteArray();
		} finally {
			in.close();
		}
	}

	void showPreviewScreen(String title, View content, final File f) {
		showingPreview = true;
		previewGen++;
		if (previewCleanup != null) {
			previewCleanup.run();
			previewCleanup = null;
		}
		LinearLayout root = new LinearLayout(activity);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundColor(activity.colBg);

		LinearLayout bar = new LinearLayout(activity);
		bar.setOrientation(LinearLayout.HORIZONTAL);
		bar.setGravity(Gravity.CENTER_VERTICAL);
		bar.setBackgroundColor(Color.rgb(45, 105, 160));
		TextView back = new TextView(activity);
		back.setText("\u2190");
		back.setTextColor(Color.WHITE);
		back.setTextSize(20);
		back.setGravity(Gravity.CENTER);
		back.setPadding(16 * activity.dp, 0, 16 * activity.dp, 0);
		back.setContentDescription("Back");
		activity.applyRipple(back);
		back.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				activity.showMain();
			}
		});
		bar.addView(back, new LinearLayout.LayoutParams(-2, -1));

		TextView titleTv = new TextView(activity);
		titleTv.setText(title);
		titleTv.setTextSize(16);
		titleTv.setTextColor(Color.WHITE);
		titleTv.setSingleLine(true);
		titleTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
		titleTv.setGravity(Gravity.CENTER_VERTICAL);
		bar.addView(titleTv, new LinearLayout.LayoutParams(0, -1, 1));

		TextView openBtn = new TextView(activity);
		openBtn.setText("\u2197"); // matches the "Open with" icon used in the long-press menu
		openBtn.setTextColor(Color.WHITE);
		openBtn.setTextSize(18);
		openBtn.setGravity(Gravity.CENTER);
		openBtn.setPadding(16 * activity.dp, 0, 16 * activity.dp, 0);
		openBtn.setContentDescription("Open with another app");
		activity.applyRipple(openBtn);
		openBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				activity.openItem(f, true);
			}
		});
		bar.addView(openBtn, new LinearLayout.LayoutParams(-2, -1));

		root.addView(bar, new LinearLayout.LayoutParams(-1, 52 * activity.dp));
		root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
		activity.applySystemInsets(root);
		activity.setContentView(root);
	}

	class ZoomImageView extends View {
		android.graphics.Bitmap bmp;
		android.graphics.Matrix matrix = new android.graphics.Matrix();
		float baseScale = 1f, curScale = 1f, panX, panY, lastTouchX, lastTouchY;
		int activePointerId = -1;
		boolean laidOut;
		ScaleGestureDetector scaleDetector;
		GestureDetector gestureDetector;

		static final int GESTURE_UNDECIDED = 0, GESTURE_PAN = 1, GESTURE_DISMISS = 2;
		int gestureMode = GESTURE_UNDECIDED;
		float downX, downY;
		float dismissThreshold;

		ZoomImageView(Context c, android.graphics.Bitmap b) {
			super(c);
			bmp = b;
			dismissThreshold = 120 * activity.dp;
			scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
				public boolean onScale(ScaleGestureDetector d) {
					curScale *= d.getScaleFactor();
					curScale = Math.max(baseScale, Math.min(curScale, baseScale * 6f));
					invalidate();
					return true;
				}
			});
			gestureDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
				public boolean onDoubleTap(MotionEvent e) {
					boolean zoomedIn = curScale > baseScale * 1.01f;
					float target = zoomedIn ? baseScale : baseScale * 3f;
					// keep the tapped point stationary while scaling in/out
					float imgX = (e.getX() - panX) / curScale;
					float imgY = (e.getY() - panY) / curScale;
					curScale = target;
					panX = e.getX() - imgX * curScale;
					panY = e.getY() - imgY * curScale;
					invalidate();
					return true;
				}
			});
			setOnTouchListener(new View.OnTouchListener() {
				public boolean onTouch(View v, MotionEvent e) {
					scaleDetector.onTouchEvent(e);
					gestureDetector.onTouchEvent(e);
					switch (e.getActionMasked()) {
						case MotionEvent.ACTION_DOWN :
							lastTouchX = e.getX();
							lastTouchY = e.getY();
							downX = e.getX();
							downY = e.getY();
							activePointerId = e.getPointerId(0);
							gestureMode = GESTURE_UNDECIDED;
							return true;
						case MotionEvent.ACTION_POINTER_DOWN :
							// a second finger landed: this is a pinch, not a dismiss drag
							if (gestureMode == GESTURE_DISMISS)
								snapBack();
							gestureMode = GESTURE_PAN;
							return true;
						case MotionEvent.ACTION_MOVE :
							if (scaleDetector.isInProgress() || activePointerId == -1)
								return true;
							int idx = e.findPointerIndex(activePointerId);
							if (idx < 0)
								return true;
							float x = e.getX(idx), y = e.getY(idx);
							if (gestureMode == GESTURE_UNDECIDED) {
								float dx = x - downX, dy = y - downY;
								if (Math.abs(dx) < 8 * activity.dp && Math.abs(dy) < 8 * activity.dp)
									return true; // not enough movement yet to tell pan from dismiss
								boolean notZoomed = curScale <= baseScale * 1.01f;
								gestureMode = (notZoomed && dy > Math.abs(dx) * 1.5f) ? GESTURE_DISMISS : GESTURE_PAN;
							}
							if (gestureMode == GESTURE_DISMISS) {
								float dy = Math.max(0, y - downY); // only follows downward drags
								setTranslationY(dy);
								setAlpha(Math.max(0.4f, 1f - dy / (dismissThreshold * 3f)));
							} else {
								panX += x - lastTouchX;
								panY += y - lastTouchY;
								invalidate();
							}
							lastTouchX = x;
							lastTouchY = y;
							return true;
						case MotionEvent.ACTION_UP :
						case MotionEvent.ACTION_CANCEL :
							if (gestureMode == GESTURE_DISMISS) {
								if (getTranslationY() > dismissThreshold)
									activity.showMain();
								else
									snapBack();
							}
							activePointerId = -1;
							gestureMode = GESTURE_UNDECIDED;
							return true;
					}
					return true;
				}
			});
		}

		void snapBack() {
			animate().translationY(0).alpha(1f).setDuration(150).start();
		}

		protected void onSizeChanged(int w, int h, int ow, int oh) {
			super.onSizeChanged(w, h, ow, oh);
			if (bmp.getWidth() == 0 || bmp.getHeight() == 0 || w == 0 || h == 0)
				return;
			baseScale = Math.min((float) w / bmp.getWidth(), (float) h / bmp.getHeight());
			curScale = baseScale;
			panX = (w - bmp.getWidth() * curScale) / 2f;
			panY = (h - bmp.getHeight() * curScale) / 2f;
			laidOut = true;
		}

		protected void onDraw(android.graphics.Canvas c) {
			if (!laidOut)
				return;
			matrix.reset();
			matrix.postScale(curScale, curScale);
			matrix.postTranslate(panX, panY);
			c.drawBitmap(bmp, matrix, null);
		}
	}

}
