package com.PixelFlow.app.API;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ApiConfigurationTest {
    @Test public void readsPlainUtf8AndMissingUrl() throws Exception {
        assertEquals("http://8.140.36.87", read("# 配置\nurl=http://8.140.36.87  \n".getBytes(StandardCharsets.UTF_8)));
        assertEquals("",read(new byte[0]));
        assertEquals("",read("#".getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void acceptsUtf8BomBeforeFirstKey() throws Exception {
        assertEquals("http://8.140.36.87",read("\ufeffurl=http://8.140.36.87\n".getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void acceptsInstalledDeviceUtf16LayoutAndBigEndian() throws Exception {
        String config="\ufeff# Android\r\r\nurl=http://8.140.36.87\r\r\nweb_url=http://localhost:5173\r\n";
        byte[] installed=config.getBytes(StandardCharsets.UTF_16LE);
        java.util.Properties old=new java.util.Properties(); old.load(new ByteArrayInputStream(installed));
        assertNull(old.getProperty("url"));
        assertEquals("http://8.140.36.87",read(installed));
        assertEquals("http://8.140.36.87",read(config.getBytes(StandardCharsets.UTF_16BE)));
    }
    private String read(byte[] data) throws Exception {return ApiConfiguration.readUrl(new ByteArrayInputStream(data));}
}
