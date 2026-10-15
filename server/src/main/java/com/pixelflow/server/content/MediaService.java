package com.pixelflow.server.content;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;

@Service
public class MediaService {
    public static final int MAX_BYTES=8*1024*1024;
    private final JdbcTemplate db;
    private final String base;
    public MediaService(JdbcTemplate db,@Value("${pixelflow.public-base-url}") String base) {
        this.db=db;
        URI uri=URI.create(base);
        if (!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null
                || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("Public base URL must be an HTTP(S) origin");
        this.base=base.replaceAll("/+$","");
    }
    @Transactional
    public Map<String,Object> upload(Actor actor,MultipartFile file) {
        if (file.isEmpty()) throw invalid("文件不能为空");
        if (file.getSize()>MAX_BYTES) throw tooLarge();
        byte[] normalized;
        int width,height;
        // 真实格式由内容识别；不使用上传文件名、扩展名或客户端 Content-Type。
        try (InputStream source=file.getInputStream()) {
            byte[] input=source.readNBytes(MAX_BYTES+1);
            if (input.length>MAX_BYTES) throw tooLarge();
            try (var imageInput=new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
                var readers=ImageIO.getImageReaders(imageInput);
                if (!readers.hasNext()) throw invalid("仅支持有效 JPEG 或 PNG 图片");
                var reader=readers.next();
                try {
                    if (!Set.of("png","jpeg").contains(reader.getFormatName().toLowerCase(Locale.ROOT)))
                        throw invalid("仅支持 JPEG 或 PNG 图片");
                    reader.setInput(imageInput,true,true);
                    width=reader.getWidth(0); height=reader.getHeight(0);
                    if (width<1 || height<1 || width>8192 || height>8192 || (long)width*height>4_000_000)
                        throw invalid("图片最多 400 万像素，单边不超过 8192");
                    var image=reader.read(0);
                    try (var bytes=new ByteArrayOutputStream()) {
                        if (!ImageIO.write(image,"png",bytes)) throw invalid("图片无法转换");
                        if (bytes.size()>MAX_BYTES) throw tooLarge();
                        normalized=bytes.toByteArray();
                    } finally { image.flush(); }
                } finally { reader.dispose(); }
            }
        } catch (IOException e) { throw invalid("图片损坏或无法读取"); }
        // 小规模课程版本每用户最多 100 张，避免无界写盘；附件与元数据同事务持久化。
        lockOwner(actor);
        if (db.queryForObject("SELECT COUNT(*) FROM media WHERE owner_id=?",Integer.class,actor.id())>=100)
            throw new ApiException(409,"MEDIA_QUOTA","上传数量已达 100 张上限");
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO media(id,owner_id,width,height,content_bytes,created_at) VALUES(?,?,?,?,?,?)",
                id,actor.id(),width,height,normalized,Instant.now().getEpochSecond());
        return Map.of("mediaId",id,"url",url(id),"width",width,"height",height,"type",0,"size",normalized.length,"contentType","image/png");
    }
    public List<PostService.Clip> ownedClips(Actor actor,List<String> ids) {
        if (new HashSet<>(ids).size()!=ids.size()) throw invalid("mediaIds 不能重复");
        List<PostService.Clip> clips=new ArrayList<>();
        for (String id:ids) {
            var rows=db.queryForList("SELECT width,height FROM media WHERE id=? AND owner_id=?",id,actor.id());
            if (rows.isEmpty()) throw new ApiException(404,"MEDIA_NOT_FOUND","媒体不存在或不属于当前用户");
            var row=rows.get(0);
            clips.add(new PostService.Clip(0,((Number)row.get("width")).intValue(),((Number)row.get("height")).intValue(),url(id)));
        }
        return clips;
    }
    /** 调用方必须在事务内，且在锁作品之前锁用户；上传、引用变更和删除共用此顺序。 */
    public void lockOwner(Actor actor) {
        db.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",String.class,actor.id());
    }

    public Map<String,Object> list(Actor actor,int count,String after,boolean unusedOnly) {
        PostService.checkPage(count,null);
        if (!after.isEmpty() && !after.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new ApiException(400,"INVALID_ARGUMENT","after 须为列表返回的媒体 ID");
        var rows=db.queryForList("SELECT m.id,m.width,m.height,m.created_at,OCTET_LENGTH(m.content_bytes) AS byte_size,"
                + "(SELECT COUNT(*) FROM post_media pm WHERE pm.media_id=m.id) AS reference_count "
                + "FROM media m WHERE m.owner_id=? AND m.id>? "
                + (unusedOnly ? "AND NOT EXISTS (SELECT 1 FROM post_media pm WHERE pm.media_id=m.id) " : "")
                + "ORDER BY m.id LIMIT ?",actor.id(),after,count+1);
        boolean more=rows.size()>count;
        var items=rows.subList(0,Math.min(count,rows.size())).stream().map(row -> Map.<String,Object>of(
                "mediaId",row.get("id"),"url",url((String)row.get("id")),"width",row.get("width"),
                "height",row.get("height"),"size",row.get("byte_size"),"createdAt",row.get("created_at"),
                "referenceCount",row.get("reference_count"),"contentType","image/png")).toList();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("items",items);
        result.put("nextCursor",more ? items.get(items.size()-1).get("mediaId") : null);
        result.put("usedCount",db.queryForObject("SELECT COUNT(*) FROM media WHERE owner_id=?",Integer.class,actor.id()));
        result.put("limit",100);
        return result;
    }

    @Transactional
    public void delete(Actor actor,String id) {
        lockOwner(actor);
        if (db.queryForObject("SELECT COUNT(*) FROM media WHERE id=? AND owner_id=?",Integer.class,id,actor.id())==0)
            throw new ApiException(404,"MEDIA_NOT_FOUND","媒体不存在或不属于当前用户");
        if (db.queryForObject("SELECT COUNT(*) FROM post_media WHERE media_id=?",Integer.class,id)>0)
            throw new ApiException(409,"MEDIA_IN_USE","图片仍被作品或草稿引用，请先移除引用");
        db.update("DELETE FROM media WHERE id=? AND owner_id=?",id,actor.id());
    }
    public void attach(long postId,List<String> ids) {
        db.update("DELETE FROM post_media WHERE post_id=?",postId);
        for (int i=0;i<ids.size();i++) db.update("INSERT INTO post_media(post_id,media_id,position) VALUES(?,?,?)",postId,ids.get(i),i);
    }
    public byte[] content(String id,Actor actor) {
        // 条件与读取合为一条查询，不能通过猜测 UUID 访问私有媒体。
        var rows=db.query("SELECT m.content_bytes FROM media m WHERE m.id=? AND "
                + "(m.owner_id=? OR ?=TRUE OR EXISTS (SELECT 1 FROM post_media pm JOIN posts p ON pm.post_id=p.id "
                + "JOIN users u ON p.author_id=u.id WHERE pm.media_id=m.id AND p.status='PUBLISHED' AND u.enabled=TRUE))",
                (rs,n)->rs.getBytes(1),id,actor==null ? "" : actor.id(),actor!=null && actor.role().equals("ADMIN"));
        if (rows.isEmpty()) throw new ApiException(404,"MEDIA_NOT_FOUND","媒体不存在或不可见");
        return rows.get(0);
    }
    public String url(String id) { return base+"/api/media/"+id+"/content"; }
    private static ApiException invalid(String message) { return new ApiException(400,"INVALID_MEDIA",message); }
    private static ApiException tooLarge() { return new ApiException(413,"MEDIA_TOO_LARGE","图片不得超过 8 MiB"); }
}
