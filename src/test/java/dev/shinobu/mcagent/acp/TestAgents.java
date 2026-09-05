package dev.shinobu.mcagent.acp;

import org.junit.jupiter.api.Assumptions;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stand-in ACP agents built from POSIX shell, for tests that need a real
 * child process rather than a pair of pipes.
 *
 * <p>Request ids are tracked with a counter rather than parsed, which holds
 * because the client numbers its requests from 1 and these tests send only
 * requests, never notifications, before shutting the agent down.
 */
final class TestAgents {

    private TestAgents() {
    }

    /**
     * Answers {@code initialize}, {@code session/new} and {@code session/close},
     * handing out a fresh session id each time, then keeps reading stdin so it
     * stays alive until something closes it.
     */
    static final String COOPERATIVE = """
            echo 'starting up' >&2
            n=0
            s=0
            while IFS= read -r line; do
              n=$((n+1))
              case "$line" in
                *initialize*)
                  printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":1,"agentInfo":{"name":"fake","title":"Fake Agent","version":"9.9.9"},"agentCapabilities":{"sessionCapabilities":{"close":{}}},"authMethods":[]}}\\n' "$n"
                  ;;
                *session/close*)
                  printf '{"jsonrpc":"2.0","id":%s,"result":{}}\\n' "$n"
                  ;;
                *session/new*)
                  s=$((s+1))
                  printf '{"jsonrpc":"2.0","id":%s,"result":{"sessionId":"s%s"}}\\n' "$n" "$s"
                  ;;
              esac
            done
            """;

    /** Complains to stderr and exits, the way a misconfigured command does. */
    static final String FAILS_IMMEDIATELY = """
            echo 'command not found: claude-agent-acp' >&2
            exit 127
            """;

    /** Starts, but never answers anything — exercises initialize timing out. */
    static final String NEVER_ANSWERS = """
            while IFS= read -r line; do :; done
            """;

    static AgentSpec spec(String id, String script) {
        return new AgentSpec(id, "sh", List.of("-c", script), Map.of());
    }

    /** Skips the calling test where these scripts cannot run. */
    static void requirePosixShell() {
        Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"),
                "the stand-in agents are POSIX shell scripts");
    }
}
