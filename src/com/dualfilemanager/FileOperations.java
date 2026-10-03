package com.dualfilemanager;

import android.app.*;
import android.content.*;
import android.widget.CheckBox;
import java.io.*;
import java.text.*;
import java.util.*;
import java.util.zip.*;

class FileOperations {
    final MainActivity activity;

    FileOperations(MainActivity activity) {
        this.activity = activity;
    }

    void startTransfer(File src, File dst, boolean move, String title, String verb) {
        new Job(src, dst, move, title, verb).start();
    }

    void startTransfer(ArrayList<File> srcs, ArrayList<File> dsts, boolean move, String title, String verb, int pre) {
        Job j = new Job(srcs, dsts, move, title, verb);
        j.pre = pre;
        j.start();
    }

    void startTransferToZip(ArrayList<File> srcs, boolean move, String title, String verb, ZipItem dstZip) {
        Job j = new Job(srcs, new ArrayList<File>(), move, title, verb);
        j.dstZip = dstZip;
        j.start();
    }

    void deleteToTrash(ArrayList<File> targets) {
        new DelJob(targets).start();
    }

    ArrayList<File> one(File f) {
        ArrayList<File> a = new ArrayList<File>();
        a.add(f);
        return a;
    }

	void emptyTrash(final ArrayList<File> targets) {
		final OperationProgress progress = new OperationProgress(activity, "Emptying Trash", true, true, activity.dp);
		progress.update("Preparing...", 0, 1, "Counting items...");
		new Thread(new Runnable() {
			public void run() {
				final long[] total = {0};
				for (int i = 0; i < targets.size(); i++) total[0] += countDeleteNodes(targets.get(i));
				final long[] done = {0};
				for (int i = 0; i < targets.size() && !progress.cancelled; i++)
					deleteWithProgress(targets.get(i), done, total[0], progress);
				final boolean cancelled = progress.cancelled;
				activity.runOnUiThread(new Runnable() {
					public void run() {
						progress.dismiss();
						activity.lastTrashBatch = null; activity.hideUndoBar(); activity.refresh();
						activity.toast(cancelled ? "Empty Trash cancelled" : "Trash emptied");
					}
				});
			}
		}).start();
	}

	long countDeleteNodes(File f) {
		long n = 1;
		if (f.isDirectory()) {
			File[] c = f.listFiles();
			if (c != null) for (int i = 0; i < c.length; i++) n += countDeleteNodes(c[i]);
		}
		return n;
	}

	void deleteWithProgress(final File f, final long[] done, final long total, final OperationProgress progress) {
		if (progress.cancelled) return;
		if (f.isDirectory()) {
			File[] c = f.listFiles();
			if (c != null) for (int i = 0; i < c.length && !progress.cancelled; i++)
				deleteWithProgress(c[i], done, total, progress);
		}
		if (progress.cancelled) return;
		f.delete();
		done[0]++;
		final long d = done[0];
		final String name = f.getName();
		activity.runOnUiThread(new Runnable() {
			public void run() {
				int pct = total > 0 ? (int) (d * 100L / total) : 100;
				progress.update(name, d, total, pct + "%   " + d + " / " + total + " items");
			}
		});
	}

	File trashDir(File root) { return activity.trashDir(root); }

	ArrayList<File[]> moveToTrash(ArrayList<File> targets) {
		ArrayList<File[]> moved = new ArrayList<File[]>();
		for (int i = 0; i < targets.size(); i++) {
			File f = targets.get(i);
			File root = f.getAbsolutePath().startsWith(activity.rightRoot.getAbsolutePath())
					&& !f.getAbsolutePath().startsWith(activity.leftRoot.getAbsolutePath()) ? activity.rightRoot : activity.leftRoot;
			File t = trashDir(root);
			t.mkdirs();
			File dst = uniqueInDir(t, f.getName());
			if (f.renameTo(dst))
				moved.add(new File[]{f, dst});
			else {
				delete(f);
				moved.add(new File[]{f, null});
			} // couldn't move: gone for good
		}
		return moved;
	}

