package dev.shinobu.mcagent.session;

import dev.shinobu.mcagent.acp.AgentProcessPool;
import dev.shinobu.mcagent.acp.AgentSpec;
import dev.shinobu.mcagent.acp.SessionListener;
import dev.shinobu.mcagent.acp.model.PermissionRequest;
import dev.shinobu.mcagent.acp.model.SessionUpdate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * Owns every session on the server and is the only place ACP events cross onto
 * the server thread.
 *
 * <p>The ACP layer calls back on its reader thread; touching a world or an
 * entity from there would be a race at best. Every callback here therefore
 * does nothing but hand the event to {@code mainThread}, and all session state
 * is mutated on the far side of that hop. Observers are called there too, so a
 * listener can move an entity without thinking about it.
 *
 * <p>Takes an {@link Executor} rather than the server so it can be tested
 * without one; the mod passes {@code server::execute}.
 */
public final class SessionManager implements AutoCloseable {

    /** Told about sessions changing, always on the server thread. */
    public interface Observer {
        /** The session's displayed state changed, or its running tool did. */
        default void onStateChanged(AgentSession session) {
        }

        /** Output for the terminal wall. */
        default void onOutput(AgentSession session, SessionUpdate update) {
        }

        /** A decision is needed. The avatar should come and ask. */
        default void onPermissionRequested(AgentSession session, PermissionRequest request) {
        }

        /** The session is gone; remove its avatar and wall. */
        default void onClosed(AgentSession session) {
        }
    }

    private final Executor mainThread;
    private final AgentProcessPool pool;
    private final LongSupplier clock;
    private final BiConsumer<String, Throwable> diagnostics;

    private final Map<UUID, AgentSession> sessions = new LinkedHashMap<>();
    private final List<Observer> observers = new CopyOnWriteArrayList<>();

    public SessionManager(Executor mainThread, AgentProcessPool pool,
                          BiConsumer<String, Throwable> diagnostics) {
        this(mainThread, pool, System::currentTimeMillis, diagnostics);
    }

    public SessionManager(Executor mainThread, AgentProcessPool pool, LongSupplier clock,
                          BiConsumer<String, Throwable> diagnostics) {
        this.mainThread = mainThread;
        this.pool = pool;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.diagnostics = diagnostics == null ? (message, error) -> {
        } : diagnostics;
    }

    public void addObserver(Observer observer) {
        observers.add(observer);
    }

    // ---------------------------------------------------------------- queries

    /** Sessions in creation order. Server thread only. */
    public Collection<AgentSession> sessions() {
        return new ArrayList<>(sessions.values());
    }

    public AgentSession session(UUID id) {
        return sessions.get(id);
    }

    /** Sessions belonging to one player, for the session list GUI. */
    public List<AgentSession> sessionsOwnedBy(UUID ownerId) {
        List<AgentSession> owned = new ArrayList<>();
        for (AgentSession session : sessions.values()) {
            if (session.ownerId().equals(ownerId)) {
                owned.add(session);
            }
        }
        return owned;
    }

    /** How many sessions already run against this directory, to warn about clashes. */
    public int sessionsInWorkspace(String workspace) {
        int count = 0;
        for (AgentSession session : sessions.values()) {
            if (session.workspace().equals(workspace)) {
                count++;
            }
        }
        return count;
    }

    // -------------------------------------------------------------- lifecycle

    /**
     * Opens a session. The returned future completes on the server thread once
     * the agent has answered, or fails if it could not be started.
     */
    public CompletableFuture<AgentSession> open(String name, UUID ownerId, String ownerName,
                                                AgentSpec spec, String workspace) {
        AgentSession session = new AgentSession(name, ownerId, ownerName, spec, workspace);

        return pool.openSession(spec, workspace, listenerFor(session))
                .handleAsync((pooled, error) -> {
                    if (error != null) {
                        diagnostics.accept("could not open a session in " + workspace, error);
                        throw error instanceof java.util.concurrent.CompletionException completion
                                ? completion
                                : new java.util.concurrent.CompletionException(error);
                    }
                    session.attach(pooled);
                    sessions.put(session.id(), session);
                    notifyObservers(observer -> observer.onStateChanged(session));
                    return session;
                }, mainThread);
    }

