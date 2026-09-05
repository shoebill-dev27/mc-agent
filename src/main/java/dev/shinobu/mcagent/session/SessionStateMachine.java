package dev.shinobu.mcagent.session;

import dev.shinobu.mcagent.acp.model.PermissionRequest;
import dev.shinobu.mcagent.acp.model.SessionUpdate;
import dev.shinobu.mcagent.acp.model.ToolCallDelta;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Works out what a session is doing, from the events it emits.
 *
 * <p>State is <em>derived</em> from a handful of facts rather than moved
 * through by hand. Ad-hoc transitions are where this kind of thing goes wrong:
 * a tool failing while a permission is pending, or a turn ending with a tool
 * still marked running, would each need their own rule. Recomputing from
 * "is a permission pending / is a tool running / is a turn open" cannot fall
 * into a state nobody anticipated.
 *
 * <p>Holds no display strings: the wording and colours belong to the UI layer,
 * which can translate them. Not thread-safe — it is owned by the session on
 * the server thread, and events are marshalled there before reaching it.
 */
public final class SessionStateMachine {

    /** Finished tools are kept for the wall's scrollback, but not forever. */
    private static final int MAX_REMEMBERED_TOOLS = 100;

    private final Map<String, ToolCallState> tools = new LinkedHashMap<>();

    private boolean turnInProgress;
    private PermissionRequest pendingPermission;
    private String errorMessage;
    private SessionUpdate.Usage usage;
    private long turnStartedAtMillis;

    // ------------------------------------------------------------------ state

    public SessionState state() {
        if (errorMessage != null) {
            return SessionState.ERROR;
        }
        if (pendingPermission != null) {
            return SessionState.AWAITING;
        }
        if (activeTool() != null) {
            return SessionState.WORKING;
        }
        if (turnInProgress) {
            return SessionState.THINKING;
        }
        return SessionState.IDLE;
    }

    /**
     * The tool the avatar is currently showing: the most recently started one
     * still running, or null when nothing is.
     *
     * <p>Gated on the turn being open, so a tool the agent never got round to
     * marking finished cannot strand the avatar in {@code WORKING} forever.
     */
    public ToolCallState activeTool() {
        if (!turnInProgress) {
            return null;
        }
        ToolCallState newest = null;
        for (ToolCallState tool : tools.values()) {
            if (tool.isRunning() && (newest == null || tool.startedAtMillis() >= newest.startedAtMillis())) {
                newest = tool;
            }
        }
        return newest;
    }

    /** The permission the player has yet to answer, or null. */
    public PermissionRequest pendingPermission() {
        return pendingPermission;
    }

    /** Latest context and cost report, or null if none has arrived. */
    public SessionUpdate.Usage usage() {
        return usage;
    }

    public String errorMessage() {
        return errorMessage;
    }

    /** Tools seen this session, oldest first. */
    public Collection<ToolCallState> tools() {
        return tools.values();
    }

    /**
     * How long the current activity has been going: the running tool if there
     * is one, otherwise the turn. Zero when idle.
     */
    public long elapsedMillis(long nowMillis) {
        ToolCallState tool = activeTool();
        if (tool != null) {
            return tool.elapsedMillis(nowMillis);
        }
        if (turnInProgress) {
            return Math.max(0, nowMillis - turnStartedAtMillis);
        }
        return 0;
    }

    // ----------------------------------------------------------------- events

    /** A prompt went out; the session is thinking until a tool starts. */
    public void onPromptSent(long nowMillis) {
        turnInProgress = true;
        turnStartedAtMillis = nowMillis;
        errorMessage = null;
    }

    /**
     * The turn finished. Every stop reason lands back on idle, including
     * refusals and cancellations — none of them is a malfunction, and showing
     * a red avatar for "the model declined" would train the player to ignore
     * red. Only a dead agent is an error.
     */
    public void onTurnEnded(String stopReason) {
        // Deliberately does not rewrite tool statuses. A tool left at pending
        // is a fact about what the agent reported; activeTool() already stops
        // returning it once the turn is closed, so nothing has to be invented.
        turnInProgress = false;
    }

    public void onUpdate(SessionUpdate update, long nowMillis) {
        if (update instanceof SessionUpdate.ToolCall toolCall) {
            applyToolCall(toolCall.delta(), nowMillis);
        } else if (update instanceof SessionUpdate.Usage report) {
            usage = report;
        }
        // Message and thought chunks are the terminal wall's business; they do
        // not move the state.
    }

    private void applyToolCall(ToolCallDelta delta, long nowMillis) {
        String id = delta.toolCallId();
        if (id == null) {
            return;
        }
        ToolCallState tool = tools.computeIfAbsent(id, key -> new ToolCallState(key, nowMillis));
        tool.apply(delta, nowMillis);
        evictOldFinishedTools();
    }

    /** A permission request outranks whatever else is going on. */
    public void onPermissionRequested(PermissionRequest request) {
        pendingPermission = request;
    }

    /** The player decided, or the request was withdrawn. */
    public void onPermissionResolved() {
        pendingPermission = null;
    }

    /** The agent process died or the transport failed. */
    public void onDisconnected(String reason) {
        errorMessage = reason == null ? "disconnected" : reason;
        turnInProgress = false;
        pendingPermission = null;
    }

    /** Clears the error so a reconnected session can be used again. */
    public void clearError() {
        errorMessage = null;
    }

    // ---------------------------------------------------------------- internal

    private void evictOldFinishedTools() {
        if (tools.size() <= MAX_REMEMBERED_TOOLS) {
            return;
        }
        Iterator<Map.Entry<String, ToolCallState>> iterator = tools.entrySet().iterator();
        while (iterator.hasNext() && tools.size() > MAX_REMEMBERED_TOOLS) {
            // Insertion-ordered, so this walks oldest first. Running tools are
            // never dropped, however old — losing one would strand the avatar
            // in WORKING with nothing to show.
            if (iterator.next().getValue().isFinished()) {
                iterator.remove();
            }
        }
    }
}
