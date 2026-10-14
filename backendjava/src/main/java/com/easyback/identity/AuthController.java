package com.easyback.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService service;
    private final IdentityStore store;
    private final boolean secure;
    public AuthController(AuthService service, IdentityStore store, @Value("${easyback.secure-cookies}") boolean secure) {
        this.service = service; this.store = store; this.secure = secure;
    }
    public record Login(@NotBlank @Email @Size(max=254) String email, @NotBlank @Size(max=200) String password) {}
    public record Change(@NotBlank @Size(max=200) String currentPassword, @NotBlank @Size(max=200) String newPassword) {}
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store"); return Map.of("token", token.getToken());
    }
    @PostMapping("/login")
    public AuthService.LoginResult login(@Valid @RequestBody Login input, HttpServletRequest request, HttpServletResponse response) {
        return tokens(service.login(input.email(), input.password(), request.getRemoteAddr()), response);
    }
    @PostMapping("/refresh")
    public AuthService.LoginResult refresh(@CookieValue(name="EB_REFRESH", required=false) String refresh, HttpServletResponse response) {
        return tokens(service.refresh(refresh), response);
    }
    @GetMapping("/me")
    public IdentityStore.UserView me(Authentication authentication) { return store.view(Administration.actor(authentication)); }
    @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CookieValue(name="EB_REFRESH", required=false) String refresh, HttpServletResponse response) {
        service.logout(refresh); cookie(response, "", 0);
    }
    @PostMapping("/password") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(@Valid @RequestBody Change input, Authentication authentication, HttpServletResponse response) {
        service.changePassword(Administration.actor(authentication), input.currentPassword(), input.newPassword());
        cookie(response, "", 0);
    }
    AuthService.LoginResult tokens(AuthService.Tokens tokens, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store"); cookie(response, tokens.refresh(), 604800); return tokens.body();
    }
    private void cookie(HttpServletResponse response, String value, long seconds) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("EB_REFRESH", value).httpOnly(true)
            .secure(secure).sameSite("Strict").path("/api/v1/auth").maxAge(seconds).build().toString());
    }
}
