package com.easyback.identity;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final IdentityStore store;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final String issuer, dummyHash;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Attempts> attempts = new ConcurrentHashMap<>();
    private record Attempts(int count, Instant expires) {}
    public record LoginResult(String accessToken, IdentityStore.UserView user) {}
    public record Tokens(LoginResult body, String refresh) {}
    public static class InvalidRefresh extends ApiException {
        InvalidRefresh() { super(401, "SESSION_EXPIRED", "Sign in again."); }
    }
    public AuthService(IdentityStore store, PasswordEncoder passwords, JwtEncoder encoder,
        @Value("${easyback.issuer}") String issuer) {
        this.store = store; this.passwords = passwords; this.encoder = encoder; this.issuer = issuer;
        dummyHash = passwords.encode(UUID.randomUUID().toString());
    }
    @Transactional
    public Tokens login(String email, String password, String ip) {
        String key = ip + ":" + Administration.normalizeEmail(email);
        checkRate(key, 10); checkRate(ip, 100);
        var account = store.findByEmail(Administration.normalizeEmail(email));
        boolean valid = password.getBytes(StandardCharsets.UTF_8).length <= 72
            && passwords.matches(password, account.map(IdentityStore.Account::passwordHash).orElse(dummyHash));
        if (!valid || account.isEmpty() || !account.get().active()) {
            failed(key); failed(ip);
            throw new ApiException(401, "INVALID_LOGIN", "Email or password is incorrect.");
        }
        var user = account.get();
        store.db().sql("SELECT id FROM users WHERE id = :id FOR UPDATE").param("id", user.id()).query(UUID.class).single();
        var locked = store.account(user.id());
        if (!locked.active() || !locked.passwordHash().equals(user.passwordHash()))
            throw new ApiException(401, "INVALID_LOGIN", "Email or password is incorrect.");
        attempts.remove(key);
        UUID session = UUID.randomUUID();
        store.db().sql("INSERT INTO user_sessions(id, user_id, expires_at) VALUES (:id, :user, :expires)")
            .param("id", session).param("user", user.id()).param("expires", OffsetDateTime.now(ZoneOffset.UTC).plusDays(7)).update();
        store.audit(user.id(), "SIGNED_IN", user.id());
        return issue(user.id(), session);
    }
    private void checkRate(String key, int limit) {
        var value = attempts.get(key);
        if (value != null && value.expires().isAfter(Instant.now()) && value.count() >= limit)
            throw new ApiException(429, "LOGIN_LIMIT", "Too many attempts. Try again in 15 minutes.");
    }
    private void failed(String key) {
        var now = Instant.now();
        if (attempts.size() >= 10000) {
            attempts.entrySet().removeIf(e -> e.getValue().expires().isBefore(now));
            if (attempts.size() >= 10000 && !attempts.containsKey(key))
                throw new ApiException(429, "LOGIN_LIMIT", "Too many attempts. Try later.");
        }
        attempts.compute(key, (k, old) -> old == null || old.expires().isBefore(now)
            ? new Attempts(1, now.plusSeconds(900)) : new Attempts(old.count() + 1, old.expires()));
    }
    @Transactional(noRollbackFor = InvalidRefresh.class)
    public Tokens refresh(String raw) {
        if (raw == null || raw.length() > 100) throw new InvalidRefresh();
        String hash = hash(raw);
        var sessionId = store.db().sql("SELECT session_id FROM refresh_tokens WHERE token_hash = :hash")
            .param("hash", hash).query(UUID.class).optional().orElseThrow(InvalidRefresh::new);
        var session = store.db().sql("SELECT user_id FROM user_sessions WHERE id = :id FOR UPDATE")
            .param("id", sessionId).query(UUID.class).single();
        boolean used = store.db().sql("SELECT used_at IS NOT NULL FROM refresh_tokens WHERE token_hash = :hash")
            .param("hash", hash).query(Boolean.class).single();
        if (used) {
            store.db().sql("UPDATE user_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("id", sessionId).update();
            store.audit(session, "REFRESH_REPLAY_REVOKED", sessionId);
            throw new InvalidRefresh();
        }
        if (!sessionActive(sessionId, session) || !store.account(session).active()) throw new InvalidRefresh();
        store.db().sql("UPDATE refresh_tokens SET used_at = CURRENT_TIMESTAMP WHERE token_hash = :hash")
            .param("hash", hash).update();
        return issue(session, sessionId);
    }
    Tokens issue(UUID user, UUID session) {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        store.db().sql("INSERT INTO refresh_tokens(id, session_id, token_hash) VALUES (:id, :session, :hash)")
            .param("id", UUID.randomUUID()).param("session", session).param("hash", hash(raw)).update();
        var now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(user.toString()).audience(List.of("easyback-app"))
            .issuedAt(now).expiresAt(now.plusSeconds(900)).claim("sid", session.toString()).build();
        String jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new Tokens(new LoginResult(jwt, store.view(user)), raw);
    }
    boolean sessionActive(UUID session, UUID user) {
        return store.db().sql("""
            SELECT count(*) FROM user_sessions WHERE id = :id AND user_id = :user
            AND revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP
            """).param("id", session).param("user", user).query(Long.class).single() == 1;
    }
    @Transactional
    public void logout(String raw) {
        if (raw == null || raw.length() > 100) return;
        store.db().sql("""
            UPDATE user_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE id IN
            (SELECT session_id FROM refresh_tokens WHERE token_hash = :hash)
            """).param("hash", hash(raw)).update();
    }
    @Transactional
    public void changePassword(UUID user, String current, String next) {
        store.guard(); Administration.passwordPolicy(next);
        var account = store.account(user);
        if (current.getBytes(StandardCharsets.UTF_8).length > 72 || !passwords.matches(current, account.passwordHash()))
            throw new ApiException(400, "CURRENT_PASSWORD", "Current password is incorrect.");
        if (passwords.matches(next, account.passwordHash()))
            throw new ApiException(400, "SAME_PASSWORD", "Choose a different password.");
        store.db().sql("UPDATE users SET password_hash = :hash, must_change_password = false, version = version + 1 WHERE id = :id")
            .param("hash", passwords.encode(next)).param("id", user).update();
        store.revokeSessions(user); store.audit(user, "PASSWORD_CHANGED", user);
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
