package dev.shinobu.mcagent.session;

import dev.shinobu.mcagent.acp.AgentProcessPool;
import dev.shinobu.mcagent.acp.AgentSpec;
import dev.shinobu.mcagent.acp.TestAgents;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link SessionManager} against a real pooled agent.
 *
 * <p>The executor here is drained by hand, which is the point: if any session
 * state were mutated on the ACP reader thread instead of being handed across,
 * these assertions would see it before a single task had been drained.
 */
@Timeout(60)
class SessionManagerTest {

    private static final long TIMEOUT_MS = 15_000;

    /** Stands in for the server thread; runs nothing until asked. */
    private static final class ManualExecutor implements Executor {
        final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();

        @Override
        public void execute(Runnable command) {
            queue.add(command);
        }

        /** @return how many tasks ran */
        int drain() {
            int ran = 0;
            Runnable task;
            while ((task = queue.poll()) != null) {
                task.run();
                ran++;
            }
            return ran;
        }
    }

    /** Records observer traffic, and which thread delivered it. */
    private static final class RecordingObserver implements SessionManager.Observer {
        final List<SessionState> states = new ArrayList<>();
        final List<SessionUpdate> output = new ArrayList<>();
        final List<PermissionRequest> permissions = new ArrayList<>();
        final List<AgentSession> closed = new ArrayList<>();
        final Set<String> threads = ConcurrentHashMap.newKeySet();

        @Override
        public void onStateChanged(AgentSession session) {
            threads.add(Thread.currentThread().getName());
            states.add(session.state().state());
        }

        @Override
        public void onOutput(AgentSession session, SessionUpdate update) {
            threads.add(Thread.currentThread().getName());
            output.add(update);
        }

        @Override
        public void onPermissionRequested(AgentSession session, PermissionRequest request) {
            threads.add(Thread.currentThread().getName());
            permissions.add(request);
        }

        @Override
        public void onClosed(AgentSession session) {
            threads.add(Thread.currentThread().getName());
            closed.add(session);
        }
    }

    @TempDir
    Path workspaces;

    private final ManualExecutor mainThread = new ManualExecutor();
    private final RecordingObserver observer = new RecordingObserver();
    private AgentProcessPool pool;
    private SessionManager manager;

