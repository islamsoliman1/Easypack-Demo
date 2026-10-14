package com.easyback.identity;

import com.easyback.web.RailwaySpaConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RailwaySpaSecurityTest {
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfiguration.class, RailwaySpaConfiguration.class, ProbeApi.class})
    static class TestConfiguration {
        @Bean IdentityStore store() { return mock(IdentityStore.class); }
        @Bean AuthService auth() { return mock(AuthService.class); }
    }

    @RestController
    static class ProbeApi {
        @GetMapping("/api/v1/auth/csrf") Map<String, String> csrf(CsrfToken token) { return Map.of("token", token.getToken()); }
        @PostMapping("/api/v1/auth/login") void login() {}
        @GetMapping("/api/v1/auth/me") String me() { return "authenticated"; }
        @GetMapping("/api/v1/probe") String probe() { return "protected"; }
        @GetMapping("/actuator/health/readiness") String ready() { return "ready"; }
    }

    static class Fixture implements AutoCloseable {
        final AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        final MockMvc mvc;
        Fixture(boolean railway, boolean enabled) {
            var environment = new MockEnvironment()
                .withProperty("easyback.jwt-secret", "test-only-not-a-deployment-secret-".repeat(3))
                .withProperty("easyback.issuer", "easyback-api")
                .withProperty("easyback.secure-cookies", "true")
                .withProperty("easyback.allowed-origins", "https://trial.example.invalid")
                .withProperty("springdoc.api-docs.enabled", "false")
                .withProperty("easyback.railway.spa-enabled", Boolean.toString(enabled));
            if (railway) environment.setActiveProfiles("railway");
            context.setEnvironment(environment);
            context.setServletContext(new MockServletContext());
            context.register(TestConfiguration.class);
            context.refresh();
            mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
        }
        String bearer(boolean mustChangePassword) {
            UUID user = UUID.randomUUID(), session = UUID.randomUUID();
            when(context.getBean(AuthService.class).sessionActive(session, user)).thenReturn(true);
            when(context.getBean(IdentityStore.class).account(user)).thenReturn(
                new IdentityStore.Account(user, "owner@example.invalid", "Owner", "unused", true, mustChangePassword, 0));
            when(context.getBean(IdentityStore.class).permissions(user)).thenReturn(Set.of());
            var claims = JwtClaimsSet.builder().issuer("easyback-api").subject(user.toString())
                .audience(List.of("easyback-app")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .claim("sid", session.toString()).build();
            return "Bearer " + context.getBean(JwtEncoder.class).encode(
                JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        }
        @Override public void close() { context.close(); }
    }

    @Test void explicitPagesAndUuidDeepLinksServeIndexDirectlyForGetAndHead() throws Exception {
        try (var f = new Fixture(true, true)) {
            for (String path : List.of("/", "/index.html", "/users", "/roles", "/machine-models", "/parts", "/machines",
                "/boms", "/orders", "/warehouse", "/preparations", "/raw-stock", "/tasks", "/defects", "/operations",
                "/operations/templates", "/shifts", "/reports", "/orders/" + UUID.randomUUID(),
                "/operations/" + UUID.randomUUID(), "/machines/" + UUID.randomUUID() + "/assembly")) {
                var result = f.mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/html"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("railway-test-index"))).andReturn();
                assertNull(result.getResponse().getForwardedUrl());
                f.mvc.perform(head(path)).andExpect(status().isOk()).andExpect(content().bytes(new byte[0]))
                    .andExpect(header().string("Content-Type", result.getResponse().getContentType()))
                    .andExpect(header().string("Content-Length", result.getResponse().getHeader("Content-Length")));
            }
        }
    }

    @Test void staticFilesAreServedButMissingAssetsNeverBecomeSpa() throws Exception {
        try (var f = new Fixture(true, true)) {
            f.mvc.perform(get("/assets/railway-fixture.js")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("railwayFixture")));
            f.mvc.perform(head("/assets/railway-fixture.js")).andExpect(status().isOk());
            f.mvc.perform(get("/brand-mark.svg")).andExpect(status().isOk());
            f.mvc.perform(get("/assets/missing.js")).andExpect(status().isNotFound());
            f.mvc.perform(get("/assets/railway-fixture.js.map")).andExpect(status().isUnauthorized());
        }
    }

    @Test void publicPagesReceiveSecurityHeaders() throws Exception {
        try (var f = new Fixture(true, true)) {
            f.mvc.perform(get("/").secure(true)).andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "same-origin"))
                .andExpect(header().string("Permissions-Policy", "camera=(), microphone=(), geolocation=()"))
                .andExpect(header().exists("Strict-Transport-Security"))
                .andExpect(header().string("Cache-Control", "no-store"));
        }
    }

    @Test void unknownRoutesApisActuatorAndDisabledDocsAreNotPublic() throws Exception {
        try (var f = new Fixture(true, true)) {
            for (String path : List.of("/unknown", "/orders/not-a-uuid", "/machines/123/assembly", "/operations/123",
                "/orders/00000000-0000-0000-0000-000000000000/extra", "/.env", "/api/unknown", "/api/v1/unknown",
                "/actuator/env", "/actuator/info", "/v3/api-docs", "/swagger-ui/index.html", "/swagger-ui.html")) {
                f.mvc.perform(get(path)).andExpect(status().isUnauthorized());
            }
            f.mvc.perform(get("/api/v1/unknown").header("Authorization", f.bearer(false))).andExpect(status().isNotFound());
            f.mvc.perform(get("/v3/api-docs").header("Authorization", f.bearer(false))).andExpect(status().isForbidden());
            f.mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        }
    }

    @Test void profileAndExplicitOptOutBothKeepDefaultDenyAll() throws Exception {
        for (boolean[] mode : List.of(new boolean[]{false, true}, new boolean[]{true, false})) {
            try (var f = new Fixture(mode[0], mode[1])) {
                assertFalse(f.context.containsBean("railwayPublicPaths"));
                for (String path : List.of("/", "/index.html", "/orders", "/brand-mark.svg", "/assets/railway-fixture.js")) {
                    f.mvc.perform(get(path)).andExpect(status().isUnauthorized());
                    f.mvc.perform(get(path).header("Authorization", f.bearer(false))).andExpect(status().isForbidden());
                }
            }
        }
    }

    @Test void csrfAndHttpMethodBoundaryRemainEnforced() throws Exception {
        try (var f = new Fixture(true, true)) {
            for (String path : List.of("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout", "/")) {
                f.mvc.perform(post(path)).andExpect(status().isForbidden());
            }
            var csrf = f.mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
            var cookie = csrf.getResponse().getCookie("XSRF-TOKEN");
            assertNotNull(cookie);
            assertTrue(cookie.getSecure());
            // Without the demo profile there must be no passwordless entry.
            f.mvc.perform(post("/api/v1/auth/demo").servletPath("/api/v1/auth/demo")
                .cookie(cookie).header("X-XSRF-TOKEN", cookie.getValue())).andExpect(status().isUnauthorized());
            f.mvc.perform(post("/api/v1/auth/login").cookie(cookie).header("X-XSRF-TOKEN", cookie.getValue()))
                .andExpect(status().isOk());
            f.mvc.perform(post("/").cookie(cookie).header("X-XSRF-TOKEN", cookie.getValue())).andExpect(status().isUnauthorized());
            f.mvc.perform(options("/orders")).andExpect(status().isUnauthorized());
            f.mvc.perform(get("/api/v1/auth/csrf").header("Origin", "https://untrusted.example.invalid"))
                .andExpect(status().isForbidden());
        }
    }

    @Test void authenticationAndMandatoryPasswordChangeRemainEnforced() throws Exception {
        try (var f = new Fixture(true, true)) {
            f.mvc.perform(get("/api/v1/probe")).andExpect(status().isUnauthorized());
            f.mvc.perform(get("/").header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
            f.mvc.perform(get("/api/v1/probe").header("Authorization", f.bearer(false))).andExpect(status().isOk());
            String bearer = f.bearer(true);
            f.mvc.perform(get("/api/v1/probe").servletPath("/api/v1/probe").header("Authorization", bearer))
                .andExpect(status().isForbidden()).andExpect(content().string(org.hamcrest.Matchers.containsString("PASSWORD_CHANGE_REQUIRED")));
            f.mvc.perform(get("/api/v1/auth/me").servletPath("/api/v1/auth/me").header("Authorization", bearer))
                .andExpect(status().isOk());
        }
    }

    @Test void railwayProfilePinsSensitiveDefaultsAndDatabaseReadiness() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-railway.yml"));
        var properties = yaml.getObject();
        assertNotNull(properties);
        assertEquals("${PORT:8080}", properties.getProperty("server.port"));
        assertEquals("${DB_URL}", properties.getProperty("spring.datasource.url"));
        assertEquals("3", properties.getProperty("spring.datasource.hikari.maximum-pool-size"));
        assertEquals("true", properties.getProperty("easyback.secure-cookies"));
        assertEquals("false", properties.getProperty("springdoc.api-docs.enabled"));
        assertEquals("false", properties.getProperty("springdoc.swagger-ui.enabled"));
        assertEquals("health", properties.getProperty("management.endpoints.web.exposure.include"));
        assertEquals("readinessState,db", properties.getProperty("management.endpoint.health.group.readiness.include"));
    }
}
