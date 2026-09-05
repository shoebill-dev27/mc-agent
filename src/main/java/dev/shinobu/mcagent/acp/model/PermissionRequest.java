package dev.shinobu.mcagent.acp.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The agent asking whether it may run a tool.
 *
 * <p>Captured shape (see {@code docs/acp-findings.md}): the request carries the
 * whole tool call, diff included, so the approval dialog and the diff book can
 * be filled from this one message without asking the agent for anything more.
 */
public record PermissionRequest(String sessionId, ToolCallDelta toolCall, List<PermissionOption> options) {

    /** One button on the approval dialog. */
    public record PermissionOption(String optionId, String name, String kind) {
        public static final String ALLOW_ONCE = "allow_once";
        public static final String ALLOW_ALWAYS = "allow_always";
        public static final String REJECT_ONCE = "reject_once";
        public static final String REJECT_ALWAYS = "reject_always";

        public boolean allows() {
            return kind != null && kind.startsWith("allow");
        }

        /** True for the durable choices, which the UI puts behind a confirmation. */
        public boolean isDurable() {
            return kind != null && kind.endsWith("_always");
        }
    }

    public static PermissionRequest parse(JsonObject params) {
        JsonObject toolCall = Json.object(params, "toolCall");
        return new PermissionRequest(
                Json.string(params, "sessionId"),
                toolCall == null ? null : ToolCallDelta.parse(toolCall, true),
                parseOptions(Json.get(params, "options")));
    }

    private static List<PermissionOption> parseOptions(JsonElement array) {
        List<PermissionOption> out = new ArrayList<>();
        if (array == null || !array.isJsonArray()) {
            return out;
        }
        for (JsonElement element : array.getAsJsonArray()) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject option = element.getAsJsonObject();
            String optionId = Json.string(option, "optionId");
            if (optionId != null) {
                out.add(new PermissionOption(
                        optionId, Json.string(option, "name"), Json.string(option, "kind")));
            }
        }
        return out;
    }

    /** The first option of the given kind, if the agent offered one. */
    public Optional<PermissionOption> optionOfKind(String kind) {
        return options.stream().filter(o -> kind.equals(o.kind())).findFirst();
    }

    /**
     * The safest option to fall back on: an explicit rejection if one is
     * offered, otherwise any non-allowing option. Used when a request times out
     * or the server shuts down with a decision still pending — never guess in
     * favour of letting the tool run.
     */
    public Optional<PermissionOption> safeRefusal() {
        return optionOfKind(PermissionOption.REJECT_ONCE)
                .or(() -> options.stream().filter(o -> !o.allows()).findFirst());
    }

    /** All the file diffs attached to this request, for the diff book. */
    public List<ToolContent.Diff> diffs() {
        List<ToolContent.Diff> out = new ArrayList<>();
        if (toolCall == null || toolCall.content() == null) {
            return out;
        }
        for (ToolContent content : toolCall.content()) {
            if (content instanceof ToolContent.Diff diff) {
                out.add(diff);
            }
        }
        return out;
    }
}
