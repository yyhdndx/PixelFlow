package com.PixelFlow.app.Streams;

import android.content.Context;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 首页推荐流第一页的磁盘缓存：冷启动秒开 + 弱网兜底。
 * 按账号 key 隔离（游客与每个登录账号各自一个文件），避免点赞/收藏状态串号。
 * 缓存失败静默降级，不影响主流程。
 */
public final class FeedCache {
    private final File file;

    private FeedCache(File file) { this.file = file; }

    public static FeedCache forAccount(Context context, String accountKey) {
        String safe = (accountKey == null || accountKey.isEmpty()) ? "guest"
                : accountKey.replaceAll("[^A-Za-z0-9_@.-]", "_");
        return new FeedCache(new File(new File(context.getCacheDir(), "feed-cache"), safe + ".dat"));
    }

    public synchronized void save(List<WorkItem> items) {
        if (items == null || items.isEmpty()) return;
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) return;
            try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(file))) {
                out.writeObject(new ArrayList<>(items));
            }
        } catch (IOException ignored) {
            // 磁盘缓存失败不影响主流程
        }
    }

    @SuppressWarnings("unchecked")
    public synchronized List<WorkItem> load() {
        if (!file.exists()) return null;
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(file))) {
            Object value = in.readObject();
            if (value instanceof List) return (List<WorkItem>) value;
        } catch (IOException | ClassNotFoundException ignored) {
            file.delete(); // 损坏的缓存直接删除
        }
        return null;
    }

    public synchronized void clear() {
        file.delete();
    }
}
