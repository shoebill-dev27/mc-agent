package dev.shinobu.mcagent.acp.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parses payloads captured verbatim from a live {@code claude-agent-acp}
 * session (see {@code docs/acp-findings.md}). These are real bytes off the
 * wire, not invented fixtures, so a protocol change breaks a test rather than
 * a player's session.
 */
class SessionUpdateTest {

    private static JsonObject json(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    private static SessionUpdate parse(String raw) {
        return SessionUpdate.parse(json(raw));
    }

    @Test
    void parsesAgentMessageChunk() {
        SessionUpdate update = parse("""
                {"sessionUpdate":"agent_message_chunk",
                 "content":{"type":"text","text":"Reading .gitignore first."},
                 "messageId":"msg_011CeiZNZKK85nMW4ZYJpGkb"}
                """);

        SessionUpdate.AgentMessageChunk chunk =
                assertInstanceOf(SessionUpdate.AgentMessageChunk.class, update);
        assertEquals("Reading .gitignore first.", chunk.text());
        assertEquals("msg_011CeiZNZKK85nMW4ZYJpGkb", chunk.messageId());
    }

    @Test
    void parsesThoughtChunk() {
        SessionUpdate update = parse("""
                {"sessionUpdate":"agent_thought_chunk",
                 "content":{"type":"text","text":" containing the word \\"hello\\"."}}
                """);

        assertEquals(" containing the word \"hello\".",
                assertInstanceOf(SessionUpdate.AgentThoughtChunk.class, update).text());
    }

    /**
     * The creating notification: generic title, empty input, and — importantly
     * — status {@code pending} rather than {@code in_progress}. The avatar has
     * to treat this as "working" or it would never visibly do anything.
     */
    @Test
    void parsesToolCallCreation() {
        SessionUpdate update = parse("""
                {"_meta":{"claudeCode":{"toolName":"Read"}},
                 "toolCallId":"toolu_01EA914t1sVQ3NHEx3qchvvJ",
                 "sessionUpdate":"tool_call","rawInput":{},"status":"pending",
                 "title":"Read File","kind":"read","locations":[],"content":[]}
                """);

        ToolCallDelta delta = assertInstanceOf(SessionUpdate.ToolCall.class, update).delta();
        assertTrue(delta.creation());
        assertEquals("toolu_01EA914t1sVQ3NHEx3qchvvJ", delta.toolCallId());
        assertEquals(ToolCallDelta.STATUS_PENDING, delta.status());
        assertEquals("read", delta.kind());
        assertEquals("Read File", delta.title());
        assertEquals("Read", delta.toolName(), "toolName comes from _meta.claudeCode");
    }

    @Test
    void parsesToolCallUpdateWithLocations() {
        SessionUpdate update = parse("""
                {"_meta":{"claudeCode":{"toolName":"Read"}},
                 "toolCallId":"toolu_01EA914t1sVQ3NHEx3qchvvJ",
                 "sessionUpdate":"tool_call_update",
                 "rawInput":{"file_path":"/home/s/mc-agent/.gitignore"},
                 "title":"Read .gitignore","kind":"read",
                 "locations":[{"path":"/home/s/mc-agent/.gitignore","line":1}],
                 "content":[]}
                """);

        ToolCallDelta delta = assertInstanceOf(SessionUpdate.ToolCall.class, update).delta();
        assertTrue(!delta.creation());
        assertEquals("Read .gitignore", delta.title());
        assertNull(delta.status(), "an omitted field must stay null, meaning unchanged");
        assertEquals(1, delta.locations().size());
        assertEquals("/home/s/mc-agent/.gitignore", delta.locations().get(0).path());
        assertEquals(1, delta.locations().get(0).line());
    }

    /** A file creation: the diff arrives with {@code oldText} null. */
    @Test
    void parsesWriteDiff() {
        SessionUpdate update = parse("""
                {"toolCallId":"toolu_01ReXScvnNBRZssUWadme2ot",
                 "sessionUpdate":"tool_call_update",
                 "title":"Write PROBE_SHOULD_NOT_EXIST.txt","kind":"edit",
                 "content":[{"type":"diff","path":"/home/s/PROBE.txt",
                             "oldText":null,"newText":"hello"}],
                 "locations":[{"path":"/home/s/PROBE.txt"}]}
                """);

        ToolCallDelta delta = assertInstanceOf(SessionUpdate.ToolCall.class, update).delta();
        assertEquals(1, delta.content().size());
        ToolContent.Diff diff = assertInstanceOf(ToolContent.Diff.class, delta.content().get(0));
        assertNull(diff.oldText());
        assertEquals("hello", diff.newText());
        assertEquals(1, diff.addedLines());
        assertEquals(0, diff.removedLines());
        assertNull(delta.locations().get(0).line(), "a location may omit its line");
    }

    /** What comes back after a player rejects a permission request. */
    @Test
    void parsesFailedToolCallWithTextContent() {
        SessionUpdate update = parse("""
                {"_meta":{"claudeCode":{"toolName":"Write"}},
                 "toolCallId":"toolu_01ReXScvnNBRZssUWadme2ot",
                 "sessionUpdate":"tool_call_update","status":"failed",
                 "rawOutput":"User refused permission to run tool",
                 "content":[{"type":"content",
                             "content":{"type":"text","text":"User refused"}}]}
                """);

        ToolCallDelta delta = assertInstanceOf(SessionUpdate.ToolCall.class, update).delta();
        assertEquals(ToolCallDelta.STATUS_FAILED, delta.status());
        assertEquals("User refused permission to run tool", delta.rawOutput());
        assertEquals("User refused",
                assertInstanceOf(ToolContent.Text.class, delta.content().get(0)).text());
    }

    @Test
    void parsesUsageWithAndWithoutCost() {
        SessionUpdate.Usage withoutCost = assertInstanceOf(SessionUpdate.Usage.class,
                parse("{\"sessionUpdate\":\"usage_update\",\"used\":24202,\"size\":200000}"));
        assertEquals(24202, withoutCost.used());
        assertEquals(200000, withoutCost.size());
        assertNull(withoutCost.costAmount());
        assertEquals(0.12101, withoutCost.fraction(), 0.0001);

        SessionUpdate.Usage withCost = assertInstanceOf(SessionUpdate.Usage.class, parse("""
                {"sessionUpdate":"usage_update","used":24532,"size":200000,
                 "cost":{"amount":0.10601340000000001,"currency":"USD"}}
                """));
        assertEquals(0.1060134, withCost.costAmount(), 1e-9);
        assertEquals("USD", withCost.costCurrency());
    }

    @Test
    void unknownVariantIsPreservedRatherThanThrowing() {
        SessionUpdate update = parse("{\"sessionUpdate\":\"plan\",\"entries\":[]}");

        SessionUpdate.Unknown unknown = assertInstanceOf(SessionUpdate.Unknown.class, update);
        assertEquals("plan", unknown.kind());
        assertNotNull(unknown.raw());
    }

    @Test
    void malformedInputDegradesToUnknown() {
        assertInstanceOf(SessionUpdate.Unknown.class, SessionUpdate.parse(null));
        assertInstanceOf(SessionUpdate.Unknown.class, parse("{\"nothing\":\"useful\"}"));
        // An unrecognised content type must not sink the whole message.
        SessionUpdate update = parse("""
                {"sessionUpdate":"tool_call","toolCallId":"t1",
                 "content":[{"type":"terminal","terminalId":"x"}]}
                """);
        ToolCallDelta delta = assertInstanceOf(SessionUpdate.ToolCall.class, update).delta();
        assertInstanceOf(ToolContent.Unknown.class, delta.content().get(0));
    }
}
