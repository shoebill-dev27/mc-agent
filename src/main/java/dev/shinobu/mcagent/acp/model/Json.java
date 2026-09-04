package dev.shinobu.mcagent.acp.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Null-tolerant accessors for the JSON we get off the wire.
 *
 * <p>ACP messages routinely omit fields (an omitted field on a patch means
 * "unchanged") and occasionally send explicit nulls, so every read here
 * answers "absent" the same way rather than throwing.
 */
public final class Json {

    private Json() {
    }

    /** The string at {@code key}, or null if absent, null, or not a string. */
    public static String string(JsonObject object, String key) {
        JsonElement element = get(object, key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    /** The long at {@code key}, or {@code fallback} if absent or not a number. */
    public static long number(JsonObject object, String key, long fallback) {
        JsonElement element = get(object, key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsLong();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The double at {@code key}, or null if absent or not a number. */
    public static Double decimal(JsonObject object, String key) {
        JsonElement element = get(object, key);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsDouble();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The nested object at {@code key}, or null. */
    public static JsonObject object(JsonObject object, String key) {
        JsonElement element = get(object, key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /** The element at {@code key}, treating JSON null as absent. */
    public static JsonElement get(JsonObject object, String key) {
        if (object == null) {
            return null;
        }
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? null : element;
    }

    /** Whether {@code key} is present at all, including as an explicit null. */
    public static boolean has(JsonObject object, String key) {
        return object != null && object.has(key);
    }
}
