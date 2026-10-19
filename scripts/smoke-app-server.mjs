// Protocol handshake only: no model request, filesystem tools or account changes.
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

const cwd = await mkdtemp(join(tmpdir(), "minecraft-harness-"));
const proc = spawn(process.argv[2] || "codex", ["app-server"], {
  cwd,
  stdio: ["pipe", "pipe", "pipe"],
});
const timeout = setTimeout(() => {
  console.error("Handshake timed out");
  process.exitCode = 1;
  proc.kill();
}, 15_000);
const lines = createInterface({ input: proc.stdout });
let initialized = false;
proc.stderr.resume();
proc.on("error", (error) => {
  console.error(error.message);
  process.exitCode = 1;
});
proc.stdin.on("error", () => {});
lines.on("line", (line) => {
  try {
    const message = JSON.parse(line);
    if (message.id !== 1) return;
    if (message.error || !message.result)
      throw new Error("Initialize rejected");
    proc.stdin.write(
      JSON.stringify({ method: "initialized", params: {} }) + "\n",
      () => {
        initialized = true;
        console.log(
          "PASS: real Codex app-server initialize/initialized over stdio",
        );
        proc.stdin.end();
      },
    );
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
    proc.kill();
  }
});
proc.on("close", async () => {
  clearTimeout(timeout);
  if (!initialized) process.exitCode = 1;
  await rm(cwd, { recursive: true, force: true });
});
proc.stdin.write(
  JSON.stringify({
    id: 1,
    method: "initialize",
    params: {
      clientInfo: {
        name: "minecraft_harness",
        title: "Minecraft Harness",
        version: "0.1.0",
      },
    },
  }) + "\n",
);
