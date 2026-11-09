package com.PixelFlow.app.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import androidx.appcompat.app.AlertDialog;
import com.PixelFlow.app.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class PixelFlowDialogs {
    private PixelFlowDialogs() { }

    public static MaterialAlertDialogBuilder builder(Context context) {
        return new MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_PixelFlow_Dialog);
    }

    public static MaterialAlertDialogBuilder destructive(Context context) {
        return new MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_PixelFlow_Dialog_Destructive);
    }

    public static View inflate(Context context, int layout) {
        return LayoutInflater.from(builder(context).getContext()).inflate(layout, null);
    }

    public static void showForm(AlertDialog dialog) {
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }
}
