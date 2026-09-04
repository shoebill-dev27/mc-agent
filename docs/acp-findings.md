# ACP wire format — observed, not assumed

Phase 0 record. Everything below was captured from a live
`@agentclientprotocol/claude-agent-acp` process via `tools/acp-probe.mjs`,
not read from documentation. The Java client is written against these shapes.

Reproduce with:

```bash
node tools/acp-probe.mjs --stage init
node tools/acp-probe.mjs --stage prompt --cwd <repo> --prompt "..." --out /tmp/acp.jsonl
```

Environment at capture time: Ubuntu 22.04, Node v20.20.2,
`@anthropic-ai/claude-code@2.1.260`, adapter `agentInfo.version` 0.49.0
(npm dist-tag reported 0.74.0).

---

## 1. Protocol version is **1**, not 2

`initialize` negotiated `protocolVersion: 1`. The v2 consolidation (where
`tool_call_update` is the sole upsert) is **not** what this adapter speaks.

Both variants are used, with distinct roles:

- `tool_call` — creation. Always arrives with `status: "pending"` and a
  generic `title` (`"Read File"`, `"Write"`), empty `rawInput`/`locations`.
- `tool_call_update` — patch, keyed by `toolCallId`. Fills in the real
  `title` (`"Read .gitignore"`), `rawInput`, `locations`, `content`, and the
  terminal `status`.

Client must treat both as an upsert keyed by `toolCallId`, and must tolerate
a field being absent (meaning "unchanged") rather than null-ing it.

## 2. `status: "in_progress"` was never observed

Observed statuses: **`pending`, `completed`, `failed`**.

**Consequence for the UI state model:** the avatar must enter the `WORKING`
state on `pending`, not on `in_progress`. Keying `WORKING` off `in_progress`
alone would mean the avatar never visibly works. Treat `in_progress` as an
additional `WORKING` trigger if some other adapter emits it.

## 3. `session/request_permission` — exact shape

Sent as a **request from the agent to us** (agent uses its own id space,
starting at 0). Captured verbatim:

```json
{
  "options": [
    { "kind": "allow_always", "name": "Always Allow all Write", "optionId": "allow_always" },
    { "kind": "allow_once",   "name": "Allow",                  "optionId": "allow"  },
    { "kind": "reject_once",  "name": "Reject",                 "optionId": "reject" }
  ],
  "sessionId": "…",
  "toolCall": {
    "toolCallId": "toolu_…",
    "rawInput": { "file_path": "/abs/path.txt", "content": "hello" },
    "title": "Write PROBE_SHOULD_NOT_EXIST.txt",
    "kind": "edit",
    "content": [
      { "type": "diff", "path": "/abs/path.txt", "oldText": null, "newText": "hello" }
    ],
    "locations": [ { "path": "/abs/path.txt" } ]
  }
}
```

Our response:

```json
{ "outcome": { "outcome": "selected", "optionId": "reject" } }
```

Two things this settles:

- The three `optionKind` values are exactly the ones the approval dialog was
  designed around. The mapping table (icon + placement per `optionKind`)
  works as planned, and `name` carries a human label we can show verbatim.
- **The diff ships inside the permission request itself.** The book GUI can
  render `toolCall.content[].{oldText,newText}` directly — no extra round
  trip, no separate diff fetch.

After rejection the agent emits a `tool_call_update` with `status: "failed"`
and `rawOutput: "User refused permission to run tool"`, then finishes the
turn normally with `stopReason: "end_turn"`.

## 4. `sessionUpdate` variants actually seen

`agent_message_chunk`, `agent_thought_chunk`, `tool_call`,
`tool_call_update`, `available_commands_update`, **`usage_update`**.

`usage_update` was not anticipated and is valuable:

```json
{ "sessionUpdate": "usage_update", "used": 24532, "size": 200000,
  "cost": { "amount": 0.1060134, "currency": "USD" } }
```

Live context consumption *and* running cost, pushed without being asked.
The phase-2 "token/cost display" is nearly free — wire it to the panel or
the wall header whenever we want it.

## 5. `_meta.claudeCode.toolName` beats `kind`

Every tool call carries `_meta.claudeCode.toolName` with the real Claude Code
tool name (`"Read"`, `"Write"`). This is more specific than the nine-value
`kind` enum.

**Display `toolName` when present, fall back to `kind`.** `kind` still drives
colour, icon and particles, since it is the cross-agent field that Codex and
Gemini will also populate.

## 6. `content[]` has (at least) two shapes

```json
{ "type": "diff",    "path": "…", "oldText": null, "newText": "hello" }
{ "type": "content", "content": { "type": "text", "text": "```\n…\n```" } }
```

Parse defensively and ignore unknown `type` values rather than failing.

## 7. Agent capabilities worth exploiting

```json
{
  "loadSession": true,
  "promptCapabilities": { "image": true, "embeddedContext": true },
  "mcpCapabilities": { "http": true, "sse": true },
  "sessionCapabilities": {
    "additionalDirectories": {}, "close": {}, "delete": {},
    "fork": {}, "list": {}, "resume": {}
  },
  "_meta": { "claudeCode": { "promptQueueing": true } }
}
```

- **`session/close` exists.** Killing the avatar should call `close`, not just
  `cancel`. `cancel` interrupts the current turn; `close` ends the session.
- `loadSession` / `resume` confirm that restoring sessions after a server
  restart is viable.
- `promptQueueing: true` — the "type a follow-up while it works" behaviour in
  the prompt screen is natively supported; we do not need our own queue.
- `fork` is interesting later: one avatar could spawn a branch of another.
- `authMethods: []` — no auth step. The adapter reuses the host's existing
  Claude Code credentials. The mod stores no secrets, as designed.

## 8. Startup latency is a real UX problem

| run | time to `initialize` response |
|---|---|
| first (cold npx resolution) | **34.7 s** |
| second (warm npm cache) | ~9 s |

Launching via `npx -y <pkg>` re-resolves the package every time. Session
creation would stall for seconds behind a spinner.

**Mitigation:** install the adapter once (`npm i -g` or a pinned local
`node_modules`) and point `agents.claude.command` at the resolved binary
instead of `npx`. Keep the `npx` form documented as the zero-install
fallback.

## 9. Node 20 works despite `engines: >=22`

The package declares `engines: { node: ">=22" }`; npm does not enforce this
by default and the adapter ran correctly end-to-end on Node v20.20.2.

This is an **unsupported configuration**. If odd failures appear, installing
Node 22+ (user-level, via nvm/fnm — no sudo needed) and pointing
`agents.claude.command` at that binary is the first thing to try.
