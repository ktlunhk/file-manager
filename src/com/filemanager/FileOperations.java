package com.filemanager;

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

    /** local files, and items of another MEGA account, are sent into a folder of the account dstDir belongs to */
    void startUploadToMega(ArrayList<File> srcs, MegaItem dstDir, boolean move) {
        new MegaUpJob(srcs, dstDir.acc, dstDir.handle, move).start();
    }

    class MegaUpJob {
        final ArrayList<File> srcs;
        final MegaClient acc; // the account that receives the files
        final String parent;
        final boolean move; // delete the source after it was uploaded (a MEGA source goes to its Rubbish Bin)
        final boolean fromMega; // at least one source lives in another MEGA account: its data is streamed, nothing is saved
        volatile boolean cancelled;
        volatile long done, total;
        volatile String cur = "Preparing...";
        int uploaded, skipped, moveFail;
        long lastUi;
        OperationProgress operationProgress;
        final Runnable updater;

        MegaUpJob(ArrayList<File> srcs, MegaClient acc, String parent, boolean move) {
            this.srcs = srcs;
            this.acc = acc;
            this.parent = parent;
            this.move = move;
            boolean fm = false;
            for (int i = 0; i < srcs.size(); i++) if (srcs.get(i) instanceof MegaItem) fm = true;
            this.fromMega = fm;
            updater = new Runnable() {
                public void run() {
                    if (operationProgress == null) return;
                    int pct = total > 0 ? (int) Math.min(1000L, Math.max(0L, done) * 1000L / total) : 0;
                    operationProgress.update(cur, Math.max(0L, done), total, (pct / 10) + "%   " + activity.human(Math.max(0L, done)) + " / " + activity.human(total));
                }
            };
        }

        void tick(boolean force) {
            long now = System.currentTimeMillis();
            if (!force && now - lastUi < 100) return;
            lastUi = now;
            activity.uiPost(updater);
        }

        long sizeOf(File f) throws IOException {
            if (cancelled) throw new InterruptedIOException("Cancelled");
            if (f.isDirectory() && !activity.isSymlink(f)) {
                long t = 0;
                File[] c = f.listFiles();
                if (c != null) for (int i = 0; i < c.length; i++) t += sizeOf(c[i]);
                return t;
            }
            return Math.max(f.length(), 0L);
        }

        void start() {
            operationProgress = new OperationProgress(activity, fromMega ? "Copying to MEGA" : "Uploading to MEGA", true, true, activity.dp, new Runnable() {
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
                        for (int i = 0; i < srcs.size(); i++) total += sizeOf(srcs.get(i));
                        tick(true);
                        for (int i = 0; i < srcs.size(); i++) upload(srcs.get(i), parent);
                    } catch (InterruptedIOException e) {
                        cancelled = true;
                    } catch (Exception e) {
                        err = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                    finish(err);
                }
            }).start();
        }

        void upload(File f, String parentHandle) throws IOException {
            if (cancelled) throw new InterruptedIOException("Cancelled");
            String name = f.getName();
            if (f.isDirectory()) {
                String h = acc.child(parentHandle, name, true);
                if (h == null) h = acc.makeFolder(name, parentHandle);
                File[] c = f.listFiles();
                if (c != null) for (int i = 0; i < c.length; i++) upload(c[i], h);
                if (move) {
                    File[] left = f.listFiles();
                    if (left != null && left.length == 0) removeSource(f);
                }
                return;
            }
            if (acc.child(parentHandle, name, false) != null) {
                skipped++;
                done += Math.max(f.length(), 0L);
                tick(false);
                return;
            }
            cur = name;
            tick(true);
            MegaClient.Progress pg = new MegaClient.Progress() {
                public void bytes(long n) throws IOException {
                    if (cancelled) throw new InterruptedIOException("Cancelled");
                    done += n;
                    tick(false);
                }
            };
            if (f instanceof MegaItem) {
                // another MEGA account: the download of the source is decrypted and encrypted again on the fly
                MegaItem mi = (MegaItem) f;
                InputStream in = mi.openStream();
                try {
                    acc.uploadStream(in, name, mi.length(), parentHandle, pg);
                } finally {
                    try {
                        in.close();
                    } catch (IOException e) {
                    }
                }
            } else
                acc.uploadFile(f, parentHandle, pg);
            uploaded++;
            if (move) removeSource(f);
        }

        void removeSource(File f) {
            if (f instanceof MegaItem) {
                MegaItem mi = (MegaItem) f;
                if (mi.isSystemNode()) return;
                try {
                    mi.acc.trashOrDelete(mi.handle);
                } catch (IOException e) {
                    moveFail++;
                }
            } else {
                if (!f.delete()) moveFail++;
            }
        }

        void finish(final String err) {
            activity.uiPost(new Runnable() {
                public void run() {
                    try {
                        if (operationProgress != null) operationProgress.dismiss();
                    } catch (Exception e) {
                    }
                    String msg;
                    if (err != null) msg = "Error: " + err;
                    else if (cancelled) msg = "Cancelled";
                    else msg = (move ? "Moved " : fromMega ? "Copied " : "Uploaded ") + uploaded + (uploaded == 1 ? " file" : " files")
                            + (skipped > 0 ? ", skipped " + skipped + " already in MEGA" : "")
                            + (moveFail > 0 ? ", " + moveFail + " could not be removed from the source" : "");
                    activity.selected = null;
                    activity.exitMulti();
                    activity.listCache.clear();
                    activity.refresh();
                    activity.toast(msg);
                    activity.refreshMegaQuota();
                }
            });
        }
    }

    void startTransferToZip(ArrayList<File> srcs, boolean move, String title, String verb, ZipItem dstZip) {
        if (ZipItem.fromMega(dstZip.zip)) {
            activity.toast(ZipItem.MEGA_READ_ONLY);
            return;
        }
        if (ZipItem.isTar(dstZip.zip)) {
            activity.toast("tar archives are read-only");
            return;
        }
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
				activity.uiPost(new Runnable() {
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
		if (!f.delete()) {
			final String failedName = f.getName();
			activity.uiPost(new Runnable() { public void run() { activity.toast("Could not delete: " + failedName); } });
			return;
		}
		done[0]++;
		final long d = done[0];
		final String name = f.getName();
		activity.uiPost(new Runnable() {
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
			File root = isInside(f, activity.rightRoot) && !isInside(f, activity.leftRoot) ? activity.rightRoot : activity.leftRoot;
			File t = trashDir(root);
			t.mkdirs();
			File dst = uniqueInDir(t, f.getName());
			if (f.renameTo(dst)) {
				moved.add(new File[]{f, dst});
			} else {
				// renameTo can fail across filesystems. Never turn a failed trash move into a permanent delete.
				try {
					copyTreeVerified(f, dst);
					if (!deleteChecked(f)) { deleteChecked(dst); moved.add(new File[]{f, null}); }
					else moved.add(new File[]{f, dst});
				} catch (IOException e) {
					deleteChecked(dst);
					moved.add(new File[]{f, null});
				}
			}
		}
		return moved;
	}

	boolean isInside(File child, File parent) {
		if (child == null || parent == null) return false;
		try {
			String c = child.getCanonicalPath();
			String p = parent.getCanonicalPath();
			return c.equals(p) || c.startsWith(p + File.separator);
		} catch (IOException e) { return false; }
	}

	void copyTreeVerified(File src, File dst) throws IOException {
		if (activity.isSymlink(src)) throw new IOException("Refusing to follow symbolic link");
		if (src.isDirectory()) {
			if (!dst.exists() && !dst.mkdirs()) throw new IOException("Cannot create " + dst.getName());
			File[] kids = src.listFiles();
			if (kids == null && src.canRead()) throw new IOException("Cannot list " + src.getName());
			if (kids != null) for (int i=0;i<kids.length;i++) copyTreeVerified(kids[i], new File(dst, kids[i].getName()));
		} else {
			InputStream in = new FileInputStream(src); OutputStream out = null; long nsum=0;
			try { out = new FileOutputStream(dst); byte[] b=new byte[65536]; int n; while((n=in.read(b))>0){out.write(b,0,n); nsum+=n;} out.flush(); }
			finally { try{in.close();}catch(Exception e){} if(out!=null)try{out.close();}catch(Exception e){} }
			if (nsum != src.length() || dst.length() != src.length()) throw new IOException("Verification failed for " + src.getName());
			dst.setLastModified(src.lastModified());
		}
	}

	boolean deleteChecked(File f) {
		if (f == null || !f.exists()) return true;
		if (f.isDirectory() && !activity.isSymlink(f)) { File[] c=f.listFiles(); if(c!=null) for(int i=0;i<c.length;i++) if(!deleteChecked(c[i])) return false; }
		return f.delete();
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
		int policy; // 0 = ask, 1 = overwrite all, 2 = skip all, 3 = keep both all
		int files, copied, skipped, moveFail;
		long lastUi;
		long startedAt;
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
					long elapsed = Math.max(1L, System.currentTimeMillis() - startedAt);
					long speed = done > 0 ? done * 1000L / elapsed : 0L;
					long remain = speed > 0 && total > done ? (total - done) / speed : -1L;
					int itemNo = Math.min(files, copied + skipped + 1);
					String eta = remain >= 0 ? "   ETA " + formatDuration(remain) : "";
					String count = files > 0 ? "   " + itemNo + " / " + files + " files" : "";
					String rate = speed > 0 ? "   " + activity.human(speed) + "/s" : "";
					operationProgress.update(cur, done, total, (pct / 10) + "%   " + activity.human(done) + " / " + activity.human(total) + count + rate + eta);
				}
			};
		}

		void start() {
			startedAt = System.currentTimeMillis();
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
							for (int i = 0; i < srcs.size(); i++) {
								File vs = srcs.get(i);
								File vd = dsts.get(i);
								if (!(vs instanceof ZipItem) && !(vs instanceof MegaItem) && !(vd instanceof ZipItem) && !(vd instanceof MegaItem)) {
									String sp = vs.getCanonicalPath();
									String dp = vd.getCanonicalPath();
									if (sp.equals(dp)) throw new IOException("Source and destination are the same");
									if (vs.isDirectory() && dp.startsWith(sp + File.separator))
										throw new IOException("Cannot copy a folder inside itself");
								}
								total += sizeOf(vs);
							}
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

		String formatDuration(long seconds) {
			if (seconds < 60) return seconds + "s";
			long minutes = seconds / 60;
			if (minutes < 60) return minutes + "m " + (seconds % 60) + "s";
			return (minutes / 60) + "h " + (minutes % 60) + "m";
		}

		void tick(boolean force) {
			long now = System.currentTimeMillis();
			if (!force && now - lastUi < 100)
				return;
			lastUi = now;
			activity.uiPost(updater);
		}

		long sizeOf(File f) throws IOException {
			if (cancelled)
				throw new InterruptedIOException("Cancelled");
			if (f.isDirectory() && !activity.isSymlink(f)) {
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
			if (s.isDirectory() && !activity.isSymlink(s)) {
				if (d.exists() && !d.isDirectory())
					throw new IOException("A file named \"" + d.getName() + "\" is in the way");
				if (!d.exists() && !d.mkdirs())
					throw new IOException("Cannot create folder " + d.getName());
				File[] c = s.listFiles();
				if (c != null)
					for (int i = 0; i < c.length; i++)
						copyNode(c[i], new File(d, c[i].getName()));
				if (move && !(s instanceof ZipItem) && !(s instanceof MegaItem)) {
					File[] left = s.listFiles();
					if (left != null && left.length == 0 && !s.delete())
						moveFail++;
				} else if (move && s instanceof MegaItem && !((MegaItem) s).isSystemNode()) {
					File[] left = s.listFiles();
					if (left != null && left.length == 0) {
						try {
							((MegaItem) s).acc.trashOrDelete(((MegaItem) s).handle);
						} catch (IOException e) {
							moveFail++;
						}
					}
				}
				return;
			}
			boolean over = false;
			if (d.exists()) {
				if (d.isDirectory()) throw new IOException("A folder named \"" + d.getName() + "\" is in the way");
				int decision = resolveAction(s, d);
				if (decision == 2) { skipped++; done += Math.max(s.length(), 0L); tick(false); return; }
				if (decision == 3 || cancelled) throw new InterruptedIOException("Cancelled");
				if (decision == 4) d = uniqueInDir(d.getParentFile(), d.getName());
				else over = true;
			}
			cur = s.getName();
			tick(true);
			copyData(s, d, over);
			copied++;
			if (move && !(s instanceof ZipItem) && !(s instanceof MegaItem)) {
				if (!s.delete())
					moveFail++;
			} else if (move && s instanceof MegaItem) {
				try {
					((MegaItem) s).acc.trashOrDelete(((MegaItem) s).handle);
				} catch (IOException e) {
					moveFail++;
				}
			}
		}

		void copyData(File s, File d, boolean over) throws IOException {
			// Always write local output to a temporary file first. A cancelled or failed copy never leaves a partial destination.
			File parent = d.getParentFile();
			if (parent == null) throw new IOException("Invalid destination");
			File target = new File(parent, d.getName() + ".dfm-part");
			int partNo = 2;
			while (target.exists()) target = new File(parent, d.getName() + ".dfm-part-" + (partNo++));
			InputStream in = null;
			OutputStream out = null;
			PZip zf = null;
			boolean ok = false;
			try {
				if (s instanceof ZipItem) {
					ZipItem zi = (ZipItem) s;
					zf = new PZip(zi.zip);
					PEntry ze = zf.getEntry(zi.entry);
					if (ze == null) throw new IOException("Entry not found: " + zi.entry);
					in = zf.getInputStream(ze);
				} else if (s instanceof MegaItem) in = ((MegaItem) s).openStream();
				else in = new FileInputStream(s);
				out = new BufferedOutputStream(new FileOutputStream(target));
				byte[] buf = new byte[65536];
				int n;
				while ((n = in.read(buf)) > 0) {
					if (cancelled) throw new InterruptedIOException("Cancelled");
					out.write(buf, 0, n);
					done += n;
					tick(false);
				}
				out.flush();
				out.close(); out = null;
				if (!(s instanceof ZipItem) && !(s instanceof MegaItem) && target.length() != s.length())
					throw new IOException("Copy verification failed for " + s.getName());

				File backup = null;
				if (d.exists()) {
					backup = new File(parent, d.getName() + ".dfm-old");
					int oldNo = 2;
					while (backup.exists()) backup = new File(parent, d.getName() + ".dfm-old-" + (oldNo++));
					if (!d.renameTo(backup)) throw new IOException("Cannot safely replace " + d.getName());
				}
				if (!target.renameTo(d)) {
					if (backup != null) backup.renameTo(d);
					throw new IOException("Cannot finalize " + d.getName());
				}
				if (backup != null) deleteChecked(backup);
				ok = true;
			} finally {
				if (in != null) try { in.close(); } catch (IOException e) { }
				if (out != null) try { out.close(); } catch (IOException e) { }
				if (zf != null) try { zf.close(); } catch (IOException e) { }
				if (!ok) target.delete();
			}
		}

		String stamp(long size, long time) {
			DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
			return activity.human(Math.max(size, 0L)) + ", " + df.format(new Date(time));
		}

		int resolveAction(File s, File d) {
			File par = d.getParentFile();
			return ask(d.getName(), par != null ? par.getName() : null, stamp(d.length(), d.lastModified()), stamp(s.length(), s.lastModified()));
		}

		// 1 overwrite, 2 skip, 3 cancel, 4 keep both. Called on worker thread and waits for UI choice.
		int ask(final String name, final String where, final String exInfo, final String newInfo) {
			if (policy == 1) return 1;
			if (policy == 2) return 2;
			if (policy == 3) return 4;
			final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
			final int[] choice = {0}; // 1 overwrite, 2 skip, 3 cancel
			if (activity.isFinishing()) { // screen was recreated: the dialog cannot be shown
				cancelled = true;
				return 3;
			}
			activity.uiPost(new Runnable() {
				public void run() {
					String msg = "\"" + name + "\" already exists" + (where != null ? " in " + where : "") + ".\n\n"
							+ "Existing: " + exInfo + "\nNew: " + newInfo + "\n\nOverwrite?";
					AlertDialog.Builder b = activity.createDialog("File exists", msg);
					final CheckBox all = new CheckBox(b.getContext());
					activity.themeDialogView(all);
					all.setText("Apply to all (" + files + " files)");
					b.setCancelable(true);
					b.setOnCancelListener(new DialogInterface.OnCancelListener() { public void onCancel(DialogInterface di) { choice[0] = 3; cancelled = true; latch.countDown(); } });
					if (files > 1) {
						android.widget.FrameLayout allBox = new android.widget.FrameLayout(b.getContext());
						activity.padDialogBox(allBox);
						allBox.addView(all, new android.widget.FrameLayout.LayoutParams(-1, -2));
						b.setView(allBox);
					}
					DialogInterface.OnClickListener l = new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface di, int which) {
							if (which == DialogInterface.BUTTON_POSITIVE)
								choice[0] = 1;
							else if (which == DialogInterface.BUTTON_NEGATIVE)
								choice[0] = 2;
							else if (which == DialogInterface.BUTTON_NEUTRAL)
								choice[0] = 4;
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
					b.setPositiveButton("Overwrite", l).setNegativeButton("Skip", l).setNeutralButton("Keep both", l);
					b.show();
				}
			});
			try {
				latch.await();
			} catch (InterruptedException e) {
				cancelled = true;
				return 3;
			}
			return choice[0];
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

		String uniqueZipEntryName(String name, HashMap<String, long[]> exist, HashSet<String> dirSet) {
			int slash = name.lastIndexOf('/');
			String path = slash >= 0 ? name.substring(0, slash + 1) : "";
			String base = slash >= 0 ? name.substring(slash + 1) : name;
			int dot = base.lastIndexOf('.');
			String stem = dot > 0 ? base.substring(0, dot) : base;
			String ext = dot > 0 ? base.substring(dot) : "";
			int n = 2;
			String candidate = path + stem + " (" + n + ")" + ext;
			while (exist.containsKey(candidate) || dirSet.contains(candidate)) {
				n++;
				candidate = path + stem + " (" + n + ")" + ext;
			}
			return candidate;
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
			if (PZip.needsPassword(zip))
				throw new IOException("Adding to a password-protected zip is not supported");
			PZip zf0 = new PZip(zip);
			try {
				Enumeration<PEntry> en = zf0.entries();
				while (en.hasMoreElements()) {
					PEntry ze = en.nextElement();
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
						int action = ask(nm, zip.getName(), stamp(ex[0], ex[1]), stamp(it.s.length(), it.s.lastModified()));
						if (action == 1) {
							it.replace = true;
						} else if (action == 4) {
							it.name = uniqueZipEntryName(it.name, exist, dirSet);
							exist.put(it.name, new long[]{it.s.length(), it.s.lastModified()});
						} else if (action == 3) {
							cancelled = true;
							throw new InterruptedIOException("Cancelled");
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
			PZip zf = null;
			ZipOutputStream zo = null;
			boolean ok = false;
			try {
				zf = new PZip(zip);
				zo = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)));
				cur = "Rewriting " + zip.getName() + "...";
				tick(true);
				Enumeration<PEntry> en = zf.entries();
				while (en.hasMoreElements()) {
					if (cancelled)
						throw new InterruptedIOException("Cancelled");
					PEntry ze = en.nextElement();
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
					PZip sz = null;
					try {
						if (it.s instanceof ZipItem) {
							ZipItem zi = (ZipItem) it.s;
							sz = new PZip(zi.zip);
							PEntry se = sz.getEntry(zi.entry);
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
				File oldZip = new File(zip.getParentFile(), zip.getName() + ".dfm-old");
				int oldNo = 2;
				while (oldZip.exists()) oldZip = new File(zip.getParentFile(), zip.getName() + ".dfm-old-" + (oldNo++));
				if (!zip.renameTo(oldZip)) throw new IOException("Cannot safely replace zip");
				if (!tmp.renameTo(zip)) {
					oldZip.renameTo(zip);
					throw new IOException("Cannot finalize temp zip");
				}
				deleteChecked(oldZip);
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
			activity.uiPost(new Runnable() {
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
		String firstFail;
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
						if (res.get(0)[1] == null) {
							failed++;
							if (firstFail == null)
								firstFail = f.getName();
						}
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
			activity.uiPost(updater);
		}

		void finish(final ArrayList<File[]> moved) {
			activity.uiPost(new Runnable() {
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
						msg = "Moved " + ok + " to trash, " + failed + " could not be trashed" + (firstFail != null ? " (" + firstFail + ")" : "");
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
