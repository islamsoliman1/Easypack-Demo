package com.easyback.localization;

import java.util.*;
import java.util.concurrent.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class LocalizationTest {
    @Test void normalizedWeightedLanguagesAndFallback() {
        assertEquals("ar", RequestLanguage.normalize(null));
        assertEquals("ar", RequestLanguage.normalize("fr-FR"));
        assertEquals("en", RequestLanguage.normalize("fr-FR, en-GB;q=0.8, ar;q=0.2"));
        assertEquals("ar", RequestLanguage.normalize("en;q=0,ar-EG;q=0.9"));
        assertEquals("ar", RequestLanguage.normalize("malformed;!"));
    }
    @Test void requestsRemainIndependentAndThreadStateIsCleared() throws Exception {
        var filter = new RequestLanguage();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            var tasks = List.of("ar-EG", "en-GB").stream().<Callable<String>>map(language -> () -> {
                var request = new MockHttpServletRequest("GET", "/api/v1/parts"); request.addHeader("Accept-Language",language);
                var response = new MockHttpServletResponse(); var result = new String[1];
                filter.doFilter(request,response,(r,s)-> {
                    try { barrier.await(5,TimeUnit.SECONDS); } catch(Exception e) { throw new AssertionError(e); }
                    result[0] = LocalizedText.message("VALIDATION", "fallback");
                });
                assertEquals(language.substring(0,2),response.getHeader("Content-Language"));
                assertTrue(response.getHeaders("Vary").contains("Accept-Language"));
                assertEquals("ar",RequestLanguage.current());
                return result[0];
            }).toList();
            var results=pool.invokeAll(tasks);
            assertEquals("راجع البيانات المدخلة.",results.get(0).get());
            assertEquals("Check the submitted fields.",results.get(1).get());
        }
    }
    @Test void projectionsKeepOriginalsMachineContractsAndHistoricalSnapshots() {
        var historical=Map.of("partName","محرك السير", "serialNumber","محرك السير", "status","OPEN",
            "checks",List.of(Map.of("label","فحص السلامة","criterion","عمل زر الإيقاف الطارئ")));
        @SuppressWarnings("unchecked") var en=(Map<String,Object>)LocalizedResponse.project(historical,"en");
        assertEquals("Conveyor motor",en.get("partName"));
        assertEquals("محرك السير",en.get("serialNumber")); assertEquals("OPEN",en.get("status"));
        assertEquals(Map.of("partName","محرك السير"),en.get("originalText"));
        @SuppressWarnings("unchecked") var check=(Map<String,Object>)((List<?>)en.get("checks")).getFirst();
        assertEquals(LocalizedText.display("فحص السلامة","en"),check.get("label"));
        assertEquals(Map.of("label","فحص السلامة","criterion","عمل زر الإيقاف الطارئ"),check.get("originalText"));
        assertEquals("محرك السير",historical.get("partName"));
        assertEquals("Conveyor motor",LocalizedText.display("محرك السير","en"));
        assertEquals("محرك جديد خاص",LocalizedText.display("محرك جديد خاص","en"));
        assertEquals("الطلب ملغي",LocalizedText.display("Order cancelled","ar"));
        assertEquals("Order cancelled",LocalizedText.display("Order cancelled","en"));
        @SuppressWarnings("unchecked") var missing=(Map<String,Object>)LocalizedResponse.project(Map.of("name","محرك جديد خاص"),"en");
        assertEquals(List.of("name"),missing.get("translationMissing"));
    }
    @Test void allErrorBundlesHaveParityAndExportLabelsCoverRealStatuses() throws Exception {
        var ar=bundle("ar"); var en=bundle("en");
        assertEquals(ar.keySet(),en.keySet());
        for(var key:ar.stringPropertyNames()) { assertFalse(ar.getProperty(key).isBlank()); assertFalse(en.getProperty(key).isBlank()); assertFalse(en.getProperty(key).contains("undefined")); }
        for(var status:List.of("DRAFT","OPEN","IN_PROGRESS","AWAITING_TESTING","TESTING","REWORK","COMPLETED","CANCELLED"))
            assertTrue(en.containsKey("status."+status));
    }
    private static Properties bundle(String language) throws Exception {
        var result=new Properties();
        try(var input=LocalizationTest.class.getResourceAsStream("/localization/messages_"+language+".properties")) {
            assertNotNull(input); result.load(new InputStreamReader(input,StandardCharsets.UTF_8));
        }
        return result;
    }
}
