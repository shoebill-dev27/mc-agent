#!/usr/bin/env node
/**
 * ACP protocol probe.
 *
 * Phase 0 tool: launches an ACP agent adapter over stdio and records the real
 * wire shapes (initialize result, session/update variants, the exact
 * session/request_permission payload) so the Java client is written against
 * observed traffic rather than guesses. Also reused whenever a new adapter
 * (Codex, Gemini CLI) is added.
 *
 * Usage:
 *   node tools/acp-probe.mjs --stage init
 *   node tools/acp-probe.mjs --stage session --cwd /abs/path
 *   node tools/acp-probe.mjs --stage close   --cwd /abs/path
 *   node tools/acp-probe.mjs --stage prompt  --cwd /abs/path --prompt "..."
 *
 * Stages are cumulative: `prompt` runs init + session + prompt.
 * Only the `prompt` stage causes the agent to do real work.
 *
 * Options:
 *   --command <cmd>      adapter executable          (default: npx)
 *   --args <a,b,c>       adapter args                (default: -y,@agentclientprotocol/claude-agent-acp)
 *   --protocol <n>       protocolVersion to offer    (default: 1)
 *   --timeout <ms>       give up after this long     (default: 120000)
 *   --out <file>         also append the transcript as JSONL
 */

import { spawn } from "node:child_process";
import { appendFileSync } from "node:fs";
import process from "node:process";

// ---------------------------------------------------------------- arg parsing

function parseArgs(argv) {
  const out = {
    stage: "init",
    command: "npx",
    args: ["-y", "@agentclientprotocol/claude-agent-acp"],
    cwd: process.cwd(),
    prompt: "Reply with exactly: pong. Do not use any tools.",
    protocol: 1,
    timeout: 120000,
    outFile: null,
  };
  for (let i = 0; i < argv.length; i++) {
    const next = () => argv[++i];
    switch (argv[i]) {
      case "--stage": out.stage = next(); break;
      case "--command": out.command = next(); break;
      case "--args": out.args = next().split(","); break;
      case "--cwd": out.cwd = next(); break;
      case "--prompt": out.prompt = next(); break;
      case "--protocol": out.protocol = Number(next()); break;
      case "--timeout": out.timeout = Number(next()); break;
      case "--out": out.outFile = next(); break;
      default: throw new Error(`unknown option: ${argv[i]}`);
    }
  }
  if (!["init", "session", "close", "prompt"].includes(out.stage)) {
    throw new Error(`--stage must be one of init|session|close|prompt`);
  }
  return out;
}

const opts = parseArgs(process.argv.slice(2));

// ------------------------------------------------------------------- logging

const started = Date.now();
const stamp = () => String(Date.now() - started).padStart(6, " ") + "ms";

function record(direction, payload) {
  const line = { t: Date.now() - started, direction, payload };
  if (opts.outFile) appendFileSync(opts.outFile, JSON.stringify(line) + "\n");
  const arrow = direction === "out" ? "-->" : direction === "in" ? "<--" : "   ";
  console.log(`${stamp()} ${arrow} ${JSON.stringify(payload)}`);
}

function note(msg) {
  console.log(`${stamp()}     # ${msg}`);
}

// ------------------------------------------------------------ process + peer

note(`spawning: ${opts.command} ${opts.args.join(" ")}`);
note(`cwd for session/new: ${opts.cwd}`);

const child = spawn(opts.command, opts.args, {
  stdio: ["pipe", "pipe", "pipe"],
  env: process.env,
});

child.on("error", (err) => {
  console.error(`FATAL: could not spawn adapter: ${err.message}`);
  process.exit(1);
});

child.stderr.setEncoding("utf8");
child.stderr.on("data", (chunk) => {
  for (const line of chunk.split("\n")) {
    if (line.trim()) console.log(`${stamp()} err ${line}`);
  }
});

let nextId = 1;
const pending = new Map();

function request(method, params) {
  const id = nextId++;
  const msg = { jsonrpc: "2.0", id, method, params };
  record("out", msg);
  child.stdin.write(JSON.stringify(msg) + "\n");
  return new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
}

function notify(method, params) {
  const msg = { jsonrpc: "2.0", method, params };
  record("out", msg);
  child.stdin.write(JSON.stringify(msg) + "\n");
}

function respond(id, result) {
  const msg = { jsonrpc: "2.0", id, result };
  record("out", msg);
  child.stdin.write(JSON.stringify(msg) + "\n");
}

function respondError(id, code, message) {
  const msg = { jsonrpc: "2.0", id, error: { code, message } };
  record("out", msg);
  child.stdin.write(JSON.stringify(msg) + "\n");
}

// Observed shapes, summarised at exit — this is the actual deliverable.
const seenUpdates = new Set();
const seenToolKinds = new Set();
const seenToolStatuses = new Set();
const permissionSamples = [];
const clientCallsSamples = [];

