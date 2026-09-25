package com.PixelFlow.app.Hashtag;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.PixelFlow.app.R;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import io.noties.markwon.Markwon;
import io.noties.markwon.core.CorePlugin;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.tasklist.TaskListPlugin;
import io.noties.markwon.html.HtmlPlugin;
import io.noties.markwon.image.ImagesPlugin;
import io.noties.markwon.image.glide.GlideImagesPlugin;
import io.noties.markwon.linkify.LinkifyPlugin;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class HashtagActivity extends AppCompatActivity {

    private static final String DEEPSEEK_API_URL = "https://api.deepseek.com/v1/chat/completions";
    // Model access must be routed through the business server; never ship credentials.
    private static final String API_KEY = "";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private TextView hashtagTitle;
    private TextView hashtagDescription;
    private TextView trendingText;
    private TextView postCount;
    private TextView aiGeneratedText;
    private LinearLayout aiContentCard;
    private LinearLayout loadingLayout;
    private LinearLayout errorLayout;
    private ProgressBar loadingProgress;
    private MaterialButton retryButton;
    private ImageView backButton;
    private ImageView refreshButton;
    private com.nex3z.flowlayout.FlowLayout relatedTagsContainer;
    private LinearLayout relatedTagsCard;

    private String currentHashtag;
    private OkHttpClient httpClient;
    private Gson gson;
    private Handler mainHandler;
    private Markwon markwon;

    // 预定义的话题描述
    private static final List<String> DEFAULT_DESCRIPTIONS = Arrays.asList(
            "这是一个热门话题，吸引了众多用户的关注和讨论。",
            "这个话题涵盖了广泛的讨论范围，从技术到生活都有涉及。",
            "在这里，你可以找到关于这个话题的最新观点和讨论。",
            "用户们在这个话题下分享他们的见解和经验。",
            "这个话题正在成为社区中的热门讨论焦点。"
    );

    // 相关话题示例
    private static final List<String> RELATED_TAGS = Arrays.asList(
            "科技", "生活", "娱乐", "体育", "美食",
            "旅行", "学习", "工作", "健康", "时尚"
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hashtag);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        initViews();
        initMarkwon();

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        gson = new Gson();
        mainHandler = new Handler(Looper.getMainLooper());

        currentHashtag = getIntent().getStringExtra("hashtag");
        if (currentHashtag == null || currentHashtag.isEmpty()) {
            Toast.makeText(this, "话题不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setupUI();
        loadHashtagData();
    }

    private void initViews() {
        hashtagTitle = findViewById(R.id.hashtag_title);
        hashtagDescription = findViewById(R.id.hashtag_description);
        trendingText = findViewById(R.id.trending_text);
        postCount = findViewById(R.id.post_count);
        aiGeneratedText = findViewById(R.id.ai_generated_text);
        aiContentCard = findViewById(R.id.ai_content_card);
        loadingLayout = findViewById(R.id.loading_layout);
        errorLayout = findViewById(R.id.error_layout);
        loadingProgress = findViewById(R.id.loading_progress);
        retryButton = findViewById(R.id.retry_button);
        backButton = findViewById(R.id.back_button);
        refreshButton = findViewById(R.id.refresh_button);
        relatedTagsContainer = findViewById(R.id.related_tags_container);
        relatedTagsCard = findViewById(R.id.related_tags_card);

        backButton.setOnClickListener(v -> onBackPressed());
        retryButton.setOnClickListener(v -> retryAIRequest());

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.app_bar), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), systemBars.top, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
    }

    private void initMarkwon() {
        // 创建Markwon实例，配置必要的插件
        markwon = Markwon.builder(this)
                .usePlugin(CorePlugin.create())
                .usePlugin(LinkifyPlugin.create())        // 自动识别链接
                .usePlugin(ImagesPlugin.create())         // 图片支持
                .usePlugin(GlideImagesPlugin.create(Glide.with(this))) // 使用Glide加载图片
                .usePlugin(HtmlPlugin.create())           // HTML支持
                .usePlugin(StrikethroughPlugin.create())  // 删除线
                .usePlugin(TaskListPlugin.create(this))   // 任务列表
                .usePlugin(TablePlugin.create(this))      // 表格
                .build();
    }

    private void setupUI() {
        hashtagTitle.setText(currentHashtag);

        Random random = new Random();
        String description = DEFAULT_DESCRIPTIONS.get(random.nextInt(DEFAULT_DESCRIPTIONS.size()));
        hashtagDescription.setText(description);

        int postCountNum = 100 + random.nextInt(900);
        postCount.setText(postCountNum + " 个内容");

        aiContentCard.setVisibility(View.VISIBLE);
        loadingLayout.setVisibility(View.VISIBLE);
        aiGeneratedText.setVisibility(View.GONE);
        errorLayout.setVisibility(View.GONE);

        showRelatedTags();
        startAIRequest();
    }

    private void loadHashtagData() {
        updateTrendingStatus();
    }

    private void updateTrendingStatus() {
        Random random = new Random();
        String[] trendingTexts = {"正在热议", "热门话题", "趋势上升", "社区焦点"};
        trendingText.setText(trendingTexts[random.nextInt(trendingTexts.length)]);
    }

    private void showRelatedTags() {
        relatedTagsContainer.removeAllViews();

        Random random = new Random();
        int count = 3 + random.nextInt(5);

        for (int i = 0; i < count && i < RELATED_TAGS.size(); i++) {
            String tag = RELATED_TAGS.get(random.nextInt(RELATED_TAGS.size()));

            TextView tagView = new TextView(this);
            tagView.setText(tag);
            tagView.setTextColor(getColor(R.color.primary_color));
            tagView.setTextSize(14);
            tagView.setPadding(16, 8, 16, 8);
            tagView.setBackgroundResource(R.drawable.tag_background);
            tagView.setOnClickListener(v -> {
                Intent intent = new Intent(HashtagActivity.this, HashtagActivity.class);
                intent.putExtra("hashtag", tag);
                startActivity(intent);
            });

            relatedTagsContainer.addView(tagView);
        }

        if (relatedTagsContainer.getChildCount() > 0) {
            relatedTagsCard.setVisibility(View.VISIBLE);
        }
    }

    private void startAIRequest() {
        new AIGenerationTask().execute(currentHashtag);
    }

    private void retryAIRequest() {
        errorLayout.setVisibility(View.GONE);
        loadingLayout.setVisibility(View.VISIBLE);
        startAIRequest();
    }

    @SuppressLint("StaticFieldLeak")
    private class AIGenerationTask extends AsyncTask<String, Void, String> {

        @Override
        protected String doInBackground(String... params) {
            String hashtag = params[0];

            try {
                return generateAIContent(hashtag);
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }

        @Override
        protected void onPostExecute(String result) {
            mainHandler.post(() -> {
                loadingLayout.setVisibility(View.GONE);

                if (result != null && !result.isEmpty()) {
                    renderMarkdownContent(result);
                } else {
                    errorLayout.setVisibility(View.VISIBLE);
                }
            });
        }

        private String generateAIContent(String hashtag) throws IOException {
            if (API_KEY.isEmpty()) {
                return "话题智能服务尚未接入，请稍后再试。";
            }
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", "deepseek-chat");

            JsonArray messages = new JsonArray();
            JsonObject systemMessage = new JsonObject();
            systemMessage.addProperty("role", "system");
            systemMessage.addProperty("content", "你是一个社交媒体话题分析助手，请为用户生成关于特定话题的精彩介绍和见解。请使用Markdown格式，中文回答，内容要有趣、生动，包含一些实用信息或有趣的角度。");
            messages.add(systemMessage);

            JsonObject userMessage = new JsonObject();
            userMessage.addProperty("role", "user");
            userMessage.addProperty("content", "请为话题 " + hashtag + " 生成一段精彩的介绍文案，使用Markdown格式，大约200-300字。要求：\n\n" +
                    "1. 使用二级标题（##）介绍话题\n" +
                    "2. 使用**粗体**强调重要观点\n" +
                    "3. 适当使用列表（- 或 1.）\n" +
                    "4. 可以添加引用块（>）引用有趣的内容\n" +
                    "5. 如果有相关资源，可以添加[链接](url)\n" +
                    "6. 使用友好的语气，让用户感觉像是在和一个朋友聊天\n\n" +
                    "请确保Markdown格式正确，便于渲染显示。");
            messages.add(userMessage);

            requestBody.add("messages", messages);
            requestBody.addProperty("max_tokens", 1000);
            requestBody.addProperty("temperature", 0.7);

            RequestBody body = RequestBody.create(requestBody.toString(), JSON);

            Request request = new Request.Builder()
                    .url(DEEPSEEK_API_URL)
                    .header("Authorization", "Bearer " + API_KEY)
                    .header("Content-Type", "application/json")
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String responseBody = response.body().string();
                    JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);

                    if (jsonResponse.has("choices")) {
                        JsonArray choices = jsonResponse.getAsJsonArray("choices");
                        if (choices.size() > 0) {
                            JsonObject choice = choices.get(0).getAsJsonObject();
                            JsonObject message = choice.getAsJsonObject("message");
                            return message.get("content").getAsString().trim();
                        }
                    }
                }
                return getFallbackContent(hashtag);
            } catch (Exception e) {
                e.printStackTrace();
                return getFallbackContent(hashtag);
            }
        }

        private String getFallbackContent(String hashtag) {
            // 返回Markdown格式的备用内容
            return "## 话题 #" + hashtag + " 精彩解读\n\n" +
                    "🌟 **为什么这个话题如此重要？**\n" +
                    "这个话题正在社交媒体上引起广泛关注！它反映了当前社会的热点趋势，吸引了不同背景的用户参与讨论。\n\n" +
                    "💬 **大家都在讨论什么？**\n" +
                    "- 分享个人见解和经验\n" +
                    "- 交流技术分析和创新想法\n" +
                    "- 讨论行业发展和未来趋势\n" +
                    "- 寻找志同道合的伙伴\n\n" +
                    "> \"独行者速，众行者远。\" 在这里，你永远不会孤单前行。\n\n" +
                    "🔍 **有趣的角度探索：**\n" +
                    "1. 这个话题如何影响了相关行业？\n" +
                    "2. 有哪些创新的应用案例？\n" +
                    "3. 未来可能的发展方向是什么？\n\n" +
                    "**立即加入讨论**，分享你的独特观点吧！";
        }
    }

    private void renderMarkdownContent(String markdownContent) {
        // 使用Markwon渲染Markdown
        Spanned spanned = markwon.toMarkdown(markdownContent);

        // 设置到TextView
        aiGeneratedText.setText(spanned);
        aiGeneratedText.setMovementMethod(LinkMovementMethod.getInstance());
        aiGeneratedText.setHighlightColor(Color.TRANSPARENT);
        aiGeneratedText.setVisibility(View.VISIBLE);

        // 可选：如果需要自定义样式，可以在这里添加
        // 例如，为代码块添加背景色
        addCustomStyles();
    }

    private void addCustomStyles() {
        // 这里可以添加一些自定义样式
        // 例如，为TextView添加内边距等
        aiGeneratedText.setPadding(
                aiGeneratedText.getPaddingLeft(),
                aiGeneratedText.getPaddingTop(),
                aiGeneratedText.getPaddingRight(),
                16
        );
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
        }
    }
}