    /** Sends a prompt. Safe to call while a turn is running; the agent queues it. */
    public void prompt(AgentSession session, String text) {
        if (session.isClosed() || session.pooled() == null) {
            return;
        }
        CompletableFuture<String> turn = session.pooled().session().prompt(text);
        session.beginTurn(turn, clock.getAsLong());
        notifyObservers(observer -> observer.onStateChanged(session));

        turn.whenCompleteAsync((stopReason, error) -> {
            if (error != null) {
                // A failed turn is usually the agent going away, which the
                // disconnect callback reports with a better reason. Do not
                // overwrite that with a generic error here.
                diagnostics.accept("turn failed for session " + session.name(), error);
                if (!session.isClosed()) {
                    session.endTurn(null);
                }
            } else {
                session.endTurn(stopReason);
            }
            notifyObservers(observer -> observer.onStateChanged(session));
        }, mainThread);
    }

    /** Interrupts the running turn without ending the session. */
    public void cancel(AgentSession session) {
        if (session.isClosed() || session.pooled() == null) {
            return;
        }
        session.pooled().session().cancel();
    }

    /**
     * Answers the outstanding permission request.
     *
     * @return true if there was one to answer
     */
    public boolean decide(AgentSession session, String optionId) {
        boolean decided = session.decidePermission(optionId);
        if (decided) {
            notifyObservers(observer -> observer.onStateChanged(session));
        }
        return decided;
    }

    /** Ends a session for good — what killing the avatar does. */
    public void close(AgentSession session) {
        if (session.isClosed()) {
            return;
        }
        session.markClosed();
        sessions.remove(session.id());

        // Refuse anything outstanding before the agent goes: leaving a request
        // unanswered would block it, and answering "allow" on the way out would
        // run a tool nobody approved.
        session.abandonPendingDecision();

        if (session.pooled() != null) {
            session.pooled().close();
        }
        notifyObservers(observer -> observer.onClosed(session));
    }

    /** Server is stopping. Nothing may outlive it. */
    @Override
    public void close() {
        for (AgentSession session : new ArrayList<>(sessions.values())) {
            close(session);
        }
        sessions.clear();
        pool.close();
    }

    // --------------------------------------------------------------- plumbing

    /**
     * Bridges one session's ACP callbacks onto the server thread.
     *
     * <p>The permission callback is the interesting one: it returns a future
     * immediately and completes it whenever the player decides, so the agent
     * waits without anything else being held up.
     */
    private SessionListener listenerFor(AgentSession session) {
        return new SessionListener() {
            @Override
            public void onUpdate(SessionUpdate update) {
                mainThread.execute(() -> {
                    if (session.isClosed()) {
                        return;
                    }
                    SessionState before = session.state().state();
                    ToolCallState toolBefore = session.state().activeTool();

                    session.onUpdate(update, clock.getAsLong());
                    notifyObservers(observer -> observer.onOutput(session, update));

                    if (session.state().state() != before
                            || session.state().activeTool() != toolBefore) {
                        notifyObservers(observer -> observer.onStateChanged(session));
                    }
                });
            }

            @Override
            public CompletableFuture<String> onPermissionRequest(PermissionRequest request) {
                CompletableFuture<String> decision = new CompletableFuture<>();
                mainThread.execute(() -> {
                    if (session.isClosed()) {
                        // Refuse rather than leave the agent waiting on a
                        // session that no longer exists.
                        decision.complete(request.safeRefusal()
                                .map(PermissionRequest.PermissionOption::optionId).orElse(null));
                        return;
                    }
                    session.beginPermission(request).whenComplete((optionId, error) ->
                            decision.complete(error == null ? optionId : null));
                    notifyObservers(observer -> observer.onPermissionRequested(session, request));
                    notifyObservers(observer -> observer.onStateChanged(session));
                });
                return decision;
            }

            @Override
            public void onDisconnected(String reason) {
                mainThread.execute(() -> {
                    if (session.isClosed()) {
                        return;
                    }
                    session.onDisconnected(reason);
                    notifyObservers(observer -> observer.onStateChanged(session));
                });
            }
        };
    }

    private void notifyObservers(java.util.function.Consumer<Observer> action) {
        for (Observer observer : observers) {
            try {
                action.accept(observer);
            } catch (RuntimeException e) {
                // A broken avatar must not take the session down with it.
                diagnostics.accept("session observer threw", e);
            }
        }
    }
}
