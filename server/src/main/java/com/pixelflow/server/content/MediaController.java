package com.pixelflow.server.content;

import com.pixelflow.server.auth.AuthService.Actor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

@RestController
public class MediaController {
    private final MediaService media;
    public MediaController(MediaService media) { this.media=media; }
    @PostMapping(value="/api/media",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> upload(@RequestParam("file") MultipartFile file,@AuthenticationPrincipal Actor actor) {
        return media.upload(actor,file);
    }
    @GetMapping("/api/media")
    public Map<String,Object> list(@AuthenticationPrincipal Actor actor,@RequestParam(defaultValue="20") int count,
            @RequestParam(defaultValue="") String after,@RequestParam(defaultValue="false") boolean unusedOnly) {
        return media.list(actor,count,after,unusedOnly);
    }
    @DeleteMapping("/api/media/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id,@AuthenticationPrincipal Actor actor) {
        media.delete(actor,id);
    }
    @GetMapping("/api/media/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable String id,@AuthenticationPrincipal Actor actor) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
                .header("Content-Disposition","inline; filename=\"image.png\"").body(media.content(id,actor));
    }
}
