package com.pixelflow.server.auth;

import com.pixelflow.server.common.ApiException;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
public class AuthService {
    public record Actor(String id, String role, String sessionId) {}
    public record Tokens(String accessToken, String refreshToken, String tokenType, long expiresIn) {}
    private final JdbcTemplate db;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash = passwords.encode(UUID.randomUUID().toString());

    public AuthService(JdbcTemplate db, @Value("${pixelflow.jwt-secret}") String secret) {
        this.db = db;
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            bytes = new byte[32]; random.nextBytes(bytes);
        } else if (bytes.length < 32) {
            throw new IllegalArgumentException("PIXELFLOW_JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        SecretKeySpec key = new SecretKeySpec(bytes,"HmacSHA256");
        encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        jwtDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("pixelflow"));
        decoder = jwtDecoder;
    }

    @Transactional
    public Map<String,String> register(String username, String password, String nickname) {
        checkPassword(password);
        if (Set.of("admin","demo_author").contains(username.toLowerCase(Locale.ROOT))
                || username.toLowerCase(Locale.ROOT).startsWith("demo_media_"))
            throw new ApiException(400,"RESERVED_USERNAME","该用户名保留用于系统初始化");
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO users(id,username,password_hash,nickname,role) VALUES(?,?,?,?,?)",
                id,username.toLowerCase(Locale.ROOT),passwords.encode(password),nickname,"CREATOR");
        return Map.of("id",id,"username",username.toLowerCase(Locale.ROOT),"nickname",nickname,"role","CREATOR");
    }

    public void checkPassword(String password) {
        if (password.length() < 10 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ApiException(400,"INVALID_PASSWORD","密码至少 10 个字符，UTF-8 编码不超过 72 字节");
    }

    @Transactional
    public Tokens login(String username, String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) throw unauthorized();
        var rows = db.queryForList("SELECT * FROM users WHERE username=?",username.toLowerCase(Locale.ROOT));
        String hash = rows.isEmpty() ? dummyHash : (String) rows.get(0).get("password_hash");
        boolean matches = passwords.matches(password,hash);
        if (rows.isEmpty() || !matches || !Boolean.TRUE.equals(rows.get(0).get("enabled"))) throw unauthorized();
        return issue((String) rows.get(0).get("id"));
    }

    private Tokens issue(String userId) {
        String sessionId = UUID.randomUUID().toString();
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now();
        db.update("INSERT INTO sessions(id,user_id,refresh_hash,expires_at) VALUES(?,?,?,?)",
                sessionId,userId,hash(refresh),now.plusSeconds(7*86400).getEpochSecond());
        var claims = JwtClaimsSet.builder().issuer("pixelflow").subject(userId).id(sessionId)
                .issuedAt(now).expiresAt(now.plusSeconds(900)).build();
        String access = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
        return new Tokens(access,refresh,"Bearer",900);
    }

    @Transactional
    public Tokens refresh(String token) {
        var rows = db.queryForList("SELECT s.* FROM sessions s JOIN users u ON s.user_id=u.id "
                + "WHERE s.refresh_hash=? AND s.expires_at>? AND u.enabled=TRUE FOR UPDATE",hash(token),Instant.now().getEpochSecond());
        if (rows.isEmpty()) throw unauthorized();
        var session = rows.get(0);
        db.update("DELETE FROM sessions WHERE id=?",session.get("id"));
        return issue((String) session.get("user_id"));
    }

    public Actor authenticate(String token) {
        Jwt jwt = decoder.decode(token);
        var rows = db.queryForList("SELECT u.id,u.role FROM sessions s JOIN users u ON s.user_id=u.id "
                + "WHERE s.id=? AND u.id=? AND s.expires_at>? AND u.enabled=TRUE",
                jwt.getId(),jwt.getSubject(),Instant.now().getEpochSecond());
        if (rows.isEmpty()) throw unauthorized();
        return new Actor(jwt.getSubject(),(String)rows.get(0).get("role"),jwt.getId());
    }

    public void logout(Actor actor) { db.update("DELETE FROM sessions WHERE id=?",actor.sessionId()); }
    public String passwordHash(String password) { checkPassword(password); return passwords.encode(password); }
    private static ApiException unauthorized() { return new ApiException(401,"UNAUTHORIZED","账号、密码或令牌无效"); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
