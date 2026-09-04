package dev.shinobu.mcagent.acp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link JsonRpcPeer} against a fake remote end over pipes, so the
 * transport is exercised without spawning an agent process.
 */
@Timeout(15)
class JsonRpcPeerTest {

    private static final long TIMEOUT_MS = 5_000;

    private JsonRpcPeer peer;
    private BufferedReader remoteIn;
    private Writer remoteOut;
    private RecordingHandler handler;

    /** Captures what the peer hands us from the remote side. */
    private static final class RecordingHandler implements JsonRpcPeer.Handler {
        final BlockingQueue<JsonObject> notifications = new ArrayBlockingQueue<>(16);
        final BlockingQueue<String> errors = new ArrayBlockingQueue<>(16);
        final CompletableFuture<Void> closed = new CompletableFuture<>();

        /** Set by a test to control how the next incoming request is answered. */
        volatile CompletableFuture<JsonElement> nextRequestAnswer;
        volatile JsonObject lastRequestParams;
        volatile String lastRequestMethod;

        @Override
        public CompletableFuture<JsonElement> onRequest(String method, JsonObject params) {
            lastRequestMethod = method;
            lastRequestParams = params;
            return nextRequestAnswer;
        }

        @Override
        public void onNotification(String method, JsonObject params) {
            JsonObject record = new JsonObject();
            record.addProperty("method", method);
            record.add("params", params);
            notifications.offer(record);
        }

        @Override
        public void onTransportError(String message, Throwable cause) {
            errors.offer(message);
        }

        @Override
        public void onClosed() {
            closed.complete(null);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        // remote --> peer
        PipedOutputStream remoteWrites = new PipedOutputStream();
        PipedInputStream peerReads = new PipedInputStream(remoteWrites);
        // peer --> remote
        PipedOutputStream peerWrites = new PipedOutputStream();
        PipedInputStream remoteReads = new PipedInputStream(peerWrites);

        remoteOut = new OutputStreamWriter(remoteWrites, StandardCharsets.UTF_8);
        remoteIn = new BufferedReader(new InputStreamReader(remoteReads, StandardCharsets.UTF_8));

        handler = new RecordingHandler();
        peer = new JsonRpcPeer(peerReads, peerWrites, handler, "test");
    }

    @AfterEach
    void tearDown() {
        peer.close();
    }

    private void sendFromRemote(String json) throws IOException {
        remoteOut.write(json);
        remoteOut.write('\n');
        remoteOut.flush();
    }

    private JsonObject readAtRemote() throws IOException {
        String line = remoteIn.readLine();
        assertNotNull(line, "expected a message from the peer");
        return JsonParser.parseString(line).getAsJsonObject();
    }

    @Test
    void requestGetsCorrelatedWithItsResponse() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", 1);
        CompletableFuture<JsonElement> future = peer.request("initialize", params);

        JsonObject sent = readAtRemote();
        assertEquals("2.0", sent.get("jsonrpc").getAsString());
        assertEquals("initialize", sent.get("method").getAsString());
        assertEquals(1, sent.getAsJsonObject("params").get("protocolVersion").getAsInt());
        assertEquals(1, peer.pendingRequestCount());

        long id = sent.get("id").getAsLong();
        sendFromRemote("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":1}}");

