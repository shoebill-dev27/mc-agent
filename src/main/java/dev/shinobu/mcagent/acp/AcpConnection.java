package dev.shinobu.mcagent.acp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.shinobu.mcagent.acp.model.InitializeResult;
import dev.shinobu.mcagent.acp.model.Json;
import dev.shinobu.mcagent.acp.model.PermissionRequest;
import dev.shinobu.mcagent.acp.model.SessionUpdate;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * ACP spoken over a pair of streams: negotiates {@code initialize}, opens
 * sessions, and routes everything the agent pushes back to the right
 * {@link SessionListener}.
 *
 * <p>Deliberately takes streams rather than a {@link Process}, so the whole
 * protocol can be exercised over pipes in tests. {@link AgentProcess} is the
 * thin layer that supplies a real process's streams.
 *
 * <p>Knows nothing about Minecraft. See {@link SessionListener} for the
 * threading contract callbacks run under.
 */
public final class AcpConnection implements JsonRpcPeer.Handler, AutoCloseable {

    private static final String METHOD_INITIALIZE = "initialize";
    private static final String METHOD_SESSION_NEW = "session/new";
    private static final String METHOD_SESSION_PROMPT = "session/prompt";
    private static final String METHOD_SESSION_CANCEL = "session/cancel";
    private static final String METHOD_SESSION_CLOSE = "session/close";
    private static final String METHOD_REQUEST_PERMISSION = "session/request_permission";
    private static final String METHOD_SESSION_UPDATE = "session/update";

    private final JsonRpcPeer peer;
    private final String name;
    private final Map<String, SessionListener> listeners = new ConcurrentHashMap<>();

    /** Reported to the caller for logging; not on any hot path. */
    private final BiConsumer<String, Throwable> diagnostics;

    /** Notified once when the connection dies, whatever the cause. */
    private final List<Consumer<String>> disconnectHooks = new CopyOnWriteArrayList<>();

    private volatile InitializeResult initializeResult;
    private volatile boolean disconnected;

    public AcpConnection(InputStream in, OutputStream out, String name,
                         BiConsumer<String, Throwable> diagnostics) {
        this.name = name;
        this.diagnostics = diagnostics == null ? (message, error) -> {
        } : diagnostics;
        this.peer = new JsonRpcPeer(in, out, this, name);
    }

    /** What the agent said about itself, or null before {@link #initialize()}. */
    public InitializeResult initializeResult() {
        return initializeResult;
    }

    public boolean isDisconnected() {
        return disconnected;
    }

    /**
     * Registers an owner-level callback for the connection dying, separate
     * from the per-session {@link SessionListener#onDisconnected}. The pool
     * uses it to evict a dead agent even when no session is left to notice.
     */
    public void onDisconnect(Consumer<String> hook) {
        disconnectHooks.add(hook);
        if (disconnected) {
            // Already gone; do not let a late registration miss it.
            hook.accept("connection already disconnected");
        }
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Negotiates the protocol. Declares no filesystem or terminal capability on
     * purpose: the agent uses its own tools, so the mod never becomes a file
     * access path of its own.
     */
    public CompletableFuture<InitializeResult> initialize() {
        JsonObject fs = new JsonObject();
        fs.addProperty("readTextFile", false);
        fs.addProperty("writeTextFile", false);

        JsonObject capabilities = new JsonObject();
        capabilities.add("fs", fs);
        capabilities.addProperty("terminal", false);

        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", InitializeResult.PREFERRED_PROTOCOL_VERSION);
        params.add("clientCapabilities", capabilities);

        return peer.request(METHOD_INITIALIZE, params).thenApply(result -> {
            InitializeResult parsed = InitializeResult.parse(asObject(result));
            initializeResult = parsed;
            return parsed;
        });
    }

    /**
     * Opens a session rooted at {@code cwd}.
     *
     * <p>The listener can only be registered once the response names the
     * session, and the agent starts pushing updates (an
     * {@code available_commands_update}) immediately afterwards. That is not a
     * race: responses and notifications share one stream, and this
     * continuation runs inline on the reader thread, so registration completes
     * before the next line is read.
     */
    public CompletableFuture<AcpSession> newSession(String cwd, SessionListener listener) {
        JsonObject params = new JsonObject();
        params.addProperty("cwd", cwd);
        params.add("mcpServers", new JsonArray());

        return peer.request(METHOD_SESSION_NEW, params).thenApply(result -> {
            String sessionId = Json.string(asObject(result), "sessionId");
            if (sessionId == null) {
                throw new IllegalStateException("agent " + name + " opened a session without an id");
            }
            listeners.put(sessionId, listener);
            return new AcpSession(this, sessionId, cwd);
        });
    }

    CompletableFuture<String> prompt(String sessionId, String text) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        JsonArray prompt = new JsonArray();
        prompt.add(block);

        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        params.add("prompt", prompt);

        return peer.request(METHOD_SESSION_PROMPT, params)
                .thenApply(result -> Json.string(asObject(result), "stopReason"));
    }

