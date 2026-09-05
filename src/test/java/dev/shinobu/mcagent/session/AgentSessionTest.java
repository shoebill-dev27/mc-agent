package dev.shinobu.mcagent.session;

import com.google.gson.JsonParser;
import dev.shinobu.mcagent.acp.AgentSpec;
import dev.shinobu.mcagent.acp.model.PermissionRequest;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Permission bookkeeping, which is the part of a session where getting it
 * wrong runs a tool nobody approved.
 */
class AgentSessionTest {

    private final AgentSession session = new AgentSession(
            "mc-agent", UUID.randomUUID(), "shinobu",
            AgentSpec.of("claude", "claude-agent-acp"), "/repo");

    private static PermissionRequest request() {
        return PermissionRequest.parse(JsonParser.parseString("""
                {"sessionId":"s1",
                 "options":[{"kind":"allow_always","name":"Always Allow","optionId":"allow_always"},
                            {"kind":"allow_once","name":"Allow","optionId":"allow"},
                            {"kind":"reject_once","name":"Reject","optionId":"reject"}],
                 "toolCall":{"toolCallId":"t1","title":"Write x.txt","kind":"edit"}}
                """).getAsJsonObject());
    }

    /** A request with nothing but allow options, to prove we never pick one. */
    private static PermissionRequest requestWithNoRefusal() {
        return PermissionRequest.parse(JsonParser.parseString("""
                {"sessionId":"s1",
                 "options":[{"kind":"allow_once","name":"Allow","optionId":"allow"}],
                 "toolCall":{"toolCallId":"t1","title":"Write x.txt","kind":"edit"}}
                """).getAsJsonObject());
    }

    @Test
    void answeringCompletesTheAgentsFuture() throws Exception {
        CompletableFuture<String> waiting = session.beginPermission(request());
        assertTrue(session.hasPendingPermission());
        assertEquals(SessionState.AWAITING, session.state().state());

        assertTrue(session.decidePermission("allow"));

        assertEquals("allow", waiting.get());
        assertFalse(session.hasPendingPermission());
        assertNotEquals(SessionState.AWAITING, session.state().state());
    }

    @Test
    void answeringWithNothingOutstandingIsANoOp() {
        assertFalse(session.decidePermission("allow"));
    }

    /**
     * The approval dialog is an inventory screen: a player can leave it open
     * while the turn moves on and another request arrives. Clicking a button
     * from the old one must not answer the new one.
     */
    @Test
    void anOptionTheCurrentRequestDoesNotOfferIsRefused() {
        CompletableFuture<String> waiting = session.beginPermission(requestWithNoRefusal());

        assertFalse(session.decidePermission("allow_always"),
                "that option belonged to a different request");
        assertFalse(session.decidePermission(null));
        assertFalse(waiting.isDone());
        assertTrue(session.hasPendingPermission(), "the real question is still open");

        assertTrue(session.decidePermission("allow"), "the offered option still works");
    }

    /**
     * The safety-critical case. Ending a session with a decision outstanding
     * must refuse it: leaving it unanswered blocks the agent forever, and
     * answering "allow" on the way out runs an edit the player never saw.
     */
    @Test
    void abandoningAPendingDecisionRefusesItRatherThanAllowingIt() throws Exception {
        CompletableFuture<String> waiting = session.beginPermission(request());

        session.abandonPendingDecision();

        assertEquals("reject", waiting.get(), "must pick the refusing option, never an allow");
        assertFalse(session.hasPendingPermission());
    }

    /** With no refusing option offered, cancel — still never an allow. */
    @Test
    void abandoningWithNoRefusalOfferedCancelsInstead() throws Exception {
        CompletableFuture<String> waiting = session.beginPermission(requestWithNoRefusal());

        session.abandonPendingDecision();

        assertEquals(null, waiting.get(),
                "a null decision is read as cancelled, which still does not run the tool");
    }

    @Test
    void aSecondRequestRefusesTheOneThePlayerCanNoLongerSee() throws Exception {
        CompletableFuture<String> first = session.beginPermission(request());
        CompletableFuture<String> second = session.beginPermission(request());

        assertEquals("reject", first.get(), "the superseded request must not be left hanging");
        assertFalse(second.isDone());
        assertTrue(session.hasPendingPermission());
    }

    @Test
    void aDisconnectRefusesWhateverWasOutstanding() throws Exception {
        CompletableFuture<String> waiting = session.beginPermission(request());

        session.onDisconnected("agent exited with code 1");

        assertEquals("reject", waiting.get());
        assertEquals(SessionState.ERROR, session.state().state());
        assertFalse(session.hasPendingPermission());
    }

    @Test
    void turnsMoveTheStateMachine() {
        session.beginTurn(new CompletableFuture<>(), 1_000);
        assertEquals(SessionState.THINKING, session.state().state());

        session.endTurn("end_turn");
        assertEquals(SessionState.IDLE, session.state().state());
    }
}