        JsonElement result = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertEquals(1, result.getAsJsonObject().get("protocolVersion").getAsInt());
        assertEquals(0, peer.pendingRequestCount());
    }

    @Test
    void outOfOrderResponsesStillMatchTheRightRequest() throws Exception {
        CompletableFuture<JsonElement> first = peer.request("one", new JsonObject());
        CompletableFuture<JsonElement> second = peer.request("two", new JsonObject());

        long firstId = readAtRemote().get("id").getAsLong();
        long secondId = readAtRemote().get("id").getAsLong();

        // Answer the second request first.
        sendFromRemote("{\"jsonrpc\":\"2.0\",\"id\":" + secondId + ",\"result\":{\"which\":\"two\"}}");
        sendFromRemote("{\"jsonrpc\":\"2.0\",\"id\":" + firstId + ",\"result\":{\"which\":\"one\"}}");

        assertEquals("two", second.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .getAsJsonObject().get("which").getAsString());
        assertEquals("one", first.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .getAsJsonObject().get("which").getAsString());
    }

    @Test
    void errorResponseSurfacesAsJsonRpcException() throws Exception {
        CompletableFuture<JsonElement> future = peer.request("nope", new JsonObject());
        long id = readAtRemote().get("id").getAsLong();

        sendFromRemote("{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"error\":{\"code\":-32601,\"message\":\"method not found\"}}");

        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        JsonRpcException rpc = assertInstanceOf(JsonRpcException.class, thrown.getCause());
        assertEquals(-32601, rpc.code());
    }

    @Test
    void notificationsReachTheHandler() throws Exception {
        // Shaped like a real ACP session/update.
        sendFromRemote("{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":"
                + "{\"sessionId\":\"s1\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\"}}}");

        JsonObject got = handler.notifications.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertNotNull(got, "handler should have been notified");
        assertEquals("session/update", got.get("method").getAsString());
        assertEquals("s1", got.getAsJsonObject("params").get("sessionId").getAsString());
    }

    /**
     * The behaviour the permission flow depends on: an incoming request may be
     * answered long after it arrives, and traffic behind it must keep flowing
     * in the meantime.
     */
    @Test
    void incomingRequestCanBeAnsweredLaterWithoutBlockingTheReader() throws Exception {
        CompletableFuture<JsonElement> answer = new CompletableFuture<>();
        handler.nextRequestAnswer = answer;

        sendFromRemote("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"session/request_permission\","
                + "\"params\":{\"sessionId\":\"s1\"}}");

        // Give the reader a moment, then prove it is not stuck: a later
        // notification still gets through while the request is unanswered.
        sendFromRemote("{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"s1\"}}");
        assertNotNull(handler.notifications.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                "reader thread blocked waiting for the request to be answered");
        assertEquals("session/request_permission", handler.lastRequestMethod);

        // Nothing has been written back yet. Checked with ready() rather than a
        // competing reader thread, which would race us for the real response.
        Thread.sleep(200);
        assertFalse(remoteIn.ready(), "peer answered before the handler decided");

        // Now the player decides.
        JsonObject outcome = new JsonObject();
        outcome.addProperty("optionId", "allow");
        answer.complete(outcome);

        JsonObject response = readAtRemote();
        assertEquals(0, response.get("id").getAsInt());
        assertEquals("allow", response.getAsJsonObject("result").get("optionId").getAsString());
    }

    @Test
    void unsupportedIncomingRequestGetsMethodNotFound() throws Exception {
        handler.nextRequestAnswer = null;

        sendFromRemote("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"fs/read_text_file\",\"params\":{}}");

        JsonObject response = readAtRemote();
        assertEquals(7, response.get("id").getAsInt());
        assertEquals(-32601, response.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void garbageLineIsReportedButKeepsTheConnectionAlive() throws Exception {
        sendFromRemote("this is not json");
        assertNotNull(handler.errors.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        // Still usable afterwards.
        sendFromRemote("{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{}}");
        assertNotNull(handler.notifications.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                "connection should survive one unparseable line");
    }

    @Test
    void closingLocallyFailsInFlightRequests() {
        CompletableFuture<JsonElement> future = peer.request("initialize", new JsonObject());
        assertFalse(future.isDone());

        peer.close();

        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertInstanceOf(IOException.class, thrown.getCause());
    }

    @Test
    void remoteDisconnectFailsInFlightRequestsAndReportsClosure() throws Exception {
        CompletableFuture<JsonElement> future = peer.request("initialize", new JsonObject());
        readAtRemote();

        remoteOut.close();

        assertThrows(ExecutionException.class, () -> future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertTrue(handler.closed.isDone() || handler.closed.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) == null,
                "handler should have been told the peer closed");
    }
}
