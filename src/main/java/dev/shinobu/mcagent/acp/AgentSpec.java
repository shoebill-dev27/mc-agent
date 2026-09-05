package dev.shinobu.mcagent.acp;

import java.util.List;
import java.util.Map;

/**
 * How to launch one ACP agent adapter.
 *
 * <p>Kept as plain configuration data because it is the whole extension point:
 * adding Codex or Gemini CLI is a new entry here, not new code. It is also what
 * lets a Windows client drive an agent living in WSL — the command becomes
 * {@code wsl.exe -d Ubuntu -- bash -lc "exec claude-agent-acp"} and stdio
 * crosses the boundary unchanged.
 *
 * @param id      the key this agent is configured under, e.g. {@code claude}
 * @param command executable to run. Prefer an installed binary over {@code npx},
 *                which re-resolves the package on every launch (measured 35s
 *                cold, 9s warm, against 0.2s for the binary).
 * @param args    arguments passed to it
 * @param env     extra environment variables; the host environment is inherited
 */
public record AgentSpec(String id, String command, List<String> args, Map<String, String> env) {

    public AgentSpec {
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
    }

    public static AgentSpec of(String id, String command, String... args) {
        return new AgentSpec(id, command, List.of(args), Map.of());
    }

    /** For logs and process thread names. */
    public String describe() {
        return args.isEmpty() ? command : command + " " + String.join(" ", args);
    }
}
