package com.pixelflow.server.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

public final class ClientIpResolver {
    private final List<IpAddressMatcher> proxies;

    public ClientIpResolver(List<String> trusted) {
        proxies = (trusted == null ? List.<String>of() : trusted).stream().map(value -> {
            String[] parts=value.split("/",-1);
            if (parts.length>2 || literal(parts[0])==null) throw new IllegalArgumentException("Invalid trusted proxy address");
            if (parts.length==2) {
                int bits;
                try { bits=Integer.parseInt(parts[1]); }
                catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid trusted proxy prefix"); }
                int max=literal(parts[0]).contains(":") ? 128 : 32;
                if (bits<0 || bits>max) throw new IllegalArgumentException("Invalid trusted proxy prefix");
            }
            return new IpAddressMatcher(value);
        }).toList();
    }

    public String resolve(HttpServletRequest request) {
        String socket=literal(request.getRemoteAddr());
        if (socket==null) return request.getRemoteAddr();
        if (proxies.stream().noneMatch(proxy -> proxy.matches(socket))) return socket;
        var headers=Collections.list(request.getHeaders("X-Real-IP"));
        String forwarded=headers.size()==1 ? literal(headers.get(0)) : null;
        return forwarded==null ? socket : forwarded;
    }

    static String literal(String value) {
        if (value==null || value.isEmpty()) return null;
        if (value.contains(":")) {
            if (!value.matches("[0-9a-fA-F:.]+")) return null;
            try { return InetAddress.getByName(value).getHostAddress(); }
            catch (UnknownHostException error) { return null; }
        }
        String[] parts=value.split("\\.",-1);
        if (parts.length!=4) return null;
        StringJoiner result=new StringJoiner(".");
        for (String part:parts) {
            if (!part.matches("0|[1-9][0-9]{0,2}")) return null;
            int number=Integer.parseInt(part);
            if (number>255) return null;
            result.add(Integer.toString(number));
        }
        return result.toString();
    }
}
