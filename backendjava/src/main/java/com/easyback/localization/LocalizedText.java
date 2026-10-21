package com.easyback.localization;

import java.io.IOException;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/** Reviewed immutable source translations. No catalog writes or automatic user-text translation. */
public final class LocalizedText {
    private LocalizedText() {}
    private static final Map<String, String> DEMO = reviewedContent();
    private static final Map<String, String> ARABIC = arabicSources();
    @SuppressWarnings("unchecked")
    private static Map<String, String> load(String name) {
        try (var input = new ClassPathResource("localization/"+name+".json").getInputStream()) {
            return Map.copyOf(new JsonMapper().readValue(input, Map.class));
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    private static Map<String,String> arabicSources() {
        var result=new HashMap<String,String>(); DEMO.forEach((ar,en)->result.put(en,ar));
        result.putAll(load("system-content")); return Map.copyOf(result);
    }
    private static Map<String,String> reviewedContent() {
        var result=new HashMap<>(load("demo-content")); result.putAll(load("notification-content")); return Map.copyOf(result);
    }
    public static String display(String source) { return display(source, RequestLanguage.current()); }
    public static String display(String source, String language) {
        if (source != null && source.matches("طلب التصنيع [A-Za-z0-9][A-Za-z0-9._/-]{0,63}"))
            return language.equals("en") ? "Manufacturing order " + source.substring("طلب التصنيع ".length()) : source;
        return source == null ? null : (language.equals("en") ? DEMO : ARABIC).getOrDefault(source, source);
    }
    public static boolean missing(String source, String language) {
        if (source == null || source.isBlank()) return false;
        if (!source.equals(display(source, language))) return false;
        return language.equals("en") ? source.matches("(?s).*[\\u0600-\\u06ff].*") && !DEMO.containsKey(source)
            : source.matches("(?s).*[A-Za-z].*") && !source.matches("(?s).*[\\u0600-\\u06ff].*") && !ARABIC.containsKey(source);
    }
    public static String searchText(String source) { return source + " " + display(source, "ar") + " " + display(source, "en"); }
    public static String message(String code, String fallback) {
        var bundle = ResourceBundle.getBundle("localization.messages", Locale.forLanguageTag(RequestLanguage.current()));
        return bundle.containsKey(code) ? bundle.getString(code) : (RequestLanguage.current().equals("en") ? fallback : bundle.getString("REQUEST_FAILED"));
    }
    public static String status(String code) { return message("status." + code, code); }
}
