package dev.shinobu.mcagent.acp;

import java.util.concurrent.CompletableFuture;

/**
 * A handle on one conversation with an agent.
 *
 * <p>Several of these share a single {@link AcpConnection}: ACP multiplexes
 * sessions over one process, so opening a second session against the same
 * repository does not start a second agent.
 */
public final class AcpSession {

    /** Why a turn stopped. Observed: {@code end_turn}. */
    public static final String STOP_END_TURN = "end_turn";
    public static final String STOP_CANCELLED = "cancelled";
    public static final String STOP_MAX_TOKENS = "max_tokens";
    public static final String STOP_REFUSAL = "refusal";

    private final AcpConnection connection;
    private final String sessionId;
    private final String cwd;

    AcpSession(AcpConnection connection, String sessionId, String cwd) {
        this.connection = connection;
        this.sessionId = sessionId;
        this.cwd = cwd;
    }

    public String sessionId() {
        return sessionId;
    }

    /** The absolute working directory this session was opened against. */
    public String cwd() {
        return cwd;
    }

    public AcpConnection connection() {
        return connection;
    }

    /**
     * Sends a prompt and completes with the {@code stopReason} when the turn
     * ends. This can take minutes; never wait on it from the server thread.
     *
     * <p>The agent reports {@code promptQueueing}, so calling this again while a
     * turn is running queues the follow-up rather than failing.
     */
    public CompletableFuture<String> prompt(String text) {
        return connection.prompt(sessionId, text);
    }

    /** Interrupts the running turn. The pending prompt completes as cancelled. */
    public void cancel() {
        connection.cancel(sessionId);
    }

    /**
     * Ends the session for good — what killing the avatar does. Distinct from
     * {@link #cancel()}, which only interrupts the current turn. Falls back to
     * cancelling if the agent does not advertise the close capability.
     */
    public CompletableFuture<Void> close() {
        return connection.closeSession(sessionId);
    }

    @Override
    public String toString() {
        return "AcpSession[" + sessionId + " @ " + cwd + "]";
    }
}
