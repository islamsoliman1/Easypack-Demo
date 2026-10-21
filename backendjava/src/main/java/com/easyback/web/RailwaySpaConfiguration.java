package com.easyback.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@RestController
@Profile("railway")
@ConditionalOnProperty(name = "easyback.railway.spa-enabled", havingValue = "true")
public class RailwaySpaConfiguration implements WebMvcConfigurer {
    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final String ORDER = "/orders/{id:" + UUID + "}";
    private static final String OPERATION = "/operations/{id:" + UUID + "}";
    private static final String ASSEMBLY = "/machines/{id:" + UUID + "}/assembly";
    private static final Set<String> PAGES = Set.of("/", "/index.html", "/users", "/roles", "/machine-models",
        "/parts", "/machines", "/boms", "/orders", "/warehouse", "/preparations", "/raw-stock", "/tasks",
        "/defects", "/operations", "/operations/templates", "/shifts", "/reports", "/notifications");
    private static final Pattern DETAILS = Pattern.compile("/(?:orders|operations)/" + UUID + "|/machines/" + UUID + "/assembly");
    // Vite assets only; dotfiles, traversal and source maps are never public.
    private static final Pattern ASSETS = Pattern.compile("/assets/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-][A-Za-z0-9_.-]*\\.(?:js|css|svg|png|jpg|jpeg|webp|avif|ico|woff2?|ttf)");

    @Bean("railwayPublicPaths")
    public RequestMatcher publicPaths() {
        return request -> {
            if (!Set.of("GET", "HEAD").contains(request.getMethod())) return false;
            String path = request.getRequestURI().substring(request.getContextPath().length());
            return PAGES.contains(path) || DETAILS.matcher(path).matches() || path.equals("/brand-mark.svg")
                || (!path.contains("..") && ASSETS.matcher(path).matches());
        };
    }

    @RequestMapping(method = {RequestMethod.GET, RequestMethod.HEAD}, value = {"/", "/index.html", "/users", "/roles",
        "/machine-models", "/parts", "/machines", "/boms", "/orders", "/warehouse", "/preparations", "/raw-stock",
        "/tasks", "/defects", "/operations", "/operations/templates", "/shifts", "/reports", "/notifications", ORDER, OPERATION, ASSEMBLY})
    public ResponseEntity<Resource> index(HttpServletRequest request) throws IOException {
        var resource = new ClassPathResource("static/index.html");
        var response = ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.TEXT_HTML)
            .contentLength(resource.contentLength());
        return request.getMethod().equals("HEAD") ? response.build() : response.body(resource);
    }

    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**").addResourceLocations("classpath:/static/assets/");
        registry.addResourceHandler("/brand-mark.svg").addResourceLocations("classpath:/static/");
    }
}
