package dev.shinobu.mcagent.session;

import com.google.gson.JsonParser;
import dev.shinobu.mcagent.acp.model.PermissionRequest;
import dev.shinobu.mcagent.acp.model.SessionUpdate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The avatar's whole vocabulary is derived here, so this is where the states
 * a player will actually see get pinned down.
 */
class SessionStateMachineTest {

    private final SessionStateMachine machine = new SessionStateMachine();

    private void update(String json, long now) {
        machine.onUpdate(SessionUpdate.parse(JsonParser.parseString(json).getAsJsonObject()), now);
    }

    private static PermissionRequest permission(String sessionId) {
        return PermissionRequest.parse(JsonParser.parseString("""
                {"sessionId":"%s",
                 "options":[{"kind":"allow_once","name":"Allow","optionId":"allow"},
                            {"kind":"reject_once","name":"Reject","optionId":"reject"}],
                 "toolCall":{"toolCallId":"t1","title":"Write x.txt","kind":"edit"}}
                """.formatted(sessionId)).getAsJsonObject());
    }

    @Test
    void startsIdle() {
        assertEquals(SessionState.IDLE, machine.state());
        assertNull(machine.activeTool());
        assertEquals(0, machine.elapsedMillis(1_000));
    }

    @Test
    void aSentPromptMeansThinking() {
        machine.onPromptSent(1_000);

        assertEquals(SessionState.THINKING, machine.state());
        assertEquals(500, machine.elapsedMillis(1_500), "thinking time is measured from the prompt");
    }

    /**
     * The finding that shapes everything: the adapter never sends
     * {@code in_progress}. If {@code pending} did not mean working, the avatar
     * would sit in THINKING for the whole session and the state display would
     * be useless.
     */
    @Test
    void aPendingToolMeansWorking() {
        machine.onPromptSent(1_000);

        update("""
                {"sessionUpdate":"tool_call","toolCallId":"t1","status":"pending",
                 "title":"Read File","kind":"read",
                 "_meta":{"claudeCode":{"toolName":"Read"}}}
                """, 2_000);

        assertEquals(SessionState.WORKING, machine.state());
        assertNotNull(machine.activeTool());
        assertEquals("Read", machine.activeTool().displayName());
        assertEquals(1_000, machine.elapsedMillis(3_000), "elapsed follows the tool once one is running");
    }

    @Test
    void aFinishedToolGoesBackToThinkingWhileTheTurnIsOpen() {
        machine.onPromptSent(1_000);
        update("{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"t1\",\"status\":\"pending\"}", 2_000);
        update("{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"t1\",\"status\":\"completed\"}", 3_000);

        assertEquals(SessionState.THINKING, machine.state());
        assertNull(machine.activeTool());
    }

    /** An update must not blank out what an earlier one established. */
    @Test
    void deltasMergeRatherThanReplace() {
        machine.onPromptSent(1_000);
        update("""
                {"sessionUpdate":"tool_call","toolCallId":"t1","status":"pending",
                 "title":"Read File","kind":"read","_meta":{"claudeCode":{"toolName":"Read"}}}
                """, 2_000);
        update("""
                {"sessionUpdate":"tool_call_update","toolCallId":"t1","title":"Read .gitignore",
                 "locations":[{"path":"/repo/.gitignore","line":1}]}
                """, 2_100);

        ToolCallState tool = machine.activeTool();
        assertEquals("Read .gitignore", tool.title());
        assertEquals("read", tool.kind(), "kind survives an update that omits it");
        assertEquals("Read", tool.displayName(), "so does the tool name");
        assertTrue(tool.isRunning(), "an update with no status leaves the status alone");

        update("{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"t1\",\"status\":\"completed\"}", 2_500);
        assertEquals("Read .gitignore", machine.tools().iterator().next().title(),
                "the panel must not flick back to the generic title as the tool finishes");
    }

    @Test
    void theNewestRunningToolIsTheOneShown() {
        machine.onPromptSent(1_000);
        update("{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"t1\",\"status\":\"pending\",\"title\":\"first\"}", 2_000);
        update("{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"t2\",\"status\":\"pending\",\"title\":\"second\"}", 2_500);

        assertEquals("second", machine.activeTool().title());

        update("{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"t2\",\"status\":\"completed\"}", 3_000);
        assertEquals("first", machine.activeTool().title(), "falls back to the one still running");
    }

    @Test
    void aPermissionRequestOutranksARunningTool() {
        machine.onPromptSent(1_000);
        update("{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"t1\",\"status\":\"pending\"}", 2_000);
        assertEquals(SessionState.WORKING, machine.state());

        machine.onPermissionRequested(permission("s1"));
        assertEquals(SessionState.AWAITING, machine.state());
        assertNotNull(machine.pendingPermission());

        machine.onPermissionResolved();
        assertEquals(SessionState.WORKING, machine.state(), "back to what it was doing");
        assertNull(machine.pendingPermission());
    }

