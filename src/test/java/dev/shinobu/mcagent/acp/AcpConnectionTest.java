package dev.shinobu.mcagent.acp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.shinobu.mcagent.acp.model.InitializeResult;
import dev.shinobu.mcagent.acp.model.PermissionRequest;
import dev.shinobu.mcagent.acp.model.SessionUpdate;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link AcpConnection} against a scripted agent over pipes.
 *
 * <p>The initialize payload is the one captured from a live
 * {@code claude-agent-acp} (see {@code docs/acp-findings.md}), so the
 * capability parsing is checked against reality rather than a guess.
 */
@Timeout(15)
class AcpConnectionTest {

    private static final long TIMEOUT_MS = 5_000;

    /** The real initialize result, trimmed of fields nothing reads. */
    private static final String REAL_INITIALIZE_RESULT = """
            {"protocolVersion":1,
             "agentCapabilities":{
               "_meta":{"claudeCode":{"promptQueueing":true}},
               "promptCapabilities":{"image":true,"embeddedContext":true},
               "loadSession":true,
               "sessionCapabilities":{"additionalDirectories":{},"close":{},
                                      "delete":{},"fork":{},"list":{},"resume":{}}},
             "agentInfo":{"name":"@agentclientprotocol/claude-agent-acp",
                          "title":"Claude Agent","version":"0.49.0"},
             "authMethods":[]}
            """;

    /** An agent that cannot close sessions, to exercise the fallback. */
    private static final String MINIMAL_INITIALIZE_RESULT = """
            {"protocolVersion":1,
             "agentCapabilities":{"sessionCapabilities":{"list":{}}},
             "agentInfo":{"name":"toy","title":"Toy Agent","version":"0.0.1"},
             "authMethods":[]}
            """;

    private AcpConnection connection;
    private BufferedReader agentIn;
    private Writer agentOut;
    private final List<String> diagnostics = new ArrayList<>();

    /** Records what one session receives. */
    private static final class TestListener implements SessionListener {
        final BlockingQueue<SessionUpdate> updates = new ArrayBlockingQueue<>(32);
        final BlockingQueue<String> disconnects = new ArrayBlockingQueue<>(8);
        final BlockingQueue<PermissionRequest> permissions = new ArrayBlockingQueue<>(8);

        /** Answered by the test whenever it likes, as a player would. */
        volatile CompletableFuture<String> decision = new CompletableFuture<>();
        volatile boolean returnNullDecision;

        @Override
        public void onUpdate(SessionUpdate update) {
            updates.offer(update);
        }

        @Override
        public CompletableFuture<String> onPermissionRequest(PermissionRequest request) {
            permissions.offer(request);
            return returnNullDecision ? null : decision;
        }

        @Override
        public void onDisconnected(String reason) {
            disconnects.offer(reason);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        PipedOutputStream agentWrites = new PipedOutputStream();
        PipedInputStream connectionReads = new PipedInputStream(agentWrites);
        PipedOutputStream connectionWrites = new PipedOutputStream();
        PipedInputStream agentReads = new PipedInputStream(connectionWrites);

        agentOut = new OutputStreamWriter(agentWrites, StandardCharsets.UTF_8);
        agentIn = new BufferedReader(new InputStreamReader(agentReads, StandardCharsets.UTF_8));

        connection = new AcpConnection(connectionReads, connectionWrites, "test",
                (message, error) -> diagnostics.add(message));
    }

    @AfterEach
    void tearDown() {
        connection.close();
    }

    // ---------------------------------------------------------------- helpers

    private JsonObject readFromConnection() throws IOException {
        String line = agentIn.readLine();
        assertNotNull(line, "expected a message from the connection");
        return JsonParser.parseString(line).getAsJsonObject();
    }

    private void sendToConnection(String json) throws IOException {
        agentOut.write(json.replace("\n", " "));
        agentOut.write('\n');
        agentOut.flush();
    }

    private void respond(long id, String resultJson) throws IOException {
        sendToConnection("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + resultJson + "}");
    }

