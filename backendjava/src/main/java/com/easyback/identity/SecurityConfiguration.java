package com.easyback.identity;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.csrf.*;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean PasswordEncoder passwords() { return new BCryptPasswordEncoder(12); }
    private SecretKeySpec key(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length < 64) throw new IllegalStateException("JWT_SECRET must contain at least 64 bytes.");
        return new SecretKeySpec(value.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
    @Bean JwtEncoder encoder(@Value("${easyback.jwt-secret}") String secret) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key(secret)));
    }
    @Bean JwtDecoder decoder(@Value("${easyback.jwt-secret}") String secret, @Value("${easyback.issuer}") String issuer) {
        var decoder = NimbusJwtDecoder.withSecretKey(key(secret)).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience().contains("easyback-app")
            ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audience));
        return decoder;
    }
    @Bean SecurityFilterChain security(HttpSecurity http, IdentityStore store, AuthService auth,
        @Value("${easyback.secure-cookies}") boolean secure,
        @Value("${easyback.allowed-origins}") String origins,
        @Value("${springdoc.api-docs.enabled}") boolean docs,
        @Qualifier("railwayPublicPaths") Optional<RequestMatcher> railwayPublicPaths,
        @Qualifier("demoPublicPaths") Optional<RequestMatcher> demoPublicPaths) throws Exception {
        var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieCustomizer(c -> c.secure(secure).sameSite("Strict").path("/"));
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(Arrays.asList(origins.split(","))); cors.setAllowCredentials(true);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-XSRF-TOKEN"));
        var source = new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/**", cors);
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .cors(c -> c.configurationSource(source))
            .csrf(c -> c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .headers(h -> h.addHeaderWriter((request, response) -> railwayPublicPaths.ifPresent(paths -> {
                if (paths.matches(request)) {
                    response.setHeader("Referrer-Policy", "same-origin");
                    response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
                }
            })))
            .authorizeHttpRequests(a -> {
                railwayPublicPaths.ifPresent(paths -> a.requestMatchers(paths).permitAll());
                demoPublicPaths.ifPresent(paths -> {
                    a.requestMatchers(paths).permitAll();
                    a.requestMatchers("/api/v1/auth/password", "/api/v1/auth/login").denyAll();
                });
                a.requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf", "/actuator/health", "/actuator/health/**").permitAll();
                a.requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll();
                if (docs) a.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                a.requestMatchers("/api/v1/**").authenticated().anyRequest().denyAll();
            })
            .exceptionHandling(e -> e.authenticationEntryPoint((r, s, x) -> write(s, 401, "UNAUTHENTICATED"))
                .accessDeniedHandler((r, s, x) -> write(s, 403, "FORBIDDEN")))
            .oauth2ResourceServer(o -> o.authenticationEntryPoint((r, s, x) -> write(s, 401, "UNAUTHENTICATED"))
                .jwt(j -> j.jwtAuthenticationConverter(jwt -> {
                    try {
                        UUID user = UUID.fromString(jwt.getSubject()), session = UUID.fromString(jwt.getClaimAsString("sid"));
                        if (!auth.sessionActive(session, user) || !store.account(user).active()) throw new IllegalArgumentException();
                        var authorities = store.permissions(user).stream().map(SimpleGrantedAuthority::new).toList();
                        return new JwtAuthenticationToken(jwt, authorities, user.toString());
                    } catch (ApiException | IllegalArgumentException ex) {
                        throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
                    }
                })));
        http.addFilterAfter(new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
                var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                if (authentication instanceof JwtAuthenticationToken token) {
                    response.setHeader("Cache-Control", "no-store");
                    if (store.account(UUID.fromString(token.getName())).mustChangePassword()
                        && !Set.of("/api/v1/auth/me", "/api/v1/auth/password", "/api/v1/auth/logout", "/api/v1/auth/csrf").contains(request.getServletPath())) {
                        write(response, 403, "PASSWORD_CHANGE_REQUIRED"); return;
                    }
                }
                chain.doFilter(request, response);
            }
        }, BearerTokenAuthenticationFilter.class);
        return http.build();
    }
    static void write(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + com.easyback.localization.LocalizedText.message(code, "Request denied.") + "\"}");
    }
}
