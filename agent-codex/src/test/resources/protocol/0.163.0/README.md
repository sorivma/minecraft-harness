# Codex protocol baseline

Pinned executable: **codex-cli 0.163.0**, verified locally on 2026-10-19.
These are synthetic examples derived from the executable's exported JSON Schema,
not recordings of private sessions or evidence of account entitlement.

`schemas/` preserves the selected schemas and their transitive definitions.
`examples/` contains minimal valid instances for model/catalog/history pages,
turn input, streaming/final events, approvals, questions and MCP elicitation.
`manifest.json` records schema SHA-256 hashes. The three method inventories cover
the non-experimental export; they do not claim that these methods have UI support.

Regenerate with `python scripts/capture-codex-fixtures.py` (requires the exact
pinned executable and Python `jsonschema`). Verify offline with
`python scripts/capture-codex-fixtures.py --verify`.

Reference: [official App Server documentation](https://learn.chatgpt.com/docs/app-server).
The local versioned schemas control wire shapes when rolling documentation differs.
Behavioral replay fixtures must additionally cover interleaving, disconnect,
request-id type preservation and terminal snapshots during adapter implementation.