	File uniqueInDir(File dir, String name) {
		File out = new File(dir, name);
		if (!out.exists())
			return out;
		String base = name, ext = "";
		int dot = name.lastIndexOf('.');
		if (dot > 0) {
			base = name.substring(0, dot);
			ext = name.substring(dot);
		}
		int i = 2;
		while (out.exists()) {
			out = new File(dir, base + " (" + i + ")" + ext);
			i++;
		}
		return out;
	}

	class Job {
		final File src, dst;
		final boolean move;
		final String title, verb;
		final ArrayList<File> srcs, dsts; // one entry each for a single item, several for a multi-selection
		int pre; // items already moved by a plain rename before the job started
		volatile boolean cancelled;
		volatile long done, total;
		volatile String cur = "Preparing...";
		int policy; // 0 = ask, 1 = overwrite all, 2 = skip all
		int files, copied, skipped, moveFail;
		long lastUi;
		OperationProgress operationProgress;
		final Runnable updater;
		ZipItem dstZip; // non-null: destination is a folder inside a zip (or the zip itself)

		Job(File src, File dst, boolean move, String title, String verb) {
			this(one(src), one(dst), move, title, verb);
		}

		Job(ArrayList<File> srcs, ArrayList<File> dsts, boolean move, String title, String verb) {
			this.srcs = srcs;
			this.dsts = dsts;
			this.src = srcs.get(0);
			this.dst = dsts.isEmpty() ? srcs.get(0) : dsts.get(0);
			this.move = move;
			this.title = title;
			this.verb = verb;
			updater = new Runnable() {
				public void run() {
					if (operationProgress == null) return;
					int pct = total > 0 ? (int) Math.min(1000L, done * 1000L / total) : 0;
					operationProgress.update(cur, done, total, (pct / 10) + "%   " + activity.human(done) + " / " + activity.human(total));
				}
			};
		}

		void start() {
			operationProgress = new OperationProgress(activity, title, true, true, activity.dp, new Runnable() {
				public void run() {
					cancelled = true;
					cur = "Cancelling...";
				}
			});
			updater.run();
			new Thread(new Runnable() {
				public void run() {
					String err = null;
					try {
						if (dstZip != null) {
							addToZip();
						} else {
							total = 0;
							for (int i = 0; i < srcs.size(); i++)
								total += sizeOf(srcs.get(i));
							tick(true);
							for (int i = 0; i < srcs.size(); i++)
								copyNode(srcs.get(i), dsts.get(i));
						}
					} catch (InterruptedIOException e) {
						cancelled = true;
					} catch (Exception e) {
						err = e.getMessage() == null ? e.toString() : e.getMessage();
					}
					finish(err);
				}
			}).start();
		}

		void tick(boolean force) {
			long now = System.currentTimeMillis();
			if (!force && now - lastUi < 100)
				return;
			lastUi = now;
			activity.runOnUiThread(updater);
		}

		long sizeOf(File f) throws IOException {
			if (cancelled)
				throw new InterruptedIOException("Cancelled");
			if (f.isDirectory()) {
				long t = 0;
				File[] c = f.listFiles();
				if (c != null)
					for (int i = 0; i < c.length; i++)
						t += sizeOf(c[i]);
				return t;
			}
			files++;
			return Math.max(f.length(), 0L);
		}

