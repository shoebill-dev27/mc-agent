package dev.shinobu.mcagent.acp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Newline-delimited JSON-RPC 2.0 over a pair of streams, which is how ACP
 * agents talk over stdio.
 *
 * <p>Deliberately knows nothing about Minecraft or about ACP itself: it moves
 * JSON and correlates ids. That keeps it unit-testable without booting a game.
 *
 * <p>Threading: one reader thread and one writer thread per peer. Handler
 * callbacks run on the reader thread, so implementations must not block.
 * {@link Handler#onRequest} returns a future precisely so that a request which
 * needs a human — a permission prompt a player has to walk over and answer —
 * can be answered minutes later without stalling the messages behind it.
 */
public final class JsonRpcPeer implements AutoCloseable {

    /** Receives traffic initiated by the remote peer. */
    public interface Handler {
        /**
         * An incoming request. Complete the returned future with the result
         * value whenever the answer is known; completing it exceptionally with
         * a {@link JsonRpcException} sends that error back instead. Returning
         * {@code null} replies "method not found".
         */
        CompletableFuture<JsonElement> onRequest(String method, JsonObject params);

        /** An incoming notification. Must return promptly. */
        void onNotification(String method, JsonObject params);

        /** A malformed line, or the connection dying. */
        default void onTransportError(String message, Throwable cause) {
        }

        /** The remote end closed its output stream. */
        default void onClosed() {
        }
    }

    /** Queue sentinel telling the writer thread to stop. Compared by
     *  identity, so it can never collide with a real outgoing message. */
    private static final Object SHUTDOWN = new Object();

    /** How long close() lets the writer flush what is already queued. */
    private static final long WRITER_DRAIN_MS = 250;

    private final BufferedReader in;
    private final Writer out;
    /**
     * Kept so {@link #close()} can shut the streams from another thread.
     * Closing the {@link BufferedReader} instead would deadlock: the reader
     * thread holds the {@link InputStreamReader} monitor for the whole of a
     * blocking read, and {@code BufferedReader.close()} needs that same
     * monitor. Closing the raw stream is safe because a blocked reader is
     * parked in {@code wait()}, which releases the stream's own monitor.
     */
    private final InputStream rawIn;
    private final OutputStream rawOut;
    private final Handler handler;
    private final String name;

    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<String, CompletableFuture<JsonElement>> pending = new ConcurrentHashMap<>();
    private final BlockingQueue<Object> outbox = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private final Thread reader;
    private final Thread writer;

    public JsonRpcPeer(InputStream input, OutputStream output, Handler handler, String name) {
        this.rawIn = input;
        this.rawOut = output;
        this.in = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.out = new OutputStreamWriter(output, StandardCharsets.UTF_8);
        this.handler = handler;
        this.name = name;

        this.reader = new Thread(this::readLoop, "acp-read-" + name);
        this.writer = new Thread(this::writeLoop, "acp-write-" + name);
        this.reader.setDaemon(true);
        this.writer.setDaemon(true);
        this.reader.start();
        this.writer.start();
    }

    // -------------------------------------------------------------- outgoing

    /** Sends a request and completes when the peer answers. */
    public CompletableFuture<JsonElement> request(String method, JsonObject params) {
        long numericId = nextId.getAndIncrement();
        String id = Long.toString(numericId);
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        pending.put(id, future);

        JsonObject msg = new JsonObject();
        msg.addProperty("jsonrpc", "2.0");
        msg.addProperty("id", numericId);
        msg.addProperty("method", method);
        if (params != null) {
            msg.add("params", params);
        }

        if (!enqueue(msg)) {
            pending.remove(id);
            future.completeExceptionally(new IOException("peer " + name + " is closed"));
        }
        return future;
    }

    /** Sends a notification; nothing comes back. */
    public void notify(String method, JsonObject params) {
        JsonObject msg = new JsonObject();
        msg.addProperty("jsonrpc", "2.0");
        msg.addProperty("method", method);
        if (params != null) {
            msg.add("params", params);
        }
        enqueue(msg);
    }

    private boolean enqueue(JsonObject msg) {
        if (closed.get()) {
            return false;
        }
        return outbox.offer(msg.toString());
    }

    private void writeLoop() {
        try {
            while (true) {
                Object item = outbox.take();
                if (item == SHUTDOWN) {
                    return;
                }
                out.write((String) item);
                out.write('\n');
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!closed.get()) {
                handler.onTransportError("write failed on peer " + name, e);
            }
        }
    }

    // -------------------------------------------------------------- incoming

    private void readLoop() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    dispatch(JsonParser.parseString(line).getAsJsonObject());
                } catch (RuntimeException e) {
                    // One bad line must not take the connection down.
                    handler.onTransportError("unparseable message on peer " + name + ": " + line, e);
                }
            }
            if (!closed.get()) {
                handler.onClosed();
            }
        } catch (IOException e) {
            if (!closed.get()) {
                handler.onTransportError("read failed on peer " + name, e);
            }
        } finally {
            failPending(new IOException("peer " + name + " disconnected"));
        }
    }

    private void dispatch(JsonObject msg) {
        boolean hasMethod = msg.has("method");
        boolean hasId = msg.has("id") && !msg.get("id").isJsonNull();

        if (hasMethod && hasId) {
            handleIncomingRequest(msg);
        } else if (hasMethod) {
            handler.onNotification(msg.get("method").getAsString(), paramsOf(msg));
        } else if (hasId) {
            handleResponse(msg);
        } else {
            handler.onTransportError("message with neither method nor id: " + msg, null);
        }
    }

    private void handleResponse(JsonObject msg) {
        String id = msg.get("id").getAsJsonPrimitive().getAsString();
        CompletableFuture<JsonElement> future = pending.remove(id);
        if (future == null) {
            handler.onTransportError("response for unknown id " + id, null);
            return;
        }
        if (msg.has("error") && !msg.get("error").isJsonNull()) {
            JsonObject err = msg.getAsJsonObject("error");
            future.completeExceptionally(new JsonRpcException(
                    err.has("code") ? err.get("code").getAsInt() : 0,
                    err.has("message") ? err.get("message").getAsString() : "(no message)",
                    err.get("data")));
        } else {
            future.complete(msg.get("result"));
        }
    }

    private void handleIncomingRequest(JsonObject msg) {
        JsonElement id = msg.get("id");
        String method = msg.get("method").getAsString();

        CompletableFuture<JsonElement> result;
        try {
            result = handler.onRequest(method, paramsOf(msg));
        } catch (RuntimeException e) {
            respondError(id, -32603, "handler threw: " + e);
            return;
        }
        if (result == null) {
            respondError(id, -32601, "method not supported: " + method);
            return;
        }
        result.whenComplete((value, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause()
                        : error;
                if (cause instanceof JsonRpcException rpc) {
                    respondError(id, rpc.code(), rpc.getMessage());
                } else {
                    respondError(id, -32603, String.valueOf(cause));
                }
            } else {
                JsonObject response = new JsonObject();
                response.addProperty("jsonrpc", "2.0");
                response.add("id", id);
                response.add("result", value == null ? new JsonObject() : value);
                enqueue(response);
            }
        });
    }

    private void respondError(JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);

        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("error", error);
        enqueue(response);
    }

    private static JsonObject paramsOf(JsonObject msg) {
        JsonElement params = msg.get("params");
        return params != null && params.isJsonObject() ? params.getAsJsonObject() : new JsonObject();
    }

    private void failPending(Throwable cause) {
        for (CompletableFuture<JsonElement> future : pending.values()) {
            future.completeExceptionally(cause);
        }
        pending.clear();
    }

    // -------------------------------------------------------------- teardown

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        // Let the writer drain anything already queued before its stream goes.
        outbox.offer(SHUTDOWN);
        try {
            writer.join(WRITER_DRAIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Close the raw streams, never the Reader/Writer wrappers — see rawIn.
        closeQuietly(rawIn);
        closeQuietly(rawOut);
        reader.interrupt();

        failPending(new IOException("peer " + name + " closed locally"));
    }

    private void closeQuietly(Closeable stream) {
        try {
            stream.close();
        } catch (IOException e) {
            // Best effort: we are tearing down, and the process this talks to
            // is going away regardless.
        }
    }

    /** Visible for tests: how many requests are still awaiting a response. */
    public int pendingRequestCount() {
        return pending.size();
    }
}
