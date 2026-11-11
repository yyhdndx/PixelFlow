package com.PixelFlow.app.profile;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;
import com.PixelFlow.app.R;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

public final class InterestBubbleView extends ViewGroup {
    private double[] weights = new double[0];
    private List<InterestBubbleLayout.Circle> circles = new ArrayList<>();

    public InterestBubbleView(Context context, AttributeSet attrs) { super(context, attrs); }

    public void setTopics(JsonArray topics) {
        removeAllViews();
        weights = new double[topics.size()];
        for (int i = 0; i < topics.size(); i++) {
            JsonObject topic = topics.get(i).getAsJsonObject();
            String name = topic.get("name").getAsString();
            weights[i] = number(topic, "share", number(topic, "score", 0));
            TextView label = new TextView(getContext());
            label.setText(name);
            label.setContentDescription(name);
            label.setGravity(Gravity.CENTER);
            label.setTextSize(15);
            label.setMaxLines(2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setTextColor(getContext().getColor(R.color.profile_ink));
            int inset = Math.round(10 * getResources().getDisplayMetrics().density);
            label.setPadding(inset, inset, inset, inset);
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(topicColor(name));
            label.setBackground(background);
            addView(label);
        }
        requestLayout();
    }

    private static int topicColor(String name) {
        switch (name) {
            case "美食": return Color.rgb(249, 218, 202);
            case "园艺": return Color.rgb(210, 229, 210);
            case "数码": return Color.rgb(207, 225, 244);
            case "阅读": return Color.rgb(226, 216, 246);
            case "运动": return Color.rgb(249, 226, 190);
            case "宠物": return Color.rgb(245, 214, 226);
            case "其他": return Color.rgb(219, 228, 234);
            default:
                int[] palette = {0xFFF9DACA, 0xFFD2E5D2, 0xFFCFE1F4,
                        0xFFE2D8F6, 0xFFF9E2BE, 0xFFF5D6E2, 0xFFDBE4EA};
                return palette[Math.floorMod(name.hashCode(), palette.length)];
        }
    }

    private static double number(JsonObject topic, String key, double fallback) {
        try {
            double value = topic.get(key).getAsDouble();
            return Double.isFinite(value) && value >= 0 ? value : fallback;
        } catch (RuntimeException ignored) { return fallback; }
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        float density = getResources().getDisplayMetrics().density;
        circles = InterestBubbleLayout.arrange(width / density, weights);
        int height = 0;
        for (InterestBubbleLayout.Circle circle : circles) {
            int size = Math.round(circle.diameter * density);
            getChildAt(circle.index).measure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
            height = Math.max(height, Math.round((circle.y + circle.diameter / 2) * density));
        }
        setMeasuredDimension(width, resolveSize(height, heightSpec));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        float density = getResources().getDisplayMetrics().density;
        for (InterestBubbleLayout.Circle circle : circles) {
            int x = Math.round((circle.x - circle.diameter / 2) * density);
            int y = Math.round((circle.y - circle.diameter / 2) * density);
            android.view.View child = getChildAt(circle.index);
            child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
        }
    }
}
