package dev.shinobu.mcagent.session;

import dev.shinobu.mcagent.acp.model.ToolCallDelta;
import dev.shinobu.mcagent.acp.model.ToolContent;

import java.util.List;

/**
 * The accumulated state of one tool call.
 *
 * <p>The wire sends deltas: {@code tool_call} creates the entry with a generic
 * title, and {@code tool_call_update} fills in the rest a message or two later.
 * A field absent from a delta means "unchanged", so applying one must never
 * blank out what an earlier one established — the panel would flicker from
 * "Read .gitignore" back to "Read File" at the exact moment the tool finished.
 */
public final class ToolCallState {

    private final String toolCallId;
    private final long startedAtMillis;

    private String title;
    private String kind;
    private String status;
    private String toolName;
    private String rawOutput;
    private List<ToolContent> content = List.of();
    private List<ToolCallDelta.Location> locations = List.of();
    private long finishedAtMillis;

    public ToolCallState(String toolCallId, long nowMillis) {
        this.toolCallId = toolCallId;
        this.startedAtMillis = nowMillis;
    }

    /** Merges a delta in, leaving fields it does not mention alone. */
    public void apply(ToolCallDelta delta, long nowMillis) {
        if (delta.title() != null) {
            title = delta.title();
        }
        if (delta.kind() != null) {
            kind = delta.kind();
        }
        if (delta.toolName() != null) {
            toolName = delta.toolName();
        }
        if (delta.rawOutput() != null) {
            rawOutput = delta.rawOutput();
        }
        if (delta.content() != null) {
            content = delta.content();
        }
        if (delta.locations() != null) {
            locations = delta.locations();
        }
        if (delta.status() != null) {
            status = delta.status();
            if (isFinished() && finishedAtMillis == 0) {
                finishedAtMillis = nowMillis;
            }
        }
    }

    public String toolCallId() {
        return toolCallId;
    }

    public String kind() {
        return kind;
    }

    public String status() {
        return status;
    }

    public String title() {
        return title;
    }

    public List<ToolContent> content() {
        return content;
    }

    public List<ToolCallDelta.Location> locations() {
        return locations;
    }

    public String rawOutput() {
        return rawOutput;
    }

    /**
     * Whether this tool is still going.
     *
     * <p>{@code pending} counts as running. The adapter never emits
     * {@code in_progress} — a tool goes straight from {@code pending} to
     * {@code completed} — so treating only {@code in_progress} as running would
     * mean the avatar never visibly works. See {@code docs/acp-findings.md}.
     */
    public boolean isRunning() {
        return ToolCallDelta.STATUS_PENDING.equals(status)
                || ToolCallDelta.STATUS_IN_PROGRESS.equals(status);
    }

    public boolean isFinished() {
        return ToolCallDelta.STATUS_COMPLETED.equals(status)
                || ToolCallDelta.STATUS_FAILED.equals(status);
    }

    public boolean failed() {
        return ToolCallDelta.STATUS_FAILED.equals(status);
    }

    public long startedAtMillis() {
        return startedAtMillis;
    }

    /** How long this has been running, or how long it took. */
    public long elapsedMillis(long nowMillis) {
        long end = finishedAtMillis > 0 ? finishedAtMillis : nowMillis;
        return Math.max(0, end - startedAtMillis);
    }

    /**
     * What to show the player. Prefers the agent's own tool name ("Read",
     * "Write") over the coarse {@code kind}, falling back through whatever is
     * available so the panel is never blank.
     */
    public String displayName() {
        if (toolName != null && !toolName.isBlank()) {
            return toolName;
        }
        if (kind != null && !kind.isBlank()) {
            return kind;
        }
        return "tool";
    }

    /** A one-line description for the panel and the terminal wall. */
    public String describe() {
        if (title != null && !title.isBlank()) {
            return title;
        }
        return displayName();
    }

    @Override
    public String toString() {
        return "ToolCallState[" + toolCallId + " " + status + " " + describe() + "]";
    }
}
