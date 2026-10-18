// ProfileFragment.java
package com.PixelFlow.app.ButtonFragment;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.PixelFlow.app.R;
import com.PixelFlow.app.UserDataManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class ProfileFragment extends Fragment {

    private TextView tvUserName;
    private TextView tvUserBio;
    private ImageView ivAvatar;
    private ImageView btnEditProfile;
    private TextView tvPostsCount, tvFollowersCount, tvFollowingCount;

    private UserDataManager userDataManager;

    public static ProfileFragment newInstance() {
        return new ProfileFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        userDataManager = new UserDataManager(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_profile, container, false);
        initView(view);
        loadUserData();
        return view;
    }

    private void initView(View view) {
        tvUserName = view.findViewById(R.id.tv_user_name);
        tvUserBio = view.findViewById(R.id.tv_user_bio);
        ivAvatar = view.findViewById(R.id.iv_avatar);
        btnEditProfile = view.findViewById(R.id.btn_edit_profile);
        tvPostsCount = view.findViewById(R.id.tv_posts_count);
        tvFollowersCount = view.findViewById(R.id.tv_followers_count);
        tvFollowingCount = view.findViewById(R.id.tv_following_count);

        // 点击头像选择图片
        ivAvatar.setOnClickListener(v -> showImagePickerDialog());

        // 点击用户名编辑
        tvUserName.setOnClickListener(v -> showEditNameDialog());

        // 点击编辑按钮
        btnEditProfile.setOnClickListener(v -> showEditProfileDialog());

        // 点击bio编辑
        tvUserBio.setOnClickListener(v -> showEditBioDialog());

        // 添加统计项目的点击事件
        view.findViewById(R.id.stats_posts).setOnClickListener(v -> showPosts());
        view.findViewById(R.id.stats_followers).setOnClickListener(v -> showFollowers());
        view.findViewById(R.id.stats_following).setOnClickListener(v -> showFollowing());

        view.findViewById(R.id.btn_settings).setOnClickListener(v -> openSettings());
        view.findViewById(R.id.btn_logout).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), com.PixelFlow.app.AccountActivity.class)));
    }

    private void loadUserData() {
        // 从UserDataManager加载用户数据
        String userName = userDataManager.getUserName();
        String userBio = userDataManager.getUserBio();
        String avatarPath = userDataManager.getAvatarPath();

        tvUserName.setText(userName.isEmpty() ? "点击设置用户名" : userName);
        tvUserBio.setText(userBio.isEmpty() ? "点击设置个人简介" : userBio);

        if (!avatarPath.isEmpty()) {
            loadAvatar(avatarPath);
        }

        // 加载统计数据
        tvPostsCount.setText(String.valueOf(userDataManager.getPostsCount()));
        tvFollowersCount.setText(String.valueOf(userDataManager.getFollowersCount()));
        tvFollowingCount.setText(String.valueOf(userDataManager.getFollowingCount()));
    }

    private void loadAvatar(String path) {
        File imgFile = new File(path);
        if (imgFile.exists()) {
            Bitmap bitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
            ivAvatar.setImageBitmap(bitmap);
        }
    }

    private void showImagePickerDialog() {
        String[] options = {"拍照", "从相册选择", "取消"};
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("选择头像");
        builder.setItems(options, (dialog, which) -> {
            switch (which) {
                case 0:
                    // 拍照
                    // openCamera();
                    Toast.makeText(getContext(), "拍照功能开发中", Toast.LENGTH_SHORT).show();
                    break;
                case 1:
                    // 从相册选择
                    openGallery();
                    break;
                case 2:
                    dialog.dismiss();
                    break;
            }
        });
        builder.show();
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        galleryLauncher.launch(intent);
    }

    private final ActivityResultLauncher<Intent> galleryLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri selectedImageUri = result.getData().getData();
                    if (selectedImageUri != null) {
                        try {
                            // 压缩并保存图片
                            InputStream inputStream = requireContext().getContentResolver()
                                    .openInputStream(selectedImageUri);
                            Bitmap bitmap = BitmapFactory.decodeStream(inputStream);

                            // 压缩图片
                            Bitmap compressedBitmap = compressBitmap(bitmap, 500, 500);

                            // 保存到本地
                            String savedPath = saveAvatarToLocal(compressedBitmap);
                            if (savedPath != null) {
                                userDataManager.saveAvatarPath(savedPath);
                                ivAvatar.setImageBitmap(compressedBitmap);
                                Toast.makeText(getContext(), "头像更新成功", Toast.LENGTH_SHORT).show();
                            }

                            if (inputStream != null) {
                                inputStream.close();
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                            Toast.makeText(getContext(), "图片加载失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            });

    private Bitmap compressBitmap(Bitmap bitmap, int maxWidth, int maxHeight) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        float ratio = Math.min((float) maxWidth / width, (float) maxHeight / height);
        if (ratio < 1) {
            width = Math.round(width * ratio);
            height = Math.round(height * ratio);
            return Bitmap.createScaledBitmap(bitmap, width, height, true);
        }
        return bitmap;
    }

    private String saveAvatarToLocal(Bitmap bitmap) {
        try {
            File storageDir = requireContext().getFilesDir();
            File avatarFile = new File(storageDir, "avatar_" + System.currentTimeMillis() + ".jpg");

            FileOutputStream fos = new FileOutputStream(avatarFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, fos);
            fos.flush();
            fos.close();

            return avatarFile.getAbsolutePath();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private void showEditNameDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("设置用户名");

        final EditText input = new EditText(requireContext());
        input.setText(userDataManager.getUserName());
        input.setHint("请输入用户名");
        input.setMaxLines(1);

        builder.setView(input);

        builder.setPositiveButton("确定", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty()) {
                userDataManager.saveUserName(newName);
                tvUserName.setText(newName);
                Toast.makeText(getContext(), "用户名已更新", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", (dialog, which) -> dialog.cancel());

        builder.show();

        // 弹出软键盘
        input.requestFocus();
    }

    private void showEditBioDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("设置个人简介");

        final EditText input = new EditText(requireContext());
        input.setText(userDataManager.getUserBio());
        input.setHint("介绍一下自己吧...");
        input.setMaxLines(3);
        input.setSingleLine(false);

        // 添加输入监听，限制字数
        final TextView charCount = new TextView(requireContext());
        charCount.setText("0/60");
        charCount.setTextColor(getResources().getColor(android.R.color.darker_gray));

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                charCount.setText(s.length() + "/60");
            }
        });

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 0);
        layout.addView(input);
        layout.addView(charCount);

        builder.setView(layout);

        builder.setPositiveButton("确定", (dialog, which) -> {
            String newBio = input.getText().toString().trim();
            if (newBio.length() <= 60) {
                userDataManager.saveUserBio(newBio);
                tvUserBio.setText(newBio.isEmpty() ? "点击设置个人简介" : newBio);
                Toast.makeText(getContext(), "个人简介已更新", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), "简介不能超过60字", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", (dialog, which) -> dialog.cancel());

        builder.show();

        // 弹出软键盘
        input.requestFocus();
    }

    private void showEditProfileDialog() {
        String[] options = {"修改头像", "修改用户名", "修改个人简介", "取消"};
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("编辑资料");
        builder.setItems(options, (dialog, which) -> {
            switch (which) {
                case 0:
                    showImagePickerDialog();
                    break;
                case 1:
                    showEditNameDialog();
                    break;
                case 2:
                    showEditBioDialog();
                    break;
                case 3:
                    dialog.dismiss();
                    break;
            }
        });
        builder.show();
    }

    private void showPosts() {
        // 显示用户作品
        Toast.makeText(getContext(), "查看作品", Toast.LENGTH_SHORT).show();
    }

    private void showFollowers() {
        // 显示粉丝
        Toast.makeText(getContext(), "查看粉丝", Toast.LENGTH_SHORT).show();
    }

    private void showFollowing() {
        // 显示关注
        Toast.makeText(getContext(), "查看关注", Toast.LENGTH_SHORT).show();
    }

    private void openSettings() {
        // 打开设置
        Toast.makeText(getContext(), "设置", Toast.LENGTH_SHORT).show();
    }

    private void logout() {
        // 退出登录
        new AlertDialog.Builder(requireContext())
                .setTitle("确认退出")
                .setMessage("确定要退出登录吗？")
                .setPositiveButton("确定", (dialog, which) -> {
                    // 清除用户数据
                    userDataManager.clearUserData();
                    Toast.makeText(getContext(), "已退出登录", Toast.LENGTH_SHORT).show();
                    loadUserData();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