    @BeforeEach
    void setUp() {
        TestAgents.requirePosixShell();
        pool = new AgentProcessPool(Duration.ofMinutes(10), null, null, null);
        manager = new SessionManager(mainThread, pool, null);
        manager.addObserver(observer);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    private String repo(String name) throws IOException {
        return Files.createDirectories(workspaces.resolve(name)).toString();
    }

    private static AgentSpec agent() {
        return TestAgents.spec("fake", TestAgents.COOPERATIVE);
    }

    /** Runs queued server-thread work until {@code condition} holds. */
    private void drainUntil(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            mainThread.drain();
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(message);
    }

    private AgentSession openSession() throws Exception {
        var future = manager.open("mc-agent", UUID.randomUUID(), "shinobu", agent(), repo("main"));
        drainUntil(future::isDone, "the session never opened");
        return future.get();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void openingRegistersTheSession() throws Exception {
        AgentSession session = openSession();

        assertNotNull(session.acpSessionId());
        assertEquals(1, manager.sessions().size());
        assertEquals(session, manager.session(session.id()));
        assertEquals(SessionState.IDLE, session.state().state());
    }

    /**
     * Nothing may reach the session before the server thread runs it. If the
     * ACP reader thread were mutating state directly, the session would already
     * be registered here.
     */
    @Test
    void nothingHappensUntilTheServerThreadRunsIt() throws Exception {
        manager.open("mc-agent", UUID.randomUUID(), "shinobu", agent(), repo("main"));

        // Give the agent ample time to answer on its own thread.
        Thread.sleep(300);
        assertTrue(manager.sessions().isEmpty(),
                "a session must not appear until the server thread has processed it");
        assertTrue(observer.states.isEmpty());
    }

    @Test
    void everyObserverCallbackArrivesOnTheServerThread() throws Exception {
        AgentSession session = openSession();
        manager.prompt(session, "read the gitignore");
        drainUntil(() -> session.state().state() == SessionState.IDLE
                && !observer.output.isEmpty(), "the turn never finished");
        manager.close(session);

        assertEquals(Set.of(Thread.currentThread().getName()), observer.threads,
                "observers touch entities, so they must only ever run on the server thread");
    }

    @Test
    void aPromptRunsThroughWorkingAndBackToIdle() throws Exception {
        AgentSession session = openSession();

        manager.prompt(session, "read the gitignore");
        assertEquals(SessionState.THINKING, session.state().state(),
                "the state should flip as soon as the prompt goes out");

        drainUntil(() -> session.state().state() == SessionState.IDLE, "the turn never ended");

        assertTrue(observer.states.contains(SessionState.WORKING),
                "a running tool should have been visible, saw: " + observer.states);
    }

    @Test
    void theRunningToolIsVisibleWhileItWorks() throws Exception {
        AgentSession session = openSession();
        List<String> toolsSeen = new ArrayList<>();
        manager.addObserver(new SessionManager.Observer() {
            @Override
            public void onStateChanged(AgentSession changed) {
                ToolCallState tool = changed.state().activeTool();
                if (tool != null) {
                    toolsSeen.add(tool.displayName() + " " + tool.describe());
                }
            }
        });

        manager.prompt(session, "read the gitignore");
        drainUntil(() -> session.state().state() == SessionState.IDLE, "the turn never ended");

        assertTrue(toolsSeen.contains("Read Read File"),
                "the panel needs the tool name while it runs, saw: " + toolsSeen);
    }

    @Test
    void outputIsForwardedForTheTerminalWall() throws Exception {
        AgentSession session = openSession();

        manager.prompt(session, "read the gitignore");
        drainUntil(() -> session.state().state() == SessionState.IDLE, "the turn never ended");

        assertTrue(observer.output.stream().anyMatch(u -> u instanceof SessionUpdate.ToolCall),
                "tool calls should reach the wall as well as the state machine");
    }

    @Test
    void closingRemovesTheSessionAndTellsObservers() throws Exception {
        AgentSession session = openSession();

        manager.close(session);

        assertTrue(session.isClosed());
        assertTrue(manager.sessions().isEmpty());
        assertEquals(List.of(session), observer.closed);
    }

    @Test
    void closingTwiceIsHarmless() throws Exception {
        AgentSession session = openSession();

        manager.close(session);
        manager.close(session);

        assertEquals(1, observer.closed.size());
    }

    @Test
    void eventsArrivingAfterACloseAreIgnored() throws Exception {
        AgentSession session = openSession();
        manager.prompt(session, "read the gitignore");

        manager.close(session);
        int outputBefore = observer.output.size();
        mainThread.drain();

        assertEquals(outputBefore, observer.output.size(),
                "a closed session must not keep feeding its wall");
    }

    @Test
    void sessionsAreListedByOwnerAndWorkspace() throws Exception {
        UUID owner = UUID.randomUUID();
        var future = manager.open("one", owner, "shinobu", agent(), repo("main"));
        drainUntil(future::isDone, "the session never opened");
        AgentSession session = future.get();

        assertEquals(List.of(session), manager.sessionsOwnedBy(owner));
        assertTrue(manager.sessionsOwnedBy(UUID.randomUUID()).isEmpty());
        assertEquals(1, manager.sessionsInWorkspace(session.workspace()));
    }

    @Test
    void aFailedLaunchFailsTheFutureRatherThanThrowing() throws Exception {
        AgentSpec missing = AgentSpec.of("missing", "definitely-not-a-real-command-9a8b7c");

        var future = manager.open("broken", UUID.randomUUID(), "shinobu", missing, repo("main"));
        drainUntil(future::isDone, "the failure never surfaced");

        assertTrue(future.isCompletedExceptionally());
        assertTrue(manager.sessions().isEmpty());
        assertFalse(observer.states.isEmpty() && !observer.closed.isEmpty());
    }

    @Test
    void shuttingDownClosesEverySession() throws Exception {
        openSession();
        var second = manager.open("two", UUID.randomUUID(), "shinobu", agent(), repo("other"));
        drainUntil(second::isDone, "the second session never opened");

        manager.close();

        assertTrue(manager.sessions().isEmpty());
        assertEquals(2, observer.closed.size(), "both avatars should have been told to go");
    }
}
