package dev.shinobu.mcagent.acp;

import dev.shinobu.mcagent.acp.model.InitializeResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * A running ACP adapter process and the {@link AcpConnection} on its stdio.
 *
 * <p>One of these serves every session against the same agent and workspace:
 * ACP multiplexes, so a second session on the same repository costs nothing.
 * The pool that owns it decides when the last session has gone and it should
 * be shut down.
 *
 * <p>Everything protocol-shaped lives in {@link AcpConnection}; this class only
 * deals with the process — spawning it, draining stderr, and making sure it
 * dies. That separation is what lets the protocol be tested over pipes.
 */
public final class AgentProcess implements AutoCloseable {

    /** How many stderr lines to keep for diagnosing a failed agent. */
    private static final int STDERR_HISTORY = 200;

    /** Grace period for a polite shutdown before the process is killed. */
    private static final long TERMINATE_GRACE_MS = 2_000;

    private final AgentSpec spec;
    private final Process process;
    private final AcpConnection connection;
    private final Deque<String> stderr = new ArrayDeque<>();
    private final BiConsumer<String, Throwable> diagnostics;

    private AgentProcess(AgentSpec spec, Process process, BiConsumer<String, Throwable> diagnostics) {
        this.spec = spec;
        this.process = process;
        this.diagnostics = diagnostics;
        this.connection = new AcpConnection(
                process.getInputStream(), process.getOutputStream(), spec.id(), diagnostics);

        startStderrPump();
        watchForExit();
    }

    /**
     * Launches the adapter. Does not initialize the protocol — call
     * {@link #initialize()} next, which is where a wrong command or a missing
     * runtime actually surfaces.
     *
     * @param workingDirectory directory to launch in, or null for the server's
     * @throws IOException if the command cannot be started at all
     */
    public static AgentProcess spawn(AgentSpec spec, String workingDirectory,
                                     BiConsumer<String, Throwable> diagnostics) throws IOException {
        BiConsumer<String, Throwable> sink = diagnostics == null ? (message, error) -> {
        } : diagnostics;

        List<String> commandLine = new ArrayList<>();
        commandLine.add(spec.command());
        commandLine.addAll(spec.args());

        ProcessBuilder builder = new ProcessBuilder(commandLine);
        if (workingDirectory != null) {
            builder.directory(new java.io.File(workingDirectory));
        }
        // Inherit the host environment so the adapter finds the agent CLI and
        // its existing credentials; the mod holds no secrets of its own.
        builder.environment().putAll(spec.env());
        builder.redirectErrorStream(false);

        Process process = builder.start();
        return new AgentProcess(spec, process, sink);
    }

    public CompletableFuture<InitializeResult> initialize() {
        return connection.initialize();
    }

    public CompletableFuture<AcpSession> newSession(String cwd, SessionListener listener) {
        return connection.newSession(cwd, listener);
    }

    public AgentSpec spec() {
        return spec;
    }

    public AcpConnection connection() {
        return connection;
    }

    public boolean isAlive() {
        return process.isAlive() && !connection.isDisconnected();
    }

    public int sessionCount() {
        return connection.sessionCount();
    }

    /** The last few stderr lines, for showing why an agent failed. */
    public List<String> recentStderr() {
        synchronized (stderr) {
            return List.copyOf(stderr);
        }
    }

    // ------------------------------------------------------------- plumbing

    private void startStderrPump() {
        Thread pump = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (stderr) {
                        if (stderr.size() == STDERR_HISTORY) {
                            stderr.removeFirst();
                        }
                        stderr.addLast(line);
                    }
                }
            } catch (IOException e) {
                // Expected when the process goes away; nothing to salvage.
            }
        }, "acp-stderr-" + spec.id());
        pump.setDaemon(true);
        pump.start();
    }

    private void watchForExit() {
        process.onExit().thenAccept(exited -> {
            int code = exited.exitValue();
            // A clean exit code still means every live session just died.
            String reason = "agent " + spec.id() + " exited with code " + code;
            if (code != 0) {
                List<String> tail = recentStderr();
                if (!tail.isEmpty()) {
                    diagnostics.accept(reason + "; last stderr: "
                            + String.join(" | ", tail.subList(Math.max(0, tail.size() - 5), tail.size())), null);
                }
            }
            connection.fanOutDisconnect(reason);
        });
    }

    /**
     * Shuts the agent down: close stdio first so it sees EOF and can exit on its
     * own, then escalate. Leaving an orphan here would outlive the Minecraft
     * server, so the forced kill is not optional.
     */
    @Override
    public void close() {
        connection.close();
        process.destroy();
        try {
            if (!process.waitFor(TERMINATE_GRACE_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String toString() {
        return "AgentProcess[" + spec.id() + " pid=" + (process.isAlive() ? process.pid() : "dead") + "]";
    }

    /** Exposed for the pool's logging; the map itself is not mutated here. */
    public Map<String, String> env() {
        return spec.env();
    }
}