function handleIncoming(msg) {
  record("in", msg);

  // Response to something we sent.
  if (msg.id !== undefined && (msg.result !== undefined || msg.error !== undefined)) {
    const waiter = pending.get(msg.id);
    if (waiter) {
      pending.delete(msg.id);
      msg.error ? waiter.reject(new Error(JSON.stringify(msg.error))) : waiter.resolve(msg.result);
    }
    return;
  }

  // Notification from the agent.
  if (msg.id === undefined && msg.method) {
    if (msg.method === "session/update") {
      const u = msg.params?.update ?? msg.params;
      const kind = u?.sessionUpdate ?? "(no sessionUpdate field)";
      seenUpdates.add(kind);
      if (kind === "tool_call" || kind === "tool_call_update") {
        if (u.kind) seenToolKinds.add(u.kind);
        if (u.status) seenToolStatuses.add(u.status);
      }
    }
    return;
  }

  // Request FROM the agent — this is where request_permission shows up.
  if (msg.id !== undefined && msg.method) {
    if (msg.method === "session/request_permission") {
      permissionSamples.push(msg.params);
      note("!!! session/request_permission received — auto-rejecting (probe is read-only)");
      const options = msg.params?.options ?? [];
      note(`    options: ${JSON.stringify(options)}`);
      const reject =
        options.find((o) => o.kind === "reject_once") ??
        options.find((o) => String(o.kind ?? "").startsWith("reject")) ??
        options[options.length - 1];
      if (reject) {
        respond(msg.id, { outcome: { outcome: "selected", optionId: reject.optionId } });
      } else {
        respond(msg.id, { outcome: { outcome: "cancelled" } });
      }
      return;
    }
    // Any other client-side method the adapter expects us to implement.
    clientCallsSamples.push({ method: msg.method, params: msg.params });
    note(`unimplemented client method: ${msg.method} -> returning method not found`);
    respondError(msg.id, -32601, `probe does not implement ${msg.method}`);
  }
}

let buffer = "";
child.stdout.setEncoding("utf8");
child.stdout.on("data", (chunk) => {
  buffer += chunk;
  let nl;
  while ((nl = buffer.indexOf("\n")) !== -1) {
    const line = buffer.slice(0, nl).trim();
    buffer = buffer.slice(nl + 1);
    if (!line) continue;
    try {
      handleIncoming(JSON.parse(line));
    } catch {
      console.log(`${stamp()} raw ${line}`);
    }
  }
});

// ---------------------------------------------------------------- the script

function summarise() {
  console.log("\n================ OBSERVED SHAPES ================");
  console.log("sessionUpdate variants :", [...seenUpdates].join(", ") || "(none)");
  console.log("tool kinds             :", [...seenToolKinds].join(", ") || "(none)");
  console.log("tool statuses          :", [...seenToolStatuses].join(", ") || "(none)");
  console.log("request_permission     :", permissionSamples.length
    ? JSON.stringify(permissionSamples, null, 2)
    : "(none seen)");
  console.log("other client methods   :", clientCallsSamples.length
    ? JSON.stringify(clientCallsSamples, null, 2)
    : "(none)");
  console.log("================================================\n");
}

async function main() {
  const initResult = await request("initialize", {
    protocolVersion: opts.protocol,
    clientCapabilities: {
      // Deliberately declaring no fs/terminal capabilities: the mod lets the
      // agent use its own tools rather than proxying filesystem access.
      fs: { readTextFile: false, writeTextFile: false },
      terminal: false,
    },
  });
  note(`negotiated protocolVersion: ${JSON.stringify(initResult?.protocolVersion)}`);
  note(`agentCapabilities: ${JSON.stringify(initResult?.agentCapabilities)}`);
  note(`authMethods: ${JSON.stringify(initResult?.authMethods)}`);
  if (opts.stage === "init") return;

  const session = await request("session/new", { cwd: opts.cwd, mcpServers: [] });
  note(`sessionId: ${JSON.stringify(session?.sessionId)}`);
  if (opts.stage === "session") return;

  if (opts.stage === "close") {
    // Verifying that session/close really exists, rather than trusting the
    // capability flag. An error here means the mod must fall back to cancel.
    const closed = await request("session/close", { sessionId: session.sessionId });
    note(`session/close returned: ${JSON.stringify(closed)}`);
    return;
  }

  const promptResult = await request("session/prompt", {
    sessionId: session.sessionId,
    prompt: [{ type: "text", text: opts.prompt }],
  });
  note(`stopReason: ${JSON.stringify(promptResult?.stopReason)}`);
}

const timer = setTimeout(() => {
  note(`TIMEOUT after ${opts.timeout}ms`);
  summarise();
  child.kill("SIGTERM");
  process.exit(2);
}, opts.timeout);

main()
  .then(() => {
    note("stage complete");
    summarise();
  })
  .catch((err) => {
    console.error(`\nERROR: ${err.message}`);
    summarise();
    process.exitCode = 1;
  })
  .finally(() => {
    clearTimeout(timer);
    child.kill("SIGTERM");
  });
