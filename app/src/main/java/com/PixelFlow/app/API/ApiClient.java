package com.PixelFlow.app.API;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import okhttp3.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class ApiClient {
    private static final String TAG = "ApiClient";
    private static String BASE_URL = "https://college-training-camp.bytedance.com";
    private static final int TIMEOUT = 30;

    private static ApiClient instance;
    private OkHttpClient client;
    private Gson gson;
    private Context context;

    private ApiClient(Context context) {
        this.context = context.getApplicationContext();
        client = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT, TimeUnit.SECONDS)
                .build();
        gson = new Gson();
        loadConfigFromAssets();
    }

    public static synchronized ApiClient getInstance(Context context) {
        if (instance == null) {
            instance = new ApiClient(context);
        }
        return instance;
    }

    private void loadConfigFromAssets() {
        try {
            InputStream inputStream = context.getAssets().open("api.properties");
            Properties properties = new Properties();
            properties.load(inputStream);
            BASE_URL = properties.getProperty("url");
            inputStream.close();

            Log.d(TAG, "Loaded BASE_URL: " + BASE_URL);
        } catch (IOException e) {
            Log.e(TAG, "Failed to load config from assets: " + e.getMessage());
            BASE_URL = "https://college-training-camp.bytedance.com";
        }
    }

    public interface ApiCallback {
        void onSuccess(ApiResponse response);
        void onFailure(String errorMessage);
    }

    public void getFeed(int count, ApiCallback callback) {
        // 构建URL参数 - 现在固定支持视频播放
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(BASE_URL + "/feed/")).newBuilder()
                .addQueryParameter("count", String.valueOf(count))
                .addQueryParameter("accept_video_clip", "true")  // 固定为true以支持视频
                .build();

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Network request failed: " + e.getMessage());
                callback.onFailure("网络请求失败: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    callback.onFailure("服务器错误: " + response.code());
                    return;
                }

                try {
                    assert response.body() != null;
                    String responseBody = response.body().string();
                    Log.d(TAG, "Response: " + responseBody);

                    ApiResponse apiResponse = gson.fromJson(responseBody, ApiResponse.class);

                    if (apiResponse.getStatusCode() == 0) {
                        callback.onSuccess(apiResponse);
                    } else {
                        callback.onFailure("API返回错误: " + apiResponse.getStatusCode());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "JSON parsing failed: " + e.getMessage());
                    callback.onFailure("数据解析失败: " + e.getMessage());
                }
            }
        });
    }
}
