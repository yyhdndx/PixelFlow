package com.PixelFlow.app.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.PixelFlow.app.R;

import java.util.List;

/**
 * PixelFlow 底部动作面板：抓手 + 标题 + 副标题 + 图标选项 + 取消。
 * 替代默认 AlertDialog，统一商用级选择交互。
 */
public final class ActionSheetDialog {

    /** 面板选项：图标 + 文案 + 点击行为 */
    public static final class Option {
        public final int iconRes;
        public final CharSequence label;
        public final Runnable action;

        public Option(int iconRes, CharSequence label, Runnable action) {
            this.iconRes = iconRes;
            this.label = label;
            this.action = action;
        }
    }

    private ActionSheetDialog() { }

    public static Dialog show(Context context, CharSequence title, CharSequence subtitle, List<Option> options) {
        View root = LayoutInflater.from(context).inflate(R.layout.dialog_action_sheet, null);

        TextView titleView = root.findViewById(R.id.sheet_title);
        TextView subtitleView = root.findViewById(R.id.sheet_subtitle);
        LinearLayout optionsView = root.findViewById(R.id.sheet_options);
        TextView cancelView = root.findViewById(R.id.sheet_cancel);

        titleView.setText(title);
        if (subtitle == null || subtitle.length() == 0) {
            subtitleView.setVisibility(View.GONE);
        } else {
            subtitleView.setText(subtitle);
            subtitleView.setVisibility(View.VISIBLE);
        }

        LayoutInflater inflater = LayoutInflater.from(context);
        final Dialog[] holder = new Dialog[1];
        for (int i = 0; i < options.size(); i++) {
            Option option = options.get(i);
            View row = inflater.inflate(R.layout.dialog_sheet_option, optionsView, false);
            ((android.widget.ImageView) row.findViewById(R.id.option_icon)).setImageResource(option.iconRes);
            ((TextView) row.findViewById(R.id.option_label)).setText(option.label);
            row.setOnClickListener(v -> {
                holder[0].dismiss();
                if (option.action != null) option.action.run();
            });
            optionsView.addView(row);
        }

        Dialog dialog = new Dialog(context);
        holder[0] = dialog;
        dialog.setContentView(root);
        cancelView.setOnClickListener(v -> dialog.dismiss());

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.setWindowAnimations(R.style.PixelFlowBottomSheet);
        }
        dialog.show();
        return dialog;
    }
}
