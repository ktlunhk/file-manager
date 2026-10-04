package com.filemanager;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/** AlertDialog.Builder whose dialogs can be moved by dragging them (title, message or any empty area). */
class DragBuilder extends AlertDialog.Builder {
	DragBuilder(Context c, int theme) {
		super(c, theme);
	}

	public AlertDialog create() {
		AlertDialog d = super.create();
		d.setOnShowListener(new DialogInterface.OnShowListener() {
			public void onShow(DialogInterface di) {
				attach((AlertDialog) di);
			}
		});
		return d;
	}

	static void attach(AlertDialog d) {
		final Window w = d.getWindow();
		if (w == null)
			return;
		w.setGravity(Gravity.CENTER);
		View content = w.getDecorView().findViewById(android.R.id.content);
		if (content == null)
			content = w.getDecorView();
		final View decor = w.getDecorView();
		final float density = d.getContext().getResources().getDisplayMetrics().density;
		final DisplayMetrics dm = d.getContext().getResources().getDisplayMetrics();
		// buttons, lists and text fields handle their own touches; only the free space of the dialog starts a drag
		content.setOnTouchListener(new View.OnTouchListener() {
			float downX, downY;
			int startX, startY;

			public boolean onTouch(View v, MotionEvent e) {
				WindowManager.LayoutParams lp = w.getAttributes();
				int a = e.getActionMasked();
				if (a == MotionEvent.ACTION_DOWN) {
					downX = e.getRawX();
					downY = e.getRawY();
					startX = lp.x;
					startY = lp.y;
					return true;
				}
				if (a == MotionEvent.ACTION_MOVE) {
					int keep = (int) (64 * density); // this much of the dialog always stays on screen
					int dw = decor.getWidth(), dh = decor.getHeight();
					int maxX = Math.max(0, (dm.widthPixels + dw) / 2 - keep);
					int minY = -Math.max(0, (dm.heightPixels - dh) / 2);
					int maxY = Math.max(0, (dm.heightPixels + dh) / 2 - keep);
					int nx = startX + (int) (e.getRawX() - downX);
					int ny = startY + (int) (e.getRawY() - downY);
					lp.x = Math.max(-maxX, Math.min(maxX, nx));
					lp.y = Math.max(minY, Math.min(maxY, ny));
					w.setAttributes(lp);
					return true;
				}
				return a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL;
			}
		});
	}
}
