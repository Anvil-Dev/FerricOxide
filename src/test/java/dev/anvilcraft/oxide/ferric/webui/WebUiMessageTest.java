package dev.anvilcraft.oxide.ferric.webui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WebUiMessageTest {

    @Test
    void createSerializesTypedJson() {
        String json = WebUiMessage.create("my.type")
            .put("count", 3)
            .put("label", "hi")
            .put("flag", true)
            .put("ticks", 9876543210L)
            .toJson();

        WebUiMessage parsed = WebUiMessage.parse(json);
        assertEquals("my.type", parsed.type());
        assertEquals(3, parsed.integer("count", -1));
        assertEquals("hi", parsed.string("label"));
        assertTrue(parsed.bool("flag", false));
        assertEquals(9876543210L, parsed.longValue("ticks", -1L));
    }

    @Test
    void parseHandlesMalformedInput() {
        assertNull(WebUiMessage.parse(null));
        assertNull(WebUiMessage.parse(""));
        assertNull(WebUiMessage.parse("not json"));
        assertNull(WebUiMessage.parse("[1, 2, 3]"));
        assertNull(WebUiMessage.parse("\"just a string\""));
    }

    @Test
    void missingKeysFallBackToDefaults() {
        WebUiMessage message = WebUiMessage.parse("{\"type\":\"t\"}");

        assertNull(message.string("nope"));
        assertEquals(-1, message.integer("nope", -1));
        assertEquals(42L, message.longValue("nope", 42L));
        assertFalse(message.bool("nope", false));
    }

    @Test
    void nullTypeIsReportedAsNull() {
        WebUiMessage message = WebUiMessage.parse("{\"other\":1}");
        assertNull(message.type());
    }

    @Test
    void nonNumericValueFallsBack() {
        WebUiMessage message = WebUiMessage.parse("{\"type\":\"t\",\"count\":\"abc\"}");
        assertEquals(-1, message.integer("count", -1));
    }
}