    @Test
    void endingTheTurnReturnsToIdle() {
        machine.onPromptSent(1_000);
        update("{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"t1\",\"status\":\"pending\"}", 2_000);

        machine.onTurnEnded("end_turn");

        assertEquals(SessionState.IDLE, machine.state());
        assertEquals(0, machine.elapsedMillis(9_000));
    }

    /**
     * A tool the agent never marked finished must not strand the avatar. The
     * status is left as reported; the turn being closed is what settles it.
     */
    @Test
    void aToolLeftPendingDoesNotStrandTheAvatarInWorking() {
        machine.onPromptSent(1_000);
        update("{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"t1\",\"status\":\"pending\"}", 2_000);

        machine.onTurnEnded("end_turn");

        assertEquals(SessionState.IDLE, machine.state());
        assertNull(machine.activeTool());
        assertTrue(machine.tools().iterator().next().isRunning(),
                "the reported status is a fact and should not be rewritten");
    }

    /**
     * A refusal or a cancellation is not a malfunction. Showing red for those
     * would teach the player to ignore red, which is the one colour that has
     * to mean something.
     */
    @Test
    void refusalsAndCancellationsAreNotErrors() {
        for (String stopReason : new String[]{"end_turn", "cancelled", "refusal", "max_tokens"}) {
            SessionStateMachine fresh = new SessionStateMachine();
            fresh.onPromptSent(1_000);
            fresh.onTurnEnded(stopReason);
            assertEquals(SessionState.IDLE, fresh.state(), stopReason + " should land on idle");
        }
    }

    @Test
    void aDeadAgentIsAnErrorAndOutranksEverything() {
        machine.onPromptSent(1_000);
        update("{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"t1\",\"status\":\"pending\"}", 2_000);
        machine.onPermissionRequested(permission("s1"));

        machine.onDisconnected("agent exited with code 1");

        assertEquals(SessionState.ERROR, machine.state());
        assertEquals("agent exited with code 1", machine.errorMessage());
        assertNull(machine.pendingPermission(), "a pending decision is moot once the agent is gone");

        machine.clearError();
        assertEquals(SessionState.IDLE, machine.state());
    }

    @Test
    void aFailedToolIsNotByItselfASessionError() {
        machine.onPromptSent(1_000);
        update("{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"t1\",\"status\":\"pending\"}", 2_000);
        update("""
                {"sessionUpdate":"tool_call_update","toolCallId":"t1","status":"failed",
                 "rawOutput":"User refused permission to run tool"}
                """, 3_000);

        assertEquals(SessionState.THINKING, machine.state(),
                "a rejected edit is the normal flow, not a broken session");
        assertTrue(machine.tools().iterator().next().failed());
    }

    @Test
    void usageIsRemembered() {
        update("""
                {"sessionUpdate":"usage_update","used":24532,"size":200000,
                 "cost":{"amount":0.1060134,"currency":"USD"}}
                """, 1_000);

        SessionUpdate.Usage usage = machine.usage();
        assertNotNull(usage);
        assertEquals(24532, usage.used());
        assertEquals(0.1060134, usage.costAmount(), 1e-9);
        assertEquals(0.12266, usage.fraction(), 0.0001);
    }

    @Test
    void messageChunksDoNotMoveTheState() {
        machine.onPromptSent(1_000);
        update("{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"hi\"}}", 2_000);
        update("{\"sessionUpdate\":\"agent_thought_chunk\",\"content\":{\"type\":\"text\",\"text\":\"hm\"}}", 2_100);

        assertEquals(SessionState.THINKING, machine.state());
    }

    @Test
    void aLongSessionDoesNotAccumulateToolsForever() {
        machine.onPromptSent(0);
        for (int i = 0; i < 250; i++) {
            update("{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"t" + i + "\",\"status\":\"pending\"}", i);
            update("{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"t" + i + "\",\"status\":\"completed\"}", i);
        }

        assertTrue(machine.tools().size() <= 100, "actual: " + machine.tools().size());
    }

    @Test
    void aRunningToolIsNeverEvicted() {
        machine.onPromptSent(0);
        update("{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"long-runner\",\"status\":\"pending\"}", 0);
        for (int i = 0; i < 250; i++) {
            update("{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"t" + i + "\",\"status\":\"pending\"}", i + 1);
            update("{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"t" + i + "\",\"status\":\"completed\"}", i + 1);
        }

        assertTrue(machine.tools().stream().anyMatch(t -> "long-runner".equals(t.toolCallId())),
                "dropping a running tool would strand the avatar in WORKING with nothing to show");
    }

    @Test
    void anUpdateWithoutAToolIdIsIgnored() {
        machine.onPromptSent(1_000);
        update("{\"sessionUpdate\":\"tool_call\",\"status\":\"pending\"}", 2_000);

        assertEquals(SessionState.THINKING, machine.state());
        assertFalse(machine.tools().iterator().hasNext());
    }
}
