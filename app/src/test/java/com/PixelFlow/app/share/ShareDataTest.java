package com.PixelFlow.app.share;

import com.PixelFlow.app.API.PostItem;
import com.google.gson.Gson;
import java.util.Properties;
import org.junit.Test;
import static org.junit.Assert.*;

public class ShareDataTest {
    @Test public void hashRoutePreservesDeploymentPathAndEscapesIdentity() {
        Properties values = new Properties(); values.setProperty("web_url", "https://example.org/pixel///");
        assertEquals("https://example.org/pixel/#/post/a%20b", ShareConfig.postLink(values, "a b"));
        values.setProperty("web_url", "http://localhost:5173");
        assertEquals("http://localhost:5173/#/post/30", ShareConfig.postLink(values, "30"));
    }
    @Test public void invalidOrMissingWebConfigNeverFallsBackToApi() {
        for (String url : new String[]{"", "ftp://host", "https://host/#/", "https://host/?q=1", "https://user@host", "not a url"}) {
            Properties values = new Properties(); values.setProperty("web_url", url); values.setProperty("url", "https://api.example.org");
            try { ShareConfig.postLink(values, "30"); fail(url); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void publicationTimeAndBackendCommentTotalMapWithoutChangingCreateTime() {
        PostItem post = new Gson().fromJson("{\"create_time\":11,\"publish_time\":22,\"comment_count\":57}", PostItem.class);
        assertEquals(Long.valueOf(22), post.getPublishTime()); assertEquals(22, ShareData.timestamp(post));
        assertEquals(11, post.getCreateTime()); assertEquals(57, ShareData.comments(post));
    }
    @Test public void absentNullableFieldsFallBackAndNegativeCommentsAreClamped() {
        PostItem post = new Gson().fromJson("{\"create_time\":11}", PostItem.class);
        assertNull(post.getPublishTime()); assertNull(post.getCommentCount());
        assertEquals(11, ShareData.timestamp(post)); assertEquals(0, ShareData.comments(post));
        post = new Gson().fromJson("{\"publish_time\":0,\"comment_count\":-2}", PostItem.class);
        assertEquals(0, ShareData.timestamp(post)); assertEquals(0, ShareData.comments(post));
    }
    @Test public void onlyExplicitPublishedSamePostCanShare() {
        PostItem post = new PostItem(); post.setPostId("30");
        for (String state : new String[]{null, "DRAFT", "REVIEWING", "REJECTED", "DELETED", "published"}) {
            post.setStatus(state); assertFalse(ShareData.isPublic(post, "30"));
        }
        post.setStatus("PUBLISHED"); assertTrue(ShareData.isPublic(post, "30"));
        assertFalse(ShareData.isPublic(post, "31")); assertFalse(ShareData.isPublic(null, "30"));
    }
}
