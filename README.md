# mc-agent

Operate AI coding agents from inside Minecraft.

Not a chat bridge. Each session is an **allay** that lives in the world: it
orbits while it thinks, speeds up and changes colour while it runs a tool,
flies over to you and flashes red when it needs your approval, and dies when
you kill it. Several sessions means several allays, each visibly in a
different state — a workshop where you can see what your agents are doing.

The mod speaks [ACP (Agent Client Protocol)](https://agentclientprotocol.com),
so it is not tied to one vendor. Claude Code works today; Codex CLI and
Gemini CLI are a config entry away.

> **Status:** early. Phase 0 (toolchain + protocol verification) is done;
> phase 1 (the MVP listed below) is in progress.

---

## Requirements

| | |
|---|---|
| Minecraft | 26.2 (Java Edition) |
| Java | 25 — `sudo apt install openjdk-25-jdk` on Ubuntu 22.04 |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.159.0+26.2 |
| Node | 20+ (an ACP adapter is a Node process) |
| An agent | e.g. `npm i -g @agentclientprotocol/claude-agent-acp` |

Minecraft 26.1+ ships unobfuscated, so there is no mappings step and no
Yarn dependency.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./gradlew build
```

Use the wrapper, not a system Gradle — Ubuntu 22.04 packages Gradle 4.4.1,
which cannot parse this build.

Run it:

```bash
./gradlew runClient   # singleplayer
./gradlew runServer   # dedicated server
```

## Configuration — `config/mcagent.json`

```jsonc
{
  "workspaceRoots": ["/home/you/projects"],
  "defaultAgent": "claude",
  "agents": {
    "claude": {
      // Point at an installed binary. Going through `npx` re-resolves the
      // package on every launch: measured 35 s cold, 9 s warm, versus 0.2 s
      // for the installed binary.
      "command": "/home/you/.npm-global/bin/claude-agent-acp",
      "args": [],
      "env": {}
    }
  }
}
```

**Running Minecraft on Windows with your code in WSL2?** The adapter is
launched over stdio, which crosses the boundary fine — just launch it
through `wsl.exe`:

```jsonc
"command": "wsl.exe",
"args": ["-d", "Ubuntu", "--", "bash", "-lc", "exec claude-agent-acp"]
```

No bridge process required.

## Commands

| | |
|---|---|
| `/agent new <path>` | start a session in a directory under a workspace root |
| `/agent list` | every session you may control |
| `/agent say <text>` | prompt the session in focus |
| `/agent cancel` | interrupt the running turn |
| `/agent tp` | go to the session in focus |
| `/agent end` | close the session in focus |
| `/agent permission [session]` | open the approval dialog |
| `/agent diff [session]` | read the pending change as a book |
| `/agent approve [session]` | allow, this once |
| `/agent approve always [session]` | allow for the rest of the session |
| `/agent deny [session]` | refuse |

The session "in focus" is the nearest avatar within `avatar.focusRadius`,
otherwise the last one you dealt with. Right-clicking an avatar puts it in
focus and opens whatever it is waiting to show you; hitting one asks whether
you meant to end it.

Approving from chat and approving from the dialog do the same thing. The
dialog puts "always allow" behind a second confirmation because a button in an
inventory is easy to hit by accident; typing the word `always` is not, so the
command does not ask twice.

## Security

Sessions edit real files and run real commands on the host.

- Only the session's owner (and server operators) can send prompts, approve
  tool calls, or end a session. Everyone else can watch. This is enforced
  **server-side**, not in the client.
- Sessions can only be opened under a configured `workspaceRoots` path.
- **The mod stores no credentials.** It declares no filesystem capability to
  the agent and holds no API keys; the adapter reuses whatever authentication
  the host's agent CLI already has. See `.env.example`.
- Approving "always allow" is deliberately behind a second confirmation —
  misclicks are easy in a game.

Note that running a session against a repository you also have open in an
editor-based agent will have the two fight over the same files.

## Phase 1 scope

1. Pick a repository, create a session, prompt it, approve its edits, and see
   the result — without leaving the game.
2. Read the allay alone to tell idle / thinking / running-a-tool / waiting-on-you
   / errored apart, including which tool is running.
3. Killing the allay ends the session and leaves no orphaned process.
4. Non-owners are refused, on a dedicated server.
5. The ACP layer is covered by JUnit tests that run without Minecraft.

## Layout

```
src/main/java/dev/shinobu/mcagent/
  acp/       ACP client — imports no Minecraft types, unit-testable on its own
  diff/      line diff behind the approval dialog — also Minecraft-free
  session/   session lifecycle, agent process pool
  entity/    the allay avatar and how state is presented
  display/   terminal wall
  gui/       repo picker, session list, approval dialog, diff book
  net/       client<->server payloads
  security/  ownership checks
src/client/java/dev/shinobu/mcagent/client/
  screen/    prompt screen
docs/acp-findings.md   observed ACP wire format — the basis for the client
tools/acp-probe.mjs    the probe that captured it; rerun for new adapters
```

## License

MIT
