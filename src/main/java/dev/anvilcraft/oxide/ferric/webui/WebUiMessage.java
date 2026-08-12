package dev.anvilcraft.oxide.ferric.webui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Lightweight typed-message helpers for the {@code window.ipc.postMessage(...)} channel.
 *
 * <p>Convention: every message is a JSON object with a {@code type} discriminator. JS pages
 * build messages with {@code JSON.stringify({type: "...", ...})}; Java code can parse them with
 * the static accessors and push new ones with {@link #create(String)}.
 */
public final class WebUiMessage {
    private final JsonObject json;

    private WebUiMessage(JsonObject json) {
        this.json = json;
    }

    /**
     * Starts a new outbound message with the given type.
     */
    public static WebUiMessage create(String type) {
        Objects.requireNonNull(type, "type");
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        return new WebUiMessage(json);
    }

    /**
     * Parses an inbound JSON string. Returns null for malformed or non-object payloads.
     */
    public static @Nullable WebUiMessage parse(String json) {
        try {
            JsonElement element = JsonParser.parseString(json);
            if (element.isJsonObject()) {
                return new WebUiMessage(element.getAsJsonObject());
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return null;
    }

    /**
     * The {@code type} discriminator, or {@code null} when absent.
     */
    public @Nullable String type() {
        return json.has("type") && !json.get("type").isJsonNull() ? json.get("type").getAsString() : null;
    }

    public WebUiMessage put(String key, String value) {
        json.addProperty(key, value);
        return this;
    }

    public WebUiMessage put(String key, int value) {
        json.addProperty(key, value);
        return this;
    }

    public WebUiMessage put(String key, long value) {
        json.addProperty(key, value);
        return this;
    }

    public WebUiMessage put(String key, boolean value) {
        json.addProperty(key, value);
        return this;
    }

    public @Nullable String string(String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }

    public int integer(String key, int fallback) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return fallback;
        }
    }

    public long longValue(String key, long fallback) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsLong();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return fallback;
        }
    }

    public boolean bool(String key, boolean fallback) {
        return json.has(key) && json.get(key).isJsonPrimitive()
               ? json.get(key).getAsBoolean()
               : fallback;
    }

    public float floatValue(String key, float fallback) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsFloat();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return fallback;
        }
    }

    /**
     * Serializes to the JSON string passed to {@code window.ipc.postMessage(...)}.
     */
    public String toJson() {
        return json.toString();
    }

    @Override
    public String toString() {
        return toJson();
    }
}
