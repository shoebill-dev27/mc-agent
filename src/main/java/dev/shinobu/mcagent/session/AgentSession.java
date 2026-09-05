package dev.shinobu.mcagent.session;

import dev.shinobu.mcagent.acp.AgentProcessPool;
import dev.shinobu.mcagent.acp.AgentSpec;
import dev.shinobu.mcagent.acp.model.PermissionRequest;
import dev.shinobu.mcagent.acp.model.SessionUpdate;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * One conversation, as the mod sees it: who owns it, where it works, what it
 * is doing, and the decision it may be waiting on.
 *
 * <p>Everything here is touched only from the server thread. The ACP layer
 * delivers events on its own reader thread; {@link SessionManager} marshals
 * them across before they reach this object, so nothing in here needs locking.
 */
public final class AgentSession {

    private final UUID id = UUID.randomUUID();
    private final String name;
    private final UUID ownerId;
    private final String ownerName;
    private final AgentSpec spec;
    private final String workspace;
    private final SessionStateMachine state = new SessionStateMachine();

    private AgentProcessPool.PooledSession pooled;

    /** Completed when the player answers; held while the avatar shows AWAITING. */
    private CompletableFuture<String> pendingDecision;

    /** The turn in flight, so ending the session can stop waiting on it. */
    private CompletableFuture<String> currentTurn;

    private boolean closed;

    public AgentSession(String name, UUID ownerId, String ownerName, AgentSpec spec, String workspace) {
        this.name = name;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.spec = spec;
        this.workspace = workspace;
    }

    // ---------------------------------------------------------------- identity

    public UUID id() {
        return id;
    }

    /** Shown on the avatar's panel and in the session list. */
    public String name() {
        return name;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public String ownerName() {
        return ownerName;
    }

    public AgentSpec spec() {
        return spec;
    }

    /** Absolute path this session works in; already validated against the roots. */
    public String workspace() {
        return workspace;
    }

    public SessionStateMachine state() {
        return state;
    }

    public boolean isClosed() {
        return closed;
    }

    /** The ACP session id, or null before the agent has answered. */
    public String acpSessionId() {
        return pooled == null ? null : pooled.sessionId();
    }

    // --------------------------------------------------------------- lifecycle

    void attach(AgentProcessPool.PooledSession pooled) {
        this.pooled = pooled;
    }

    AgentProcessPool.PooledSession pooled() {
        return pooled;
    }

    void markClosed() {
        closed = true;
    }

    // ------------------------------------------------------------- permissions

    /**
     * Records that the agent is waiting on a decision.
     *
     * @return the future the ACP layer is waiting on
     */
    CompletableFuture<String> beginPermission(PermissionRequest request) {
        // A second request while one is outstanding should not be possible —
        // the agent blocks on the first — but if it happens, refusing the older
        // one is safer than leaving the agent waiting forever on a request the
        // player can no longer see.
        refuseAnyPendingDecision(request);

        pendingDecision = new CompletableFuture<>();
        state.onPermissionRequested(request);
        return pendingDecision;
    }

    /**
     * Answers the outstanding permission.
     *
     * <p>The option has to be one the agent actually offered for this request.
     * A dialog can outlive the request it was opened for - the player leaves it
     * up while the turn moves on, or answers one request as a second arrives -
     * and forwarding a stale id would answer the wrong question, possibly
     * allowing a tool the player never saw.
     *
     * @return true if there was one to answer
     */
    public boolean decidePermission(String optionId) {
        if (pendingDecision == null || !offersOption(optionId)) {
            return false;
        }
        CompletableFuture<String> decision = pendingDecision;
        pendingDecision = null;
        state.onPermissionResolved();
        decision.complete(optionId);
        return true;
    }

    public boolean hasPendingPermission() {
        return pendingDecision != null;
    }

    /** The request the player is being asked about, or null. */
    public PermissionRequest pendingPermission() {
        return state.pendingPermission();
    }

    private boolean offersOption(String optionId) {
        PermissionRequest request = state.pendingPermission();
        if (request == null || optionId == null) {
            return false;
        }
        return request.options().stream()
                .anyMatch(option -> optionId.equals(option.optionId()));
    }

    /**
     * Declines whatever is outstanding, using an option the agent offered.
     * Used when a session ends or its agent dies with a decision still open:
     * an unanswered request would otherwise block the agent indefinitely, and
     * defaulting to "allow" would let a tool run that nobody approved.
     */
    private void refuseAnyPendingDecision(PermissionRequest request) {
        if (pendingDecision == null) {
            return;
        }
        CompletableFuture<String> decision = pendingDecision;
        pendingDecision = null;
        String refusal = request == null
                ? null
                : request.safeRefusal().map(PermissionRequest.PermissionOption::optionId).orElse(null);
        // A null completion is read as "cancelled" by the connection, which is
        // also a refusal — so either way the tool does not run.
        decision.complete(refusal);
    }

    void abandonPendingDecision() {
        refuseAnyPendingDecision(state.pendingPermission());
        state.onPermissionResolved();
    }

    // ------------------------------------------------------------------ turns

    void beginTurn(CompletableFuture<String> turn, long nowMillis) {
        currentTurn = turn;
        state.onPromptSent(nowMillis);
    }

    void endTurn(String stopReason) {
        currentTurn = null;
        state.onTurnEnded(stopReason);
    }

    CompletableFuture<String> currentTurn() {
        return currentTurn;
    }

    void onUpdate(SessionUpdate update, long nowMillis) {
        state.onUpdate(update, nowMillis);
    }

    void onDisconnected(String reason) {
        abandonPendingDecision();
        state.onDisconnected(reason);
    }

    @Override
    public String toString() {
        return "AgentSession[" + name + " " + state.state() + " @ " + workspace + "]";
    }
}
