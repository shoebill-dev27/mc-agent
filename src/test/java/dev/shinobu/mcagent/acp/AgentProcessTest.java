package dev.shinobu.mcagent.acp;

import dev.shinobu.mcagent.acp.model.InitializeResult;
import dev.shinobu.mcagent.acp.model.PermissionRequest;
import dev.shinobu.mcagent.acp.model.SessionUpdate;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link AgentProcess} against a real child process.
 *
 * <p>{@link AcpConnection} covers the protocol over pipes; what is left to
 * prove here is the process plumbing — that stderr is captured, that a dying
 * agent tells its sessions, and above all that {@link AgentProcess#close()}
 * actually leaves nothing running. An orphaned adapter would outlive the
 * Minecraft server, so that last one is a stated acceptance criterion rather
 * than a nicety.
 *
 * <p>Uses a POSIX shell as the stand-in agent, so it is skipped on Windows.
 * Development happens in WSL, where it runs.
 */
@Timeout(30)
class AgentProcessTest {

    private static final long TIMEOUT_MS = 10_000;

    /**
     * A shell script that speaks just enough ACP: it answers {@code initialize}
     * and {@code session/new}, then sits reading stdin so it stays alive until
     * something closes it.
     *
     * <p>Request ids are tracked with a counter rather than parsed, which holds
     * because the client numbers its requests from 1 and this test sends only
     * requests — no notifications — before closing.
     */
    private static final String FAKE_AGENT = """
            echo 'starting up' >&2
            n=0
            while IFS= read -r line; do
              n=$((n+1))
              case "$line" in
                *initialize*)
                  printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":1,"agentInfo":{"name":"fake","title":"Fake Agent","version":"9.9.9"},"agentCapabilities":{"sessionCapabilities":{"close":{}}},"authMethods":[]}}\\n' "$n"
                  ;;
                *session/new*)
                  printf '{"jsonrpc":"2.0","id":%s,"result":{"sessionId":"s1"}}\\n' "$n"
                  ;;
              esac
            done
            """;

    /** An agent that greets stderr and exits immediately, like a bad command. */
    private static final String FAILING_AGENT = """
            echo 'command not found: claude-agent-acp' >&2
            exit 127
            """;

    private static final class TestListener implements SessionListener {
        final BlockingQueue<String> disconnects = new ArrayBlockingQueue<>(8);

        @Override
        public void onUpdate(SessionUpdate update) {
        }

        @Override
        public CompletableFuture<String> onPermissionRequest(PermissionRequest request) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onDisconnected(String reason) {
            disconnects.offer(reason);
        }
    }

    @BeforeEach
    void requirePosixShell() {
        Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"),
                "the stand-in agent is a POSIX shell script");
    }

    private static AgentSpec fakeAgent(String script) {
        return new AgentSpec("fake", "sh", List.of("-c", script), java.util.Map.of());
    }

    @Test
    void spawnsAndInitializesAgainstARealProcess() throws Exception {
        try (AgentProcess agent = AgentProcess.spawn(fakeAgent(FAKE_AGENT), null, null)) {
            InitializeResult result = agent.initialize().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

            assertEquals(1, result.protocolVersion());
            assertEquals("Fake Agent", result.displayName());
            assertEquals("9.9.9", result.agentVersion());
            assertTrue(result.supportsSessionClose());
            assertTrue(agent.isAlive());
        }
    }

    @Test
    void capturesStderrForDiagnosingAFailedAgent() throws Exception {
        try (AgentProcess agent = AgentProcess.spawn(fakeAgent(FAKE_AGENT), null, null)) {
            agent.initialize().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

            // The pump runs on its own thread; the handshake has given it time.
            assertTrue(agent.recentStderr().contains("starting up"),
                    "stderr should be retained, actual: " + agent.recentStderr());
        }
    }

    /** The acceptance criterion: killing a session must not leave a process. */
    @Test
    void closeTerminatesTheProcess() throws Exception {
        AgentProcess agent = AgentProcess.spawn(fakeAgent(FAKE_AGENT), null, null);
        agent.initialize().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertTrue(agent.isAlive());

        agent.close();

        assertFalse(agent.isAlive(), "close() must leave no running agent behind");
    }

    @Test
    void closeIsSafeToCallTwice() throws Exception {
        AgentProcess agent = AgentProcess.spawn(fakeAgent(FAKE_AGENT), null, null);
        agent.initialize().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

        agent.close();
        agent.close();

        assertFalse(agent.isAlive());
    }

    @Test
    void anAgentThatDiesTellsItsSessions() throws Exception {
        TestListener listener = new TestListener();
        try (AgentProcess agent = AgentProcess.spawn(fakeAgent(FAKE_AGENT), null, null)) {
            agent.initialize().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            // The session must actually open: the listener is registered when
            // session/new answers, not when it is sent.
            agent.newSession("/repo", listener).get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

            agent.close();

            assertNotNull(listener.disconnects.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "a dying agent must not leave sessions believing they are live");
        }
    }

    /**
     * A misconfigured command is the most likely real failure. It must surface
     * as a failed initialize with the stderr kept, not as a hang.
     */
    @Test
    void aFailingCommandSurfacesRatherThanHanging() throws Exception {
        try (AgentProcess agent = AgentProcess.spawn(fakeAgent(FAILING_AGENT), null, null)) {
            assertThrows(Exception.class,
                    () -> agent.initialize().get(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "initialize against a dead agent must fail, not block forever");

            assertTrue(agent.recentStderr().stream().anyMatch(line -> line.contains("command not found")),
                    "the reason should be recoverable from stderr, actual: " + agent.recentStderr());
            assertFalse(agent.isAlive());
        }
    }

    @Test
    void spawningANonexistentCommandThrowsImmediately() {
        AgentSpec missing = AgentSpec.of("missing", "definitely-not-a-real-command-9a8b7c");

        assertThrows(java.io.IOException.class, () -> AgentProcess.spawn(missing, null, null));
    }
}
