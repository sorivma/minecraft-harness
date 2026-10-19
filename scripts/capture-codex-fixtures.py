"""Capture minimal, synthetic protocol examples from the pinned local CLI schema.

Requires Python jsonschema for verification. Never reads credentials or live chats.
"""

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import tempfile

VERSION = "codex-cli 0.163.0"
ROOT = pathlib.Path(__file__).resolve().parents[1]
DEST = ROOT / "agent-codex/src/test/resources/protocol/0.163.0"
SCHEMAS = [
    "v2/ModelListResponse.json",
    "v2/ThreadListResponse.json",
    "v2/ThreadTurnsListResponse.json",
    "v2/ThreadItemsListResponse.json",
    "v2/TurnStartParams.json",
    "v2/TurnSteerParams.json",
    "v2/ItemStartedNotification.json",
    "v2/ItemCompletedNotification.json",
    "v2/AgentMessageDeltaNotification.json",
    "v2/TurnCompletedNotification.json",
    "CommandExecutionRequestApprovalParams.json",
    "CommandExecutionRequestApprovalResponse.json",
    "FileChangeRequestApprovalParams.json",
    "FileChangeRequestApprovalResponse.json",
    "ToolRequestUserInputParams.json",
    "ToolRequestUserInputResponse.json",
    "McpServerElicitationRequestParams.json",
    "McpServerElicitationRequestResponse.json",
    "v2/ServerRequestResolvedNotification.json",
]


def minimal(schema, root, depth=0):
    if isinstance(schema, bool):
        return None
    if "$ref" in schema:
        return minimal(
            root["definitions"][schema["$ref"].split("/")[-1]], root, depth + 1
        )
    if "enum" in schema:
        return schema["enum"][0]
    if "allOf" in schema:
        combined = {}
        for part in schema["allOf"]:
            combined.update(part)
        return minimal(combined, root, depth + 1)
    variants = schema.get("oneOf", schema.get("anyOf"))
    if variants:
        value = minimal(variants[0], root, depth + 1)
        if isinstance(value, dict):
            value.update(
                {
                    key: minimal(schema["properties"][key], root, depth + 1)
                    for key in schema.get("required", [])
                }
            )
        return value
    kind = schema.get("type", "object")
    if isinstance(kind, list):
        if "null" in kind:
            return None
        kind = kind[0]
    if kind == "object":
        return {
            key: minimal(schema["properties"][key], root, depth + 1)
            for key in schema.get("required", [])
        }
    if kind == "array":
        return []
    if kind == "string":
        return "fixture"
    if kind == "boolean":
        return False
    if kind in ("integer", "number"):
        return int(schema.get("minimum", 0))
    return None


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def verify():
    import jsonschema

    manifest = json.loads((DEST / "manifest.json").read_text(encoding="utf-8"))
    for entry in manifest["fixtures"]:
        source = DEST / entry["schema"]
        assert (
            hashlib.sha256(source.read_bytes()).hexdigest() == entry["sha256"]
        ), source
        schema = json.loads(source.read_text(encoding="utf-8"))
        value = json.loads((DEST / entry["example"]).read_text(encoding="utf-8"))
        jsonschema.Draft7Validator(schema).validate(value)
    print("Validated", len(manifest["fixtures"]), "pinned schema fixtures")


def capture():
    actual = subprocess.check_output(["codex", "--version"], text=True).strip()
    if actual != VERSION:
        raise SystemExit("Expected " + VERSION + "; got " + actual)
    manifest = {
        "version": VERSION,
        "origin": "synthetic examples from local generated schemas",
        "fixtures": [],
    }
    with tempfile.TemporaryDirectory() as directory:
        subprocess.run(
            ["codex", "app-server", "generate-json-schema", "--out", directory],
            check=True,
        )
        for name in SCHEMAS:
            source = pathlib.Path(directory) / name
            schema = json.loads(source.read_text(encoding="utf-8"))
            schema_name = "schemas/" + source.name
            sample_name = "examples/" + source.name
            write(DEST / schema_name, schema)
            value = minimal(schema, schema)
            if "data" in value:
                value["data"] = [minimal(schema["properties"]["data"]["items"], schema)]
            write(DEST / sample_name, value)
            manifest["fixtures"].append(
                {
                    "schema": schema_name,
                    "example": sample_name,
                    "sha256": hashlib.sha256(
                        (DEST / schema_name).read_bytes()
                    ).hexdigest(),
                }
            )
        # A complete method inventory is evidence of protocol availability, not UI support.
        for envelope in ["ClientRequest", "ServerRequest", "ServerNotification"]:
            schema = json.loads(
                (pathlib.Path(directory) / (envelope + ".json")).read_text()
            )
            write(
                DEST / (envelope + "-methods.json"),
                sorted(
                    branch["properties"]["method"]["enum"][0]
                    for branch in schema["oneOf"]
                ),
            )
        # Exact argument schemas used by the named action allowlist, including experimental RPCs.
        subprocess.run(
            [
                "codex",
                "app-server",
                "generate-json-schema",
                "--experimental",
                "--out",
                directory,
            ],
            check=True,
        )
        envelope = json.loads(
            (pathlib.Path(directory) / "ClientRequest.json").read_text(encoding="utf-8")
        )
        methods = set(
            re.findall(
                r'new Definition\("[^"]+","([^"]+)"',
                (
                    ROOT
                    / "agent-codex/src/main/java/dev/minecraftharness/codex/CodexActions.java"
                ).read_text(encoding="utf-8"),
            )
        )
        actions = {}
        for branch in envelope["oneOf"]:
            method = branch["properties"]["method"]["enum"][0]
            if method not in methods:
                continue
            schema = branch["properties"].get("params", {"type": "null"})
            definitions = {}

            def refs(value):
                if isinstance(value, dict):
                    if "$ref" in value:
                        name = value["$ref"].split("/")[-1]
                        if name not in definitions:
                            definitions[name] = envelope["definitions"][name]
                            refs(definitions[name])
                    for child in value.values():
                        refs(child)
                elif isinstance(value, list):
                    for child in value:
                        refs(child)

            refs(schema)
            actions[method] = dict(schema, definitions=definitions)
        assert methods == set(actions), methods - set(actions)
        write(DEST / "action-request-schemas.json", actions)
    write(DEST / "manifest.json", manifest)
    verify()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    verify() if args.verify else capture()
