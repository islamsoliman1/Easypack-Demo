package com.easyback.localization;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalizedResponseMvcTest {
    record Item(String name, String code, String status, long quantity) {}
    record Envelope(Item current, List<Item> history, Map<String,String> flags) {}
    record PreciseNumbers(BigDecimal amount, long quantity) {}
    @RestController static class Probe {
        private static final Item ITEM = new Item("محرك السير", "PART-01", "OPEN", 123);
        @GetMapping("/api/v1/locale-test/list") List<Item> list() { return List.of(ITEM); }
        @GetMapping("/api/v1/locale-test/item") Item item() { return ITEM; }
        @GetMapping("/api/v1/locale-test/envelope") Envelope envelope() { return new Envelope(ITEM,List.of(ITEM),Map.of("allow","true")); }
        @GetMapping("/api/v1/locale-test/precision") PreciseNumbers precision() { return new PreciseNumbers(new BigDecimal("999999999999999999.123456"),Long.MAX_VALUE); }
    }
    @Test void typedListAndNestedRecordsUseJsonTreeSerializerWithoutDtoCasts() throws Exception {
        var mapper=JsonMapper.builder().build();
        var mvc=MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new LocalizedResponse(mapper))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper)).addFilters(new RequestLanguage()).build();
        mvc.perform(get("/api/v1/locale-test/list").header("Accept-Language","en-GB"))
            .andExpect(status().isOk()).andExpect(header().string("Content-Language","en"))
            .andExpect(jsonPath("$[0].name").value("Conveyor motor"))
            .andExpect(jsonPath("$[0].originalText.name").value("محرك السير"))
            .andExpect(jsonPath("$[0].code").value("PART-01")).andExpect(jsonPath("$[0].quantity").value(123));
        mvc.perform(get("/api/v1/locale-test/item").header("Accept-Language","en"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Conveyor motor"))
            .andExpect(jsonPath("$.status").value("OPEN"));
        mvc.perform(get("/api/v1/locale-test/envelope").header("Accept-Language","en"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.current.name").value("Conveyor motor"))
            .andExpect(jsonPath("$.history[0].originalText.name").value("محرك السير"))
            .andExpect(jsonPath("$.flags.allow").value("true"));
        mvc.perform(get("/api/v1/locale-test/list").header("Accept-Language","ar"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("محرك السير"));
        // Assert the wire tokens directly: parsing through a floating-point client
        // would itself lose precision and conceal a projection regression.
        for (var language : List.of("ar", "en")) {
            var json=mvc.perform(get("/api/v1/locale-test/precision").header("Accept-Language",language))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertTrue(json.contains("\"amount\":999999999999999999.123456"),json);
            assertTrue(json.contains("\"quantity\":9223372036854775807"),json);
        }
    }
}