		void copyNode(File s, File d) throws IOException {
			if (cancelled)
				throw new InterruptedIOException("Cancelled");
			if (s.isDirectory()) {
				if (d.exists() && !d.isDirectory())
					throw new IOException("A file named \"" + d.getName() + "\" is in the way");
				if (!d.exists() && !d.mkdirs())
					throw new IOException("Cannot create folder " + d.getName());
				File[] c = s.listFiles();
				if (c != null)
					for (int i = 0; i < c.length; i++)
						copyNode(c[i], new File(d, c[i].getName()));
				if (move && !(s instanceof ZipItem)) {
					File[] left = s.listFiles();
					if (left != null && left.length == 0)
						s.delete();
				}
				return;
			}
			boolean over = false;
			if (d.exists()) {
				if (d.isDirectory())
					throw new IOException("A folder named \"" + d.getName() + "\" is in the way");
				if (!resolve(s, d)) {
					skipped++;
					done += Math.max(s.length(), 0L);
					tick(false);
					return;
				}
				if (cancelled)
					throw new InterruptedIOException("Cancelled");
				over = true;
			}
			cur = s.getName();
			tick(true);
			copyData(s, d, over);
			copied++;
			if (move && !(s instanceof ZipItem)) {
				if (!s.delete())
					moveFail++;
			}
		}

		void copyData(File s, File d, boolean over) throws IOException {
			// when overwriting, write to a temp file first so a failure/cancel keeps the original
			File target = over ? new File(d.getParentFile(), d.getName() + ".dfm-part") : d;
			InputStream in = null;
			OutputStream out = null;
			ZipFile zf = null;
			boolean ok = false;
			try {
				if (s instanceof ZipItem) {
					ZipItem zi = (ZipItem) s;
					zf = new ZipFile(zi.zip);
					ZipEntry ze = zf.getEntry(zi.entry);
					if (ze == null)
						throw new IOException("Entry not found: " + zi.entry);
					in = zf.getInputStream(ze);
				} else
					in = new FileInputStream(s);
				out = new FileOutputStream(target);
				byte[] buf = new byte[65536];
				int n;
				while ((n = in.read(buf)) > 0) {
					if (cancelled)
						throw new InterruptedIOException("Cancelled");
					out.write(buf, 0, n);
					done += n;
					tick(false);
				}
				out.close();
				out = null;
				if (over) {
					if (!d.delete())
						throw new IOException("Cannot replace " + d.getName());
					if (!target.renameTo(d))
						throw new IOException("Cannot rename temp file");
				}
				ok = true;
			} finally {
				if (in != null)
					try {
						in.close();
					} catch (IOException e) {
					}
				if (out != null)
					try {
						out.close();
					} catch (IOException e) {
					}
				if (zf != null)
					try {
						zf.close();
					} catch (IOException e) {
					}
				if (!ok)
					target.delete();
			}
		}

		String stamp(long size, long time) {
			DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
			return activity.human(Math.max(size, 0L)) + ", " + df.format(new Date(time));
		}

		boolean resolve(File s, File d) {
			File par = d.getParentFile();
			return ask(d.getName(), par != null ? par.getName() : null, stamp(d.length(), d.lastModified()),
					stamp(s.length(), s.lastModified()));
		}

