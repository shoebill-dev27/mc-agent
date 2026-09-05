package dev.shinobu.mcagent.acp;

import dev.shinobu.mcagent.acp.model.PermissionRequest;
import dev.shinobu.mcagent.acp.model.SessionUpdate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link AgentProcessPool} against real child processes.
 *
 * <p>What matters here is arithmetic that is easy to get subtly wrong:
 * two sessions on one repository must share a process, killing one avatar must
 * not kill the other's agent, and nothing may be left running afterwards.
 */
@Timeout(60)
class AgentProcessPoolTest {

    private static final long TIMEOUT_MS = 15_000;

    /** Long enough that it never fires unless a test wants it to. */
    private static final Duration NEVER_IDLE = Duration.ofMinutes(10);

    private final AtomicInteger spawns = new AtomicInteger();
    private final List<AgentProcess> spawned = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final ConcurrentHashMap<String, String> diagnostics = new ConcurrentHashMap<>();

    private AgentProcessPool pool;

    /**
     * Workspaces have to be real directories: the pool launches the agent in
     * one, so a made-up path fails at spawn rather than in the pool logic.
     */
    @TempDir
    Path workspaces;

    /** Ignores everything; these tests are about lifecycle, not traffic. */
    private static final class QuietListener implements SessionListener {
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
    void setUp() {
        TestAgents.requirePosixShell();
    }

    /** An existing directory to stand in for a repository. */
    private String repo(String name) throws IOException {
        return Files.createDirectories(workspaces.resolve(name)).toString();
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.close();
        }
        for (AgentProcess process : spawned) {
            process.close();
        }
    }

    /** Counts spawns so process reuse can be asserted directly. */
    private AgentProcessPool newPool(Duration idle) {
        AgentProcessPool.ProcessFactory counting = (spec, workingDirectory) -> {
            spawns.incrementAndGet();
            AgentProcess process = AgentProcess.spawn(spec, workingDirectory, null);
            spawned.add(process);
            return process;
        };
        pool = new AgentProcessPool(idle, counting, null,
                (message, error) -> diagnostics.put(message, String.valueOf(error)));
        return pool;
    }

