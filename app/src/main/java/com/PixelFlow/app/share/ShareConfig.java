package com.PixelFlow.app.share;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public final class ShareConfig {
    private ShareConfig() { }

    public static String postLink(Properties values, String postId) {
        String base = values.getProperty("web_url", "").trim();
        try {
            URI uri = new URI(base);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || postId == null || postId.trim().isEmpty()) throw new IllegalArgumentException();
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            return base + "/#/post/" + URLEncoder.encode(postId, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid web_url", invalid);
        }
    }
}
