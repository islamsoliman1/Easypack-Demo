package com.easyback.localization;

import java.util.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.json.JsonMapper;

@RestControllerAdvice(basePackages = "com.easyback")
public class LocalizedResponse implements ResponseBodyAdvice<Object> {
    private final JsonMapper mapper;
    public LocalizedResponse(JsonMapper mapper) { this.mapper = mapper; }
    private static final Set<String> FIELDS = Set.of("name", "description", "title", "notes", "location", "reference", "displayName",
        "modelName", "partName", "machineName", "orderTitle", "actorName", "workerName", "reporterName", "recipientName", "responsibleName",
        "reason", "cancellationReason", "requirementNotes", "installNotes", "removalReason", "deliveryNotes", "startNotes", "resultNotes", "resolutionNotes", "label", "criterion",
        "startedBy", "finishedBy", "resolvedBy", "removedBy", "installedBy", "uploadedBy");
    @Override public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converter) { return true; }
    @Override public Object beforeBodyWrite(Object body, MethodParameter type, MediaType mediaType,
        Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null || !request.getURI().getPath().startsWith("/api/v1/") || !mediaType.isCompatibleWith(MediaType.APPLICATION_JSON)) return body;
        // A JSON tree deliberately changes the runtime container type. Returning List<Map>
        // would leave Spring's declared List<DTO> serializer in place and cause DTO casts.
        return mapper.valueToTree(project(mapper.convertValue(body, Object.class), RequestLanguage.current()));
    }
    static Object project(Object value, String language) {
        if (value instanceof List<?> list) return list.stream().map(item -> project(item, language)).toList();
        if (!(value instanceof Map<?, ?> map)) return value;
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, String> original = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        // File names and security/error contracts are never translated as content.
        for (var entry : map.entrySet()) {
            String key = entry.getKey().toString(); Object item = entry.getValue();
            if ((FIELDS.contains(key) || key.equals("body") && map.containsKey("target") && map.containsKey("type")) && item instanceof String text) {
                original.put(key, text);
                if (LocalizedText.missing(text, language)) missing.add(key);
                result.put(key, LocalizedText.display(text, language));
            } else result.put(key, project(item, language));
        }
        if (!original.isEmpty()) { result.put("originalText", original); result.put("translationMissing", missing); }
        return result;
    }
}