    private AgentProcessPool.PooledSession open(AgentSpec spec, String workspace) throws Exception {
        return pool.openSession(spec, workspace, new QuietListener())
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void twoSessionsOnOneRepositoryShareOneProcess() throws Exception {
        newPool(NEVER_IDLE);
        AgentSpec spec = TestAgents.spec("fake", TestAgents.COOPERATIVE);

        AgentProcessPool.PooledSession first = open(spec, repo("main"));
        AgentProcessPool.PooledSession second = open(spec, repo("main"));

        assertEquals(1, spawns.get(), "ACP multiplexes; a second session must not start a second agent");
        assertEquals(1, pool.agentCount());
        assertEquals(2, pool.sessionCount(spec, repo("main")));
        assertNotEquals(first.sessionId(), second.sessionId(), "each session needs its own id");
    }

    @Test
    void differentWorkspacesGetTheirOwnProcess() throws Exception {
        newPool(NEVER_IDLE);
        AgentSpec spec = TestAgents.spec("fake", TestAgents.COOPERATIVE);

        open(spec, repo("one"));
        open(spec, repo("two"));

        assertEquals(2, spawns.get(), "a process is scoped to one working directory");
        assertEquals(2, pool.agentCount());
    }

    /** Killing one avatar must not take the other session's agent with it. */
    @Test
    void closingOneSessionLeavesTheProcessUpForTheOther() throws Exception {
        newPool(NEVER_IDLE);
        AgentSpec spec = TestAgents.spec("fake", TestAgents.COOPERATIVE);
        AgentProcessPool.PooledSession first = open(spec, repo("main"));
        open(spec, repo("main"));

        first.close();

        assertEquals(1, pool.sessionCount(spec, repo("main")));
        assertEquals(1, pool.agentCount());
        assertTrue(spawned.get(0).isAlive(), "the surviving session still needs its agent");
    }

    @Test
    void theProcessGoesAwayAfterTheLastSessionAndTheIdleGrace() throws Exception {
        newPool(Duration.ofMillis(200));
        AgentSpec spec = TestAgents.spec("fake", TestAgents.COOPERATIVE);
        AgentProcessPool.PooledSession only = open(spec, repo("main"));
        AgentProcess process = spawned.get(0);

        only.close();
        assertTrue(process.isAlive(), "the grace period should keep it warm briefly");

        waitUntil(() -> !process.isAlive(), "agent should have been reaped after the idle grace");
        assertEquals(0, pool.agentCount());
    }

    /** Closing and reopening is common; it must not pay the startup cost again. */
    @Test
    void reopeningDuringTheGracePeriodReusesTheProcess() throws Exception {
        newPool(Duration.ofSeconds(30));
        AgentSpec spec = TestAgents.spec("fake", TestAgents.COOPERATIVE);

        open(spec, repo("main")).close();
        open(spec, repo("main"));

        assertEquals(1, spawns.get(), "the idle shutdown should have been cancelled");
        assertTrue(spawned.get(0).isAlive());
        assertEquals(1, pool.sessionCount(spec, repo("main")));
    }

    @Test
    void closingTheSessionTwiceOnlyReleasesItOnce() throws Exception {
        newPool(NEVER_IDLE);
        AgentSpec spec = TestAgents.spec("fake", TestAgents.COOPERATIVE);
        AgentProcessPool.PooledSession first = open(spec, repo("main"));
        open(spec, repo("main"));

        first.close();
        first.close();

        assertEquals(1, pool.sessionCount(spec, repo("main")),
                "a double close must not steal the other session's reference");
    }

    @Test
    void anAgentThatDiesIsEvictedAndTheNextSessionStartsAFreshOne() throws Exception {
        newPool(NEVER_IDLE);
        AgentSpec spec = TestAgents.spec("fake", TestAgents.COOPERATIVE);
        QuietListener listener = new QuietListener();
        pool.openSession(spec, repo("main"), listener).get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

        // Kill it behind the pool's back, as a crash would.
        spawned.get(0).close();
        assertNotNull(listener.disconnects.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        waitUntil(() -> pool.agentCount() == 0, "a dead agent should not stay in the pool");

        open(spec, repo("main"));
        assertEquals(2, spawns.get(), "the next session must not be handed a corpse");
    }

    @Test
    void closingThePoolLeavesNothingRunning() throws Exception {
        newPool(NEVER_IDLE);
        AgentSpec spec = TestAgents.spec("fake", TestAgents.COOPERATIVE);
        open(spec, repo("one"));
        open(spec, repo("two"));

        pool.close();

        for (AgentProcess process : spawned) {
            assertFalse(process.isAlive(), "an adapter must not outlive the server that started it");
        }
        assertEquals(0, pool.agentCount());
    }

    @Test
    void openingAgainstAClosedPoolFails() throws Exception {
        newPool(NEVER_IDLE);
        pool.close();

        assertThrows(ExecutionException.class, () -> pool
                .openSession(TestAgents.spec("fake", TestAgents.COOPERATIVE), repo("main"), new QuietListener())
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    /** A bad command is the likeliest misconfiguration; it must not stick. */
    @Test
    void anAgentThatCannotStartIsNotLeftInThePool() throws Exception {
        newPool(NEVER_IDLE);
        AgentSpec missing = AgentSpec.of("missing", "definitely-not-a-real-command-9a8b7c");

        assertThrows(ExecutionException.class,
                () -> pool.openSession(missing, repo("main"), new QuietListener())
                        .get(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        waitUntil(() -> pool.agentCount() == 0, "a failed launch should not occupy the pool");
    }

    @Test
    void anAgentThatDiesDuringInitializeIsNotLeftInThePool() throws Exception {
        newPool(NEVER_IDLE);
        AgentSpec failing = TestAgents.spec("failing", TestAgents.FAILS_IMMEDIATELY);

        assertThrows(ExecutionException.class,
                () -> pool.openSession(failing, repo("main"), new QuietListener())
                        .get(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        waitUntil(() -> pool.agentCount() == 0, "a failed handshake should not occupy the pool");
    }

    /** Two callers arriving together must not race into two processes. */
    @Test
    void concurrentOpensShareASingleLaunch() throws Exception {
        newPool(NEVER_IDLE);
        AgentSpec spec = TestAgents.spec("fake", TestAgents.COOPERATIVE);

        List<CompletableFuture<AgentProcessPool.PooledSession>> opens = List.of(
                pool.openSession(spec, repo("main"), new QuietListener()),
                pool.openSession(spec, repo("main"), new QuietListener()),
                pool.openSession(spec, repo("main"), new QuietListener()));
        CompletableFuture.allOf(opens.toArray(CompletableFuture[]::new))
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

        assertEquals(1, spawns.get());
        assertEquals(3, pool.sessionCount(spec, repo("main")));
    }

    // ---------------------------------------------------------------- helpers

    private static void waitUntil(java.util.function.BooleanSupplier condition, String message)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError(message);
    }
}
