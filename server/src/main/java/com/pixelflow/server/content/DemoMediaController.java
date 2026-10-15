package com.pixelflow.server.content;

import com.pixelflow.server.common.ApiException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;

/** 本地生成演示封面，不依赖外部图床；不是文件上传接口。 */
@RestController
public class DemoMediaController {
    @GetMapping(value="/demo-media/{id}.png",produces=MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> image(@PathVariable int id) throws IOException {
        if (id<1 || id>24) throw new ApiException(404,"NOT_FOUND","演示封面不存在");
        BufferedImage image=new BufferedImage(600,800,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();
        try {
            g.setColor(Color.getHSBColor(id/24f,0.3f,0.85f)); g.fillRect(0,0,600,800);
            g.setColor(Color.WHITE); g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,42));
            g.drawString("PixelFlow",70,350); g.drawString("DEMO "+id,70,420);
        } finally { g.dispose(); }
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); ImageIO.write(image,"png",bytes);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1))).body(bytes.toByteArray());
    }
}
