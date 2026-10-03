package com.filemanager;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.text.TextUtils;


class OperationProgress {
    final MainActivity activity;
    final AlertDialog dialog;
    final TextView nameView;
    final TextView detailView;
    final ProgressBar progressBar;
    volatile boolean cancelled;
    Runnable cancelAction;

    OperationProgress(MainActivity activity, String title, boolean determinate, boolean cancellable, int dp) {
        this(activity, title, determinate, cancellable, dp, null);
    }

    OperationProgress(MainActivity activity, String title, boolean determinate, boolean cancellable, int dp, Runnable cancelAction) {
        this.activity = activity;
        this.cancelAction = cancelAction;
        AlertDialog.Builder b = activity.createDialog(title, null);
        android.content.Context dialogContext = b.getContext();
        LinearLayout box = new LinearLayout(dialogContext);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(20 * dp, 12 * dp, 20 * dp, 4 * dp);
        activity.themeDialogView(box);

        nameView = new TextView(dialogContext);
        nameView.setTextSize(14);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        box.addView(nameView, new LinearLayout.LayoutParams(-1, -2));

        if (determinate) {
            progressBar = new ProgressBar(dialogContext, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setMax(1000);
        } else {
            progressBar = new ProgressBar(dialogContext);
            progressBar.setIndeterminate(true);
        }
        box.addView(progressBar, new LinearLayout.LayoutParams(-1, -2));

        detailView = new TextView(dialogContext);
        detailView.setTextSize(13);
        box.addView(detailView, new LinearLayout.LayoutParams(-1, -2));
        activity.themeDialogView(box);

        b.setTitle(title).setView(box).setCancelable(false);
        if (cancellable)
            b.setNegativeButton("Cancel", null);
        dialog = b.create();
        dialog.show();
        if (cancellable) {
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    OperationProgress.this.cancelled = true;
                    if (OperationProgress.this.cancelAction != null) OperationProgress.this.cancelAction.run();
                    OperationProgress.this.nameView.setText("Cancelling...");
                    ((Button) v).setEnabled(false);
                }
            });
        }
    }

    void update(String name, long done, long total, String detail) {
        nameView.setText(name == null ? "" : name);
        if (!progressBar.isIndeterminate()) {
            int pct = total > 0 ? (int) Math.min(1000L, done * 1000L / total) : 0;
            progressBar.setProgress(pct);
        }
        detailView.setText(detail == null ? "" : detail);
    }

    void dismiss() {
        try { dialog.dismiss(); } catch (Exception e) { }
    }
}
