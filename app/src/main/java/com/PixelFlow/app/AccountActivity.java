package com.PixelFlow.app;

import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.PixelFlow.app.API.ApiClient;
import com.PixelFlow.app.API.CommunityClient;

/** Minimal account screen. Never saves passwords or tokens in instance-state/preferences. */
public class AccountActivity extends AppCompatActivity {
    private ApiClient api;
    private CommunityClient.Job job;
    private EditText username, password, nickname;
    private TextView status;
    private Button login, register, logout;
    private Button profile;
    private TextView profileText;
    private boolean busy;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        api = ApiClient.getInstance(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int)(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll);
        status = new TextView(this); layout.addView(status);
        username = field(layout, "用户名（3–32 位字母、数字或下划线）", false);
        password = field(layout, "密码（至少 10 字符，最多 72 UTF-8 字节）", true);
        nickname = field(layout, "昵称（注册时填写，最多 40 字符）", false);
        login = button(layout, "登录");
        register = button(layout, "注册");
        logout = button(layout, "退出当前账号");
        profile = button(layout, "查看我的兴趣画像");
        profileText = new TextView(this); layout.addView(profileText);
        profile.setOnClickListener(v -> {
            profileText.setText(""); setBusy(true);
            job = api.getProfile(new CommunityClient.Callback<com.google.gson.JsonObject>() {
                public void onSuccess(com.google.gson.JsonObject value) {
                    if (isDestroyed() || isFinishing()) return;
                    StringBuilder text = new StringBuilder("画像版本：").append(value.get("version").getAsLong())
                            .append("\n偏好（规则分析，非推荐结果）：");
                    for (com.google.gson.JsonElement topic : value.getAsJsonArray("topics")) {
                        com.google.gson.JsonObject row = topic.getAsJsonObject();
                        text.append("\n").append(row.get("name").getAsString()).append("：").append(row.get("score").getAsDouble());
                    }
                    if (value.getAsJsonArray("topics").size()==0) text.append("\n暂无足够行为数据");
                    text.append("\n画像为最近一次同步快照，尚非实时更新。");
                    profileText.setText(text); done("画像已读取");
                }
                public void onFailure(String message) { done(message); }
            });
        });
        TextView hint = new TextView(this);
        hint.setText("注册成功后请登录。会话仅保存在内存，进程退出后需重新登录。个人页旧资料暂为本机展示，不代表服务端资料。");
        layout.addView(hint);
        button(layout, "返回社区").setOnClickListener(v -> finish());
        login.setOnClickListener(v -> {
            if (!valid(false)) return;
            setBusy(true);
            job = api.login(username.getText().toString().trim(), password.getText().toString(), callback("登录成功"));
        });
        register.setOnClickListener(v -> {
            if (!valid(true)) return;
            setBusy(true);
            job = api.register(username.getText().toString().trim(), password.getText().toString(),
                    nickname.getText().toString().trim(), callback("注册成功，请登录"));
        });
        logout.setOnClickListener(v -> {
            profileText.setText("");
            setBusy(true);
            job = api.logout(new CommunityClient.Callback<Void>() {
                public void onSuccess(Void value) { done("已退出登录"); }
                public void onFailure(String message) { done("本机会话已清除；服务端注销未确认：" + message); }
            });
        });
        render();
    }
    private EditText field(LinearLayout layout, String hint, boolean secret) {
        EditText field = new EditText(this); field.setHint(hint); field.setSingleLine(true);
        field.setSaveEnabled(false);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : 0));
        layout.addView(field); return field;
    }
    private Button button(LinearLayout layout, String label) {
        Button button = new Button(this); button.setText(label); layout.addView(button); return button;
    }
    private boolean valid(boolean registration) {
        String name = username.getText().toString().trim(), pass = password.getText().toString();
        if (!name.matches("[A-Za-z0-9_]{3,32}") || pass.isEmpty()
                || pass.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
                || (registration && (pass.length() < 10 || nickname.getText().toString().trim().isEmpty()
                || nickname.getText().toString().trim().length() > 40))) {
            Toast.makeText(this, "请检查用户名、密码和昵称格式", Toast.LENGTH_LONG).show(); return false;
        }
        return true;
    }
    private CommunityClient.Callback<Void> callback(String success) {
        return new CommunityClient.Callback<Void>() {
            public void onSuccess(Void value) { password.setText(""); done(success); }
            public void onFailure(String message) { done(message); }
        };
    }
    private void done(String message) {
        if (isDestroyed() || isFinishing()) return;
        job = null; setBusy(false);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
    private void setBusy(boolean value) { busy = value; render(); }
    private void render() {
        status.setText(busy ? "正在处理…" : "当前账号：" + api.username());
        login.setEnabled(!busy && !api.isLoggedIn());
        register.setEnabled(!busy && !api.isLoggedIn());
        logout.setEnabled(!busy && api.isLoggedIn());
        profile.setEnabled(!busy && api.isLoggedIn());
    }
    @Override protected void onDestroy() {
        if (job != null) job.cancel();
        super.onDestroy();
    }
}
