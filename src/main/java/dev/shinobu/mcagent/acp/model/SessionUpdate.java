package dev.shinobu.mcagent.acp.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One {@code session/update} notification, parsed into the variants that
 * matter to the mod.
 *
 * <p>The variant list comes from traffic actually observed against
 * {@code claude-agent-acp} (see {@code docs/acp-findings.md}), not from the
 * specification, and anything unrecognised becomes {@link Unknown} so a new
 * variant shows up in the log rather than throwing.
 */
public sealed interface SessionUpdate {

    /** A piece of the agent's reply. Arrives token by token. */
    record AgentMessageChunk(String text, String messageId) implements SessionUpdate {
    }

    /** A piece of the agent's reasoning, shown dimmed and hideable. */
    record AgentThoughtChunk(String text) implements SessionUpdate {
    }

    /** A tool call being created or amended. */
    record ToolCall(ToolCallDelta delta) implements SessionUpdate {
    }

    /**
     * Context consumption and running cost, pushed unprompted.
     * {@code cost} is null until the agent has priced the turn.
     */
    record Usage(long used, long size, Double costAmount, String costCurrency) implements SessionUpdate {
        /** Fraction of the context window in use, 0..1. */
        public double fraction() {
            return size <= 0 ? 0 : Math.min(1.0, (double) used / size);
        }
    }

    /** The slash commands this session accepts, for prompt-screen completion. */
    record AvailableCommands(List<Command> commands) implements SessionUpdate {
        public record Command(String name, String description) {
        }
    }

    /** A variant this version does not model; kept so it can be logged. */
    record Unknown(String kind, String raw) implements SessionUpdate {
    }

    /**
     * Parses the {@code update} object of a {@code session/update} notification.
     *
     * @param update the {@code params.update} object
     * @return never null; unrecognised input becomes {@link Unknown}
     */
    static SessionUpdate parse(JsonObject update) {
        if (update == null) {
            return new Unknown("(null)", "null");
        }
        String kind = Json.string(update, "sessionUpdate");
        if (kind == null) {
            return new Unknown("(missing)", update.toString());
        }

        return switch (kind) {
            case "agent_message_chunk" -> new AgentMessageChunk(
                    textOf(update), Json.string(update, "messageId"));
            case "agent_thought_chunk" -> new AgentThoughtChunk(textOf(update));
            // tool_call creates the entry, tool_call_update amends it; both are
            // deltas keyed by toolCallId.
            case "tool_call" -> new ToolCall(ToolCallDelta.parse(update, true));
            case "tool_call_update" -> new ToolCall(ToolCallDelta.parse(update, false));
            case "usage_update" -> parseUsage(update);
            case "available_commands_update" -> parseCommands(update);
            default -> new Unknown(kind, update.toString());
        };
    }

    /** Pulls the text out of a {@code content: {type: "text", text: ...}} block. */
    private static String textOf(JsonObject update) {
        JsonObject content = Json.object(update, "content");
        if (content == null) {
            return "";
        }
        String text = Json.string(content, "text");
        return text == null ? "" : text;
    }

    private static SessionUpdate parseUsage(JsonObject update) {
        JsonObject cost = Json.object(update, "cost");
        return new Usage(
                Json.number(update, "used", 0),
                Json.number(update, "size", 0),
                cost == null ? null : Json.decimal(cost, "amount"),
                cost == null ? null : Json.string(cost, "currency"));
    }

    private static SessionUpdate parseCommands(JsonObject update) {
        List<AvailableCommands.Command> commands = new ArrayList<>();
        JsonElement array = Json.get(update, "availableCommands");
        if (array != null && array.isJsonArray()) {
            for (JsonElement element : array.getAsJsonArray()) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject command = element.getAsJsonObject();
                commands.add(new AvailableCommands.Command(
                        Json.string(command, "name"),
                        Json.string(command, "description")));
            }
        }
        return new AvailableCommands(commands);
    }
}
