package dev.shinobu.mcagent.acp.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.shinobu.mcagent.diff.LineDiff;

import java.util.ArrayList;
import java.util.List;

/**
 * One entry of a tool call's {@code content} array.
 *
 * <p>Two shapes have been observed in practice (see {@code docs/acp-findings.md}):
 * a {@code diff} carrying the before/after text of a file edit, and a
 * {@code content} wrapper around a text block. Anything else is preserved as
 * {@link Unknown} rather than rejected, so a protocol extension degrades to a
 * missing detail instead of a broken session.
 */
public sealed interface ToolContent {

    /** A file edit. {@code oldText} is null when the file is being created. */
    record Diff(String path, String oldText, String newText) implements ToolContent {
        /**
         * Lines added and removed, for the "+12 -3" summary on the approval
         * dialog. Counted from an actual diff rather than from the sizes of
         * the two texts: ACP sends the whole file either side of an edit, so
         * measuring those would report a two-line change to a 500-line file as
         * "+500 -500".
         */
        public int addedLines() {
            return LineDiff.addedLines(oldText, newText);
        }

        public int removedLines() {
            return LineDiff.removedLines(oldText, newText);
        }
    }

    /** A block of text output from the tool. */
    record Text(String text) implements ToolContent {
    }

    /** A shape this version does not understand; kept so it can be logged. */
    record Unknown(String type, String raw) implements ToolContent {
    }

    static List<ToolContent> parseArray(JsonElement array) {
        List<ToolContent> out = new ArrayList<>();
        if (array == null || !array.isJsonArray()) {
            return out;
        }
        for (JsonElement element : array.getAsJsonArray()) {
            if (element != null && element.isJsonObject()) {
                out.add(parse(element.getAsJsonObject()));
            }
        }
        return out;
    }

    static ToolContent parse(JsonObject object) {
        String type = Json.string(object, "type");
        if ("diff".equals(type)) {
            return new Diff(
                    Json.string(object, "path"),
                    Json.string(object, "oldText"),
                    Json.string(object, "newText"));
        }
        if ("content".equals(type)) {
            JsonElement inner = object.get("content");
            if (inner != null && inner.isJsonObject()) {
                JsonObject block = inner.getAsJsonObject();
                if ("text".equals(Json.string(block, "type"))) {
                    return new Text(Json.string(block, "text"));
                }
            }
            return new Unknown("content", object.toString());
        }
        return new Unknown(type == null ? "(missing)" : type, object.toString());
    }
}
