package com.easyback.localization;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Each request, including security failures, owns its language independently. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestLanguage extends OncePerRequestFilter {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    public static String current() { return CURRENT.get() == null ? "ar" : CURRENT.get(); }
    public static String normalize(String header) {
        if (header != null) try {
            for (var range : Locale.LanguageRange.parse(header)) {
                if (range.getWeight() == 0) continue;
                String language = range.getRange().split("-")[0];
                if (language.equals("en") || language.equals("ar")) return language;
            }
        } catch (IllegalArgumentException ignored) { /* Malformed headers use the documented default. */ }
        return "ar";
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        CURRENT.set(normalize(request.getHeader("Accept-Language")));
        if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
            response.setHeader("Content-Language", current());
            response.addHeader("Vary", "Accept-Language");
        }
        try { chain.doFilter(request, response); } finally { CURRENT.remove(); }
    }
}
