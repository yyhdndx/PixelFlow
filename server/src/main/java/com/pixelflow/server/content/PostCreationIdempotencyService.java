package com.pixelflow.server.content;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
public class PostCreationIdempotencyService {
    public record Result(boolean created,Map<String,Object> post) {}
    private final PostService posts;
    private final MediaService media;
    private final PostCreationIdempotencyStore store;
    public PostCreationIdempotencyService(PostService posts,MediaService media,PostCreationIdempotencyStore store) {
        this.posts=posts; this.media=media; this.store=store;
    }
    @Transactional
    public Result create(Actor actor,String key,PostService.Draft draft) {
        media.lockOwner(actor);
        String fingerprint=fingerprint(draft);
        var previous=store.lock(actor.id(),key);
        if (previous.isPresent()) {
            var entry=previous.get();
            if (!entry.fingerprint().equals(fingerprint))
                throw new ApiException(409,"IDEMPOTENCY_CONFLICT","同一 Idempotency-Key 不能用于不同的创建请求");
            return new Result(false,posts.detail(entry.postId(),actor));
        }
        var post=posts.create(actor,draft);
        store.save(actor.id(),key,fingerprint,Long.parseLong(post.get("post_id").toString()));
        return new Result(true,post);
    }
    private static String fingerprint(PostService.Draft draft) {
        try {
            var bytes=new ByteArrayOutputStream();
            try (var output=new DataOutputStream(bytes)) {
                text(output,draft.title()); text(output,draft.content());
                output.writeInt(draft.mediaIds().size());
                for (String id:draft.mediaIds()) text(output,id);
                output.writeInt(draft.clips().size());
                for (var clip:draft.clips()) {
                    output.writeInt(clip.type()); output.writeInt(clip.width()); output.writeInt(clip.height());
                    text(output,clip.url());
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException e) { throw new IllegalStateException("Cannot fingerprint draft",e); }
    }
    private static void text(DataOutputStream output,String value) throws IOException {
        output.writeInt(value.length());
        for (int i=0;i<value.length();i++) output.writeChar(value.charAt(i));
    }
}