		// Called on the worker thread: shows the dialog on the UI thread and waits.
		boolean ask(final String name, final String where, final String exInfo, final String newInfo) {
			if (policy == 1)
				return true;
			if (policy == 2)
				return false;
			final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
			final int[] choice = {0}; // 1 overwrite, 2 skip, 3 cancel
			activity.runOnUiThread(new Runnable() {
				public void run() {
					String msg = "\"" + name + "\" already exists" + (where != null ? " in " + where : "") + ".\n\n"
							+ "Existing: " + exInfo + "\nNew: " + newInfo + "\n\nOverwrite?";
					AlertDialog.Builder b = activity.createDialog("File exists", msg);
					final CheckBox all = new CheckBox(b.getContext());
					activity.themeDialogView(all);
					all.setText("Apply to all (" + files + " files)");
					b.setCancelable(false);
					if (files > 1)
						b.setView(all);
					DialogInterface.OnClickListener l = new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface di, int which) {
							if (which == DialogInterface.BUTTON_POSITIVE)
								choice[0] = 1;
							else if (which == DialogInterface.BUTTON_NEGATIVE)
								choice[0] = 2;
							else
								choice[0] = 3;
							if (all.isChecked()) {
								if (choice[0] == 1)
									policy = 1;
								if (choice[0] == 2)
									policy = 2;
							}
							latch.countDown();
						}
					};
					b.setPositiveButton("Overwrite", l).setNegativeButton("Skip", l).setNeutralButton("Cancel", l);
					b.show();
				}
			});
			try {
				latch.await();
			} catch (InterruptedException e) {
				cancelled = true;
				return false;
			}
			if (choice[0] == 3) {
				cancelled = true;
				return false;
			}
			return choice[0] == 1;
		}

		// ---- copying INTO a zip (rewrites the zip: old entries + new ones) ----
		class ZItem {
			File s;
			String name;
			boolean dir, skip, replace, haveDir, added;
		}

		void collect(File s, String name, ArrayList<ZItem> out) {
			if (s.equals(dstZip.zip))
				return; // never add the zip into itself
			ZItem it = new ZItem();
			it.s = s;
			it.name = name;
			it.dir = s.isDirectory();
			out.add(it);
			if (it.dir) {
				File[] c = s.listFiles();
				if (c != null)
					for (int i = 0; i < c.length; i++) {
						String cn = c[i].getName();
						if (cn.equals(".") || cn.equals("..") || cn.length() == 0)
							continue;
						collect(c[i], name + "/" + cn, out);
					}
			}
		}

		void pump(InputStream in, OutputStream out) throws IOException {
			byte[] buf = new byte[65536];
			int n;
			while ((n = in.read(buf)) > 0) {
				if (cancelled)
					throw new InterruptedIOException("Cancelled");
				out.write(buf, 0, n);
				done += n;
				tick(false);
			}
		}

		void addToZip() throws IOException {
			ArrayList<ZItem> items = new ArrayList<ZItem>();
			String prefix = dstZip.isRoot() ? "" : dstZip.entry + "/";
			for (int i = 0; i < srcs.size(); i++)
				collect(srcs.get(i), prefix + srcs.get(i).getName(), items);
			if (items.isEmpty())
				return;
			File zip = dstZip.zip;

			// what the zip already contains
			HashMap<String, long[]> exist = new HashMap<String, long[]>();
			HashSet<String> dirSet = new HashSet<String>();
			long oldTotal = 0, newTotal = 0;
			ZipFile zf0 = new ZipFile(zip);
			try {
				Enumeration<? extends ZipEntry> en = zf0.entries();
				while (en.hasMoreElements()) {
					ZipEntry ze = en.nextElement();
					String n = ze.getName();
					exist.put(n, new long[]{ze.getSize(), ze.getTime()});
					oldTotal += Math.max(ze.getSize(), 0L);
					int p = n.indexOf('/');
					while (p >= 0) {
						dirSet.add(n.substring(0, p));
						p = n.indexOf('/', p + 1);
					}
				}
			} finally {
				zf0.close();
			}
			for (int i = 0; i < items.size(); i++) {
				ZItem it = items.get(i);
				if (!it.dir) {
					newTotal += Math.max(it.s.length(), 0L);
					files++;
				}
			}
			total = oldTotal + newTotal;
			cur = "Checking...";
			tick(true);

			// conflicts
			for (int i = 0; i < items.size(); i++) {
				ZItem it = items.get(i);
				if (it.dir) {
					if (exist.containsKey(it.name))
						throw new IOException("A file named \"" + it.name + "\" is in the way");
					if (dirSet.contains(it.name) || exist.containsKey(it.name + "/"))
						it.haveDir = true;
				} else {
					if (dirSet.contains(it.name))
						throw new IOException("A folder named \"" + it.name + "\" is in the way");
					long[] ex = exist.get(it.name);
					if (ex != null) {
						String nm = it.s.getName();
						if (ask(nm, zip.getName(), stamp(ex[0], ex[1]), stamp(it.s.length(), it.s.lastModified()))) {
							it.replace = true;
						} else {
							it.skip = true;
							skipped++;
							total -= Math.max(it.s.length(), 0L);
						}
						if (cancelled)
							throw new InterruptedIOException("Cancelled");
					}
				}
			}
			HashSet<String> drop = new HashSet<String>();
			for (int i = 0; i < items.size(); i++)
				if (items.get(i).replace)
					drop.add(items.get(i).name);

			File tmp = new File(zip.getParentFile(), zip.getName() + ".dfm-tmp");
			ZipFile zf = null;
			ZipOutputStream zo = null;
			boolean ok = false;
			try {
				zf = new ZipFile(zip);
				zo = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)));
				cur = "Rewriting " + zip.getName() + "...";
				tick(true);
				Enumeration<? extends ZipEntry> en = zf.entries();
				while (en.hasMoreElements()) {
					if (cancelled)
						throw new InterruptedIOException("Cancelled");
					ZipEntry ze = en.nextElement();
					String name = ze.getName();
					if (drop.contains(name))
						continue;
					ZipEntry ne = new ZipEntry(name);
					if (ze.getTime() >= 0)
						ne.setTime(ze.getTime());
					zo.putNextEntry(ne);
					if (!ze.isDirectory()) {
						InputStream in = zf.getInputStream(ze);
						try {
							pump(in, zo);
						} finally {
							try {
								in.close();
							} catch (IOException e) {
							}
						}
					}
					zo.closeEntry();
				}
				for (int i = 0; i < items.size(); i++) {
					if (cancelled)
						throw new InterruptedIOException("Cancelled");
					ZItem it = items.get(i);
					if (it.skip)
						continue;
					ZipEntry ne = new ZipEntry(it.dir ? it.name + "/" : it.name);
					if (it.s.lastModified() > 0)
						ne.setTime(it.s.lastModified());
					if (it.dir) {
						if (!it.haveDir) {
							zo.putNextEntry(ne);
							zo.closeEntry();
						}
						continue;
					}
					cur = it.s.getName();
					tick(true);
					zo.putNextEntry(ne);
					InputStream in = null;
					ZipFile sz = null;
					try {
						if (it.s instanceof ZipItem) {
							ZipItem zi = (ZipItem) it.s;
							sz = new ZipFile(zi.zip);
							ZipEntry se = sz.getEntry(zi.entry);
							if (se == null)
								throw new IOException("Entry not found: " + zi.entry);
							in = sz.getInputStream(se);
						} else
							in = new FileInputStream(it.s);
						pump(in, zo);
					} finally {
						if (in != null)
							try {
								in.close();
							} catch (IOException e) {
							}
						if (sz != null)
							try {
								sz.close();
							} catch (IOException e) {
							}
					}
					zo.closeEntry();
					it.added = true;
					copied++;
				}
				zo.close();
				zo = null;
				zf.close();
				zf = null;
				if (!zip.delete())
					throw new IOException("Cannot replace zip");
				if (!tmp.renameTo(zip))
					throw new IOException("Cannot rename temp zip");
				ok = true;
			} finally {
				if (zo != null)
					try {
						zo.close();
					} catch (IOException e) {
					}
				if (zf != null)
					try {
						zf.close();
					} catch (IOException e) {
					}
				if (!ok)
					tmp.delete();
			}

			// Move: remove what was added from the source
			if (move) {
				for (int i = 0; i < items.size(); i++) {
					ZItem it = items.get(i);
					if (!it.dir && it.added && !(it.s instanceof ZipItem) && !it.s.delete())
						moveFail++;
				}
				for (int i = items.size() - 1; i >= 0; i--) {
					ZItem it = items.get(i);
					if (it.dir && !(it.s instanceof ZipItem)) {
						File[] l = it.s.listFiles();
						if (l != null && l.length == 0)
							it.s.delete();
					}
				}
			}
		}

		void finish(final String err) {
			activity.runOnUiThread(new Runnable() {
				public void run() {
					try {
						if (operationProgress != null)
							operationProgress.dismiss();
					} catch (Exception e) {
					}
					boolean okAll = err == null && !cancelled;
					String msg;
					if (err != null)
						msg = "Error: " + err;
					else if (cancelled)
						msg = "Cancelled";
					else if (copied == 0 && skipped == 0)
						msg = verb;
					else
						msg = verb + " " + copied + (copied == 1 ? " file" : " files")
								+ (skipped > 0 ? ", skipped " + skipped : "")
								+ (moveFail > 0 ? ", " + moveFail + " could not be removed from source" : "");
					activity.selected = null;
					if (pre > 0 && err == null && !cancelled)
						msg += ", " + pre + (pre == 1 ? " more item" : " more items") + " moved instantly";
					activity.exitMulti();
					if (okAll && move) {
						ArrayList<ZipItem> zs = new ArrayList<ZipItem>();
						for (int i = 0; i < srcs.size(); i++)
							if (srcs.get(i) instanceof ZipItem)
								zs.add((ZipItem) srcs.get(i));
						if (!zs.isEmpty()) {
							if (skipped == 0) {
								ZipItem.zipDeleteMany(activity, zs, msg + " (removed from zip)");
								return;
							}
							msg += " - zip left unchanged";
						}
					}
					activity.refresh();
					activity.toast(msg);
				}
			});
		}
	}

	class DelJob {
		final ArrayList<File> targets;
		volatile boolean cancelled;
		volatile int done, total, failed;
		volatile String cur = "Preparing...";
		long lastUi;
		OperationProgress operationProgress;
		final Runnable updater;

		DelJob(ArrayList<File> t) {
			targets = t;
			updater = new Runnable() {
				public void run() {
					if (operationProgress == null) return;
					int pct = total > 0 ? (int) Math.min(1000L, done * 1000L / total) : 0;
					operationProgress.update(cur, done, total, (pct / 10) + "%   " + done + " / " + total + " items");
				}
			};
		}

		void start() {
			operationProgress = new OperationProgress(activity, "Deleting", true, true, activity.dp, new Runnable() {
				public void run() { cancelled = true; cur = "Cancelling..."; }
			});
			updater.run();
			total = targets.size();
			new Thread(new Runnable() {
				public void run() {
					final ArrayList<File[]> moved = new ArrayList<File[]>();
					for (int i = 0; i < targets.size(); i++) {
						if (cancelled)
							break;
						File f = targets.get(i);
						cur = f.getName();
						tick(true);
						ArrayList<File[]> res = moveToTrash(one(f));
						moved.addAll(res);
						if (res.get(0)[1] == null)
							failed++;
						done++;
						tick(false);
					}
					finish(moved);
				}
			}).start();
		}

		void tick(boolean force) {
			long now = System.currentTimeMillis();
			if (!force && now - lastUi < 100)
				return;
			lastUi = now;
			activity.runOnUiThread(updater);
		}

		void finish(final ArrayList<File[]> moved) {
			activity.runOnUiThread(new Runnable() {
				public void run() {
					try {
						if (operationProgress != null)
							operationProgress.dismiss();
					} catch (Exception e) {
					}
					String msg;
					int ok = done - failed;
					if (cancelled)
						msg = "Cancelled (" + ok + " moved to trash)";
					else if (failed > 0)
						msg = "Moved " + ok + " to trash, " + failed + " could not be trashed";
					else
						msg = "Moved " + done + (done == 1 ? " item" : " items") + " to trash";
					if (ok > 0)
						activity.offerUndo(moved);
					activity.selected = null;
					activity.exitMulti();
					activity.refresh();
					activity.toast(msg);
				}
			});
		}
	}

    boolean delete(File f) {
        if (f == null || !f.exists()) return true;
        if (f.isDirectory() && !activity.isSymlink(f)) {
            File[] children = f.listFiles();
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    if (!delete(children[i])) return false;
                }
            }
        }
        return f.delete();
    }
}
