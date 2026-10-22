package com.PixelFlow.app;

import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.*;
import androidx.lifecycle.*;
import com.PixelFlow.app.API.ApiClient;
import com.bumptech.glide.Glide;
import com.google.android.material.textfield.TextInputEditText;

public class EditProfileActivity extends AppCompatActivity {
    private ProfileEditorModel model;
    private TextInputEditText nickname, bio;
    private TextView status;
    private Button save, retry;
    private View loading;
    private ImageView avatar;
    private boolean rendering;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_edit_profile);
        nickname = findViewById(R.id.edit_nickname); bio = findViewById(R.id.edit_bio);
        status = findViewById(R.id.editor_status); save = findViewById(R.id.editor_save); retry = findViewById(R.id.editor_retry);
        loading = findViewById(R.id.editor_loading); avatar = findViewById(R.id.editor_avatar);
        model = new ViewModelProvider(this, new ViewModelProvider.Factory() {
            @NonNull @Override @SuppressWarnings("unchecked") public <T extends ViewModel> T create(@NonNull Class<T> type) {
                return (T) new ProfileEditorModel(ApiClient.getInstance(EditProfileActivity.this));
            }
        }).get(ProfileEditorModel.class);
        TextWatcher watcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (rendering) return;
                model.draft(nickname.getText().toString(), bio.getText().toString()); save.setEnabled(model.canSave());
            }
            public void afterTextChanged(Editable s) { }
        };
        nickname.addTextChangedListener(watcher); bio.addTextChangedListener(watcher);
        save.setOnClickListener(v -> model.save()); retry.setOnClickListener(v -> model.reload());
        findViewById(R.id.editor_back).setOnClickListener(v -> leave());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { leave(); }
        });
        model.changes.observe(this, ignored -> render());
    }
    @Override protected void onResume() {
        super.onResume();
        if (!model.validSession()) { Toast.makeText(this, "请先登录后编辑资料", Toast.LENGTH_LONG).show(); finish(); }
    }
    private void render() {
        if (!model.validSession()) { finish(); return; }
        if (model.saved) { Toast.makeText(this, "资料已保存", Toast.LENGTH_SHORT).show(); setResult(RESULT_OK); finish(); return; }
        rendering = true;
        if (!nickname.getText().toString().equals(model.nickname)) nickname.setText(model.nickname);
        if (!bio.getText().toString().equals(model.bio)) bio.setText(model.bio);
        rendering = false;
        boolean editable = model.original != null && !model.loading && !model.saving;
        nickname.setEnabled(editable); bio.setEnabled(editable); save.setEnabled(model.canSave());
        save.setText(model.saving ? R.string.profile_saving : R.string.profile_save);
        loading.setVisibility(model.loading || model.saving ? View.VISIBLE : View.GONE);
        status.setText(model.message); status.setVisibility(model.message.isEmpty() ? View.GONE : View.VISIBLE);
        retry.setVisibility(!model.loading && !model.saving && (model.original == null || model.uncertain) ? View.VISIBLE : View.GONE);
        retry.setText(model.uncertain ? R.string.profile_verify_save : R.string.profile_retry);
        Glide.with(this).load(model.original == null || model.original.avatar.isEmpty() ? null : model.original.avatar)
                .placeholder(R.drawable.profile_avatar).error(R.drawable.profile_avatar).circleCrop().into(avatar);
    }
    private void leave() {
        if (model.saving) { Toast.makeText(this, "正在保存，请稍候", Toast.LENGTH_SHORT).show(); return; }
        if (!model.dirty()) { finish(); return; }
        new AlertDialog.Builder(this).setTitle(R.string.profile_discard_title).setMessage(R.string.profile_discard_message)
                .setNegativeButton(R.string.profile_keep_editing, null).setPositiveButton(R.string.profile_discard, (d, w) -> finish()).show();
    }
}
