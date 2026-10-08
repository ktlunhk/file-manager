package com.filemanager;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.util.TypedValue;
import android.graphics.Color;

/** AlertDialog.Builder whose dialogs can be moved by dragging them (title, message or any empty area). */
class DragBuilder extends AlertDialog.Builder {
    private final boolean popupDark;
    DragBuilder(Context c, int theme) {
        super(c, theme);
        // MainActivity supplies the selected light/dark dialog theme explicitly.
        popupDark = theme == android.R.style.Theme_Material_Dialog_Alert;
    }

    // Use a fixed title inset at creation time. Do not translate the title
    // or modify its padding after show: those approaches cause visual jumps.
    @Override
    public AlertDialog.Builder setTitle(CharSequence title) {
        if (title == null) return super.setTitle((CharSequence) null);
        Context context = getContext();
        float density = context.getResources().getDisplayMetrics().density;
        TextView header = new TextView(context);
        header.setText(title);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        // Resolve the color from the app-selected popup theme, not the
        // Activity theme (which can retain a pink system accent).
        header.setTextColor(popupDark ? Color.WHITE : Color.BLACK);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding((int)(16*density), (int)(20*density),
                          (int)(24*density), (int)(12*density));
        return super.setCustomTitle(header);
    }

    @Override
    public AlertDialog.Builder setTitle(int titleId) {
        return setTitle(getContext().getText(titleId));
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
		shrinkMenu(d, w);
        // Align standard message dialogs (checksums, duplicate finder results,
        // storage analyzer results) with the 16dp custom-title inset.
        // Do not translate views or modify the header after opening.
        TextView messageView = (TextView) w.getDecorView().findViewById(android.R.id.message);
        if (messageView != null) {
            int inset = (int)(16 * d.getContext().getResources().getDisplayMetrics().density);
            messageView.setPadding(inset, messageView.getPaddingTop(),
                                   messageView.getPaddingRight(), messageView.getPaddingBottom());
        }
        // Keep the platform title's original position. Changing its padding
        // after show caused a visible jump in long-press menus and occasionally
        // clipped the first character on some Android themes.
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


	/** menu dialogs (list of items): fit the width to the title and the widest item instead of the fixed wide dialog */
	static void shrinkMenu(AlertDialog d, Window w) {
		try {
			View lvView = w.getDecorView().findViewById(android.R.id.list);
			if (!(lvView instanceof ListView))
				return;
			ListView lv = (ListView) lvView;
			ListAdapter ad = lv.getAdapter();
			if (ad == null || ad.getCount() == 0)
				return;
			DisplayMetrics dm = d.getContext().getResources().getDisplayMetrics();
			float density = dm.density;
			int unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
			int widest = 0;
			for (int i = 0; i < ad.getCount(); i++) {
				View row = ad.getView(i, null, lv);
				row.measure(unspec, unspec);
				widest = Math.max(widest, row.getMeasuredWidth());
			}
			widest = widest + lv.getPaddingLeft() + lv.getPaddingRight() + (int) (16 * density);
			int titleId = d.getContext().getResources().getIdentifier("alertTitle", "id", "android");
			if (titleId != 0) {
				View title = w.getDecorView().findViewById(titleId);
				if (title != null) {
					title.measure(unspec, unspec);
					widest = Math.max(widest, title.getMeasuredWidth() + (int) (48 * density));
				}
			}
			int maxW = (int) (dm.widthPixels * 0.9f);
			int minW = (int) (240 * density);
			w.setLayout(Math.max(minW, Math.min(maxW, widest)), WindowManager.LayoutParams.WRAP_CONTENT);
		} catch (Exception ex) {
		}
	}
}