    /** Runs initialize to completion, so capability-dependent paths are live. */
    private InitializeResult handshake(String initializeResult) throws Exception {
        CompletableFuture<InitializeResult> future = connection.initialize();
        JsonObject request = readFromConnection();
        assertEquals("initialize", request.get("method").getAsString());
        respond(request.get("id").getAsLong(), initializeResult);
        return future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /** Opens a session with a scripted id and returns its handle. */
    private AcpSession openSession(String sessionId, String cwd, TestListener listener) throws Exception {
        CompletableFuture<AcpSession> future = connection.newSession(cwd, listener);
        JsonObject request = readFromConnection();
        assertEquals("session/new", request.get("method").getAsString());
        assertEquals(cwd, request.getAsJsonObject("params").get("cwd").getAsString());
        respond(request.get("id").getAsLong(), "{\"sessionId\":\"" + sessionId + "\"}");
        return future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void sendUpdate(String sessionId, String update) throws IOException {
        sendToConnection("{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{"
                + "\"sessionId\":\"" + sessionId + "\",\"update\":" + update + "}}");
    }

    // ------------------------------------------------------------------ tests

    @Test
    void initializeParsesTheRealCapabilityPayload() throws Exception {
        InitializeResult result = handshake(REAL_INITIALIZE_RESULT);

        assertEquals(1, result.protocolVersion(), "the adapter speaks v1");
        assertEquals("Claude Agent", result.displayName());
        assertEquals("0.49.0", result.agentVersion());
        assertTrue(result.supportsLoadSession());
        assertTrue(result.supportsSessionClose(), "close is a key, not a boolean value");
        assertTrue(result.supportsPromptQueueing());
        assertFalse(result.requiresAuth(), "empty authMethods means no auth step");
    }

    @Test
    void initializeDeclaresNoFilesystemCapability() throws Exception {
        connection.initialize();
        JsonObject params = readFromConnection().getAsJsonObject("params");

        JsonObject capabilities = params.getAsJsonObject("clientCapabilities");
        assertFalse(capabilities.getAsJsonObject("fs").get("readTextFile").getAsBoolean());
        assertFalse(capabilities.getAsJsonObject("fs").get("writeTextFile").getAsBoolean());
        assertFalse(capabilities.get("terminal").getAsBoolean(),
                "the mod must not become a filesystem access path of its own");
    }

    @Test
    void updatesGoToTheSessionTheyBelongTo() throws Exception {
        handshake(REAL_INITIALIZE_RESULT);
        TestListener first = new TestListener();
        TestListener second = new TestListener();
        openSession("s1", "/repo/one", first);
        openSession("s2", "/repo/two", second);

        sendUpdate("s2", "{\"sessionUpdate\":\"agent_message_chunk\","
                + "\"content\":{\"type\":\"text\",\"text\":\"for two\"}}");

        SessionUpdate got = second.updates.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertEquals("for two", assertInstanceOf(SessionUpdate.AgentMessageChunk.class, got).text());
        assertTrue(first.updates.isEmpty(), "the other session must not see it");
        assertEquals(2, connection.sessionCount());
    }

    @Test
    void updateForAnUnknownSessionIsIgnored() throws Exception {
        handshake(REAL_INITIALIZE_RESULT);
        TestListener listener = new TestListener();
        openSession("s1", "/repo", listener);

        // Arrives for a session that was just closed — normal, must not throw.
        sendUpdate("gone", "{\"sessionUpdate\":\"agent_message_chunk\","
                + "\"content\":{\"type\":\"text\",\"text\":\"stray\"}}");
        sendUpdate("s1", "{\"sessionUpdate\":\"agent_message_chunk\","
                + "\"content\":{\"type\":\"text\",\"text\":\"mine\"}}");

        SessionUpdate got = listener.updates.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertEquals("mine", assertInstanceOf(SessionUpdate.AgentMessageChunk.class, got).text());
    }

    /**
     * The core of the approval flow: the request reaches the listener with its
     * diff intact, nothing is written back until a decision is made, and the
     * chosen option goes out in the shape the agent expects.
     */
    @Test
    void permissionRequestIsAnsweredWithTheChosenOption() throws Exception {
        handshake(REAL_INITIALIZE_RESULT);
        TestListener listener = new TestListener();
        openSession("s1", "/repo", listener);

        sendToConnection("""
                {"jsonrpc":"2.0","id":0,"method":"session/request_permission","params":{
                  "sessionId":"s1",
                  "options":[{"kind":"allow_always","name":"Always Allow all Write","optionId":"allow_always"},
                             {"kind":"allow_once","name":"Allow","optionId":"allow"},
                             {"kind":"reject_once","name":"Reject","optionId":"reject"}],
                  "toolCall":{"toolCallId":"t1","title":"Write x.txt","kind":"edit",
                    "content":[{"type":"diff","path":"/repo/x.txt","oldText":null,"newText":"hi"}],
                    "locations":[{"path":"/repo/x.txt"}]}}}
                """);

        PermissionRequest request = listener.permissions.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertNotNull(request);
        assertEquals(3, request.options().size());
        assertEquals(1, request.diffs().size(), "the diff arrives with the request");
        assertEquals("hi", request.diffs().get(0).newText());
        assertTrue(request.optionOfKind("allow_always").orElseThrow().isDurable());
        assertEquals("reject", request.safeRefusal().orElseThrow().optionId());

        // Nothing sent while the player is still deciding.
        Thread.sleep(150);
        assertFalse(agentIn.ready(), "answered before a decision was made");

        listener.decision.complete("allow");

        JsonObject response = readFromConnection();
        assertEquals(0, response.get("id").getAsInt());
        JsonObject outcome = response.getAsJsonObject("result").getAsJsonObject("outcome");
        assertEquals("selected", outcome.get("outcome").getAsString());
        assertEquals("allow", outcome.get("optionId").getAsString());
    }

    @Test
    void permissionRequestForAnUnknownSessionIsCancelled() throws Exception {
        handshake(REAL_INITIALIZE_RESULT);

        sendToConnection("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"session/request_permission\","
                + "\"params\":{\"sessionId\":\"ghost\",\"options\":[],\"toolCall\":{}}}");

        JsonObject response = readFromConnection();
        assertEquals("cancelled",
                response.getAsJsonObject("result").getAsJsonObject("outcome").get("outcome").getAsString());
    }

    @Test
    void aListenerThatDeclinesToDecideCancelsTheRequest() throws Exception {
        handshake(REAL_INITIALIZE_RESULT);
        TestListener listener = new TestListener();
        listener.returnNullDecision = true;
        openSession("s1", "/repo", listener);

        sendToConnection("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"session/request_permission\","
                + "\"params\":{\"sessionId\":\"s1\",\"options\":[],\"toolCall\":{}}}");

        JsonObject response = readFromConnection();
        assertEquals("cancelled",
                response.getAsJsonObject("result").getAsJsonObject("outcome").get("outcome").getAsString());
    }

    /** Methods we declined in initialize must be refused, not silently dropped. */
    @Test
    void declinedClientMethodsAnswerMethodNotFound() throws Exception {
        handshake(REAL_INITIALIZE_RESULT);

        sendToConnection("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"fs/read_text_file\","
                + "\"params\":{\"path\":\"/etc/passwd\"}}");

        JsonObject response = readFromConnection();
        assertEquals(5, response.get("id").getAsInt());
        assertEquals(-32601, response.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void killingASessionSendsSessionClose() throws Exception {
        handshake(REAL_INITIALIZE_RESULT);
        AcpSession session = openSession("s1", "/repo", new TestListener());

        CompletableFuture<Void> closed = session.close();

        JsonObject request = readFromConnection();
        assertEquals("session/close", request.get("method").getAsString());
        assertEquals("s1", request.getAsJsonObject("params").get("sessionId").getAsString());
        assertEquals(0, connection.sessionCount(), "the session is unregistered immediately");

        respond(request.get("id").getAsLong(), "{}");
        closed.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    @Test
    void anAgentWithoutCloseFallsBackToCancel() throws Exception {
        handshake(MINIMAL_INITIALIZE_RESULT);
        AcpSession session = openSession("s1", "/repo", new TestListener());

        session.close().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

        JsonObject sent = readFromConnection();
        assertEquals("session/cancel", sent.get("method").getAsString());
        assertFalse(sent.has("id"), "cancel is a notification, not a request");
    }

    @Test
    void cancelIsANotificationCarryingTheSessionId() throws Exception {
        handshake(REAL_INITIALIZE_RESULT);
        AcpSession session = openSession("s1", "/repo", new TestListener());

        session.cancel();

        JsonObject sent = readFromConnection();
        assertEquals("session/cancel", sent.get("method").getAsString());
        assertEquals("s1", sent.getAsJsonObject("params").get("sessionId").getAsString());
        assertEquals(1, connection.sessionCount(), "cancel interrupts a turn, it does not end the session");
    }

    @Test
    void promptSendsTextAndReturnsTheStopReason() throws Exception {
        handshake(REAL_INITIALIZE_RESULT);
        AcpSession session = openSession("s1", "/repo", new TestListener());

        CompletableFuture<String> turn = session.prompt("add a test");

        JsonObject request = readFromConnection();
        assertEquals("session/prompt", request.get("method").getAsString());
        JsonObject params = request.getAsJsonObject("params");
        assertEquals("s1", params.get("sessionId").getAsString());
        JsonObject block = params.getAsJsonArray("prompt").get(0).getAsJsonObject();
        assertEquals("text", block.get("type").getAsString());
        assertEquals("add a test", block.get("text").getAsString());

        respond(request.get("id").getAsLong(), "{\"stopReason\":\"end_turn\"}");
        assertEquals(AcpSession.STOP_END_TURN, turn.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    void everySessionIsToldOnceWhenTheAgentDisconnects() throws Exception {
        handshake(REAL_INITIALIZE_RESULT);
        TestListener first = new TestListener();
        TestListener second = new TestListener();
        openSession("s1", "/repo/one", first);
        openSession("s2", "/repo/two", second);

        agentOut.close();

        assertNotNull(first.disconnects.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertNotNull(second.disconnects.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertTrue(connection.isDisconnected());

        // Closing afterwards must not deliver a second notification.
        connection.close();
        assertTrue(first.disconnects.isEmpty(), "disconnect must fan out exactly once");
    }
}
