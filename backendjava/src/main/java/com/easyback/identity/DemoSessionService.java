package com.easyback.identity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("demo")
public class DemoSessionService {
    private final IdentityStore store;
    private final AuthService auth;
    public DemoSessionService(IdentityStore store, AuthService auth) { this.store = store; this.auth = auth; }

    @Transactional
    public AuthService.Tokens enter() {
        store.guard();
        if (!store.db().sql("SELECT seeded FROM demo_installation WHERE id=1").query(Boolean.class).single())
            throw new ApiException(503, "DEMO_STARTING", "Demo data is being prepared. Please retry.");
        var account = store.account(DemoBootstrap.VISITOR);
        if (!account.active() || account.mustChangePassword())
            throw new ApiException(503, "DEMO_UNAVAILABLE", "Demo account is unavailable.");
        store.db().sql("DELETE FROM refresh_tokens WHERE session_id IN (SELECT id FROM user_sessions WHERE expires_at<CURRENT_TIMESTAMP OR revoked_at IS NOT NULL)").update();
        store.db().sql("DELETE FROM user_sessions WHERE expires_at<CURRENT_TIMESTAMP OR revoked_at IS NOT NULL").update();
        if (store.db().sql("SELECT count(*) FROM user_sessions").query(Long.class).single() >= 2000)
            throw new ApiException(429, "DEMO_BUSY", "The demo is busy. Please retry later.");
        UUID session = UUID.randomUUID();
        store.db().sql("INSERT INTO user_sessions(id,user_id,expires_at) VALUES (:id,:user,:expires)")
            .param("id", session).param("user", DemoBootstrap.VISITOR)
            .param("expires", OffsetDateTime.now(ZoneOffset.UTC).plusHours(8)).update();
        return auth.issue(DemoBootstrap.VISITOR, session);
    }
}
