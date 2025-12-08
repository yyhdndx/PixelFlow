package com.PixelFlow.app.DetailPage;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import com.PixelFlow.app.R;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.github.chrisbanes.photoview.PhotoView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FullScreenMediaDialog extends Dialog {

    private String imageUrl;
    private Context context;
    private PhotoView photoView;
    private ImageView closeButton;

    public FullScreenMediaDialog(@NonNull Context context, String imageUrl) {
        super(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        this.context = context;
        this.imageUrl = imageUrl;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 设置全屏
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_fullscreen_single_image);

        // 设置窗口全屏
        Window window = getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawableResource(android.R.color.black);
        }

        initViews();
        loadImage();
    }

    private void initViews() {
        photoView = findViewById(R.id.fullscreen_photo_view);
        closeButton = findViewById(R.id.close_button);

        if (closeButton != null) {
            // 关闭按钮点击事件
            closeButton.setOnClickListener(v -> dismiss());
        }

        // 图片点击事件 - 点击图片也关闭
        photoView.setOnClickListener(v -> dismiss());

        // 图片长按事件 - 保存图片
        photoView.setOnLongClickListener(v -> {
            showSaveImageDialog();
            return true;
        });

        // 配置PhotoView的缩放行为
        configurePhotoView(photoView);
    }

    private void loadImage() {
        // 使用Glide加载图片到PhotoView
        Glide.with(context)
                .load(imageUrl)
                .apply(new RequestOptions()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .placeholder(R.drawable.ic_image_placeholder)
                        .error(R.drawable.ic_image_error))
                .into(photoView);
    }

    private void configurePhotoView(PhotoView photoView) {
        // 设置缩放级别
        photoView.setMaximumScale(5.0f);  // 最大缩放倍数
        photoView.setMediumScale(3.0f);   // 中等缩放倍数
        photoView.setMinimumScale(1.0f);   // 最小缩放倍数

        // 设置双击缩放行为
        photoView.setScaleLevels(1.0f, 2.0f, 4.0f);

        // 设置缩放动画时长（毫秒）
        photoView.setZoomTransitionDuration(200);

        // 允许在缩放时超出边界
        photoView.setAllowParentInterceptOnEdge(true);

        // 设置背景颜色
        photoView.setBackgroundColor(0xFF000000);
    }

    private void showSaveImageDialog() {
        // 创建一个简单的对话框询问用户是否保存图片
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(context);
        builder.setTitle("保存图片");
        builder.setMessage("是否保存此图片到相册？");

        builder.setPositiveButton("保存", (dialog, which) -> {
            saveImageToGallery();
            dialog.dismiss();
        });

        builder.setNegativeButton("取消", (dialog, which) -> {
            dialog.dismiss();
        });

        builder.show();
    }

    private void saveImageToGallery() {
        // 方法1：使用Glide下载并保存（适合网络图片）
        if (imageUrl.startsWith("http")) {
            saveNetworkImage();
        }
        // 方法2：如果是本地图片，直接保存
        else if (imageUrl.startsWith("file://") || imageUrl.startsWith("/")) {
            saveLocalImage();
        }
        // 方法3：如果是Content URI
        else if (imageUrl.startsWith("content://")) {
            saveContentUriImage();
        }
    }

    private void saveNetworkImage() {
        // 使用Glide异步加载图片并保存
        new Thread(() -> {
            try {
                // 使用Glide同步下载图片
                Bitmap bitmap = Glide.with(context)
                        .asBitmap()
                        .load(imageUrl)
                        .submit()
                        .get();

                // 保存到相册
                saveBitmapToGallery(bitmap);

                // 在主线程显示提示
                ((android.app.Activity) context).runOnUiThread(() -> {
                    Toast.makeText(context, "图片保存成功", Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                e.printStackTrace();
                ((android.app.Activity) context).runOnUiThread(() -> {
                    Toast.makeText(context, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void saveLocalImage() {
        try {
            File sourceFile = new File(imageUrl.replace("file://", ""));
            if (sourceFile.exists()) {
                saveFileToGallery(sourceFile);
                Toast.makeText(context, "图片保存成功", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "图片文件不存在", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(context, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveContentUriImage() {
        try {
            // 使用ContentResolver打开并保存
            android.content.ContentResolver resolver = context.getContentResolver();
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(resolver, Uri.parse(imageUrl));
            saveBitmapToGallery(bitmap);
            Toast.makeText(context, "图片保存成功", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(context, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveBitmapToGallery(Bitmap bitmap) throws IOException {
        // 创建保存目录
        File picturesDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES);
        File appDir = new File(picturesDir, "PixelFlow");
        if (!appDir.exists() && !appDir.mkdirs()) {
            throw new IOException("无法创建保存目录");
        }

        // 生成文件名
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "PixelFlow_" + timeStamp + ".jpg";
        File imageFile = new File(appDir, fileName);

        // 保存图片
        FileOutputStream fos = new FileOutputStream(imageFile);
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
        fos.flush();
        fos.close();

        // 通知系统扫描文件，使其出现在相册中
        MediaStore.Images.Media.insertImage(context.getContentResolver(),
                imageFile.getAbsolutePath(), fileName, null);

        // 发送广播通知媒体扫描器扫描文件
        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        mediaScanIntent.setData(Uri.fromFile(imageFile));
        context.sendBroadcast(mediaScanIntent);
    }

    @SuppressLint("NewApi")
    private void saveFileToGallery(File sourceFile) throws IOException {
        // 创建保存目录
        File picturesDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES);
        File appDir = new File(picturesDir, "PixelFlow");
        if (!appDir.exists() && !appDir.mkdirs()) {
            throw new IOException("无法创建保存目录");
        }

        // 生成文件名
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "PixelFlow_" + timeStamp + ".jpg";
        File destFile = new File(appDir, fileName);

        // 复制文件
        java.nio.file.Files.copy(sourceFile.toPath(), destFile.toPath());

        // 通知系统扫描文件
        MediaStore.Images.Media.insertImage(context.getContentResolver(),
                destFile.getAbsolutePath(), fileName, null);

        // 发送广播通知媒体扫描器扫描文件
        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        mediaScanIntent.setData(Uri.fromFile(destFile));
        context.sendBroadcast(mediaScanIntent);
    }
}