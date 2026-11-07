package com.PixelFlow.app.API;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Accept editor-generated BOMs without silently losing the server URL. */
public final class ApiConfiguration {
    private ApiConfiguration() { }
    public static String readUrl(InputStream input) throws IOException {
        PushbackInputStream stream = new PushbackInputStream(input, 3);
        byte[] prefix = new byte[3];
        int count = 0, value;
        while (count < 3 && (value = stream.read()) != -1) prefix[count++] = (byte) value;
        Charset charset = StandardCharsets.UTF_8;
        int skip = 0;
        if (count >= 2 && prefix[0] == (byte) 0xff && prefix[1] == (byte) 0xfe) {
            charset = StandardCharsets.UTF_16LE; skip = 2;
        } else if (count >= 2 && prefix[0] == (byte) 0xfe && prefix[1] == (byte) 0xff) {
            charset = StandardCharsets.UTF_16BE; skip = 2;
        } else if (count == 3 && prefix[0] == (byte) 0xef && prefix[1] == (byte) 0xbb && prefix[2] == (byte) 0xbf) {
            skip = 3;
        }
        if (count > skip) stream.unread(prefix, skip, count - skip);
        Properties properties = new Properties();
        properties.load(new InputStreamReader(stream, charset));
        return properties.getProperty("url", "").trim();
    }
}
