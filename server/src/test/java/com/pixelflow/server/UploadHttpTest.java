package com.pixelflow.server;

import com.pixelflow.server.auth.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:upload-http-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long"})
class UploadHttpTest {
    @LocalServerPort int port;
    @Autowired AuthService auth;
    @Autowired ObjectMapper json;
    private HttpResponse<byte[]> upload(byte[] image) throws Exception {
        String name="u"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        auth.register(name,"test-password-2026",name);
        String token=auth.login(name,"test-password-2026").accessToken();
        String boundary="PixelFlowTestBoundary";
        var bytes=new ByteArrayOutputStream();
        bytes.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        bytes.write(image); bytes.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/media"))
                .timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+token)
                .header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build();
        return HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofByteArray());
    }
    @Test void servletRejectsOversizedMultipartWithStructuredError() throws Exception {
        var response=upload(new byte[8*1024*1024+1]);
        assertEquals(413,response.statusCode());
        assertEquals("MEDIA_TOO_LARGE",json.readTree(response.body()).get("code").asText());
        assertTrue(response.headers().firstValue("X-Request-Id").isPresent());
    }
    @Test void realMultipartRequestPersistsImage() throws Exception {
        var image=new java.awt.image.BufferedImage(12,18,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes=new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image,"png",bytes); image.flush();
        var response=upload(bytes.toByteArray());
        assertEquals(201,response.statusCode());
        var result=json.readTree(response.body());
        assertEquals(12,result.get("width").asInt());
        assertEquals("image/png",result.get("contentType").asText());
    }
}