    void cancel(String sessionId) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        peer.notify(METHOD_SESSION_CANCEL, params);
    }

    /**
     * Ends a session. Agents that do not advertise the close capability get a
     * cancel instead, which at least stops work in flight.
     */
    CompletableFuture<Void> closeSession(String sessionId) {
        listeners.remove(sessionId);

        InitializeResult result = initializeResult;
        if (result == null || !result.supportsSessionClose()) {
            cancel(sessionId);
            return CompletableFuture.completedFuture(null);
        }

        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        return peer.request(METHOD_SESSION_CLOSE, params).handle((ignored, error) -> {
            if (error != null) {
                // Losing the close is not worth failing the caller's teardown;
                // the process pool will reap the agent anyway.
                diagnostics.accept("session/close failed for " + sessionId, error);
            }
            return null;
        });
    }

    /** Number of sessions currently routed by this connection. */
    public int sessionCount() {
        return listeners.size();
    }

    @Override
    public void close() {
        peer.close();
        fanOutDisconnect("connection closed locally");
    }

    // ------------------------------------------------------ JsonRpcPeer.Handler

    @Override
    public CompletableFuture<JsonElement> onRequest(String method, JsonObject params) {
        if (!METHOD_REQUEST_PERMISSION.equals(method)) {
            // Returning null makes the peer answer "method not found", which is
            // correct for the fs/* and terminal/* methods we declined.
            return null;
        }

        PermissionRequest request = PermissionRequest.parse(params);
        SessionListener listener = listeners.get(request.sessionId());
        if (listener == null) {
            diagnostics.accept("permission request for unknown session " + request.sessionId(), null);
            return CompletableFuture.completedFuture(outcomeCancelled());
        }

        CompletableFuture<String> decision;
        try {
            decision = listener.onPermissionRequest(request);
        } catch (RuntimeException e) {
            diagnostics.accept("permission listener threw; refusing", e);
            decision = null;
        }
        if (decision == null) {
            return CompletableFuture.completedFuture(outcomeCancelled());
        }

        return decision.handle((optionId, error) -> {
            if (error != null) {
                diagnostics.accept("permission decision failed; refusing", error);
                return outcomeCancelled();
            }
            return optionId == null ? outcomeCancelled() : outcomeSelected(optionId);
        });
    }

    @Override
    public void onNotification(String method, JsonObject params) {
        if (!METHOD_SESSION_UPDATE.equals(method)) {
            return;
        }
        String sessionId = Json.string(params, "sessionId");
        SessionListener listener = sessionId == null ? null : listeners.get(sessionId);
        if (listener == null) {
            // Updates can arrive for a session we just closed; that is normal.
            return;
        }

        SessionUpdate update = SessionUpdate.parse(Json.object(params, "update"));
        try {
            listener.onUpdate(update);
        } catch (RuntimeException e) {
            diagnostics.accept("session listener threw on " + sessionId, e);
        }
    }

    @Override
    public void onTransportError(String message, Throwable cause) {
        diagnostics.accept(message, cause);
    }

    @Override
    public void onClosed() {
        fanOutDisconnect("agent " + name + " closed the connection");
    }

    /** Tells every session the connection is gone, exactly once. */
    void fanOutDisconnect(String reason) {
        if (disconnected) {
            return;
        }
        disconnected = true;
        for (SessionListener listener : listeners.values()) {
            try {
                listener.onDisconnected(reason);
            } catch (RuntimeException e) {
                diagnostics.accept("listener threw while disconnecting", e);
            }
        }
        listeners.clear();

        for (Consumer<String> hook : disconnectHooks) {
            try {
                hook.accept(reason);
            } catch (RuntimeException e) {
                diagnostics.accept("disconnect hook threw", e);
            }
        }
    }

    // ----------------------------------------------------------------- helpers

    private static JsonObject asObject(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static JsonElement outcomeSelected(String optionId) {
        JsonObject outcome = new JsonObject();
        outcome.addProperty("outcome", "selected");
        outcome.addProperty("optionId", optionId);
        JsonObject result = new JsonObject();
        result.add("outcome", outcome);
        return result;
    }

    private static JsonElement outcomeCancelled() {
        JsonObject outcome = new JsonObject();
        outcome.addProperty("outcome", "cancelled");
        JsonObject result = new JsonObject();
        result.add("outcome", outcome);
        return result;
    }
}
