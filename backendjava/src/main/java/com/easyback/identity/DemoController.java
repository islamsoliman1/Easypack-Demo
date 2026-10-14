package com.easyback.identity;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("demo")
public class DemoController {
    private final DemoSessionService sessions;
    private final AuthController auth;
    public DemoController(DemoSessionService sessions, AuthController auth) { this.sessions = sessions; this.auth = auth; }

    @Bean("demoPublicPaths")
    public RequestMatcher publicPaths() {
        return request -> request.getMethod().equals("POST") && request.getServletPath().equals("/api/v1/auth/demo");
    }

    @PostMapping("/api/v1/auth/demo")
    public AuthService.LoginResult enter(HttpServletResponse response) {
        return auth.tokens(sessions.enter(), response);
    }
}
