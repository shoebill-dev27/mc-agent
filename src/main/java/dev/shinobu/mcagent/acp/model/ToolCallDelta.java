package dev.shinobu.mcagent.acp.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A patch to one tool call, keyed by {@link #toolCallId()}.
 *
 * <p>The wire carries two notifications for the same thing: {@code tool_call}
 * creates the entry, {@code tool_call_update} amends it. Both are modelled here
 * as a delta, because the amending message only sends the fields that changed —
 * a null field means "unchanged", not "cleared". Merging is
 * {@link ToolCallState}'s job.
 *
 * <p>Observed sequence for a single tool: {@code tool_call} with a generic
 * title and {@code status=pending}, then {@code tool_call_update} filling in
 * the real title, input, locations and diff, then a final update carrying
 * {@code completed} or {@code failed}.
 */
public record ToolCallDelta(
        String toolCallId,
        /** True when this came from {@code tool_call} rather than an update. */
        boolean creation,
        String title,
        String kind,
        String status,
        /**
         * The agent's own tool name from {@code _meta.claudeCode.toolName}
         * ("Read", "Write"). More specific than {@link #kind()} but
         * Claude-specific, so it is for display only.
         */
        String toolName,
        List<ToolContent> content,
        List<Location> locations,
        String rawOutput) {

    /** A file (and optionally a line) the tool touched. */
    public record Location(String path, Integer line) {
    }

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    public static ToolCallDelta parse(JsonObject update, boolean creation) {
        return new ToolCallDelta(
                Json.string(update, "toolCallId"),
                creation,
                Json.string(update, "title"),
                Json.string(update, "kind"),
                Json.string(update, "status"),
                parseToolName(update),
                Json.has(update, "content") ? ToolContent.parseArray(Json.get(update, "content")) : null,
                Json.has(update, "locations") ? parseLocations(Json.get(update, "locations")) : null,
                Json.string(update, "rawOutput"));
    }

    private static String parseToolName(JsonObject update) {
        JsonObject meta = Json.object(update, "_meta");
        JsonObject claudeCode = meta == null ? null : Json.object(meta, "claudeCode");
        return claudeCode == null ? null : Json.string(claudeCode, "toolName");
    }

    private static List<Location> parseLocations(JsonElement array) {
        List<Location> out = new ArrayList<>();
        if (array == null || !array.isJsonArray()) {
            return out;
        }
        for (JsonElement element : array.getAsJsonArray()) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            Double line = Json.decimal(object, "line");
            out.add(new Location(Json.string(object, "path"), line == null ? null : line.intValue()));
        }
        return out;
    }
}
