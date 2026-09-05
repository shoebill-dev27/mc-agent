package dev.shinobu.mcagent.acp.model;

import com.google.gson.JsonObject;

/**
 * What the agent told us about itself during {@code initialize}.
 *
 * <p>Only the fields the mod acts on are pulled out; the rest stays in
 * {@link #raw()} so an unfamiliar agent is not misrepresented.
 */
public record InitializeResult(
        int protocolVersion,
        String agentName,
        String agentTitle,
        String agentVersion,
        boolean supportsLoadSession,
        boolean supportsSessionClose,
        boolean supportsPromptQueueing,
        boolean requiresAuth,
        JsonObject raw) {

    /** The version this client asks for. claude-agent-acp negotiates down to 1. */
    public static final int PREFERRED_PROTOCOL_VERSION = 1;

    public static InitializeResult parse(JsonObject result) {
        if (result == null) {
            result = new JsonObject();
        }
        JsonObject agentInfo = Json.object(result, "agentInfo");
        JsonObject capabilities = Json.object(result, "agentCapabilities");
        JsonObject sessionCapabilities = capabilities == null
                ? null : Json.object(capabilities, "sessionCapabilities");
        JsonObject meta = capabilities == null ? null : Json.object(capabilities, "_meta");
        JsonObject claudeCode = meta == null ? null : Json.object(meta, "claudeCode");

        boolean authRequired = false;
        var authMethods = Json.get(result, "authMethods");
        if (authMethods != null && authMethods.isJsonArray()) {
            authRequired = !authMethods.getAsJsonArray().isEmpty();
        }

        return new InitializeResult(
                (int) Json.number(result, "protocolVersion", PREFERRED_PROTOCOL_VERSION),
                agentInfo == null ? null : Json.string(agentInfo, "name"),
                agentInfo == null ? null : Json.string(agentInfo, "title"),
                agentInfo == null ? null : Json.string(agentInfo, "version"),
                capabilities != null && Json.get(capabilities, "loadSession") != null
                        && Json.get(capabilities, "loadSession").getAsJsonPrimitive().getAsBoolean(),
                // Presence of the key is the capability; the value is an empty
                // object of per-capability options.
                sessionCapabilities != null && Json.has(sessionCapabilities, "close"),
                claudeCode != null && Json.has(claudeCode, "promptQueueing"),
                authRequired,
                result);
    }

    /** A short label for logs and the terminal wall header. */
    public String displayName() {
        if (agentTitle != null && !agentTitle.isBlank()) {
            return agentTitle;
        }
        return agentName == null ? "agent" : agentName;
    }
}
