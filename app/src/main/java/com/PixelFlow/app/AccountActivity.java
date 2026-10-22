package com.PixelFlow.app;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.*;
import com.PixelFlow.app.API.*;
import com.google.android.material.textfield.*;

public class AccountActivity extends AppCompatActivity {
    private ApiClient api;
    private CommunityClient.Job job;
    private TextInputEditText username, password, nickname;
    private TextInputLayout usernameLayout, passwordLayout, nicknameLayout;
    private TextView heading, description, status;
    private Button submit, toggle, logout;
    private View form, session, loading;
    private boolean busy, registration;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_account); api = ApiClient.getInstance(this);
        registration = state != null && state.getBoolean("registration");
        username = findViewById(R.id.account_username); password = findViewById(R.id.account_password); nickname = findViewById(R.id.account_nickname);
        usernameLayout = findViewById(R.id.account_username_layout); passwordLayout = findViewById(R.id.account_password_layout);
        nicknameLayout = findViewById(R.id.account_nickname_layout);
        heading = findViewById(R.id.account_heading); description = findViewById(R.id.account_description); status = findViewById(R.id.account_status);
        submit = findViewById(R.id.account_submit); toggle = findViewById(R.id.account_toggle); logout = findViewById(R.id.account_logout);
        form = findViewById(R.id.account_form); session = findViewById(R.id.account_session); loading = findViewById(R.id.account_loading);
        findViewById(R.id.account_back).setOnClickListener(v -> finish());
        findViewById(R.id.account_return).setOnClickListener(v -> finish());
        toggle.setOnClickListener(v -> { registration = !registration; clearErrors(); render(); });
        submit.setOnClickListener(v -> submit());
        logout.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("退出当前账号？")
                .setMessage("退出后仍可以浏览公开作品。")
                .setNegativeButton("取消", null).setPositiveButton("退出登录", (d, w) -> {
                    busy = true; job = api.logout(new CommunityClient.Callback<Void>() {
                        public void onSuccess(Void value) { done("已退出登录"); }
                        public void onFailure(String message) { done("已退出本机账号，服务器退出未确认。" + message); }
                    }); render();
                }).show());
        render();
    }
    private void clearErrors() { usernameLayout.setError(null); passwordLayout.setError(null); nicknameLayout.setError(null); status.setVisibility(View.GONE); }
    private void submit() {
        clearErrors();
        String name = username.getText().toString().trim(), pass = password.getText().toString(), nick = nickname.getText().toString().trim();
        boolean valid = true;
        if (!name.matches("[A-Za-z0-9_]{3,32}")) { usernameLayout.setError("使用 3–32 位字母、数字或下划线"); valid = false; }
        if (pass.isEmpty() || pass.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72 || (registration && pass.length() < 10)) {
            passwordLayout.setError(registration ? "密码至少 10 位，最多 72 UTF-8 字节" : "请输入有效密码"); valid = false;
        }
        if (registration && (nick.isEmpty() || nick.length() > 40)) { nicknameLayout.setError("昵称需为 1–40 字"); valid = false; }
        if (!valid) return;
        busy = true; render();
        if (registration) job = api.register(name, pass, nick, new CommunityClient.Callback<Void>() {
            public void onSuccess(Void value) { password.setText(""); registration = false; done("注册成功，请登录"); }
            public void onFailure(String message) { done(message); }
        });
        else job = api.login(name, pass, new CommunityClient.Callback<Void>() {
            public void onSuccess(Void value) {
                if (isFinishing() || isDestroyed()) return;
                password.setText(""); busy = false; finish();
            }
            public void onFailure(String message) { done(message); }
        });
    }
    private void done(String message) {
        if (isFinishing() || isDestroyed()) return;
        busy = false; job = null; render(); status.setText(message); status.setVisibility(View.VISIBLE);
    }
    private void render() {
        boolean loggedIn = api.isLoggedIn();
        heading.setText(loggedIn ? R.string.account_signed_in : registration ? R.string.account_register_title : R.string.account_title);
        if (loggedIn) description.setText("@" + api.username());
        else description.setText(registration ? R.string.account_register_subtitle : R.string.account_subtitle);
        form.setVisibility(loggedIn ? View.GONE : View.VISIBLE); session.setVisibility(loggedIn ? View.VISIBLE : View.GONE);
        nicknameLayout.setVisibility(registration ? View.VISIBLE : View.GONE);
        submit.setText(busy ? "正在处理…" : getString(registration ? R.string.account_register : R.string.account_login));
        toggle.setText(registration ? R.string.account_switch_login : R.string.account_switch_register);
        submit.setEnabled(!busy); toggle.setEnabled(!busy); logout.setEnabled(!busy);
        username.setEnabled(!busy); password.setEnabled(!busy); nickname.setEnabled(!busy);
        loading.setVisibility(busy ? View.VISIBLE : View.GONE);
    }
    @Override protected void onSaveInstanceState(Bundle out) { out.putBoolean("registration", registration); super.onSaveInstanceState(out); }
    @Override protected void onDestroy() { if (job != null) job.cancel(); super.onDestroy(); }
}
